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
    /**
     * Twardy sufit itemow na tick - druga linia obrony obok budzetu czasu.
     *
     * <p>Budzet czasu jest ograniczeniem glownym, ale gdyby kiedys znow
     * przestal dzialac (a juz raz przestal - patrz estimateBudgetExceeded),
     * ten licznik sam z siebie nie pozwoli zamulic ticku.
     */
    private static final int MAX_ITEMS_PER_TICK = 16;

    /** Bezpiecznik na dlugosc lancucha "w gore". */
    private static final int MAX_CHAIN = 512;

    /** Bezpiecznik na pelny skan. */
    private static final int MAX_FULL_SCAN = 64;

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

    /**
     * Siec, ktorej dotyczy ten cache.
     *
     * <p><b>NIE jest finalna i to jest celowe.</b> Przebudowa sieci tworzy NOWY
     * obiekt {@link VelocePipeNetwork} pod TYM SAMYM UUID. Gdyby cache trzymal
     * stara referencje, {@code maintainForcedChunks} liczylby chunki z sieci,
     * ktora juz nie istnieje. Wczesniej obchodzono to kasujac cache przy kazdej
     * przebudowie - a skasowanie cache zwalnia jego force-loady, wiec KAZDA
     * przebudowa wykladowywala i ladowala chunki od nowa.
     */
    private volatile VelocePipeNetwork network;

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

    /** Ile razy tick przekroczyl budzet - liczone dla /cv perf. */
    private long overruns;

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
        VeloceCraftingCache cache = CACHES.computeIfAbsent(
                network.getId(), id -> new VeloceCraftingCache(network));
        // Siec mogla zostac przebudowana pod tym samym UUID - odswiezamy
        // referencje, zamiast kasowac cache (co kosztowaloby force-loady).
        if (cache.network != network) {
            cache.network = network;
        }
        return cache;
    }

    /**
     * Usuwa cache sieci i <b>zwalnia jej force-loady</b>.
     *
     * <p>Poprzednia wersja tylko usuwala wpis z mapy. Cache trzymal wtedy
     * swoje chunki w globalnym loaderze na zawsze: siec znikala, a chunki
     * zostawaly wymuszone do konca sesji. Przy stawianiu i burzeniu sieci
     * (albo przy kazdej zmianie ukladu rur) to sie zbieralo.
     */
    public static void drop(ServerLevel level, UUID networkId) {
        VeloceCraftingCache cache = CACHES.remove(networkId);
        if (cache != null) {
            cache.release(level);
        }
    }

    public static void clearAll() {
        CACHES.clear();
    }

    /** Liczba zywych cache'ow - do wykrywania wyciekow. */
    public static int liveCount() {
        return CACHES.size();
    }




    // ------------------------------------------------------------------
    // Odczyt (GUI) - zawsze natychmiastowy, nic nie liczy
    // ------------------------------------------------------------------








    // ------------------------------------------------------------------
    // Praca w tle - z budzetem czasowym
    // ------------------------------------------------------------------

    /**
     * Tick gry, w ktorym ostatnio zrobilismy krok pracy.
     *
     * <p><b>Po co.</b> {@code tickCraftingCache} wola ten cache KAZDY terminal
     * w sieci, co 5 tickow. Przy dwoch terminalach cache wykonywal dwa kroki
     * w tym samym ticku - czyli budzet 10 ms zamienial sie w 20 ms, a przy
     * wiekszej liczbie terminali rosl dalej. Blokada "raz na tick" trzyma
     * budzet tam, gdzie ma byc, niezaleznie od liczby terminali.
     *
     * @return true gdy w tym ticku juz pracowalismy (wolajacy ma wyjsc)
     */
    private boolean claimTick(ServerLevel level) {
        long now = level.getGameTime();
        if (now == lastTickedGameTime) {
            return true;
        }
        lastTickedGameTime = now;
        return false;
    }

    /** Ostatni tick gry, w ktorym zrobilismy krok. */
    private long lastTickedGameTime = Long.MIN_VALUE;

    /**
     * Krok pracy, gdy nikt nie patrzy.
     *
     * <p>Robimy wtedy DOKLADNIE jedna rzecz: utrzymujemy force-loady chunkow
     * z blokami sieci. To musi dzialac zawsze, bo bez tego ekstraktory i
     * craftery przestaja pracowac, gdy gracz odejdzie.
     *
     * <p>Po co osobna metoda: dzieki niej wolajacy nie musi przygotowywac
     * argumentow ({@code getAllEnabledItems} kopiuje zbior wszystkich
     * craftowalnych itemow, {@code getPreferredRecipes} buduje mape) tylko po
     * to, zeby {@code tick} wyszedl w pierwszej instrukcji.
     */
    public void tickIdle(ServerLevel level) {
        if (claimTick(level)) {
            return;
        }

        if (!startupGracePassed) {
            if (firstSeenTick < 0) {
                firstSeenTick = level.getGameTime();
            }
            if (level.getGameTime() >= firstSeenTick
                    && level.getGameTime() - firstSeenTick < STARTUP_GRACE_TICKS) {
                return;
            }
            startupGracePassed = true;
        }

        // Jedyne, co ten cache teraz robi: trzyma wymuszone chunki z blokami
        // sieci. Bez tego ekstraktory i craftery przestalyby pracowac, gdy
        // gracz odejdzie od bazy. Rzadko (co sekunde), bo to i tak tanie.
        if (!shuttingDown && level.getGameTime() % 20 == 0) {
            maintainForcedChunks(level);
        }
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
        overruns++;
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
        // Podwojne zabezpieczenie. Gdyby shuttingDown i frozen kiedys sie
        // rozjechaly, VeloceChunkLoader.retain() nie zabraloby referencji,
        // ale petla ponizej i tak dopisalaby chunk do forcedChunks - a
        // pozniejszy release() zdjalby wtedy referencje NALEZACA KOMUS INNEMU.
        // Warunek lokalny jest tanszy niz ta klasa bledow.
        if (com.craftingveloce.network.pipe.VeloceChunkLoader.isFrozen()) {
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

        // Zwalniamy to, czego juz nie chcemy ALBO czego loader juz nie trzyma.
        //
        // Drugi warunek jest istotny: po rozladowaniu swiata loader zwalnia
        // swoje chunki, a cache nadal ma je w ksiegowosci. Bez tego sprawdzenia
        // uznalby, ze juz je trzyma, i NIGDY nie wymusilby ich ponownie.
        // release() na nieistniejaca referencje jest bezpiecznym no-opem.
        for (long key : new HashSet<>(forcedChunks)) {
            if (!wanted.contains(key)
                    || !com.craftingveloce.network.pipe.VeloceChunkLoader.isHeld(level, key)) {
                com.craftingveloce.network.pipe.VeloceChunkLoader.release(level, key);
                forcedChunks.remove(key);
            }
        }
        // Ladujemy to, czego brakuje.
        int added = 0;
        for (long key : wanted) {
            if (!forcedChunks.contains(key)) {
                com.craftingveloce.network.pipe.VeloceChunkLoader.retain(level, key);
                forcedChunks.add(key);
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

    /**
     * Wchodzimy do swiata - znowu wolno wymuszac chunki.
     *
     * <p><b>Bez tego byl ciezki, cichy bug.</b> {@code releaseAll} ustawialo
     * {@code shuttingDown = true} i NIKT tego nie cofal. W trybie single-player
     * wystarczylo wyjsc do menu i wejsc ponownie (LevelEvent.Unload -> releaseAll),
     * zeby force-loading chunkow przestal dzialac DO KONCA SESJI: ekstraktory,
     * craftery i terminale w dalszych chunkach przestawaly byc tickowane,
     * a liczby w GUI zostawaly stare.
     */
    public static void onLevelLoaded() {
        shuttingDown = false;
        com.craftingveloce.network.pipe.VeloceChunkLoader.unfreeze();
    }

    /**
     * Rozladowanie jednego swiata (np. wyjscie z Netheru).
     *
     * <p><b>Celowo NIE ustawia flagi "serwer sie zamyka".</b> Wczesniej ten
     * przypadek szedl ta sama droga co zamkniecie serwera, wiec rozladowanie
     * jednego wymiaru wylaczalo force-loading wszystkim pozostalym - i nic tego
     * nie cofalo, bo {@code LevelEvent.Load} dla Nadswiata juz nie poleci.
     *
     * <p>Czyscimy tez ksiegowosc {@code forcedChunks}: loader wlasnie zwolnil
     * te chunki, wiec gdybysmy zostawili je w zbiorach, po ponownym wczytaniu
     * swiata {@code maintainForcedChunks} uznalby, ze juz je trzyma, i nigdy
     * by ich nie wymusil z powrotem.
     */
    public static void onLevelUnloaded(ServerLevel level) {
        // NIE czyscimy tu forcedChunks zadnego cache'u.
        //
        // VeloceChunkLoader.releaseAll() zwalnia chunki TYLKO tego jednego
        // swiata, a CACHES sa wspolne dla wszystkich wymiarow. Wyczyszczenie
        // ksiegowosci wszystkim cache'om oznaczalo, ze cache'e z INNYCH
        // wymiarow tracily informacje o trzymanych chunkach, ktore loader
        // nadal trzymal - a przy nastepnym maintainForcedChunks doliczaly
        // druga referencje. Licznik rosl bez konca, a raz zwolniony chunk
        // zostawal wymuszony na zawsze.
        //
        // Uzgodnienie robi teraz sam maintainForcedChunks przez isHeld().
        int released = com.craftingveloce.network.pipe.VeloceChunkLoader.appliedCount(level);
        com.craftingveloce.network.pipe.VeloceChunkLoader.releaseAll(level);
        VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                "level unloaded: released %d forced chunk(s), caches reconciled later", released);
    }

    /** Zwalnia force-loady wszystkich sieci. Wolane przy zamykaniu serwera. */
    public static void releaseAll(ServerLevel level) {
        shuttingDown = true;
        // Zamraza WSZYSTKIE force-loady, nie tylko te z cache. Bez tego
        // awaryjne doladowanie chunku przy pobieraniu itemu (extractItem)
        // omijalo blokade i zawieszalo zapis swiata.
        com.craftingveloce.network.pipe.VeloceChunkLoader.freeze();
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







}
