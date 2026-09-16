package com.craftingveloce.network.pipe;

import com.craftingveloce.block.VelocePipeBlock;
import com.craftingveloce.block.entity.VelocePipeBlockEntity;
import com.craftingveloce.rs.RefinedStorageHelper;
import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

public class VelocePipeNetworkManager extends SavedData {

    private final Map<UUID, VelocePipeNetwork> networks = new HashMap<>();
    private final Map<BlockPos, UUID> pipeToNetwork = new HashMap<>();
    private final Map<BlockPos, UUID> terminalToNetwork = new HashMap<>();

    /**
     * Rury czekajace na przebudowe sieci.
     *
     * <p><b>Po co kolejka.</b> {@code Block.neighborChanged} odpala sie przy
     * KAZDYM update sasiada - a w bazie z Create, hopperami czy czerwonym
     * kamieniem to potrafi byc kilka razy na tick. Wczesniej kazde takie
     * zdarzenie robilo od razu pelny BFS sieci plus uniewaznienie cache
     * wszystkich dotknietych sieci. Maszyna stojaca obok rury zamulala
     * serwer sama swoja praca.
     *
     * <p>Teraz zbieramy tylko pozycje i przebudowujemy najwyzej raz na
     * {@link #REBUILD_COOLDOWN_TICKS} tickow, z ticku serwera.
     */
    private final Set<BlockPos> pendingRebuilds = new java.util.LinkedHashSet<>();

    /**
     * Plaska struktura wszystkich rur: pozycja -> realne polaczenia.
     *
     * <p>To jest ZRODLO PRAWDY o tym, co jest z czym polaczone. Sieci
     * ({@link VelocePipeNetwork}) sa tylko WYNIKIEM zapytania na tej
     * strukturze - nie ma juz zadnego scalania ani rozdzielania obiektow.
     *
     * <p>Dzieki temu rozciecie sieci nie wymaga zgadywania podzialu: komponenty
     * rozdzielaja sie same, bo wynikaja wprost z polaczen miedzy r urami.
     */
    private final VelocePipeWorld world = new VelocePipeWorld();

    /**
     * Trwaly rejestr magazynow: pozycja -> informacja o magazynie.
     *
     * <p><b>Po co osobno.</b> Magazyn moze stac w niezaladowanym chunku, wiec
     * nie da sie go odczytac ze swiata - a mimo to trzeba pamietac, ze istnieje
     * i co w nim bylo. Ten rejestr przetrzymuje takie wpisy miedzy
     * przebudowami sieci.
     *
     * <p>Wpis aktualizujemy, gdy chunk jest zaladowany (swiezy odczyt), a gdy
     * nie jest - zostawiamy ostatnia znana zawartosc. Skoro chunk nie jest
     * symulowany, nikt tych itemow nie ruszyl, wiec liczba jest nadal prawdziwa.
     */
    private final Map<BlockPos, ConnectedEndpointInfo> knownEndpoints = new HashMap<>();

    /**
     * Trwaly rejestr wezlow (terminal, crafter, extractor, kontroler).
     *
     * <p>Ten sam powod co {@code knownEndpoints}: cache komponentu zamraza
     * zbior wezlow w chwili budowy, wiec wezel dodany pozniej nie bylby
     * widziany przez sciezke cache'owana. Tutaj trzymamy je niezaleznie
     * i przynaleznosc liczymy przez sasiedztwo z rurami.
     */
    private final Set<BlockPos> knownNodes = new HashSet<>();

    /** Tick ostatniej przebudowy - do odstepu miedzy nimi. */
    private long lastRebuildTick = Long.MIN_VALUE;

    /**
     * Budzet czasu na cala partie przebudow w jednym ticku.
     *
     * <p>Musi byc MNIEJSZY niz budzet ticku (50 ms), zeby zostalo miejsce na
     * reszte gry. Pojedyncza przebudowa ma wlasny budzet
     * ({@link #SCAN_BUDGET_NS}), ale bez wspolnego limitu kilka przebudow
     * pod rzad sumowalo sie do wartosci wiekszej niz caly tick.
     */
    private static final long REBUILD_BATCH_BUDGET_NS = 25_000_000L;

    /** Minimalny odstep miedzy przebudowami sieci, w tickach (5 na sekunde). */
    private static final int REBUILD_COOLDOWN_TICKS = 4;

    /**
     * Ile pozycji przebudowujemy na jeden cooldown.
     *
     * <p>Wczesniej byla to JEDNA pozycja na 4 ticki, czyli 5 na sekunde. Przy
     * bazie, w ktorej maszyna obok rur sypie update'ami, kolejka rosla szybciej,
     * niz sie oprozniala - i rozplątywanie jej zajmowalo minuty. Zostaje wiec
     * tempo ograniczone, ale bez wielominutowego zalegania.
     */
    private static final int MAX_REBUILDS_PER_TICK = 4;

    /**
     * Twardy limit dlugosci kolejki przebudow.
     *
     * <p>Bezpiecznik pamieci: przy wybuchu rozwalajacym ogromna siec pozycji
     * moze byc naprawde duzo. Lepiej zgubic czesc przebudow (kolejny update
     * sasiada i tak je dorzuci) niz trzymac dziesiatki tysiecy pozycji.
     */
    private static final int MAX_PENDING_REBUILDS = 512;

    /**
     * Budzet czasu na JEDEN BFS sieci, w nanosekundach.
     *
     * <p>{@code scanAndBuildNetwork} przechodzi po wszystkich rurach sieci,
     * a dla kazdej robi {@code getBlockState} i {@code getBlockEntity}. Bez
     * limitu ogromna siec blokowala watek serwera na dowolnie dlugo - dokladnie
     * ta klasa bledu, ktora w tym modzie juz raz zamrozila serwer.
     */
    private static final long SCAN_BUDGET_NS = 20_000_000L;

    public VelocePipeNetworkManager() {
    }

    /**
     * Obsluga odroczonych przebudow. Wolane z ticku serwera.
     *
     * <p>Jedno przebudowanie na tick i nie czesciej niz co
     * {@link #REBUILD_COOLDOWN_TICKS} - dzieki temu nawet lawina update'ow
     * sasiadow konczy sie najwyzej piecioma przebudowami na sekunde.
     */
    public void tick(ServerLevel level) {
        // UZGADNIANIE CACHE SIECI Z ZYWYMI KOMPONENTAMI.
        //
        // MUSI byc przed wczesnym returnem ponizej: uklad polaczen zmienia sie
        // takze bez kolejkowania przebudowy (samo postawienie rury zmienia
        // komponenty), a wtedy zostawalyby osierocone cache'e.
        //
        // BUG, ktory to naprawia: identyfikator sieci liczymy z reprezentanta
        // komponentu (najmniejsza pozycja), wiec przy podziale albo scaleniu
        // sieci powstaje NOWE UUID. Stary cache (z wlasnymi force-loadami)
        // nie byl nigdy zwalniany - jego chunki zostawaly wymuszone NA ZAWSZE.
        reconcileCaches(level);

        // SPRZATANIE MARTWYCH MAGAZYNOW.
        //
        // knownEndpoints nie mialo ZADNEGO usuwania pojedynczych wpisow -
        // jedyne czyszczenie to nadpisanie calej mapy przy odbudowie swiata.
        // Skutki byly dwa:
        //   1. wyciek pamieci - wpis po kazdym magazynie, jakiego kiedykolwiek
        //      dotknela rura, zostawal na zawsze,
        //   2. WIDMO W SIECI - zniszczona skrzynia nadal sasiadowala z rura,
        //      wiec toNetwork dalej wciagalo ja do sieci z OSTATNIMI znanymi
        //      liczbami. Terminal pokazywal itemy, ktorych juz nie ma, a gracz
        //      nie mogl ich wyciagnac (bo w swiecie nie ma kontenera).
        // Robimy to raz na 5 sekund i tylko dla chunkow zaladowanych: dla
        // rozladowanych nie da sie sprawdzic, czy blok jeszcze stoi, a wpis
        // z ostatnia zawartoscia jest tam zalozeniem projektu.
        long pruneNow = level.getGameTime();
        if (com.craftingveloce.util.VeloceTick.every(
                pruneNow, lastEndpointPruneTick, ENDPOINT_PRUNE_INTERVAL_TICKS)) {
            lastEndpointPruneTick = pruneNow;
            pruneDeadEndpoints(level);
        }

        if (VeloceChunkLoader.isFrozen()) {
            pendingRebuilds.clear();
            return;
        }
        if (pendingRebuilds.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        // `now >= lastRebuildTick` - przy cofnietym czasie swiata roznica bylaby
        // ujemna, wiec blokada "cooldown" bylaby spelniona BEZ KONCA i siec
        // nigdy nie zostalaby przebudowana (rury przestalyby sie laczyc).
        if (lastRebuildTick != Long.MIN_VALUE
                && now >= lastRebuildTick
                && now - lastRebuildTick < REBUILD_COOLDOWN_TICKS) {
            return;
        }
        lastRebuildTick = now;

        // BUDZET CZASU NA CALA PARTIE.
        //
        // BUG, ktory tu byl: petla mogla wykonac MAX_REBUILDS_PER_TICK
        // przebudow, a KAZDA ma wlasny budzet SCAN_BUDGET_NS (20 ms). Przy
        // czterech przebudowach dawalo to do 80 ms zamulenia w jednym ticku -
        // czyli przekroczenie calego budzetu ticku (50 ms).
        //
        // Teraz liczy sie suma: przerwiemy partie, gdy przekroczy bezpieczny
        // limit, a reszta poczeka na kolejny cooldown.
        long batchDeadline = System.nanoTime() + REBUILD_BATCH_BUDGET_NS;
        for (int done = 0; done < MAX_REBUILDS_PER_TICK; done++) {
            if (System.nanoTime() > batchDeadline) {
                break;
            }
            java.util.Iterator<BlockPos> it = pendingRebuilds.iterator();
            if (!it.hasNext()) {
                break;
            }
            BlockPos pos = it.next();
            it.remove();
            rebuildAt(level, pos);
        }
    }

    /**
     * Dodaje pozycje do kolejki przebudow, pilnujac jej dlugosci.
     *
     * <p>Jedno wspolne wejscie dla wszystkich zrodel zgloszen (zmiana sasiada,
     * rozbita rura), zeby limit byl egzekwowany wszedzie, a nie tylko tam,
     * gdzie ktos o nim pamietal.
     */
    private void queueRebuild(BlockPos pos) {
        if (pendingRebuilds.size() >= MAX_PENDING_REBUILDS) {
            return;
        }
        pendingRebuilds.add(pos.immutable());
    }

    /**
     * Czy blok-wezel (terminal, extractor, crafter) faktycznie laczy sie z rura
     * od strony {@code pipeSide}?
     *
     * <p><b>Po co to.</b> Terminal ma przod, ktory NIE jest podlaczany. Zarowno
     * {@code scanAndBuildNetwork}, jak i {@code VelocePipeBlock.canConnectFrom}
     * sprawdzaja to przez {@code canConnectFrom}, ale rejestracja wezla przy
     * postawieniu bloku patrzyla wylacznie na to, czy obok jest rura.
     *
     * <p>Skutek: terminal postawiony przodem do rury (wizualnie niepodlaczony)
     * i tak zostawal wpisany do tej sieci - jego GUI pokazywalo i wyciagalo
     * stock, do ktorego nie powinien miec dostepu.
     *
     * @param pipeSide kierunek OD rury DO wezla (czyli {@code dir.getOpposite()}
     *                 przy iterowaniu kierunkow z pozycji wezla)
     */
    public static boolean nodeConnectsToPipe(ServerLevel level, BlockPos nodePos, Direction pipeSide) {
        // Oslona prewencyjna: getBlockState na serwerze WCZYTUJE chunk.
        // Nie wiemy - nie wycinamy (ta sama zasada co w hasOpenPipeAdjacent).
        BlockState state = VeloceChunkLoader.blockStateIfLoaded(level, nodePos);
        if (state == null) {
            return true;
        }
        // Cala wiedza o typach wezlow mieszka w VeloceNodeBlocks - tutaj tylko
        // przekazujemy pytanie. Wczesniej byl tu recznie pisany lancuch
        // instanceof, ktory nie znal kontrolera ani piecow, przez co kontroler
        // dostawal null z getNetworkForTerminal (pusty stock + "auto-crafting
        // wylaczony" dla wszystkich itemow).
        return VeloceNodeBlocks.connectsFrom(state, state.getBlock(), pipeSide);
    }

    /**
     * Synchronizuje plaska strukture z tym, co stoi w swiecie wokol danej rury.
     *
     * <p>Robimy to LOKALNIE: sprawdzamy szesc sasiadow tej jednej rury i nic
     * wiecej. Nie ma tu zadnego BFS po swiecie, wiec nie zalezy od tego, ktore
     * chunki sa zaladowane - a to bylo zrodlem bledu, w ktorym siec "ucinala
     * sie" na granicy niezaladowanego chunku.
     *
     * <p>Wywolywane przy postawieniu rury, przy zmianie sasiada i przy
     * zniszczeniu. Kolejne wywolanie dla tej samej rury jest tanie, bo
     * {@code setNeighbours} porownuje zbior i nic nie robi, gdy sie nie zmienil.
     */
    public void syncPipe(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof VelocePipeBlock pipe)) {
            world.removePipe(pos);
            return;
        }
        world.addPipe(pos);

        VelocePipeBlockEntity be = level.getBlockEntity(pos) instanceof VelocePipeBlockEntity p
                ? p : null;

        Set<BlockPos> neighbours = new HashSet<>();
        for (Direction d : Direction.values()) {
            // Zamknieta strona (wrench) = brak polaczenia w tym kierunku.
            // To wlasnie tutaj zamknieta strona ROZCINA siec.
            if (be != null && be.isDisconnected(d)) {
                continue;
            }
            BlockPos np = pos.relative(d);
            if (!level.isLoaded(np)) {
                continue;
            }
            BlockState ns = level.getBlockState(np);
            if (!(ns.getBlock() instanceof VelocePipeBlock)) {
                continue;
            }
            // Sasiad musi byc otwarty w nasza strone - inaczej polaczenie
            // byloby jednostronne.
            if (level.getBlockEntity(np) instanceof VelocePipeBlockEntity other
                    && other.isDisconnected(d.getOpposite())) {
                continue;
            }
            world.addPipe(np);
            neighbours.add(np);
        }
        boolean changed = !neighbours.equals(world.neighbours(pos));
        world.setNeighbours(pos, neighbours);
        if (changed) {
            // Uklad sie zmienil - opis komponentu jest nieaktualny.
            // To jest JEDYNE miejsce, w ktorym uniewazniamy cache: wszystko
            // inne (postawienie rury, zburzenie, wybuch, wrench) przechodzi
            // przez te metode.
            world.invalidateComponent(pos);
        }
    }

    /**
     * Synchronizuje rury wokol danej pozycji - gdy zmienil sie sasiad.
     *
     * <p>Sprawdzamy sama pozycje oraz jej szesc sasiedztw. To wystarczy, bo
     * polaczenie miedzy dwiema rurami zalezy TYLKO od tych dwoch rur.
     */
    public void syncAround(ServerLevel level, BlockPos pos) {
        syncPipe(level, pos);
        for (Direction d : Direction.values()) {
            syncPipe(level, pos.relative(d));
        }
    }

    /**
     * Buduje opis sieci z KOMPONENTU plaskiej struktury.
     *
     * <p><b>To jest miejsce, w ktorym "siec" przestaje byc obiektem, ktory sie
     * scala i dzieli.</b> Nie ma tu zadnego merge'owania ani rozdzielania:
     * bierzemy komponent (zbior rur polaczonych ze soba, wyliczony przez
     * {@link VelocePipeWorld}) i czytamy z niego, co sie w nim znajduje.
     *
     * <p>Dzieki temu:
     * <ul>
     *   <li>polaczenie dwoch sieci = jedna wieksza grupa rur,</li>
     *   <li>rozciecie = dwie mniejsze grupy,</li>
     *   <li>nic nie trzeba zgadywac ani przenosic.</li>
     * </ul>
     *
     * @param seed rura, od ktorej zaczynamy czytanie komponentu
     * @return opis sieci albo {@code null}, gdy nie ma tam zadnej rury
     */
    @Nullable
    public VelocePipeNetwork buildFromComponent(ServerLevel level, BlockPos seed) {
        if (!world.hasPipe(seed)) {
            // Struktura moze byc jeszcze nie zsynchronizowana (np. po wczytaniu
            // swiata) - synchronizujemy lokalnie i probujemy ponownie.
            syncPipe(level, seed);
            if (!world.hasPipe(seed)) {
                return null;
            }
        }
        // CACHE OPISU KOMPONENTU.
        //
        // To jest miejsce, ktore oszczedza najwiecej. Bez tego kazde zapytanie
        // (ekstraktor co 10 tickow, otwarcie terminala, odczyt GUI) chodzilo po
        // swiecie sprawdzajac szesc kierunkow od KAZDEJ rury. Przy 999 rurach
        // i trzech maszynach to ok. 36 000 odwolan do swiata na sekunde.
        //
        // Teraz opis liczymy raz, a kolejne pytania dostaja gotowy wynik.
        VelocePipeWorld.Component cached = world.cachedComponent(seed);
        if (cached != null) {
            return toNetwork(level, seed, cached);
        }

        Set<BlockPos> members = world.componentMembers(seed);
        if (members.isEmpty()) {
            return null;
        }

        // Identyfikator sieci bierzemy z reprezentanta komponentu. Ten sam
        // uklad rur daje ten sam identyfikator, wiec cache i force-loady
        // przezywaja kolejne przebudowy bez zadnego przenoszenia.
        UUID id = world.componentOf(seed) != null
                ? VelocePipeWorld.componentId(world.componentOf(seed))
                : UUID.randomUUID();

        VelocePipeNetwork net = persistentNetwork(id, true);
        net.getPipes().addAll(members);

        // Czytamy, co stoi obok rur: wezly (terminal/crafter/extractor)
        // i magazyny. Bez BFS po swiecie - tylko szesc kierunkow od kazdej
        // rury, a rury znamy z komponentu (takze te z niezaladowanych chunkow).
        for (BlockPos pipePos : members) {
            if (!level.isLoaded(pipePos)) {
                // Rura w niezaladowanym chunku: nie odczytamy jej sasiadow,
                // ale sama rura JEST czescia sieci. Zachowujemy wiec to, co
                // juz o niej wiemy z poprzedniego przebiegu.
                preserveKnownNeighbours(level, net, pipePos);
                continue;
            }
            collectNeighbours(level, net, pipePos);
        }
        refreshInsertModes(level, net, members);
        net.updateTrackedChunks();

        // Zapisujemy opis, zeby kolejne zapytania byly darmowe.
        VelocePipeWorld.Component component = new VelocePipeWorld.Component();
        component.pipes.addAll(net.getPipes());
        component.nodes.addAll(net.getTerminals());
        component.storages.addAll(net.getEndpoints().keySet());
        component.builtAtTick = level.getGameTime();
        world.storeComponent(seed, component);

        return net;
    }

    /** Buduje obiekt sieci z zapisanego opisu komponentu (bez dotykania swiata). */
    /**
     * Siec o danym UUID z magazynu TRWALEGO (albo nowa, jesli jeszcze jej nie ma).
     *
     * <p><b>BUG, ktory to naprawia (zgloszenie gracza: "kontroler nie zapisuje
     * preferred").</b> Sciezki "na zadanie" - cache komponentu
     * ({@link #toNetwork}) i odczyt ze swiata - budowaly ZA KAZDYM RAZEM nowy
     * obiekt {@code VelocePipeNetwork}. Wszystko, co trzyma sie w sieci, a wiec
     * takze preferencja "piec czy crafting" ustawiana prawym klikiem
     * w kontrolerze, ladowalo w obiekcie, ktory natychmiast stawal sie smieciem
     * (i nie trafial do zapisu swiata). Gracz ustawial preferencje, GUI ja
     * pokazywalo - bo klient przelacza ja u siebie optymistycznie - a po
     * ponownym otwarciu wracala domyslna.
     *
     * <p>Teraz obowiazuje TA SAMA zasada, co w {@link #scanAndBuildNetwork}:
     * jedno UUID = JEDEN obiekt. Przebudowanie tylko odswieza jego zawartosc,
     * wiec zapisy i odczyty (takze z pakietu i z zapisu swiata) dotycza tego
     * samego miejsca.
     */
    private VelocePipeNetwork persistentNetwork(UUID id, boolean clearTopology) {
        VelocePipeNetwork net = networks.get(id);
        if (net == null) {
            net = new VelocePipeNetwork(id);
            networks.put(id, net);
        } else if (clearTopology) {
            net.getPipes().clear();
            net.getTerminals().clear();
            net.getEndpoints().clear();
        }
        return net;
    }

    private VelocePipeNetwork toNetwork(ServerLevel level, BlockPos seed, VelocePipeWorld.Component component) {
        BlockPos root = world.componentOf(seed);
        UUID id = root != null
                ? VelocePipeWorld.componentId(root)
                : UUID.randomUUID();
        // TRWALY obiekt (patrz persistentNetwork) - inaczej preferencja
        // ustawiona w kontrolerze ginelaby razem z tym obiektem.
        VelocePipeNetwork net = persistentNetwork(id, true);
        net.getPipes().addAll(component.pipes);

        // Wezly: tak samo jak magazyny - przez sasiedztwo z rurami tego
        // komponentu, a nie po zamrozonym component.nodes. Inaczej wezel
        // dodany po zbudowaniu cache nie bylby widziany przez terminal.
        // Zbior rur komponentu liczymy RAZ. Wczesniej powstawal w kazdym
        // obiegu petli po wezlach - przy 1000 rur i kilku wezlach to tysiace
        // zbednych wstawien na jedno zbudowanie sieci.
        Set<BlockPos> componentPipes = new HashSet<>(component.pipes);

        Set<BlockPos> nodeCandidates = new HashSet<>(component.nodes);
        nodeCandidates.addAll(knownNodes);
        for (BlockPos n : nodeCandidates) {
            // Ten sam warunek co dla magazynow: sama bliskosc rury nie znaczy,
            // ze polaczenie istnieje. Strona ustawiona na "Disconnected" nie
            // wciaga wezla do sieci - tak samo jak nie wciaga magazynu.
            if ((component.nodes.contains(n) || touchesAnyPipe(n, componentPipes))
                    && hasOpenPipeAdjacent(level, n, componentPipes)) {
                net.getTerminals().add(n);
            }
        }

        // Magazyny: przynaleznosc ustalamy przez SASIEDZTWO z rurami tego
        // komponentu, a nie po zamrozonym zbiorze component.storages.
        //
        // BUG, ktory tu byl: component.storages jest migawka z chwili budowy
        // cache. Jesli wtedy beczka byla w niezaladowanym chunku (albo cache
        // powstal zanim ja postawiono), zbior byl PUSTY - i to na zawsze.
        // Skutek: rura widziala magazyn (sciezka scanAndBuildNetwork), ale
        // TERMINAL nie (sciezka cache'owana), bo toNetwork odsiewalo endpoint.
        //
        // Teraz: endpoint nalezy do komponentu, jesli lezy obok jakiejkolwiek
        // jego rury. Koszt to 6 sprawdzen na zapamietany magazyn - a tych jest
        // malo (po jednym na skrzynie).
        Set<BlockPos> pipes = componentPipes;
        for (Map.Entry<BlockPos, ConnectedEndpointInfo> e : knownEndpoints.entrySet()) {
            BlockPos ep = e.getKey();
            // DRUGI WARUNEK JEST KONIECZNY, NIE OZDOBNY.
            //
            // Sama sasiedztwo z rura nie wystarcza: strona rury moze byc
            // ustawiona wrenchem na "Disconnected", a wtedy polaczenia NIE MA.
            // Bez tego sprawdzenia poprawka w collectNeighbours nic by nie
            // dala - ta sciezka (z cache komponentu) chodzi w praktyce i dalej
            // wciagalaby odlaczony magazyn do sieci, z jego zawartoscia.
            //
            // To ta sama lekcja, co przy trybie Pull: sa DWIE drogi budowy
            // sieci i obie musza znac tryb strony.
            if ((component.storages.contains(ep) || touchesAnyPipe(ep, pipes))
                    && hasOpenPipeAdjacent(level, ep, pipes)) {
                // Chunk zaladowany -> odswiez, zeby liczby byly aktualne.
                // Niezaladowany -> zostaje ostatnia znana zawartosc.
                e.getValue().refreshIfLoaded(level);
                net.getEndpoints().put(ep, e.getValue());
            }
        }
        // Trybow stron NIE da sie wziac z opisu komponentu - trzeba je
        // odczytac z rur. Bez tego tryb Pull nie dzialalby w praktyce.
        refreshInsertModes(level, net, pipes);
        net.updateTrackedChunks();
        return net;
    }

    /** Czy ta pozycja sasiaduje z ktorakolwiek rura z podanego zbioru. */
    private static boolean touchesAnyPipe(BlockPos pos, Set<BlockPos> pipes) {
        for (Direction d : Direction.values()) {
            if (pipes.contains(pos.relative(d))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Czy endpoint ma obok rure, ktora NIE jest od niego odlaczona.
     *
     * <p>Odpowiada na pytanie "czy to polaczenie w ogole istnieje" - a nie
     * "czy wolno do niego wstawiac" (to drugie rozstrzyga
     * {@code refreshInsertModes}, dokladajac tryb Pull).
     *
     * <p>Gdy rura stoi w niezaladowanym chunku, NIE wiemy, jaki ma tryb -
     * i wtedy odpowiadamy "tak". Wolimy zostawic magazyn widoczny na podstawie
     * braku danych, niz wyciac go z sieci na podstawie domyslu.
     */
    private boolean hasOpenPipeAdjacent(ServerLevel level, BlockPos endpointPos, Set<BlockPos> pipes) {
        for (Direction d : Direction.values()) {
            BlockPos pipePos = endpointPos.relative(d);
            if (!pipes.contains(pipePos)) {
                continue;
            }
            if (!level.isLoaded(pipePos)) {
                return true;   // nie wiemy - nie wycinamy
            }
            if (!(level.getBlockEntity(pipePos) instanceof VelocePipeBlockEntity be)) {
                return true;   // brak block entity - nie nasza sprawa
            }
            // Strona rury PATRZACA NA endpoint to d.getOpposite().
            if (!be.isDisconnected(d.getOpposite())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Zachowuje to, co juz wiemy o rurze z niezaladowanego chunku.
     *
     * <p>Bez tego zawartosc magazynow stojacych daleko znikalaby z GUI po
     * kazdym przeliczeniu sieci - bo nie ma skad jej odczytac.
     */
    private void preserveKnownNeighbours(ServerLevel level, VelocePipeNetwork net, BlockPos pipePos) {
        UUID owner = pipeToNetwork.get(pipePos);
        if (owner == null) {
            return;
        }
        VelocePipeNetwork old = networks.get(owner);
        if (old == null) {
            return;
        }
        for (Direction d : Direction.values()) {
            BlockPos np = pipePos.relative(d);
            if (net.getEndpoints().containsKey(np) || net.getTerminals().contains(np)) {
                continue;
            }
            ConnectedEndpointInfo ep = old.getEndpoints().get(np);
            if (ep != null) {
                net.getEndpoints().put(np, ep);
            }
            if (old.getTerminals().contains(np)) {
                net.getTerminals().add(np);
            }
        }
    }

    /**
     * Zbiera wezly i magazyny stojace obok jednej rury.
     *
     * <p>To jedyne miejsce, ktore dotyka swiata przy budowie sieci - i jest
     * lokalne: szesc kierunkow od jednej rury.
     */
    private void collectNeighbours(ServerLevel level, VelocePipeNetwork net, BlockPos pipePos) {
        // Tryb tej rury (wrench): odlaczone strony i tryb Pull. Czytamy RAZ.
        //
        // blockEntityIfLoaded, a nie getBlockEntity: wolajacy juz sprawdza
        // isLoaded, ale ta metoda jest zbyt latwa do wywolania z nowego
        // miejsca - a getBlockEntity na serwerze WCZYTALBY chunk.
        VelocePipeBlockEntity pipeBe = VeloceChunkLoader.blockEntityIfLoaded(level, pipePos)
                instanceof VelocePipeBlockEntity p ? p : null;

        for (Direction d : Direction.values()) {
            // ODLACZONA STRONA = BRAK POLACZENIA. Takze dla magazynow i wezlow.
            //
            // BUG, ktory to naprawia: ten warunek byl sprawdzany TYLKO przy
            // laczeniu rura-rura (w syncPipe), a NIE tutaj. Efekt: strona
            // ustawiona wrenchem na "Disconnected" znikala z plaskiej
            // struktury, ale collectNeighbours i tak rejestrowal stojacy za
            // nia magazyn - wiec siec dalej go widziala, pokazywala i mogla
            // do niego pushowac i pullowac. Tryb Pull dzialal, bo jest
            // sprawdzany osobno; Disconnected nie dzialal wcale.
            //
            // Wczesniej komentarz obok syncPipe mowil "to wlasnie tutaj
            // zamknieta strona ROZCINA siec" - i bylo to prawda tylko dla rur.
            if (pipeBe != null && pipeBe.isDisconnected(d)) {
                continue;
            }
            BlockPos np = pipePos.relative(d);
            if (!level.isLoaded(np)) {
                // MAGAZYN W NIEZALADOWANYM CHUNKU.
                //
                // BUG, ktory tu byl: `continue` pomijal go calkowicie, wiec
                // taka beczka NIE trafiala do sieci. Skutek: net.extractItem()
                // nie widzial jej zawartosci, zwracal EMPTY - i w logu nie bylo
                // ani "took", ani bledu, tylko cisza po "requested".
                //
                // Nie mozemy odczytac jej ze swiata (chunk nie jest symulowany),
                // ale ZNAMY ja z wczesniejszego przebiegu albo z NBT. Skoro chunk
                // jest rozladowany, nikt tych itemow nie ruszyl - wiec zapamietana
                // zawartosc jest nadal prawdziwa.
                ConnectedEndpointInfo known = knownEndpoints.get(np);
                if (known != null) {
                    net.getEndpoints().put(np, known);
                }
                continue;
            }
            BlockState ns = level.getBlockState(np);
            Block nb = ns.getBlock();

            // WEZLY - wszystkie przez jedno miejsce (VeloceNodeBlocks), zeby
            // lista typow nie mogla sie znowu rozjechac z nodeConnectsToPipe.
            //
            // UWAGA na kierunek: jestesmy przy RURZE, wiec do wezla idziemy
            // przez `d`, a wezel patrzy na rure przez `d.getOpposite()`.
            if (VeloceNodeBlocks.isNode(nb)
                    && VeloceNodeBlocks.connectsFrom(ns, nb, d.getOpposite())) {
                registerNode(net, np, nb, ns, d.getOpposite());
                continue;
            }

            if (RefinedStorageHelper.hasRSNetwork(level, np, d.getOpposite())) {
                registerEndpoint(level, net, knownEndpoints, np, d.getOpposite(),
                        ConnectedEndpointInfo.Type.REFINED_STORAGE);
            } else if (VelocePipeBlock.canConnectToInventory(level, np, d.getOpposite())) {
                BlockPos canonical = getCanonicalInventoryPos(np, ns);
                registerEndpoint(level, net, knownEndpoints, canonical, d.getOpposite(),
                        ConnectedEndpointInfo.Type.INVENTORY);
            }
        }
    }

    /**
     * Rejestruje magazyn jako endpoint sieci, z informacja o trybie rury.
     *
     * <p><b>Laczenie flagi, a nie nadpisywanie.</b> Ten sam magazyn moze byc
     * dotkniety przez kilka rur (albo kilka stron), kazda w innym trybie.
     * Wstawianie ma byc dozwolone, gdy CHOC JEDNA strona na to pozwala, wiec
     * flaga z kolejnych wywolan jest sumowana logicznie. Nadpisywanie
     * oznaczaloby, ze o calym magazynie decyduje rura odwiedzona jako
     * ostatnia - czyli wynik zalezalby od kolejnosci budowy sieci.
     *
     * <p>Przy pierwszej rejestracji w danym przebiegu flaga jest USTAWIANA
     * (nie sumowana), bo endpoint moze byc ten sam obiekt co w poprzedniej
     * budowie i niósłby wtedy stara wartosc.
     */
    private void registerEndpoint(ServerLevel level, VelocePipeNetwork net,
                                  Map<BlockPos, ConnectedEndpointInfo> known,
                                  BlockPos key, Direction accessSide,
                                  ConnectedEndpointInfo.Type type) {
        ConnectedEndpointInfo ep = net.getEndpoints().get(key);
        if (ep == null) {
            ep = new ConnectedEndpointInfo(key, accessSide, type);
            net.getEndpoints().put(key, ep);
            known.put(key, ep);
        }
        ep.refreshIfLoaded(level);
    }

    /**
     * Ustala, do ktorych magazynow siec NIE moze wstawiac (tryb Pull z wrencha).
     *
     * <p><b>Dlaczego osobnym przebiegiem, po zlozeniu calej sieci.</b> Siec
     * buduje sie dwiema drogami: pelnym skanem ({@code collectNeighbours})
     * albo z pamieci podrecznej komponentu ({@link #toNetwork}, ktora swiata
     * nie czyta). Gdyby tryb byl ustalany w trakcie skanu, to skorzyscie
     * z pamieci podrecznej nigdy by go nie odswiezylo - a to jest sciezka,
     * ktora chodzi w praktyce. Dokladnie tak samo rozjechala sie kiedys lista
     * wezlow sieci.
     *
     * <p>Dlatego tryb ustalamy TU, raz, dla obu drog - i patrzymy na WSZYSTKIE
     * rury dotykajace magazynu, bo jedna skrzynia moze miec ich kilka.
     *
     * <p><b>Zasada.</b> Wstawianie jest dozwolone, gdy CHOC JEDNA czytelna
     * strona na to pozwala. Gdy nie da sie odczytac zadnej strony (chunk
     * niezaladowany), zostawiamy wstawianie WLACZONE - wolimy nie blokowac
     * na podstawie braku danych.
     */
    private void refreshInsertModes(ServerLevel level, VelocePipeNetwork net,
                                    Collection<BlockPos> pipes) {
        for (ConnectedEndpointInfo ep : net.getEndpoints().values()) {
            // Endpointy RS nie maja stron rury przy sobie w tym sensie,
            // ale sprawdzenie ich nic nie kosztuje i nic nie psuje.
            boolean sawReadablePull = false;
            boolean pushAllowed = false;
            for (Direction d : Direction.values()) {
                BlockPos pipePos = ep.getPos().relative(d);
                if (!pipes.contains(pipePos) || !level.isLoaded(pipePos)) {
                    continue;
                }
                if (!(level.getBlockEntity(pipePos) instanceof VelocePipeBlockEntity be)) {
                    continue;
                }
                // ODLACZONA STRONA NIE JEST POLACZENIEM - nie moze tez dawac
                // prawa do wstawiania. Sam endpoint zwykle nie trafi tu wcale
                // (collectNeighbours pomija odlaczone strony), ale ten sam
                // magazyn moze byc dotkniety DRUGĄ strona tej samej rury.
                if (be.isDisconnected(d.getOpposite())) {
                    continue;
                }
                // Strona rury PATRZACA NA magazyn to d.getOpposite().
                if (be.isExtracting(d.getOpposite())) {
                    sawReadablePull = true;
                } else {
                    pushAllowed = true;
                    break;
                }
            }
            ep.resetAcceptsInsert(pushAllowed || !sawReadablePull);
        }
    }

    /**
     * Rejestruje wezel w sieci.
     *
     * <p>Kazdy wezel trafia do {@code terminals} (to lista rzeczy, ktore trzeba
     * symulowac - patrz {@code VeloceCraftingCache.collectChunksToKeep}, ktora
     * wlasnie z niej bierze chunki do force-loadowania) oraz do
     * {@code knownNodes} (zeby nie znikal z sieci, gdy jego chunk wypadnie).
     *
     * <p>Crafter dodatkowo wystawia swoj bufor jako endpoint sieci - to on jest
     * miejscem, gdzie ląduje wynik craftu.
     */
    private void registerNode(VelocePipeNetwork net, BlockPos pos, Block block,
                              net.minecraft.world.level.block.state.BlockState state,
                              Direction towardPipe) {
        net.getTerminals().add(pos);
        knownNodes.add(pos.immutable());
        if (block instanceof VeloceNetworkNode node
                && node.exposesCraftingBuffer(state)) {
            net.getEndpoints().put(pos, new CraftingBufferEndpoint(pos, towardPipe));
        }
    }

    /** Co ile tickow sprzatamy martwe magazyny z rejestru (5 sekund). */
    private static final long ENDPOINT_PRUNE_INTERVAL_TICKS = 100L;

    private long lastEndpointPruneTick = Long.MIN_VALUE;

    /**
     * Usuwa z rejestru magazyny, ktorych juz nie ma w swiecie.
     *
     * <p>Sprawdzamy WYLACZNIE chunki zaladowane. Dla rozladowanego chunku nie
     * da sie odczytac swiata, a cala idea rejestru jest to, ze pamieta
     * zawartosc magazynow poza symulacja - wiec taki wpis zostaje.
     *
     * <p>Sprawdzamy tym SAMYM warunkiem, ktory decyduje o rejestracji
     * ({@code canConnectToInventory} / {@code hasRSNetwork}). Inny warunek
     * znaczylby, ze wpis moze byc jednoczesnie "za stary" i "za nowy" -
     * zaleznie od tego, kto pyta.
     *
     * <p>Wpisy typy {@code CRAFTING_BUFFER} to bufory crafterow, ktore NIE sa
     * magazynami w swiecie - dlatego maja wlasny warunek (crafter nadal stoi),
     * a nie test na inventory.
     */
    private void pruneDeadEndpoints(ServerLevel level) {
        if (knownEndpoints.isEmpty()) {
            return;
        }
        int removed = 0;
        java.util.Iterator<Map.Entry<BlockPos, ConnectedEndpointInfo>> it =
                knownEndpoints.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, ConnectedEndpointInfo> e = it.next();
            BlockPos pos = e.getKey();
            if (!level.isLoaded(pos)) {
                continue;   // nie wiemy - zostawiamy ostatnia znana zawartosc
            }
            if (endpointStillExists(level, pos, e.getValue())) {
                continue;
            }
            it.remove();
            removed++;
        }
        if (removed > 0) {
            VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                    "pruned %d dead storage(s) from the known-endpoint registry", removed);
        }
    }

    /**
     * Czy magazyn spod tego wpisu nadal istnieje w swiecie.
     *
     * <p><b>Warunek celowo ZACHOWAWCZY.</b> Nie pytamy "czy da sie tu jeszcze
     * podlaczyc rure" (jak przy rejestracji), bo ta odpowiedz zalezy od strony
     * i od typu bloku - a pomylka w ta strone kosztowalaby WYCIELEM zywego
     * magazynu z sieci: terminal przestalby widziec skrzynie, ktora stoi.
     * Wolimy nie usunac niz usunac za duzo.
     *
     * <p>Usuwamy wiec tylko wtedy, gdy w tym miejscu naprawde nic nie ma:
     * powietrze albo blok bez block entity i bez zdolnosci przechowywania.
     * Skrzynia zamieniona na kamien wypada, a skrzynia nadal stoi - zostaje.
     */
    private static boolean endpointStillExists(ServerLevel level, BlockPos pos,
                                               ConnectedEndpointInfo ep) {
        // Oslona wewnetrzna (wolajacy tez sprawdza): bez tego odczyt bloku
        // w niezaladowanym chunku wczytalby go, a ta metoda chodzi w petli
        // po wszystkich zapamietanych magazynach sieci.
        if (!level.isLoaded(pos)) {
            return true;   // nie wiemy - zostawiamy (zasada zachowawcza)
        }
        return switch (ep.getType()) {
            // Obce zrodlo energii: istnieje, dopoki w tym miejscu jest blok
            // z capability Forge Energy (Energy Cube, generator).
            case ENERGY -> level.getBlockState(pos).isAir() == false
                    && level.getCapability(
                            net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                            pos, ep.getAccessSide()) != null;
            case REFINED_STORAGE ->
                    RefinedStorageHelper.hasRSNetwork(level, pos, ep.getAccessSide());
            // Bufor craftera nie jest magazynem w swiecie - pytamy o TA SAMA
            // regule, co przy rejestracji (o buforze decyduje STAN bloku).
            // Sam typ bloku nie wystarczy: po podmianie klatki na maszyne
            // (albo z powrotem) zostalby po niej widmo magazynu.
            case CRAFTING_BUFFER -> {
                net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                yield state.getBlock() instanceof VeloceNetworkNode node
                        && node.exposesCraftingBuffer(state);
            }
            case INVENTORY -> {
                net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                if (state.isAir()) {
                    yield false;
                }
                net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
                yield be != null
                        || net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK
                        .getCapability(level, pos, state, null, ep.getAccessSide()) != null;
            }
        };
    }

    /**
     * Zwalnia sieroce force-loady: chunki wymuszone, ktorych nikt nie pilnuje.
     *
     * <p>Minecraft zapisuje {@code setChunkForced} TRWALE w danych swiata,
     * a nasza ksiegowosc zyje tylko w pamieci. Starsza wersja kodu wymuszala
     * chunk kazdej rury - po restarcie zostaly wiec chunki trzymane na zawsze,
     * choc nasz raport pokazywal zero.
     *
     * @return ile sierot zwolniono
     */
    public int releaseOrphanForceLoads(ServerLevel level) {
        Set<Long> tracked = new HashSet<>();
        for (VelocePipeNetwork net : networks.values()) {
            for (var cp : net.getTrackedChunks()) {
                tracked.add(ChunkPos.asLong(cp.x, cp.z));
            }
        }
        return VeloceChunkLoader.releaseOrphans(level, tracked);
    }

    /**
     * Zwolnij cache sieci, ktorych komponenty juz nie istnieja.
     *
     * <p>Wolane przy zmianie ukladu polaczen. Bez tego kazdy podzial albo
     * scalenie sieci zostawial osierocony cache z jego force-loadami.
     */
    private void reconcileCaches(ServerLevel level) {
        long version = world.version();
        if (version == lastReconciledVersion) {
            return;   // uklad sie nie zmienil - nic do roboty
        }
        lastReconciledVersion = version;
        // Zmiana ukladu polaczen - a wiec takze przeladowanie chunkow, po ktorym
        // czesc magazynow zniknela albo wrocila - zmienia liczby. Kasujemy cache
        // wszystkich zywych sieci; pierwsze liczenie widocznej strony zaraz go
        // zapelni na nowo.
        for (VelocePipeNetwork net : networks.values()) {
            net.clearCraftableMemo();
        }

        Set<UUID> live = new HashSet<>();
        for (BlockPos root : world.componentRoots()) {
            live.add(VelocePipeWorld.componentId(root));
        }
        int dropped = com.craftingveloce.crafting.VeloceCraftingCache
                .retainOnly(level, live);
        if (dropped > 0) {
            VeloceLog.Network.success(VeloceLog.Side.SERVER,
                    "uzgodniono cache sieci: zwolniono %d nieaktualnych (z ich force-loadami)",
                    dropped);
        }
    }

    /** Wersja ukladu, przy ktorej ostatnio uzgadnialismy cache. */
    private long lastReconciledVersion = Long.MIN_VALUE;

    /** Zwalnia kolejke przy zamykaniu/rozladowaniu swiata. */
    public void clearPendingRebuilds() {
        pendingRebuilds.clear();
    }

    /** Ile przebudow czeka w kolejce (diagnostyka). */
    public int pendingRebuildCount() {
        return pendingRebuilds.size();
    }

    public static SavedData.Factory<VelocePipeNetworkManager> factory() {
        return new SavedData.Factory<>(
                VelocePipeNetworkManager::new,
                VelocePipeNetworkManager::load,
                null
        );
    }

    public static VelocePipeNetworkManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(factory(), "veloce_pipe_networks");
    }

    /** Plaska struktura rur - zrodlo prawdy o polaczeniach. */
    public VelocePipeWorld getWorld() {
        return world;
    }

    public VelocePipeNetwork getNetworkById(UUID id) {
        return networks.get(id);
    }

    /**
     * Czy na tej pozycji jest rura Veloce - TANIE sprawdzenie.
     *
     * <p><b>Po co osobne od {@link #getNetworkForPipe}.</b> To jest wolane
     * w petli po wszystkich blokach wybuchu, wiec musi byc O(1). Budowanie
     * sieci dla kazdego bloku wysadziloby tick.
     *
     * <p><b>Dlaczego nie mapa {@code pipeToNetwork}.</b> BUG, ktory tu byl:
     * handlery zniszczenia i wybuchu pytaly o siec przez mape wypelniana
     * przez {@code scanAndBuildNetwork} - a ten przy 1100 rurach CZESTO
     * przekracza budzet 20 ms i zwraca null (w logu: "exceeded 20 ms budget
     * after 302/482/664/746/844 pipe(s)"). Mapa byla wiec nieaktualna i
     * zniszczenie rury moglo zostac PRZEOCZONE - flat world trzymal rure,
     * ktorej juz nie ma, a siec wygladala na polaczona mimo rozciecia.
     *
     * <p>Teraz pytamy strukture plaska, ktora jest zrodlem prawdy i jest
     * aktualizowana przy kazdym postawieniu/zmianie sasiada.
     */
    public boolean isPipe(ServerLevel level, BlockPos pos) {
        if (world.hasPipe(pos)) {
            return true;
        }
        // Struktura moze jeszcze nie znac tej rury (np. postawiona w chwili,
        // gdy nie byla synchronizowana) - sprawdzamy swiat, jesli zaladowany.
        if (level.isLoaded(pos)
                && level.getBlockState(pos).getBlock() instanceof VelocePipeBlock) {
            syncPipe(level, pos);
            return world.hasPipe(pos);
        }
        return false;
    }

    /**
     * Siec zawierajaca te rure - wyliczona z plaskiej struktury.
     *
     * <p>Uzywane przez diagnostyke ({@code /cv debug} na rurze), zeby raport
     * pokazywal DOKLADNIE to samo, co widzi terminal. Wczesniej ta metoda
     * czytala stara mape i potrafila pokazac inny stan niz terminal - co
     * mylilo przy diagnozie.
     */
    @Nullable
    public VelocePipeNetwork getNetworkForPipe(ServerLevel level, BlockPos pos) {
        if (!world.hasPipe(pos)) {
            if (!isPipe(level, pos)) {
                return null;
            }
        }
        return buildFromComponent(level, pos);
    }

    /**
     * Siec, do ktorej nalezy ten wezel.
     *
     * <p><b>Jak to teraz dziala.</b> Nie ma zadnego "przypisywania wezla do
     * sieci" ani utrzymywania tej relacji. Bierzemy pierwsza rure obok wezla
     * (z tej strony, z ktorej wezel naprawde moze sie podlaczyc) i czytamy
     * CALY komponent, do ktorego ta rura nalezy - {@link #buildFromComponent}.
     *
     * <p>Dzieki temu relacja "wezel nalezy do sieci" jest WYLICZANA, a nie
     * przechowywana. Nie ma czego zsynchronizowac ani zgubic przy laczeniu
     * i rozcinaniu - jesli rury sa polaczone, wezel widzi cala grupe, a jesli
     * je rozetniesz, widzi tylko swoja czesc.
     */
    @Nullable
    /**
     * Kasuje cache liczb "ile da sie dorobic" dla sieci tego wezla.
     *
     * <p>Punkt 3/5: postawienie albo usuniecie magazynu/maszyny zmienia wynik,
     * wiec stary cache musialby klamac. Kasujemy go - klient od razu dostanie
     * pusta migawke, a pierwsze liczenie widocznej strony zaraz ja zapelni.
     */
    /**
     * Oznacza dane sieci jako brudne, czyli DO ZAPISU.
     *
     * <p>BUG, ktory to naprawia (zgloszenie gracza: "wychodze z gry, wchodze
     * i od poczatku wszystko sie generuje, nic nie ma z keszu"): zapisujemy
     * cache liczb w save(), ale SavedData zapisuje tylko wpisy oznaczone jako
     * brudne (setDirty). Cache zmienial sie bez zadnej zmiany topologii, wiec
     * nigdy nie byl zapisywany.
     */
    public void markDirty() {
        setDirty();
    }

    public void clearCraftableMemo(ServerLevel level, BlockPos pos) {
        VelocePipeNetwork network = getNetworkForTerminal(level, pos);
        if (network != null) {
            network.clearCraftableMemo();
        }
    }

    public VelocePipeNetwork getNetworkForTerminal(ServerLevel level, BlockPos terminalPos) {
        // Szukamy rury obok wezla, z tej strony, z ktorej polaczenie jest
        // w ogole mozliwe (terminal ma przod, ktory sie nie laczy).
        for (Direction d : Direction.values()) {
            BlockPos pipePos = terminalPos.relative(d);
            if (!nodeConnectsToPipe(level, terminalPos, d)) {
                continue;
            }
            // Rura moze byc w niezaladowanym chunku - wtedy synchronizujemy
            // ja ze stanu zapisanego, a nie ze swiata.
            if (!world.hasPipe(pipePos) && !level.isLoaded(pipePos)) {
                continue;
            }
            if (!world.hasPipe(pipePos)) {
                syncPipe(level, pipePos);
            }
            if (!world.hasPipe(pipePos)) {
                continue;
            }
            VelocePipeNetwork net = buildFromComponent(level, pipePos);
            if (net != null) {
                return net;
            }
        }
        return null;
    }

    /**
     * Wszystkie sieci - WYLICZONE z plaskiej struktury.
     *
     * <p><b>Dlaczego nie z mapy {@code networks}.</b> Ta mapa jest wypelniana
     * przez {@code scanAndBuildNetwork}, ktory przy 1100 rurach czesto
     * przekracza budzet i zwraca null. Raporty pokazywaly wiec inny stan niz
     * terminal (ktory liczy z plaskiej struktury) - co mylilo przy diagnozie
     * i ukrywalo prawdziwe bledy.
     *
     * <p>Wynik jest cache'owany na jeden tick gry, zeby kilka odczytow w tej
     * samej komendzie nie budowalo sieci od nowa.
     */
    /**
     * Sieci JUZ ZBUDOWANE w tym wymiarze - bez przebudowy ze swiata.
     *
     * <p>Uzywane przez utrzymanie force-loadow ({@code VeloceCraftingCache
     * .tickAll}): ono chodzi co tick, wiec nie moze sobie pozwolic na
     * przebudowe sieci, a jednocześnie musi znac sieci, ktore istnieja - inaczej
     * nie ma dla nich cache i chunki z maszynami sie rozladuja.
     */
    public Collection<VelocePipeNetwork> knownNetworks() {
        return new java.util.ArrayList<>(networks.values());
    }

    public Collection<VelocePipeNetwork> getAllNetworks(ServerLevel level) {
        long now = level.getGameTime();
        if (derivedNetworks != null && derivedNetworksTick == now) {
            return derivedNetworks;
        }
        java.util.List<VelocePipeNetwork> out = new java.util.ArrayList<>();
        for (BlockPos root : world.componentRoots()) {
            VelocePipeNetwork net = buildFromComponent(level, root);
            if (net != null) {
                out.add(net);
            }
        }
        derivedNetworks = java.util.Collections.unmodifiableList(out);
        derivedNetworksTick = now;
        return derivedNetworks;
    }

    /** Cache listy sieci wyliczonej z plaskiej struktury. */
    private java.util.List<VelocePipeNetwork> derivedNetworks;
    private long derivedNetworksTick = Long.MIN_VALUE;

    public void rebuildAt(ServerLevel level, BlockPos startPos) {
        if (!level.isLoaded(startPos)) return;

        BlockState state = level.getBlockState(startPos);
        if (state.getBlock() instanceof VelocePipeBlock) {
            UUID existingId = pipeToNetwork.get(startPos);
            scanAndBuildNetwork(level, startPos, existingId);
        }
    }

    public void onPipePlaced(ServerLevel level, BlockPos pos) {
        // Struktura najpierw, przebudowa potem - inaczej przebudowa nie
        // wiedzialaby o nowej rurze.
        syncAround(level, pos);
        rebuildAt(level, pos);
    }

    /**
     * Reakcja na zaladowanie lub rozladowanie chunka.
     *
     * <p>Gdy chunk z blokami sieci wchodzi lub wychodzi z symulacji, stock tej
     * sieci zmienia sie gwaltownie (zawartosc skrzyn nie jest juz czytana albo
     * znowu jest). Cache craftowalnosci musi zostac o tym powiadomiony, inaczej
     * GUI pokazuje nieaktualne liczby - a to bylo widoczne jako "liczby sie
     * rozjezdzaja po odejsciu od bazy".
     *
     * <p>Przegladamy tylko sieci, ktore faktycznie dotykaja tego chunka.
     */
    public void onChunkChanged(ServerLevel level, ChunkPos chunkPos, boolean loaded) {
        // Wykrywacz petli: jesli ten chunk wroci w ciagu kilku tickow po
        // rozladowaniu, to ktos go wczytuje w kolko. Wypisujemy wtedy STOS
        // WYWOLAN, zeby od razu bylo widac winowajce.
        VeloceChunkLoader.noteChunkEvent(level, ChunkPos.asLong(chunkPos.x, chunkPos.z), loaded);
        // Przy zamykaniu/zapisie swiata nic nie robimy - i tak zaraz koniec.
        // Bez tego kazdy cykl load/unload chunka produkowal wpis do loga;
        // w jednym zamknieciu swiata naliczylo sie 8000+ linii, co samo w sobie
        // zamulalo zapis.
        if (VeloceChunkLoader.isFrozen()) {
            return;
        }

        // GDY CHUNK SIE LADUJE: odswiez magazyny, ktore w nim stoja.
        //
        // To jest jedyny moment, w ktorym mozemy odczytac PRAWDZIWY stan
        // skrzyni. Bez tego endpoint czekalby na okazjonalny skan z throttlingiem,
        // a przez ten czas terminal pokazywalby stare liczby.
        //
        // Przy ROZLADOWANIU nie robimy nic - i to jest celowe: skoro chunk nie
        // jest symulowany, nikt tych itemow nie ruszyl, wiec ostatnia znana
        // zawartosc jest nadal prawdziwa. Dzieki temu terminal widzi zawartosc
        // skrzyni w niezaladowanym chunku.
        if (loaded) {
            for (Map.Entry<BlockPos, ConnectedEndpointInfo> e : knownEndpoints.entrySet()) {
                if (isInChunk(e.getKey(), chunkPos)) {
                    e.getValue().refreshIfLoaded(level);
                }
            }
        }

        // Raportowanie - tylko gdy monitor wlaczony albo sledzenie aktywne.
        boolean wantDetails = com.craftingveloce.debug.ChunkDebugNotifier.isEnabled();
        if (!wantDetails && !com.craftingveloce.debug.ChunkTrace.isEnabled()) {
            return;
        }

        java.util.List<String> affectedThings = wantDetails
                ? new java.util.ArrayList<>()
                : java.util.List.of();
        int affected = 0;
        for (BlockPos p : knownNodes) {
            if (isInChunk(p, chunkPos)) {
                affected++;
                if (wantDetails) {
                    // Przy UNLOAD nie czytamy swiata - czytanie bloku
                    // w rozladowanym chunku wczytaloby go z powrotem
                    // (patrz describeBlock). Opis bierzemy z tego, co wiemy.
                    affectedThings.add(loaded
                            ? describeBlock(level, p)
                            : "(wlasnie rozladowany) @ " + p.toShortString());
                }
            }
        }
        for (BlockPos p : knownEndpoints.keySet()) {
            if (isInChunk(p, chunkPos)) {
                affected++;
                if (wantDetails) {
                    // Przy UNLOAD nie czytamy swiata - czytanie bloku
                    // w rozladowanym chunku wczytaloby go z powrotem
                    // (patrz describeBlock). Opis bierzemy z tego, co wiemy.
                    affectedThings.add(loaded
                            ? describeBlock(level, p)
                            : "(wlasnie rozladowany) @ " + p.toShortString());
                }
            }
        }
        if (affected == 0) {
            return;
        }

        // UWAGA: opis MUSI byc zgodny z tym, co robimy. Wczesniej pisalo
        // "cache invalidated", choc nic nie bylo uniewazniane - i to mylilo
        // przy diagnozie (szukalismy uniewaznienia, ktorego nie bylo).
        com.craftingveloce.util.VeloceLog.Network.detail(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "chunk %s %s: %d element(s) sieci %s",
                chunkPos, loaded ? "loaded" : "unloaded", affected,
                loaded ? "(odswiezam magazyny)" : "(zostawiam ostatnia znana zawartosc)");

        com.craftingveloce.debug.ChunkTrace.event("CHUNK",
                "%s chunk[%d,%d] dotyczy %d elementow",
                loaded ? "LOAD" : "UNLOAD", chunkPos.x, chunkPos.z, affected);
        com.craftingveloce.debug.ChunkDebugNotifier.notifyChunkChange(
                level, chunkPos, loaded, affectedThings);
    }

    /**
     * Czy blok lezy w tym chunku - bez alokowania ChunkPos na kazdy blok.
     *
     * <p>Wczesniej bylo tu {@code new ChunkPos(p).equals(chunkPos)} w petli po
     * wszystkich terminalach i endpointach KAZDEJ sieci, czyli kilka alokacji
     * na kazde zdarzenie chunka.
     */
    private static boolean isInChunk(BlockPos p, ChunkPos chunkPos) {
        return (p.getX() >> 4) == chunkPos.x && (p.getZ() >> 4) == chunkPos.z;
    }

    private static String describeBlock(ServerLevel level, BlockPos pos) {
        // NIGDY nie czytamy swiata dla niezaladowanego chunku.
        //
        // ================================================================
        // BUG, ktory to naprawia (PRAWDZIWA petla load/unload).
        //
        // Ta metoda jest wolana takze przy zdarzeniu UNLOAD - opisujemy
        // wtedy bloki lezace w chunku, ktory WLASNIE sie rozladowal. A na
        // serwerze `Level.getBlockEntity(pos)` NIE zwraca null dla
        // niezaladowanego chunku: on go WCZYTUJE (idzie przez
        // getChunk(x, z) -> ChunkStatus.FULL, requireChunk = true).
        //
        // Skutek byl dokladnie taki, jaki zglosil uzytkownik: chunk sie
        // rozladowywal, nasz wlasny komunikat diagnostyczny wczytywal go
        // z powrotem, ten znowu sie rozladowywal, i tak w kolko - 10 razy
        // na sekunde, bez konca. Monitor chunkow powodowal wiec dokladnie
        // to, co mial tylko obserwowac.
        //
        // Nie widzial tego ani licznik wymuszen (to nie force-load), ani
        // licznik wczytan "na czas operacji" (to nie nasze getChunk).
        // Dlatego oslona jest tutaj, u zrodla.
        // ================================================================
        if (!level.isLoaded(pos)) {
            return "(niezaladowany) @ " + pos.toShortString();
        }
        try {
            var be = level.getBlockEntity(pos);
            if (be != null) {
                String name = be.getClass().getSimpleName();
                if (name.endsWith("BlockEntity")) {
                    name = name.substring(0, name.length() - "BlockEntity".length());
                }
                return name + " @ " + pos.toShortString();
            }
            return level.getBlockState(pos).getBlock().getName().getString()
                    + " @ " + pos.toShortString();
        } catch (Throwable t) {
            return "? @ " + pos.toShortString();
        }
    }

    public void onPipeBroken(ServerLevel level, BlockPos pos) {
        // NAJWAZNIEJSZE: usuniecie rury ze struktury od razu rozcina siec.
        //
        // Nie trzeba nic przeliczac ani zgadywac podzialu - komponenty
        // rozdzielaja sie SAME, bo wynikaja wprost z polaczen miedzy rurami.
        // To jest cala zaleta plaskiej struktury: rozciecie jest darmowe
        // i zawsze poprawne.
        world.removePipe(pos);
        // Sasiedzi traca polaczenie z ta rura. syncPipe() uniewazni takze
        // opis komponentu, wiec maszyny NATYCHMIAST przestana widziec
        // odcieta czesc sieci - bez tego cache trzymalby stary, polaczony
        // uklad i terminal pokazywalby itemy z czesci, ktorej juz nie ma.
        for (Direction d : Direction.values()) {
            BlockPos np = pos.relative(d);
            if (world.hasPipe(np)) {
                syncPipe(level, np);
            }
        }
        // Sama pozycja tez mogla miec zapamietany opis.
        world.invalidateComponent(pos);

        UUID netId = pipeToNetwork.remove(pos);
        if (netId == null) return;

        VelocePipeNetwork oldNet = networks.get(netId);
        if (oldNet == null) return;

        oldNet.getPipes().remove(pos);

        List<BlockPos> remainingNeighbors = new ArrayList<>();
        for (Direction d : Direction.values()) {
            BlockPos np = pos.relative(d);
            if (oldNet.getPipes().contains(np)) {
                remainingNeighbors.add(np);
            }
        }

        // Remove old network references.
        // Cache MUSI byc zwolniony razem z siecia - inaczej trzyma swoje
        // force-loady na zawsze (patrz VeloceCraftingCache.drop).
        com.craftingveloce.crafting.VeloceCraftingCache.drop(level, netId);
        networks.remove(netId);
        for (BlockPos p : oldNet.getPipes()) {
            pipeToNetwork.remove(p);
        }
        for (BlockPos t : oldNet.getTerminals()) {
            terminalToNetwork.remove(t);
        }

        // Przebudowa ODLOZONA, nie natychmiastowa.
        //
        // BUG, ktory tu byl: dla KAZDEGO ocalalego sasiada od razu lecial pelny
        // BFS (scanAndBuildNetwork) - bez budzetu, bez limitu odwiedzonych
        // pozycji i bez sprawdzenia isFrozen(). A ze onPipeBroken wolane jest
        // per rura z destroy(), z BreakEvent i - najgorzej - raz na kazda
        // dotknieta rure w petli ExplosionEvent.Detonate, to rozwalenie sciany
        // z K rur kosztowalo do ~K*6 pelnych przejsc po calej sieci w JEDNYM
        // ticku. Przy duzej sieci i wybuchu = zawieszenie serwera.
        //
        // Teraz tylko kolejkujemy - zdebounce'owany tick robi jedna przebudowe
        // na cooldown, wiec koszt jest rozlozony na kolejne ticki.
        for (BlockPos np : remainingNeighbors) {
            if (!pipeToNetwork.containsKey(np) && level.isLoaded(np)
                    && level.getBlockState(np).getBlock() instanceof VelocePipeBlock) {
                queueRebuild(np);
            }
        }

        setDirty();
    }

    public void onTerminalPlaced(ServerLevel level, BlockPos terminalPos) {
        for (Direction d : Direction.values()) {
            BlockPos neighborPos = terminalPos.relative(d);
            UUID netId = pipeToNetwork.get(neighborPos);
            if (netId == null || !networks.containsKey(netId)) {
                continue;
            }
            // Kierunek musi byc taki sam jak w scanAndBuildNetwork - inaczej
            // wezel trafial do sieci, z ktora nie jest polaczony.
            if (!nodeConnectsToPipe(level, terminalPos, d)) {
                continue;
            }
            VelocePipeNetwork net = networks.get(netId);
            net.getTerminals().add(terminalPos);
            net.updateTrackedChunks();
            terminalToNetwork.put(terminalPos, netId);
            net.invalidateEndpointCache(level);
            setDirty();
            return;
        }
    }

    public void onTerminalRemoved(ServerLevel level, BlockPos terminalPos) {
        knownNodes.remove(terminalPos);
        UUID netId = terminalToNetwork.remove(terminalPos);
        if (netId != null) {
            VelocePipeNetwork net = networks.get(netId);
            if (net != null) {
                net.getTerminals().remove(terminalPos);
                // Wezel zniknal - jego dane nie sa juz aktualne. Dla chunkow
                // zaladowanych cache sie przeliczy; dla rozladowanych zostaje
                // ostatnia znana zawartosc (patrz invalidateCache).
                net.invalidateEndpointCache(level);
                net.updateTrackedChunks();
                setDirty();
            }
        }
    }

    /**
     * Reakcja na zmiane sasiada rury: podlaczenie lub odlaczenie inventory,
     * postawienie albo znikniecie bloku obok.
     *
     * <p>Po przebudowie sieci trzeba uniewaznic cache craftowalnosci, bo zmienil
     * sie sklad magazynow. Bez tego GUI pokazywaloby liczby z poprzedniego
     * ukladu - np. po odlaczeniu skrzyni nadal widac jej zawartosc.
     */
    public void onNeighborChanged(ServerLevel level, BlockPos pipePos, BlockPos neighborPos) {
        if (!level.isLoaded(pipePos)) return;
        // Struktura najpierw: zmiana sasiada moze dodac polaczenie (postawiona
        // rura) albo je zabrac (zamknieta strona, zburzona rura). Bez tego
        // siec nie wiedzialaby o zmianie.
        syncAround(level, pipePos);
        // Przebudowe ODKLADAMY do ticku (patrz pendingRebuilds). Robienie
        // pelnego BFS w kazdym neighborChanged oznaczalo przebudowe sieci
        // kilka razy na tick, gdy obok pracowala jakas maszyna.
        queueRebuild(pipePos);

        // Uniewaznij cache sieci dotknietych ta zmiana. Szukamy po pozycji rury
        // oraz po pozycji sasiada - zmiana mogla dodac albo usunac endpoint.
        Set<VelocePipeNetwork> touched = new HashSet<>();
        for (VelocePipeNetwork net : networks.values()) {
            if (net.getPipes().contains(pipePos)
                    || net.getEndpoints().containsKey(neighborPos)
                    || net.getTerminals().contains(neighborPos)) {
                touched.add(net);
            }
        }
        for (VelocePipeNetwork net : touched) {
            // Bufor endpointu mogl wskazywac na usunieta skrzynie - przelicz od nowa.
            net.invalidateEndpointCache(level);
        }
        if (!touched.isEmpty()) {
            com.craftingveloce.util.VeloceLog.Network.detail(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "neighbor change at %s -> invalidated %d network(s)", neighborPos, touched.size());
        }
    }

    public static BlockPos getCanonicalInventoryPos(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock && state.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)) {
            net.minecraft.world.level.block.state.properties.ChestType type = state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE);
            if (type == net.minecraft.world.level.block.state.properties.ChestType.RIGHT) {
                return pos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
            }
        }
        return pos;
    }

    public VelocePipeNetwork scanAndBuildNetwork(ServerLevel level, BlockPos originPos, @Nullable UUID preferredId) {
        if (!level.isLoaded(originPos) || !(level.getBlockState(originPos).getBlock() instanceof VelocePipeBlock)) {
            return null;
        }
        // Przy zapisie/zamknieciu swiata nie budujemy sieci: to bez sensu,
        // a kazde getBlockEntity moze dociagnac chunk i zawiesic zapis.
        if (VeloceChunkLoader.isFrozen()) {
            return null;
        }

        // Budzet czasu na caly BFS. Po jego wyczerpaniu przerywamy i NIE
        // przebudowujemy sieci - kolejny update sasiada zglosi ja ponownie.
        // Lepiej miec chwilowo nieaktualny uklad sieci niz zamrozony tick.
        final long scanDeadline = System.nanoTime() + SCAN_BUDGET_NS;
        boolean budgetExceeded = false;

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visitedPipes = new HashSet<>();
        Set<BlockPos> discoveredTerminals = new HashSet<>();
        Map<BlockPos, ConnectedEndpointInfo> discoveredEndpoints = new HashMap<>();
        // Obce zrodla energii (Energy Cube, generator) - osobna lista, bo to
        // NIE sa magazyny itemow: nic z nich nie wyciagamy rura, tylko nasze
        // maszyny SAME z nich sciagaja prad.
        java.util.Set<BlockPos> discoveredEnergy = new java.util.HashSet<>();
        Set<UUID> intersectedOldNets = new HashSet<>();
        // Rury, ktore weszly do sieci z niezaladowanych chunkow. Ich block
        // entity jest niedostepne, wiec nie znamy ich zamknietych stron.
        Set<BlockPos> unloadedPipes = new HashSet<>();

        queue.add(originPos);
        visitedPipes.add(originPos);

        while (!queue.isEmpty()) {
            // Sprawdzamy czas przy KAZDYM kroku, nie co N krokow. Probowanie
            // "co 64" juz raz w tym modzie uczynilo budzet martwym.
            if (System.nanoTime() > scanDeadline) {
                budgetExceeded = true;
                break;
            }
            BlockPos current = queue.poll();
            UUID oldNetAtPos = pipeToNetwork.get(current);
            if (oldNetAtPos != null) {
                intersectedOldNets.add(oldNetAtPos);
            }

            // Bez wczytywania chunku: BFS celowo kolejkuje takze rury
            // z niezaladowanych chunkow, wiec getBlockEntity wczytalby kazda
            // z nich (a to wlasnie ta petla load/unload, ktora zglaszal
            // uzytkownik). Dla niezaladowanej rury po prostu nie znamy jej
            // zamknietych stron - i tak zaklada to komentarz nizej.
            BlockEntity curBE = VeloceChunkLoader.blockEntityIfLoaded(level, current);
            VelocePipeBlockEntity curPipeBE = (curBE instanceof VelocePipeBlockEntity p) ? p : null;

            for (Direction dir : Direction.values()) {
                if (curPipeBE != null && curPipeBE.isDisconnected(dir)) {
                    continue;
                }

                BlockPos neighborPos = current.relative(dir);
                if (!level.isLoaded(neighborPos)) {
                    // RURA W NIEZALADOWANYM CHUNKU.
                    //
                    // BUG, ktory tu byl: dopisywalismy taka rure do
                    // visitedPipes, ale NIE wrzucalismy jej do kolejki BFS -
                    // wiec przeszukiwanie ZATRZYMYWALO SIE na granicy chunku.
                    // Rury po drugiej stronie nie byly odwiedzane, a wiec
                    // ginely z sieci: raz brakowalo terminala, raz beczki, raz
                    // 86 rur (913 zamiast 999) - zaleznie od tego, od ktorego
                    // konca zaczal sie BFS i ktore chunki byly zaladowane.
                    //
                    // Objaw w grze: siec "raz widzi" magazyn, a raz nie.
                    //
                    // Teraz taka rura JEST kolejkowana, zeby przejscie moglo
                    // isc dalej.
                    //
                    // UWAGA na zamkniete strony: stan "zamknieta strona" zyje
                    // w block entity (NBT), a dla niezaladowanego chunku nie ma
                    // go skad odczytac. Rura bylaby wiec potraktowana jako
                    // calkiem otwarta i BFS przeszedlby przez zamkniete
                    // polaczenie. Dlatego NIE wchodzimy w nia jako w rozwidlenie:
                    // kolejkujemy ja, ale gdy do niej dotrzemy, jej sasiedzi sa
                    // sprawdzani tylko wtedy, gdy block entity da sie odczytac.
                    UUID existingNetId = pipeToNetwork.get(neighborPos);
                    if (existingNetId != null) {
                        intersectedOldNets.add(existingNetId);
                    }
                    if (visitedPipes.add(neighborPos)) {
                        unloadedPipes.add(neighborPos);
                        queue.add(neighborPos);
                    }
                    continue;
                }

                BlockState neighborState = level.getBlockState(neighborPos);

                // 1. Neighbor is VelocePipeBlock
                if (neighborState.getBlock() instanceof VelocePipeBlock) {
                    BlockEntity nbe = level.getBlockEntity(neighborPos);
                    if (nbe instanceof VelocePipeBlockEntity otherPipe && otherPipe.isDisconnected(dir.getOpposite())) {
                        continue;
                    }
                    if (visitedPipes.add(neighborPos)) {
                        queue.add(neighborPos);
                    }
                    continue;
                }

                // 2. WEZEL SIECI - terminal, ekstraktor, crafter, kontroler,
                //    sensor, piec albo wezel z modulu compat/*.
                //
                // BUG, ktory to naprawia: ta petla miala RECZNIE wpisane trzy
                // typy (terminal, ekstraktor, crafter). Kontroler, sensor i
                // piece nie byly tu rozpoznawane jako wezly, wiec pelny skan
                // sieci nie dodawal ich do terminali - mimo ze sasiednia,
                // lokalna sciezka (collectNeighbours) juz je znala. Teraz obie
                // pytaja ten sam interfejs, wiec nie moga sie rozjechac, a nowy
                // wezel z compat/* dziala bez zmiany w rdzeniu.
                if (neighborState.getBlock() instanceof VeloceNetworkNode node) {
                    if (node.canConnectFrom(neighborState, dir.getOpposite())) {
                        discoveredTerminals.add(neighborPos);
                        // Bufor auto-craftera jest dodatkowo endpointem magazynu:
                        // dzieki temu nadwyzka produkcji (np. 3 deski z 1 logu,
                        // gdy gracz chcial 1) jest widoczna dla calej sieci i
                        // mozna ja wyciagnac terminalem, rura czy hopperem.
                        if (node.exposesCraftingBuffer(neighborState)) {
                            discoveredEndpoints.put(neighborPos, new CraftingBufferEndpoint(
                                    neighborPos, dir.getOpposite()));
                        }
                    }
                    continue;
                }

                // 2b. Obcy blok z Forge Energy (Energy Cube, generator).
                //
                // Nasze bloki POMIJAMY: maszyny sa tylko odbiornikami, wiec nie
                // moga byc dla siebie zrodlem (inaczej sciagalyby prad jedna
                // drugiej). Nasza rura nie wystawia EnergyStorage, wiec nic
                // obcego nie pobierze z niej pradu - kierunek jest jeden.
                if (!(neighborState.getBlock() instanceof VeloceNetworkNode)
                        && level.getCapability(
                                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                                neighborPos, dir.getOpposite()) != null) {
                    discoveredEnergy.add(neighborPos.immutable());
                }

                // 3. Neighbor is Refined Storage
                if (RefinedStorageHelper.hasRSNetwork(level, neighborPos, dir.getOpposite())) {
                    ConnectedEndpointInfo ep = new ConnectedEndpointInfo(neighborPos, dir.getOpposite(), ConnectedEndpointInfo.Type.REFINED_STORAGE);
                    ep.refreshIfLoaded(level);
                    discoveredEndpoints.put(neighborPos, ep);
                    continue;
                }

                // 4. Neighbor is regular inventory
                if (VelocePipeBlock.canConnectToInventory(level, neighborPos, dir.getOpposite())) {
                    BlockPos canonicalPos = getCanonicalInventoryPos(neighborPos, neighborState);
                    ConnectedEndpointInfo ep = new ConnectedEndpointInfo(canonicalPos, dir.getOpposite(), ConnectedEndpointInfo.Type.INVENTORY);
                    ep.refreshIfLoaded(level);
                    discoveredEndpoints.put(canonicalPos, ep);
                    continue;
                }
            }
        }

        if (budgetExceeded) {
            // NIE budujemy sieci z niepelnego przejscia.
            //
            // Kontynuowanie na czesciowo zebranych danych byloby gorsze niz nic:
            // powstala siec mialaby mniej rur niz naprawde, a petla scalania
            // nizej skasowalaby stare sieci uznane za "znikniete" - czyli
            // rozbilibysmy dzialajaca siec tylko dlatego, ze zabraklo czasu na
            // policzenie jej w calosci. Kolejny update sasiada zglosi ja znowu.
            VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                    "network scan at %s exceeded %d ms budget after %d pipe(s) - deferred",
                    originPos, SCAN_BUDGET_NS / 1_000_000L, visitedPipes.size());
            queueRebuild(originPos);
            return null;
        }

        VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                "built network at %s: %d pipe(s) (%d z niezaladowanych chunkow), "
                        + "%d node(s), %d storage(s)",
                originPos, visitedPipes.size(), unloadedPipes.size(),
                discoveredTerminals.size(), discoveredEndpoints.size());

        // ID wynikowej sieci liczymy PRZED petla scalania - potrzebne, zeby
        // (patrz nizej)
        // wiedziec, ktorych starych cache'y NIE kasowac.
        //
        // Przebudowa zachowuje to samo UUID (preferredId = istniejaca siec),
        // a jej cache ma po prostu odswiezona referencje do nowego obiektu.
        // Kasowanie go zwalnialo force-loady, wiec kazda przebudowa wykladowywala
        // i ladowala chunki od nowa - mimo ze siec jest ta sama.
        // TOZSAMOSC SIECI - jednej z wciagnietych sieci NIE niszczymy.
        //
        // BUG, ktory tu byl: przy spotkaniu dwoch sieci OBIE byly kasowane
        // (networks.remove + drop cache), a powstawal jeden nowy obiekt.
        // Skutki widoczne w grze:
        //   - cache i force-loady obu sieci przepadaly, wiec terminal stojacy
        //     daleko tracil trzymanie swojego chunku,
        //   - nie dalo sie ich rozlozyc z powrotem, bo informacja o tym, ze
        //     byly DWIE sieci, juz nie istniala,
        //   - gdy obie mialy terminal, jeden tracil tozsamosc.
        //
        // Teraz siec-ciaglosc (preferredId albo ta, od ktorej zaczeto) jest
        // PRZEBUDOWYWANA na miejscu: zachowuje UUID, a wiec i cache, i
        // force-loady. Pozostale wciagniete sieci znikaja jako samodzielne
        // byty, ale ich zawartosc (rury, wezly, magazyny) jest przenoszona.
        UUID finalId = preferredId != null
                ? preferredId
                : (intersectedOldNets.isEmpty()
                        ? UUID.randomUUID()
                        : preferredIdOf(intersectedOldNets, originPos));

        // Przenoszenie zawartosci z sieci, ktore zostaly wciagniete.
        for (UUID oldId : intersectedOldNets) {
            if (oldId.equals(finalId)) {
                continue;   // te przebudowujemy, nie przenosimy
            }
            VelocePipeNetwork oldNet = networks.get(oldId);
            if (oldNet == null) {
                continue;
            }
            // Zapamietane liczby z niezaladowanych magazynow - bez tego
            // zawartosc skrzyni stojacej daleko znikalaby z GUI.
            for (Map.Entry<BlockPos, ConnectedEndpointInfo> oldEp : oldNet.getEndpoints().entrySet()) {
                if (discoveredEndpoints.containsKey(oldEp.getKey())) {
                    ConnectedEndpointInfo newEp = discoveredEndpoints.get(oldEp.getKey());
                    if (newEp.getCachedCounts().isEmpty()
                            && !oldEp.getValue().getCachedCounts().isEmpty()) {
                        newEp.getCachedCounts().putAll(oldEp.getValue().getCachedCounts());
                    }
                } else if (!level.isLoaded(oldEp.getKey())) {
                    discoveredEndpoints.put(oldEp.getKey(), oldEp.getValue());
                }
            }
            // Ta siec przestaje istniec jako osobny byt - zwalniamy jej
            // force-loady, bo jej rury i wezly przechodza do finalId.
            com.craftingveloce.crafting.VeloceCraftingCache.drop(level, oldId);
            networks.remove(oldId);
            for (BlockPos p : oldNet.getPipes()) {
                pipeToNetwork.remove(p);
            }
            for (BlockPos t : oldNet.getTerminals()) {
                terminalToNetwork.remove(t);
            }
        }

        // Siec-ciaglosc: zachowujemy ja, jesli istnieje. Wtedy jej cache
        // i force-loady NIE sa ruszane (patrz VeloceCraftingCache.get, ktory
        // odswieza tylko referencje do obiektu pod tym samym UUID).
        // Stara zawartosc zniknie - budujemy nowa liste od zera, ale obiekt
        // (a wiec UUID, cache i preferencje) zostaje ten sam.
        VelocePipeNetwork newNet = persistentNetwork(finalId, true);
        newNet.getPipes().addAll(visitedPipes);
        newNet.getTerminals().addAll(discoveredTerminals);
        newNet.clearEnergyEndpoints();
        for (BlockPos energyPos : discoveredEnergy) {
            newNet.addEnergyEndpoint(energyPos);
        }
        newNet.getEndpoints().putAll(discoveredEndpoints);
        newNet.updateTrackedChunks();

        // KLUCZOWE: synchronizuj knownEndpoints z nowo odkrytymi.
        //
        // BUG, ktory tu byl: scanAndBuildNetwork budowal nowe endpointy
        // (z refreshIfLoaded gdy chunk jest zaladowany), ale NIE aktualizowal
        // knownEndpoints. Efekt: po teleporcie obok beczki (chunk zaladowany ->
        // przebudowa z poprawnym odczytem) i z powrotem na start (chunk
        // rozladowany) - terminal pytal buildFromComponent, ktory bral z
        // knownEndpoints, ale tam byl stary pusty endpoint z NBT-load.
        // Terminal widzial 0 typow mimo ze beczka miala itemy.
        knownNodes.addAll(discoveredTerminals);
        for (Map.Entry<BlockPos, ConnectedEndpointInfo> e : discoveredEndpoints.entrySet()) {
            if (level.isLoaded(e.getKey())) {
                // Swiezy odczyt z zaladowanego chunku - zastepuje stary.
                knownEndpoints.put(e.getKey(), e.getValue());
            } else {
                // Niezaladowany - zachowaj tylko jesli nie mamy lepszego.
                knownEndpoints.putIfAbsent(e.getKey(), e.getValue());
            }
        }

        networks.put(finalId, newNet);
        for (BlockPos p : visitedPipes) {
            pipeToNetwork.put(p, finalId);
        }
        for (BlockPos t : discoveredTerminals) {
            terminalToNetwork.put(t, finalId);
        }

        // Wyczysc wpisy grafu dla sieci, ktore juz nie istnieja.
        setDirty();
        return newNet;
    }

    /**
     * Wybiera, ktora z wciagnietych sieci ma zachowac tozsamosc.
     *
     * <p>Preferujemy siec, ktora zawiera pozycje startowa przebudowy - to
     * naturalna ciaglosc: przebudowujemy siec "od tej rury". Gdy takiej nie ma,
     * bierzemy pierwsza deterministycznie (po UUID), zeby wynik byl powtarzalny
     * miedzy uruchomieniami.
     */
    private UUID preferredIdOf(Set<UUID> candidates, BlockPos originPos) {
        UUID byOrigin = pipeToNetwork.get(originPos);
        if (byOrigin != null && candidates.contains(byOrigin)) {
            return byOrigin;
        }
        return candidates.stream().min(UUID::compareTo).orElseGet(UUID::randomUUID);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag netList = new ListTag();
        for (VelocePipeNetwork net : networks.values()) {
            // Cache liczb jedzie razem z siecia do save'a - po restarcie swiata
            // gracz od razu widzi liczby, zamiast patrzec na puste cyferki.
            CompoundTag netTag = net.toNbt();
            net.saveCraftableMemo(netTag);
            netList.add(netTag);
        }
        tag.put("Networks", netList);
        return tag;
    }

    /**
     * Odtwarza plaska strukture rur z zapisanych sieci.
     *
     * <p><b>Po co.</b> Struktura polaczen NIE jest zapisywana osobno - i dobrze,
     * bo bylby to duplikat tych samych danych. Zapisywane sa sieci (z listami
     * rur i wezlow), a z nich odtwarzamy polaczenia: dla kazdej rury szukamy
     * sasiadow w tym samym zbiorze rur.
     *
     * <p>Dzieki temu struktura jest kompletna ZARAZ po wejsciu do swiata, bez
     * czekania, az gracz podejdzie do kazdej rury - a to bylo konieczne, zeby
     * terminal widzial zawartosc magazynow stojacych w niezaladowanych chunkach.
     *
     * <p>Polaczenia wyznaczamy z samych pozycji (odleglosc 1 w jednej osi),
     * bez dotykania swiata. Zamkniete strony (wrench) nie sa tu znane - zostana
     * uwzglednione przy pierwszej synchronizacji z swiatem, gdy chunk sie
     * zaladuje. Do tego czasu traktujemy rury jako polaczone, co jest
     * bezpieczniejsze niz uznanie ich za rozdzielone.
     */
    private void rebuildWorldFromNetworks() {
        world.clear();
        for (VelocePipeNetwork net : networks.values()) {
            for (BlockPos pipe : net.getPipes()) {
                world.addPipe(pipe);
            }
        }
        // Rejestr znanych magazynow: z NBT wiemy o nich razem z zawartoscia,
        // wiec po restarcie swiata od razu widzimy magazyny w niezaladowanych
        // chunkach - bez czekania, az gracz do nich podejdzie.
        knownEndpoints.clear();
        knownNodes.clear();
        for (VelocePipeNetwork net : networks.values()) {
            knownEndpoints.putAll(net.getEndpoints());
            knownNodes.addAll(net.getTerminals());
        }

        // Druga faza: polaczenia. Rura laczy sie z sasiadem, jesli obie sa
        // w tym samym zbiorze i stykaja sie sciana.
        for (VelocePipeNetwork net : networks.values()) {
            for (BlockPos pipe : net.getPipes()) {
                Set<BlockPos> neighbours = new HashSet<>();
                for (Direction d : Direction.values()) {
                    BlockPos np = pipe.relative(d);
                    if (world.hasPipe(np)) {
                        neighbours.add(np);
                    }
                }
                world.setNeighbours(pipe, neighbours);
            }
        }
        VeloceLog.Network.success(VeloceLog.Side.SERVER,
                "odtworzono strukture rur: %d rur, %d komponentow",
                world.pipeCount(), world.componentCount());
    }

    public static VelocePipeNetworkManager load(CompoundTag tag, HolderLookup.Provider provider) {
        VelocePipeNetworkManager manager = new VelocePipeNetworkManager();
        ListTag netList = tag.getList("Networks", Tag.TAG_COMPOUND);
        for (int i = 0; i < netList.size(); i++) {
            CompoundTag netTag = netList.getCompound(i);
            VelocePipeNetwork net = VelocePipeNetwork.fromNbt(netTag);
            net.restoreCraftableMemo(netTag);
            manager.networks.put(net.getId(), net);
            for (BlockPos p : net.getPipes()) {
                manager.pipeToNetwork.put(p, net.getId());
            }
            for (BlockPos t : net.getTerminals()) {
                manager.terminalToNetwork.put(t, net.getId());
            }
        }
        // Struktura polaczen od razu kompletna - inaczej terminal nie widzialby
        // zawartosci magazynow w niezaladowanych chunkach az do podejscia
        // gracza do kazdej rury.
        manager.rebuildWorldFromNetworks();
        return manager;
    }
}
