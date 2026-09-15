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

    /** Maksymalny czas pracy na jeden tick, w nanosekundach (2 ms). */
    private static final long TICK_BUDGET_NS = 2_000_000L;

    /** Twardy limit pojedynczego przeliczenia - jeden item nie moze zamulic ticku. */
    private static final int MAX_ITEMS_PER_TICK = 64;

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
    private static final int FULL_STOCK_SCAN_INTERVAL = 200;   // 10 s

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
        if (firstScan || now - lastFullStockScan >= FULL_STOCK_SCAN_INTERVAL) {
            lastFullStockScan = now;
            detectStockChanges(level);
            if (System.nanoTime() - start > TICK_BUDGET_NS) {
                lastTickNanos = System.nanoTime() - start;
                return;
            }
        }

        // 1b. Utrzymuj chunki z blokami sieci (rzadko - co sekunde).
        if (now % 20 == 0) {
            maintainForcedChunks(level);
        }

        // 2. Pierwszy skan. Kolejkujemy itemy obecne w sieci (to widzi GUI)
        //    oraz - w ramach limitu - reszte wlaczonych, zeby liczby byly
        //    dostepne takze dla itemow, ktorych chwilowo nie ma.
        if (!initialScanQueued && pending.isEmpty()) {
            initialScanQueued = true;
            queueInitialScan(enabledItems);
        }

        // 3. Przeliczaj porcje, dopoki starcza budzetu.
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
            long n = VeloceAutoCrafter.countCraftableNow(
                    level, network, item, enabledItems, preferred);
            if (n > 0) {
                craftable.put(item, n);
            } else {
                craftable.remove(item);
            }
            lastBatchSize++;
            totalComputed++;
        }

        if (pending.isEmpty() && !fullScanDone) {
            fullScanDone = true;
            VeloceLog.Craft.success(VeloceLog.Side.SERVER,
                    "crafting cache: initial scan done (%d items computed, %d ms last tick)",
                    totalComputed, lastTickMillis());
        }

        lastTickNanos = System.nanoTime() - start;
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

        // Zwalniamy to, czego juz nie chcemy.
        for (long key : new HashSet<>(forcedChunks)) {
            if (!wanted.contains(key)) {
                level.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), false);
                forcedChunks.remove(key);
            }
        }
        // Ladujemy to, czego brakuje.
        int added = 0;
        for (long key : wanted) {
            if (forcedChunks.add(key)) {
                level.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), true);
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

    /** Zwalnia wszystkie chunki trzymane przez te siec. */
    public void release(ServerLevel level) {
        for (long key : forcedChunks) {
            level.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), false);
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
        // Nie wiemy co sie zmienilo - trzeba na nowo odczytac stock.
        // Robimy to leniwie: najblizszy pelny skan to zalapie, a dodatkowo
        // przeliczamy wszystko, bo sklad mogl sie zmienic globalnie.
        lastFullStockScan = Long.MIN_VALUE;
        queueAll(enabledFallback());
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "crafting cache: endpoint set changed - scheduling refresh");
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

    /** Kolejkuje wszystko, co znamy. */
    private void queueAll(Set<Item> items) {
        for (Item it : items) {
            if (queued.add(it)) {
                pending.add(it);
            }
        }
    }

    private Set<Item> enabledFallback() {
        return new HashSet<>(craftable.keySet());
    }
}
