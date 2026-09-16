package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Trzyma chunki z blokami sieci w stanie zaladowanym.
 *
 * <p><b>Historia.</b> Ta klasa byla kiedys cache'em craftowalnosci: liczyla w
 * tle "ile da sie dorobic" dla kazdego itemu i podawala te liczby GUI. Okazalo
 * sie to zrodlem najgorszych problemow w modzie - liczenie jest rekurencyjne i
 * drogie, a przy kilku tysiacach receptur zadne "przeliczmy regularnie" nie
 * dalo sie utrzymac w budzecie ticku. Zostalo to usuniete: liczby liczy teraz
 * {@link VeloceAutoCrafter} na zadanie (widoczna strona terminala), z wlasnym
 * budzetem czasu.
 *
 * <p><b>Co zostalo.</b> Dokladnie jedna rzecz: force-load chunkow. Bez tego
 * ekstraktory i craftery przestalyby pracowac, gdy gracz odejdzie od bazy, a
 * terminale nie widzialyby zawartosci sieci.
 *
 * <p><b>Zasady, ktorych przestrzega ta klasa:</b>
 * <ol>
 *   <li><b>Raz na tick.</b> Kazdy terminal w sieci wola {@link #tickIdle}, ale
 *       praca idzie tylko raz na tick gry ({@code claimTick}).</li>
 *   <li><b>Karencja startowa.</b> Po wczytaniu swiata nie ruszamy chunkow od
 *       razu - ladowanie save'a to najbardziej obciazony moment w cyklu
 *       zycia serwera.</li>
 *   <li><b>Zamkniecie serwera.</b> Gdy serwer sie zamyka, przestajemy wymuszac
 *       i zwalniamy wszystko. Inaczej zapis swiata sie zawiesza (chunk wraca,
 *       jest rozladowywany i tak w kolko).</li>
 * </ol>
 */
public final class VeloceCraftingCache {

    /**
     * Ile tickow po starcie swiata czekamy z pierwszym skanem.
     *
     * <p>Kluczowe: ladowanie save'a to najbardziej obciazony moment w cyklu
     * zycia serwera (generowanie chunkow, wczytywanie encji, swiatlo). Jesli
     * zaczniemy wtedy liczyc, dojdziemy do zawieszenia - co juz sie raz stalo
     * przy 2118 zakolejkowanych itemach.
     */
    private static final int STARTUP_GRACE_TICKS = 200;   // 10 s

    /**
     * Maksymalna liczba CHUNKOW, ktore force-loadujemy dla jednej sieci.
     *
     * <p>Bezpiecznik: rozlegla siec (setki rur) nie moze wymusic zaladowania
     * calej mapy. Powyzej limitu ladujemy tylko chunki z wezlami
     * (terminale, craftery, extractory) - to one musza dzialac.
     *
     * <p><b>Liczy CHUNKI, nie pozycje blokow.</b> To rozroznienie jest istotne:
     * 218 rur stojacych w linii to 218 pozycji, ale az 14 chunkow - a siec
     * rozciagnieta po bazie potrafi dac kilkadziesiat chunkow przy limicie
     * wygladajacym na bezpieczny. Wczesniej limit liczyl pozycje, wiec
     * przepuszczal dokladnie te przypadki, ktore mial blokowac.
     */
    private static final int MAX_FORCED_CHUNKS = 64;

    /**
     * Klucz cache: WYMIAR + identyfikator sieci.
     *
     * <p><b>BUG, ktory to naprawia (miedzywymiarowy).</b> Kluczem bylo samo UUID
     * sieci, a to liczy sie z POZYCJI reprezentanta komponentu:
     * {@code UUID.nameUUIDFromBytes(root.toShortString())}. Siec w Netherze
     * stojaca na tych samych wspolrzednych co siec w Nadswiecie dostawala wiec
     * IDENTYCZNY identyfikator - a {@code CACHES} jest mapa statyczna, wspolna
     * dla wszystkich wymiarow. Obie sieci dzielily wiec JEDEN wpis:
     * <ul>
     *   <li>cache trzymal chunki tej sieci, ktora odezwala sie ostatnia,</li>
     *   <li>force-loady szly do zlego wymiaru (albo w ogole nie dzialaly),</li>
     *   <li>{@code retainOnly} jednego wymiaru kasowalo cache drugiego
     *       - razem z jego force-loadami.</li>
     * </ul>
     *
     * <p>Wyrownane portale (baza w tym samym miejscu w obu wymiarach) to
     * typowy uklad, wiec to nie jest teoria.
     */
    private record CacheKey(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                            UUID networkId) {
    }

    private static final Map<CacheKey, VeloceCraftingCache> CACHES = new HashMap<>();

    private static CacheKey keyOf(ServerLevel level, UUID networkId) {
        return new CacheKey(level.dimension(), networkId);
    }

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

    /** Chunki, ktore ta siec trzyma zaladowane. */
    private final Set<Long> forcedChunks = new HashSet<>();

    private boolean forceLoadInitialized = false;

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

    /** Ostatni tick gry, w ktorym zrobilismy krok. */
    private long lastTickedGameTime = Long.MIN_VALUE;

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

    private VeloceCraftingCache(VelocePipeNetwork network) {
        this.network = network;
    }

    public static VeloceCraftingCache get(ServerLevel level, VelocePipeNetwork network) {
        VeloceCraftingCache cache = CACHES.computeIfAbsent(
                keyOf(level, network.getId()), id -> new VeloceCraftingCache(network));
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
        VeloceCraftingCache cache = CACHES.remove(keyOf(level, networkId));
        if (cache != null) {
            cache.release(level);
        }
    }

    /**
     * Zwalnia cache sieci, ktorych nie ma na liscie zywych.
     *
     * <p>Identyfikator sieci liczymy z reprezentanta komponentu, wiec przy
     * podziale lub scaleniu sieci powstaje nowe UUID. Stary cache zostawal
     * wtedy w mapie na zawsze - razem ze swoimi force-loadami, ktore nigdy
     * nie byly zwalniane.
     *
     * @return ile nieaktualnych cache'y zwolniono
     */
    public static int retainOnly(ServerLevel level, java.util.Set<UUID> liveIds) {
        java.util.List<UUID> stale = new java.util.ArrayList<>();
        // Tylko cache TEGO wymiaru. Wczesniej petla szla po wszystkich, wiec
        // uzgadnianie w Nadswiecie kasowalo cache sieci w Netherze (ich UUID
        // moga byc identyczne - patrz CacheKey).
        for (Map.Entry<CacheKey, VeloceCraftingCache> e : CACHES.entrySet()) {
            if (e.getKey().dimension().equals(level.dimension())
                    && !liveIds.contains(e.getKey().networkId())) {
                stale.add(e.getKey().networkId());
            }
        }
        for (UUID id : stale) {
            drop(level, id);
        }
        return stale.size();
    }

    /** Liczba zywych cache'ow - do wykrywania wyciekow. */
    public static int liveCount() {
        return CACHES.size();
    }

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

        if (shuttingDown) {
            return;
        }

        // BUG, ktory tu byl: warunek `gameTime % 20 == 0` sprawdzal DOKLADNA
        // rownosc z wielokrotnoscia 20. A tickIdle wolane jest tylko raz na
        // tick (claimTick), przez tego terminala, ktory akurat trafil - wiec
        // jesli terminal wola w tickach 1, 6, 11, 16, 21..., to w zycie NIGDY
        // nie trafi w wielokrotnosc 20 i wymuszanie chunkow nie zdarzy sie
        // ANI RAZU.
        //
        // Objaw: "wymuszonych chunkow: 0" mimo ze terminal stoi i jest wykryty
        // jako wezel - a bez force-loadu terminal i crafter przestaja pracowac,
        // gdy gracz odejdzie od bazy.
        //
        // Miara odstepu, a nie rownosc: dziala niezaleznie od fazy, w ktorej
        // wolajacy trafia.
        long now = level.getGameTime();
        if (!com.craftingveloce.util.VeloceTick.every(
                now, lastMaintainTick, FORCE_MAINTAIN_INTERVAL_TICKS)) {
            return;
        }
        lastMaintainTick = now;
        maintainForcedChunks(level);
        // Wygasle bilety "gorących" chunkow trzeba zdjac, inaczej chunk
        // uznany raz za czesto uzywany zostawal wymuszony na zawsze.
        com.craftingveloce.network.pipe.VeloceChunkLoader.expireHotTickets(level);
    }

    /**
     * Co ile tickow utrzymujemy force-loady.
     *
     * <p>Mierzone jako ODSTEP od ostatniego razu, a nie rownosc z wielokrotnoscia -
     * patrz komentarz w {@link #tickIdle}.
     */
    private static final long FORCE_MAINTAIN_INTERVAL_TICKS = 20L;

    /** Tick ostatniego utrzymania force-loadow. */
    private long lastMaintainTick = Long.MIN_VALUE;

    /**
     * Tick gry, w ktorym ostatnio zrobilismy krok pracy.
     *
     * <p><b>Po co.</b> Ten krok wolal kiedys kazdy terminal z osobna
     * w sieci, co 5 tickow. Przy dwoch terminalach cache wykonywal dwa kroki
     * w tym samym ticku. Blokada "raz na tick" trzyma go tam, gdzie ma byc,
     * niezaleznie od liczby terminali.
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

        // Mapa: chunk -> blok, ktory jest powodem trzymania (do raportu).
        // Limit jest juz nalozony na CHUNKI w collectChunksToKeep.
        Map<Long, BlockPos> wanted = collectChunksToKeep(level);

        releaseUnwantedChunks(level, wanted.keySet());
        int added = retainWantedChunks(level, wanted);
        logForceLoad(added);
    }

    /** Nazwa wlasciciela biletu dla tej sieci - widoczna w raporcie. */
    private String ticketOwner() {
        return "net:" + network.getId().toString().substring(0, 8);
    }

    /**
     * Chunki, ktore ta siec powinna trzymac w pamieci.
     *
     * <p><b>Trzymamy TYLKO chunki z wezlami.</b> Wczesniej ladowalismy kazdy
     * chunk, w ktorym stala RURA - i to byl blad projektowy.
     *
     * <p>Rura to zwykly blok-lacznik. Nie ma wlasnego block entity z logika,
     * ktora musi tykac, i nic nie traci, gdy jej chunk wypadnie z symulacji:
     * polaczenia sieci trzymamy we WLASNYCH strukturach w pamieci
     * ({@code VelocePipeNetwork.pipes}), a nie w swiecie. Rozladowanie chunku
     * z rura nie rozrywa sieci ani nie gubi danych.
     *
     * <p>Trzymac trzeba natomiast chunki z tym, co faktycznie PRACUJE:
     * terminalem, crafterem i extractorem. One maja block entity, ktore bez
     * symulacji przestaje dzialac - i dokladnie po to jest ten mechanizm.
     *
     * <p>Skutek poprzedniej wersji widac bylo golym okiem: rura pociagnieta
     * daleko od bazy, z jedna beczka na koncu, trzymala caly swoj chunk
     * zaladowany - mimo ze w tym chunku nie bylo niczego, co wymaga symulacji.
     * Przy dlugiej sieci (218 rur w logu) dawalo to kilkanascie chunkow
     * sforsowanych "przy okazji", w dodatku bez ani jednej operacji na itemach.
     *
     * <p>Wezly maja priorytet absolutny; limit {@link #MAX_FORCED_CHUNKS}
     * ucina tylko przypadki skrajne (baza z setkami wezlow).
     */
    private Map<Long, BlockPos> collectChunksToKeep(ServerLevel level) {
        List<BlockPos> nodes = new java.util.ArrayList<>(network.getTerminals());
        nodes.sort(POSITION_ORDER);

        Map<Long, BlockPos> chosen = new LinkedHashMap<>();
        for (BlockPos p : nodes) {
            // Wezly OZDOBNE (np. klatka) nie trzymaja chunku - patrz
            // VeloceNetworkNode.keepChunkLoaded. Sprawdzamy to PO bloku, bo
            // decyduje o tym sam blok, a nie lista typow w tym miejscu.
            if (!keepsChunkLoaded(level, p)) {
                continue;
            }
            if (chosen.size() >= MAX_FORCED_CHUNKS) {
                VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                        "network %s has more than %d node chunk(s) - rest NOT force-loaded",
                        network.getId().toString().substring(0, 8), MAX_FORCED_CHUNKS);
                break;
            }
            chosen.putIfAbsent(chunkKeyOf(p), p);
        }
        return chosen;
    }

    /**
     * Czy wezel na tej pozycji ma utrzymywany chunk.
     *
     * <p>Nieczytelny wezel (chunk rozladowany) traktujemy jak "trzymaj": nie
     * umiemy wtedy odczytac jego decyzji, a zaprzestanie trzymania
     * uniemozliwiloby kiedykolwiek odczytanie go ponownie.
     */
    private static boolean keepsChunkLoaded(ServerLevel level, BlockPos pos) {
        BlockState state = com.craftingveloce.network.pipe.VeloceChunkLoader
                .blockStateIfLoaded(level, pos);
        if (state == null) {
            return true;
        }
        return !(state.getBlock()
                instanceof com.craftingveloce.network.pipe.VeloceNetworkNode node)
                || node.keepChunkLoaded(state);
    }

    /** Klucz chunku dla pozycji bloku. */
    private static long chunkKeyOf(BlockPos p) {
        return ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4);
    }

    /** Stala kolejnosc pozycji - zeby wynik byl powtarzalny. */
    private static final java.util.Comparator<BlockPos> POSITION_ORDER =
            java.util.Comparator.comparingInt((BlockPos p) -> p.getX())
                    .thenComparingInt((BlockPos p) -> p.getZ())
                    .thenComparingInt((BlockPos p) -> p.getY());

    /**
     * Zwalnia to, czego juz nie chcemy ALBO czego loader juz nie trzyma.
     *
     * <p>Drugi warunek jest istotny: po rozladowaniu swiata loader zwalnia
     * swoje chunki, a cache nadal ma je w ksiegowosci. Bez tego sprawdzenia
     * uznalby, ze juz je trzyma, i NIGDY nie wymusilby ich ponownie.
     * release() na nieistniejaca referencje jest bezpiecznym no-opem.
     */
    private void releaseUnwantedChunks(ServerLevel level, Set<Long> wanted) {
        String owner = ticketOwner();
        for (long key : new HashSet<>(forcedChunks)) {
            if (!wanted.contains(key)
                    || !com.craftingveloce.network.pipe.VeloceChunkLoader.isHeld(level, key)) {
                com.craftingveloce.network.pipe.VeloceChunkLoader.release(level, key, owner);
                forcedChunks.remove(key);
            }
        }
    }

    /** Laduje to, czego brakuje. Zwraca liczbe nowo wymuszonych chunkow. */
    private int retainWantedChunks(ServerLevel level, Map<Long, BlockPos> wanted) {
        int added = 0;
        String owner = ticketOwner();
        for (Map.Entry<Long, BlockPos> e : wanted.entrySet()) {
            long key = e.getKey();
            // Bilet zgłaszamy ZAWSZE, nie tylko przy pierwszym dodaniu.
            //
            // retain() jest idempotentne dla tego samego wlasciciela (jeden
            // bilet na wlasciciela), a zgloszenie przy kazdym przebiegu
            // odtwarza bilet, gdyby zniknal - np. po rozladowaniu jednego
            // wymiaru, gdzie ksiegowosc loadera jest czyszczona, a cache
            // celowo jej nie czysci.
            com.craftingveloce.network.pipe.VeloceChunkLoader.retain(
                    level, key, owner,
                    com.craftingveloce.network.pipe.VeloceChunkLoader.Reason.NETWORK,
                    e.getValue());
            if (forcedChunks.add(key)) {
                added++;
            }
        }
        return added;
    }

    private void logForceLoad(int added) {
        if (!forceLoadInitialized) {
            forceLoadInitialized = true;
            VeloceLog.Network.success(VeloceLog.Side.SERVER,
                    "force-loaded %d chunk(s) for network (%d pipe(s))",
                    forcedChunks.size(), network.getPipes().size());
        } else if (added > 0) {
            VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                    "force-loaded %d more chunk(s), total %d", added, forcedChunks.size());
        }
    }

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
     * <p>Ksiegowosci {@code forcedChunks} NIE czyscimy: CACHES sa wspolne dla
     * wszystkich wymiarow, a {@code releaseAll} zwalnia chunki TYLKO tego
     * jednego swiata. Wyczyszczenie oznaczaloby, ze cache'e z innych wymiarow
     * traca informacje o chunkach, ktore loader nadal trzyma - i przy nastepnym
     * {@code maintainForcedChunks} doliczaly druga referencje. Uzgodnienie robi
     * teraz sam {@code maintainForcedChunks} przez {@code isHeld()}.
     */
    public static void onLevelUnloaded(ServerLevel level) {
        int released = com.craftingveloce.network.pipe.VeloceChunkLoader.appliedCount(level);
        // Cache'e tego wymiaru MUSZA zniknac razem z nim.
        //
        // Trzymaja referencje do obiektu ServerLevel, ktory po wyjsciu do menu
        // przestaje istniec - a przy ponownym wejsciu powstaje NOWY obiekt.
        // Stary cache wymuszalby wtedy chunki na martwym swiecie.
        CACHES.keySet().removeIf(k -> k.dimension().equals(level.dimension()));
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
        // Tylko TEN wymiar: przy zatrzymaniu serwera ta metoda leci dla kazdego
        // poziomu po kolei, a wczesniej pierwsze wywolanie czyscilo CALA mape -
        // wiec pozostale wymiary nie zwalnialy juz niczego po stronie cache.
        java.util.List<CacheKey> mine = new java.util.ArrayList<>();
        for (Map.Entry<CacheKey, VeloceCraftingCache> e : CACHES.entrySet()) {
            if (e.getKey().dimension().equals(level.dimension())) {
                mine.add(e.getKey());
                released += e.getValue().forcedChunks.size();
                e.getValue().release(level);
            }
        }
        mine.forEach(CACHES::remove);
        // Sprzatanie na poziomie loadera: lapie tez chunki wymuszone poza
        // naszymi zbiorami (np. awaryjne doladowanie przy pobieraniu itemu).
        com.craftingveloce.network.pipe.VeloceChunkLoader.releaseAll(level);
        VeloceLog.Network.success(VeloceLog.Side.SERVER,
                "server stopping: released %d forced chunk(s) across %d network(s)",
                released, mine.size());
    }

    /**
     * Krok utrzymania dla WSZYSTKICH sieci tego wymiaru.
     *
     * <p><b>BUG, ktory to naprawia.</b> {@code tickIdle} bylo wolane WYLACZNIE
     * przez terminal ({@code VeloceTomTerminalBlockEntity.tickCraftingCache}).
     * Siec bez terminala - np. sam crafter z piecem i skrzyniami - nie
     * utrzymywala wiec swoich chunkow ANI RAZU: crafter i piece przestawaly
     * pracowac, gdy gracz odszedl, a wygaszanie "gorących" biletow nie
     * dzialalo wcale. Czyli automatyka padala dokladnie w tej sytuacji, do
     * ktorej istnieje.
     *
     * <p>Teraz sterownikiem jest TICK POZIOMU, wiec dziala niezaleznie od tego,
     * jakie bloki stoja w sieci. Terminal nie wola juz tego u siebie - jeden
     * sterownik, jedno miejsce.
     */
    public static void tickAll(ServerLevel level) {
        if (shuttingDown) {
            return;
        }
        com.craftingveloce.network.pipe.VelocePipeNetworkManager manager =
                com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(level);

        // CACHE MUSI POWSTAC DLA KAZDEJ ZNANEJ SIECI - inaczej nie ma czego
        // utrzymywac i force-loady nie dzialaja WCALE.
        //
        // BUG, ktory to naprawia (zgloszenie gracza: "nie moge skraftowac ani
        // glass, ani crushing wheela"; w logu "2 node(s) of the network could
        // not be read (chunk not loaded)"): po przeniesieniu utrzymania
        // force-loadow z terminala na tick poziomu zostala tylko petla po
        // ISTNIEJACYCH cache'ach, a cache nie tworzyl juz NIKT. Mapa byla
        // pusta, wiec chunki z piecem, crafterem i maszynami modulow
        // rozladowywaly sie, gdy gracz odszedl do terminala - a wtedy siec ich
        // nie widziala i nic nie bylo craftowalne, mimo ze GUI pokazywalo
        // liczby policzone wczesniej, gdy chunki byly jeszcze zaladowane.
        java.util.Set<UUID> live = new java.util.HashSet<>();
        for (VelocePipeNetwork network : manager.knownNetworks()) {
            live.add(network.getId());
            get(level, network).tickIdle(level);

            // Krok energii: pobor z obcych zrodel (maxRate = 20_000, jak kiedys w rurze)
            int energyRate = 20_000;
            var buffer = network.getEnergyBuffer();
            com.craftingveloce.network.pipe.VeloceEnergyPull.pull(level, network, buffer, energyRate);

            // Rozdanie pradu do maszyn sieci (terminals = wszystkie wezly, w tym piec/moduly)
            if (buffer.getEnergyStored() > 0) {
                for (BlockPos pos : network.getTerminals()) {
                    if (buffer.getEnergyStored() <= 0) {
                        break;
                    }
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    var target = level.getCapability(
                            net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                            pos, null);
                    if (target != null && target.canReceive()) {
                        int accepted = target.receiveEnergy(
                                Math.min(buffer.getEnergyStored(), energyRate), false);
                        if (accepted > 0) {
                            buffer.extractEnergy(accepted, false);
                        }
                    }
                }
            }
        }

        // Cache po sieciach, ktore zniknely z menedzera - zwolnij chunki,
        // zeby nie zostaly wymuszone do konca sesji.
        for (CacheKey key : new java.util.ArrayList<>(CACHES.keySet())) {
            if (!key.dimension().equals(level.dimension()) || live.contains(key.networkId())) {
                continue;
            }
            VeloceCraftingCache dead = CACHES.remove(key);
            if (dead != null) {
                dead.release(level);
            }
        }
    }

    /** Zwalnia wszystkie chunki trzymane przez te siec. */
    public void release(ServerLevel level) {
        String owner = ticketOwner();
        for (long key : forcedChunks) {
            com.craftingveloce.network.pipe.VeloceChunkLoader.release(level, key, owner);
        }
        VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                "released %d forced chunk(s) for network", forcedChunks.size());
        forcedChunks.clear();
    }
}
