package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cache craftowalnosci per siec, utrzymywany przyrostowo i z budzetem czasu.
 *
 * <p><b>Dlaczego tak.</b> Liczenie "ile da sie dorobic" jest rekurencyjne i drogie.
 * Przy paczce z 3k itemow i 15k receptur zadne podejscie "przeliczmy to
 * regularnie" sie nie obroni - ani na zadanie, ani co sekunde, ani nawet
 * rozlozone na ticki, jesli budzet nie jest ograniczony czasowo.
 *
 * <p><b>Zasady, ktorych przestrzega ta klasa:</b>
 * <ol>
 *   <li><b>Budzet czasowy, nie liczba itemow.</b> Na tick przeznaczamy okreslony
 *       czas (np. 2 ms). Wolny serwer przeliczy wiecej, obciazony mniej - ale
 *       tick nigdy nie przekroczy budzetu. Liczenie "N itemow na tick" jest
 *       zawodne, bo jeden item moze byc wielokrotnie drozszy od innego.</li>
 *   <li><b>Zmiany wykrywamy tanio.</b> Nie skanujemy calej sieci co tick.
 *       Endpointy same zglaszaja zmiany, a pelny skan jest rzadki i porcjowany.</li>
 *   <li><b>Reakcja na swiat.</b> Podlaczenie/odlaczenie inventory, zaladowanie
 *       i rozladowanie chunka - wszystko uniewaznia wlasciwe itemy.</li>
 * </ol>
 */
public final class VeloceCraftingCache {

    // --- Budzet pracy -------------------------------------------------

    /**
     * Maksymalny czas pracy cache na jeden tick, w nanosekundach.
     *
     * <p>10 ms przy ticku 50 ms (20 TPS) zostawia 80% budzetu na resztę gry,
     * a jednoczesnie pozwala przeliczyc kilkaset itemow na sekunde. Poprzednie
     * 2 ms bylo zbyt zachowawcze - cache nie nadazal i liczby w GUI zostawaly
     * stare, dopoki gracz nie wszedl i nie wyszedl z terminala.
     *
     * <p>Wazne: to jest GORNY limit, nie cel. Na spokojnym serwerze petla
     * skonczy sie wczesniej, bo po prostu nie ma juz pracy.
     */
    private static final long TICK_BUDGET_NS = 10_000_000L;

    /** Powyzej tego czasu tick raportuje przekroczenie budzetu do loga. */
    private static final long OVERRUN_WARN_NS = 25_000_000L;

    /**
     * Awaryjny limit itemow na tick.
     *
     * <p>Glownym ograniczeniem jest czas. Ten limit istnieje tylko po to, zeby
     * jeden katastrofalnie drogi item nie zjadl calego ticku zanim petla
     * zdazy sprawdzic zegar.
     */
    private static final int MAX_ITEMS_PER_TICK = 2000;

    /** Bezpiecznik na dlugosc lancucha "w gore". */
    private static final int MAX_CHAIN = 512;

    /** Bezpiecznik na pelny skan. */
    private static final int MAX_FULL_SCAN = 500;

    /**
     * Ile tickow po starcie swiata czekamy z pierwszym skanem.
     *
     * <p>Kluczowe: ladowanie save'a to najbardziej obciazony moment w cyklu
     * zycia serwera (generowanie chunkow, wczytywanie encji, swiatlo). Jesli
     * zaczniemy wtedy liczyc craftowalnosc, dojdziemy do zawieszenia - co juz
     * sie raz stalo przy 2118 zakolejkowanych itemach.
     */
    private static final int STARTUP_GRACE_TICKS = 200;   // 10 s

    /**
     * Maksymalny rozmiar kolejki. Jesli rosnie ponad to, przestajemy dodawac -
     * lepiej miec niepelny cache niz zawieszony serwer.
     */
    private static final int MAX_QUEUE = 2000;

    /**
     * Co ile tickow wolno zrobic pelny skan stocku (weryfikacja).
     * Miedzy skanami polegamy na taniej detekcji zmian.
     */
    private static final int FULL_STOCK_SCAN_INTERVAL = 20;   // 1 s

    /**
     * Krotki interwal po operacji gracza.
     *
     * <p>Gdy gracz wyciaga lub wklada itemy, chce zobaczyc zaktualizowane
     * liczby NATYCHMIAST, a nie po sekundzie. Przez kilka tickow po takiej
     * operacji skanujemy czesciej.
     */
    private static final int BUSY_SCAN_INTERVAL = 2;

    /** Do kiedy (gameTime) skanujemy w trybie "po operacji gracza". */
    private long busyUntil = 0;

    // --- Rejestry -----------------------------------------------------

    private static final Map<UUID, VeloceCraftingCache> CACHES = new HashMap<>();

    private final VelocePipeNetwork network;

    /** Gotowe liczby: ile da sie dorobic danego itemu. */
    private final Map<Item, Long> craftable = new HashMap<>();

    /** Kolejka itemow do przeliczenia. */
    private final Deque<Item> pending = new ArrayDeque<>();

    private final Set<Item> queued = new HashSet<>();

    private boolean fullScanDone = false;

    /**
     * Czy wstepny skan zostal juz zakolejkowany.
     *
     * <p>Osobne od {@link #fullScanDone}, bo tamto oznacza "skonczone", a to
     * "rozpoczęte". Bez rozroznienia kolejka byla zakolejkowana wielokrotnie
     * albo - jak w poprzedniej wersji - oznaczana jako skonczona, gdy byla
     * pusta, przez co cache nigdy sie nie wypelnial i GUI nie pokazywalo
     * zadnych liczb "+N".
     */
    private boolean initialScanQueued = false;

    /**
     * Wymusza diff stocku na najblizszym ticku, bez czyszczenia cache.
     *
     * <p>Ustawiane, gdy zmienil sie sklad sieci (dolaczona/odlaczona skrzynia,
     * chunk, wezel). Diff sam znajdzie zmienione itemy i zakolejkuje tylko
     * ich lancuch - to o rzedy wielkosci tansze od pelnego reskanu.
     */
    private boolean forceStockScan = false;

    /** Ile razy dany item wracal do kolejki, bo nie zmiescil sie w budzecie. */
    private final Map<Item, Integer> retries = new HashMap<>();

    /** Rozbicie ostatniego ticku na fazy, do logu watchdoga. */
    private long phaseScanNanos;
    private long phaseChunksNanos;
    private long phaseItemsNanos;

    /** Po tylu nieudanych probach odpuszczamy item (zostaje stara liczba). */
    private static final int MAX_RETRIES = 3;

    /** Migawka stocku do wykrywania zmian. */
    private final Map<Item, Long> lastStock = new HashMap<>();

    /** Czy minol okres karencji po starcie swiata. */
    private boolean startupGracePassed = false;

    /**
     * Tick, w ktorym ta sesja po raz pierwszy dotknela cache.
     *
     * <p>UWAGA: {@code level.getGameTime()} po wczytaniu save'a ma juz tysiace
     * tickow (swiat istnieje od dawna), wiec nie da sie na nim opierac karencji
     * startowej - warunek "gameTime < 200" jest od razu falszywy i karencja
     * nigdy nie dziala. Poprzednia wersja miala dokladnie ten blad.
     * Mierzymy wiec czas od pierwszego ticku tej sesji.
     */
    private long firstSeenTick = -1;

    /** Ostatni pelny skan stocku (gameTime). */
    private long lastFullStockScan = Long.MIN_VALUE;

    /** Czy trzeba przerwac biezaca prace - np. gdy siec zniknela. */

    // --- Statystyki ---------------------------------------------------

    private int totalComputed = 0;
    private int lastBatchSize = 0;
    private long lastTickNanos = 0;
    private int scanCount = 0;

    private VeloceCraftingCache(VelocePipeNetwork network) {
        this.network = network;
    }

    public static VeloceCraftingCache get(VelocePipeNetwork network) {
        return CACHES.computeIfAbsent(network.getId(), id -> new VeloceCraftingCache(network));
    }

    public static void drop(UUID networkId) {
        CACHES.remove(networkId);
    }

    public static void clearAll() {
        CACHES.clear();
    }

    // ------------------------------------------------------------------
    // Odczyt (GUI) - zawsze natychmiastowy, nic nie liczy
    // ------------------------------------------------------------------

    /** Gotowe liczby dla podanych itemow. */
    public Map<Item, Long> lookup(Set<Item> items) {
        Map<Item, Long> out = new HashMap<>();
        for (Item it : items) {
            Long v = craftable.get(it);
            if (v != null && v > 0) {
                out.put(it, v);
            }
        }
        return out;
    }

    /** Wszystkie gotowe liczby. */
    public Map<Item, Long> snapshot() {
        return new HashMap<>(craftable);
    }

    public boolean isFullScanDone() {
        return fullScanDone;
    }

    public int computedCount() {
        return totalComputed;
    }

    public int pendingCount() {
        return pending.size();
    }

    public int lastBatchSize() {
        return lastBatchSize;
    }

    public long lastTickMillis() {
        return lastTickNanos / 1_000_000L;
    }

    public int scanCount() {
        return scanCount;
    }

    // ------------------------------------------------------------------
    // Praca w tle - z budzetem czasowym
    // ------------------------------------------------------------------

    /**
     * Jeden krok pracy. Wolane z ticku, ale robi co najwyzej
     * {@link #TICK_BUDGET_NS} nanosekund roboty.
     */
    public void tick(ServerLevel level, Set<Item> enabledItems,
                     Map<Item, ResourceLocation> preferred) {
        long start = System.nanoTime();
        lastBatchSize = 0;
        // Rozbicie czasu na fazy - bez tego watchdog mowi tylko "wolno",
        // a nie gdzie. Kolejny taki bug znajdujemy wtedy od razu.
        long phaseStart = start;
        phaseScanNanos = 0L;
        phaseChunksNanos = 0L;
        phaseItemsNanos = 0L;

        // 0. Karencja startowa. Ladowanie save'a to najgorszy moment na
        //    jakakolwiek prace - czekamy, az serwer sie ustabilizuje.
        if (!startupGracePassed) {
            if (firstSeenTick < 0) {
                firstSeenTick = level.getGameTime();
                VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                        "crafting cache: first tick seen (gameTime=%d), waiting %d ticks",
                        firstSeenTick, STARTUP_GRACE_TICKS);
            }
            if (level.getGameTime() - firstSeenTick < STARTUP_GRACE_TICKS) {
                return;
            }
            startupGracePassed = true;
            VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                    "crafting cache: startup grace passed after %d ticks, beginning scan",
                    level.getGameTime() - firstSeenTick);
        }

        // 1. Pelny skan stocku. Na poczatku MUSI sie wykonac natychmiast -
        //    inaczej initial scan nie ma z czego wziac itemow (lastStock pusty).
        //    Potem juz rzadko, bo to jedyne miejsce czytajace cala siec.
        long now = level.getGameTime();
        boolean firstScan = lastFullStockScan == Long.MIN_VALUE;
        int interval = now < busyUntil ? BUSY_SCAN_INTERVAL : FULL_STOCK_SCAN_INTERVAL;
        if (firstScan || forceStockScan || now - lastFullStockScan >= interval) {
            forceStockScan = false;
            lastFullStockScan = now;
            phaseStart = System.nanoTime();
            detectStockChanges(level);
            phaseScanNanos = System.nanoTime() - phaseStart;
            if (System.nanoTime() - start > TICK_BUDGET_NS) {
                lastTickNanos = System.nanoTime() - start;
                warnIfOverrun();
                return;
            }
        }

        // 1b. Utrzymuj chunki z blokami sieci (rzadko - co sekunde).
        //     NIE robimy tego przy zamykaniu serwera - inaczej blokujemy zapis.
        if (!shuttingDown && now % 20 == 0) {
            phaseStart = System.nanoTime();
            maintainForcedChunks(level);
            phaseChunksNanos = System.nanoTime() - phaseStart;
        }

        // 2. Pierwszy skan: kolejkujemy itemy obecne w sieci (to widzi GUI)
        //    oraz - w ramach limitu - reszte wlaczonych.
        //
        //    UWAGA: zmiana skladu sieci NIE robi juz pelnego reskanu (patrz
        //    onEndpointChanged) - obsluguje ja diff stocku z punktu 1.
        if (!initialScanQueued && pending.isEmpty()) {
            initialScanQueued = true;
            queueInitialScan(enabledItems);
            VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                    "crafting cache: initial scan starting");
        }

        // 3. Przeliczaj porcje, dopoki starcza budzetu.
        phaseStart = System.nanoTime();
        while (!pending.isEmpty() && lastBatchSize < MAX_ITEMS_PER_TICK) {
            if (System.nanoTime() - start > TICK_BUDGET_NS) {
                break;   // budzet wyczerpany - reszta w nastepnym ticku
            }
            Item item = pending.poll();
            queued.remove(item);
            if (!enabledItems.contains(item)) {
                craftable.remove(item);
                continue;
            }
            // Budzet na ten jeden item: nie wiecej niz zostalo do konca ticku.
            long remaining = TICK_BUDGET_NS - (System.nanoTime() - start);
            long n = VeloceAutoCrafter.countCraftableFromStock(
                    level, item, lastStock, enabledItems, preferred,
                    Math.max(1_000_000L, remaining));
            if (n == VeloceAutoCrafter.UNKNOWN_COUNT) {
                // Budzet sie skonczyl w polowie - nie kasujemy starej liczby,
                // tylko wracamy z itemem w nastepnym ticku. Po kilku probach
                // odpuszczamy, zeby nie krecic sie w kolko w nieskonczonosc.
                int tries = retries.merge(item, 1, Integer::sum);
                queued.remove(item);
                if (tries <= MAX_RETRIES && pending.size() < MAX_QUEUE) {
                    queued.add(item);
                    pending.add(item);
                } else {
                    retries.remove(item);
                }
                continue;
            }
            retries.remove(item);
            if (n > 0) {
                craftable.put(item, n);
            } else {
                craftable.remove(item);
            }
            lastBatchSize++;
            totalComputed++;
        }
        phaseItemsNanos = System.nanoTime() - phaseStart;

        if (pending.isEmpty() && !fullScanDone) {
            fullScanDone = true;
            VeloceLog.Craft.success(VeloceLog.Side.SERVER,
                    "crafting cache: initial scan done (%d items computed, %d ms last tick)",
                    totalComputed, lastTickMillis());
        }

        lastTickNanos = System.nanoTime() - start;
        warnIfOverrun();
    }

    /**
     * Krzyczy w logu, gdy tick przekroczy zalozony budzet.
     *
     * <p>Staly bezpiecznik po zamrozeniu serwera: kazda operacja, ktora
     * wymknie sie throttlingowi, jest tu natychmiast widoczna z dokladnym
     * czasem i liczba zadan, zamiast objawiac sie tylko zamulonym serwerem.
     */
    private void warnIfOverrun() {
        if (lastTickNanos <= OVERRUN_WARN_NS) {
            return;
        }
        VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                "crafting cache TICK OVERRUN: %d ms total = scan %d ms + chunks %d ms + items %d ms "
                        + "(budget %d ms) - %d item(s) pending, %d done this tick",
                lastTickNanos / 1_000_000L,
                phaseScanNanos / 1_000_000L,
                phaseChunksNanos / 1_000_000L,
                phaseItemsNanos / 1_000_000L,
                TICK_BUDGET_NS / 1_000_000L,
                pending.size(), lastBatchSize);
    }

    /**
     * Wykrywa zmiany stocku i kolejkuje tylko dotkniety lancuch.
     *
     * <p>To jedyne miejsce, ktore czyta caly stock sieci. Wolane rzadko
     * (raz na {@link #FULL_STOCK_SCAN_INTERVAL} tickow), nie co tick.
     */
    private void detectStockChanges(ServerLevel level) {
        Map<Item, Long> stock = network.getAllItemCounts(level);
        scanCount++;

        if (stock.isEmpty() && lastStock.isEmpty()) {
            return;
        }
        Set<Item> changed = new HashSet<>();
        for (Map.Entry<Item, Long> e : stock.entrySet()) {
            Long prev = lastStock.get(e.getKey());
            if (prev == null || !prev.equals(e.getValue())) {
                changed.add(e.getKey());
            }
        }
        for (Item prev : lastStock.keySet()) {
            if (!stock.containsKey(prev)) {
                changed.add(prev);
            }
        }
        lastStock.clear();
        lastStock.putAll(stock);

        if (changed.isEmpty()) {
            return;
        }
        queueChain(level, changed);
    }

    /** Kolejkuje dotkniete itemy i ich lancuch "w gore". */
    private void queueChain(ServerLevel level, Set<Item> changed) {
        Set<Item> affected = VeloceRecipeGraph.get(level).affectedBy(changed, MAX_CHAIN);
        int added = 0;
        for (Item it : affected) {
            if (pending.size() >= MAX_QUEUE) {
                VeloceLog.Craft.why(VeloceLog.Side.SERVER,
                        "crafting cache: queue full (%d), skipping rest of chain", MAX_QUEUE);
                break;
            }
            if (queued.add(it)) {
                pending.add(it);
                added++;
            }
        }
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "crafting cache: %d changed -> %d queued (%d new)",
                changed.size(), affected.size(), added);
    }

    /**
     * Kolejkuje wstepny skan: TYLKO itemy obecne w sieci.
     *
     * <p>Reszta nie jest potrzebna od razu - gracz widzi w GUI to, co ma.
     * Dobijanie kolejki do limitu (poprzednia wersja wpychala 2000 itemow)
     * znaczylo 2000 rekurencyjnych symulacji bez powodu, i to podczas startu.
     * Pozostale itemy dolacza sie pozniej, leniwie, gdy siec sie rozrosnie.
     */
    private void queueInitialScan(Set<Item> enabled) {
        int fromStock = 0;
        // Priorytet 1: itemy obecne w sieci - to one sa widoczne w GUI.
        for (var e : lastStock.entrySet()) {
            if (e.getValue() <= 0 || fromStock >= MAX_FULL_SCAN) {
                continue;
            }
            if (queued.add(e.getKey())) {
                pending.add(e.getKey());
                fromStock++;
            }
        }
        // Priorytet 2: reszta wlaczonych, w ramach limitu i limitu kolejki.
        int fromEnabled = 0;
        for (Item it : enabled) {
            if (fromStock + fromEnabled >= MAX_FULL_SCAN || pending.size() >= MAX_QUEUE) {
                break;
            }
            if (queued.add(it)) {
                pending.add(it);
                fromEnabled++;
            }
        }
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "crafting cache: initial scan queued %d item(s) (%d from stock, %d from enabled)",
                pending.size(), fromStock, fromEnabled);
    }

    // ------------------------------------------------------------------
    // Force-load chunkow z blokami sieci
    // ------------------------------------------------------------------

    /**
     * Maksymalna liczba chunkow, ktore force-loadujemy dla jednej sieci.
     *
     * <p>Bezpiecznik: rozlegla siec (setki rur) nie moze wymusic zaladowania
     * calej mapy. Powyzej limitu ladujemy tylko chunki z wezlami
     * (terminale, craftery, extractory) - to one musza dzialac.
     */
    private static final int MAX_FORCED_CHUNKS = 256;

    /** Chunki, ktore ta siec trzyma zaladowane. */
    private final Set<Long> forcedChunks = new HashSet<>();

    private boolean forceLoadInitialized = false;

    /**
     * Utrzymuje chunki z blokami sieci w stanie zaladowanym.
     *
     * <p>Dzieki temu crafter moze pracowac, a terminal zbierac dane, nawet gdy
     * gracz jest daleko. Bez tego siec "gubi" zawartosc po wyjsciu z zasiegu
     * symulacji i liczby w GUI sa nieaktualne.
     *
     * <p>Chunki sa zwalniane, gdy siec przestaje istniec (patrz {@link #release}).
     */
    private void maintainForcedChunks(ServerLevel level) {
        if (shuttingDown) {
            return;
        }
        Set<BlockPos> toLoad = new HashSet<>();

        // Priorytet: wezly sieci (terminal, crafter, extractor).
        int nodes = 0;
        for (BlockPos p : network.getTerminals()) {
            toLoad.add(p);
            nodes++;
        }
        // Potem rury - o ile mieścimy sie w limicie.
        for (BlockPos p : network.getPipes()) {
            if (toLoad.size() >= MAX_FORCED_CHUNKS) {
                break;
            }
            toLoad.add(p);
        }

        Set<Long> wanted = new HashSet<>();
        for (BlockPos p : toLoad) {
            wanted.add(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4));
        }

        // Zwalniamy to, czego juz nie chcemy. Przez globalny loader - inaczej
        // zabralibysmy chunk innej sieci, ktora nadal go potrzebuje.
        for (long key : new HashSet<>(forcedChunks)) {
            if (!wanted.contains(key)) {
                com.craftingveloce.network.pipe.VeloceChunkLoader.release(level, key);
                forcedChunks.remove(key);
            }
        }
        // Ladujemy to, czego brakuje.
        int added = 0;
        for (long key : wanted) {
            if (forcedChunks.add(key)) {
                com.craftingveloce.network.pipe.VeloceChunkLoader.retain(level, key);
                added++;
            }
        }
        if (!forceLoadInitialized) {
            forceLoadInitialized = true;
            VeloceLog.Network.success(VeloceLog.Side.SERVER,
                    "force-loaded %d chunk(s) for network (%d node(s), %d pipe(s))",
                    forcedChunks.size(), nodes, network.getPipes().size());
        } else if (added > 0) {
            VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                    "force-loaded %d more chunk(s), total %d", added, forcedChunks.size());
        }
    }

    /**
     * Czy serwer sie zamyka.
     *
     * <p>KLUCZOWE dla zapisu swiata. Gdy serwer sie zamyka, Minecraft probuje
     * rozladowac chunki. Jesli nasze force-loady dalej dzialaja, chunk wraca,
     * jest znowu rozladowywany i tak w kolko - zapis swiata sie zawiesza.
     *
     * <p>Dlatego przy zamknieciu: przestajemy wymuszac I zwalniamy wszystko,
     * co trzymalismy.
     */
    private static volatile boolean shuttingDown = false;

    /** Zwalnia force-loady wszystkich sieci. Wolane przy zamykaniu serwera. */
    public static void releaseAll(ServerLevel level) {
        shuttingDown = true;
        int released = 0;
        for (VeloceCraftingCache cache : CACHES.values()) {
            released += cache.forcedChunks.size();
            cache.release(level);
        }
        // Sprzatanie na poziomie loadera: lapie tez chunki wymuszone poza
        // naszymi zbiorami (np. awaryjne doladowanie przy pobieraniu itemu).
        com.craftingveloce.network.pipe.VeloceChunkLoader.releaseAll(level);
        VeloceLog.Network.success(VeloceLog.Side.SERVER,
                "server stopping: released %d forced chunk(s) across %d network(s)",
                released, CACHES.size());
        CACHES.clear();
    }

    /** Zwalnia wszystkie chunki trzymane przez te siec. */
    public void release(ServerLevel level) {
        for (long key : forcedChunks) {
            com.craftingveloce.network.pipe.VeloceChunkLoader.release(level, key);
        }
        VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                "released %d forced chunk(s) for network", forcedChunks.size());
        forcedChunks.clear();
    }

    // ------------------------------------------------------------------
    // Reakcja na zdarzenia swiata
    // ------------------------------------------------------------------

    /** Inventory podlaczone/odlaczone albo chunk zaladowany/rozladowany. */
    public void onEndpointChanged(ServerLevel level) {
        // Zmienil sie sklad sieci (dolaczona/odlaczona skrzynia, chunk, wezel).
        // Nie wiemy CO dokladnie, wiec trzeba przeliczyc na nowo.
        //
        // Wazne: NIE czyscimy kolejki i NIE kolejkujemy wszystkiego od razu.
        // Poprzednia wersja przy kazdym wywolaniu startowala od zera, a ze
        // zdarzen bylo duzo, cache nigdy nie konczyl liczenia - liczby w GUI
        // zostawaly stare az do ponownego otwarcia terminala.
        //
        // Zamiast tego: wymuszamy na najblizszym ticku zwykly diff stocku.
        // Diff sam wykryje, ktore itemy zniknely/pojawily sie w sieci i
        // zakolejkuje TYLKO ich lancuch. Poprzednia wersja ustawiala
        // needsFullRescan, co czyscilo cache i wrzucalo z powrotem wszystkie
        // ~500 itemow przy KAZDEJ zmianie skladu sieci. Przy kilku sieciach
        // i ciaglych zmianach (chunk load/unload) kolejka nigdy sie nie
        // konczyla i serwer przestawal odpowiadac na interakcje gracza.
        forceStockScan = true;
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "crafting cache: network composition changed - stock diff scheduled");
    }

    /**
     * Wymus pelny skan stocku przy najblizszym ticku.
     *
     * <p>Uzywane gdy zmienil sie uklad sieci (wezel postawiony albo usuniety).
     * Nie robimy skanu natychmiast - to by oznaczalo prace w losowym momencie,
     * np. podczas ladowania swiata. Zamiast tego przyspieszamy najblizszy tick.
     */
    public void forceRefreshOnNextTick() {
        lastFullStockScan = Long.MIN_VALUE;
    }

    /**
     * Gracz wlasnie wyciagnal albo wlozyl itemy - przyspiesz odswiezanie.
     *
     * <p>Wywolywane po operacji na terminalu, hopperze czy rurze. Przez
     * kilkanascie tickow cache skanuje stock czesciej, zeby liczby "+N"
     * zaktualizowaly sie od razu, a nie po sekundzie.
     */
    public void markBusy(ServerLevel level) {
        busyUntil = level.getGameTime() + 40;   // 2 s przyspieszonego skanowania
    }

    /** Item zmienil sie lokalnie (np. po craftowaniu). */
    public void invalidateChain(ServerLevel level, Set<Item> changed) {
        queueChain(level, changed);
    }

    /** Wymus przeliczenie jednego itemu. */
    public void invalidate(Item item) {
        if (queued.add(item)) {
            pending.add(item);
        }
    }


}
