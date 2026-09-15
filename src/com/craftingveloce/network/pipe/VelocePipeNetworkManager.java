package com.craftingveloce.network.pipe;

import com.craftingveloce.block.VelocePipeBlock;
import com.craftingveloce.block.VeloceTomTerminalBlock;
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
     * Tablica polaczen miedzy sieciami.
     *
     * <p>To ona sprawia, ze laczenie sieci A z B NIE niszczy zadnej z nich:
     * powstaje tylko krawedz. Rozlaczenie usuwa krawedz i obie sieci wracaja
     * dokladnie do stanu sprzed - z wlasnymi cache i force-loadami.
     */
    private final VeloceNetworkGraph graph = new VeloceNetworkGraph();

    /** Tick ostatniej przebudowy - do odstepu miedzy nimi. */
    private long lastRebuildTick = Long.MIN_VALUE;

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
        if (pendingRebuilds.isEmpty()) {
            return;
        }
        if (VeloceChunkLoader.isFrozen()) {
            // Serwer sie zamyka - przebudowy sieci sa wtedy bez sensu i tylko
            // przeszkadzaja w zapisie.
            pendingRebuilds.clear();
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

        for (int done = 0; done < MAX_REBUILDS_PER_TICK; done++) {
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
    private static boolean nodeConnectsToPipe(ServerLevel level, BlockPos nodePos, Direction pipeSide) {
        BlockState state = level.getBlockState(nodePos);
        Block block = state.getBlock();
        if (block instanceof VeloceTomTerminalBlock terminalBlock) {
            return terminalBlock.canConnectFrom(state, pipeSide);
        }
        if (block instanceof com.craftingveloce.block.VeloceExtractorBlock extractorBlock) {
            return extractorBlock.canConnectFrom(state, pipeSide);
        }
        if (block instanceof com.craftingveloce.block.VeloceCraftingTableBlock craftingTableBlock) {
            return craftingTableBlock.canConnectFrom(state, pipeSide);
        }
        return false;
    }

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

    /** Tablica polaczen miedzy sieciami - do raportow i testow. */
    public VeloceNetworkGraph getGraph() {
        return graph;
    }

    public VelocePipeNetwork getNetworkById(UUID id) {
        return networks.get(id);
    }

    public VelocePipeNetwork getNetworkForPipe(BlockPos pos) {
        UUID id = pipeToNetwork.get(pos);
        return id != null ? networks.get(id) : null;
    }

    public VelocePipeNetwork getNetworkForTerminal(ServerLevel level, BlockPos terminalPos) {
        UUID id = terminalToNetwork.get(terminalPos);
        if (id != null && networks.containsKey(id)) {
            return networks.get(id);
        }

        // Szukamy sieci wsrod sasiadow, ale TYLKO z tej strony, z ktorej wezel
        // naprawde moze sie podlaczyc (patrz nodeConnectsToPipe).
        for (Direction d : Direction.values()) {
            BlockPos neighborPos = terminalPos.relative(d);
            UUID netId = pipeToNetwork.get(neighborPos);
            if (netId == null || !networks.containsKey(netId)) {
                continue;
            }
            // d to kierunek OD wezla DO rury, wiec z perspektywy rury jest to
            // strona przeciwna.
            if (!nodeConnectsToPipe(level, terminalPos, d.getOpposite())) {
                continue;
            }
            VelocePipeNetwork net = networks.get(netId);
            net.getTerminals().add(terminalPos);
            net.updateTrackedChunks();
            terminalToNetwork.put(terminalPos, netId);
            // Doszedl wezel - jego dane sa czescia stocku sieci.
            net.invalidateEndpointCache(level);
            setDirty();
            return net;
        }
        return null;
    }

    public Collection<VelocePipeNetwork> getAllNetworks() {
        return Collections.unmodifiableCollection(networks.values());
    }

    public void rebuildAt(ServerLevel level, BlockPos startPos) {
        if (!level.isLoaded(startPos)) return;

        BlockState state = level.getBlockState(startPos);
        if (state.getBlock() instanceof VelocePipeBlock) {
            UUID existingId = pipeToNetwork.get(startPos);
            scanAndBuildNetwork(level, startPos, existingId);
        }
    }

    public void onPipePlaced(ServerLevel level, BlockPos pos) {
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
        // Przy zamykaniu/zapisie swiata nie ma czego uniewazniac - cache i tak
        // za chwile znikna. Bez tego kazdy cykl load/unload chunka produkowal
        // wpis do loga i przebudowywal sieci: w jednym zamknieciu swiata
        // naliczylo sie 8000+ linii, co samo w sobie zamulalo zapis.
        if (VeloceChunkLoader.isFrozen()) {
            return;
        }
        // Opisy blokow budujemy TYLKO gdy debug na czacie jest wlaczony
        // (domyslnie wylaczony). Wczesniej describeBlock() - z nazwa klasy,
        // nazwa bloku i sklejaniem stringow - lecialo przy KAZDEJ zmianie
        // chunka, nawet gdy nikt tego nie ogladal.
        boolean wantDetails = com.craftingveloce.debug.ChunkDebugNotifier.isEnabled();
        java.util.List<String> affectedThings = wantDetails
                ? new java.util.ArrayList<>()
                : java.util.List.of();
        int affected = 0;
        for (VelocePipeNetwork net : networks.values()) {
            if (!net.getTrackedChunks().contains(chunkPos)) {
                continue;
            }
            affected++;

            if (!wantDetails) {
                continue;
            }

            // Zbierz CO dokladnie lezy w tym chunku - do raportu na czacie.
            for (BlockPos p : net.getTerminals()) {
                if (isInChunk(p, chunkPos)) {
                    affectedThings.add(describeBlock(level, p));
                }
            }
            for (BlockPos p : net.getEndpoints().keySet()) {
                if (isInChunk(p, chunkPos)) {
                    affectedThings.add(describeBlock(level, p));
                }
            }

        }
        if (affected > 0) {
            com.craftingveloce.util.VeloceLog.Network.detail(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "chunk %s %s affects %d network(s) - cache invalidated",
                    chunkPos, loaded ? "loaded" : "unloaded", affected);

            // Zapis do konsoli: pelny cykl load/unload z liczba sieci.
            // Na czacie tylko gdy monitor wlaczony - czat nie jest do logow.
            com.craftingveloce.debug.ChunkTrace.event("CHUNK",
                    "%s chunk[%d,%d] dotyczy %d sieci",
                    loaded ? "LOAD" : "UNLOAD", chunkPos.x, chunkPos.z, affected);
            com.craftingveloce.debug.ChunkDebugNotifier.notifyChunkChange(
                    level, chunkPos, loaded, affectedThings);
        }
    }

    /** Krotki opis bloku w chunku - do komunikatu debugowego. */
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

        // Usuniecie rury moze rozerwac styk dwoch sieci. Sprawdzamy to OD RAZU,
        // a nie dopiero przy nastepnej przebudowie: jesli sieci mialy byc
        // rozdzielone, gracz musi to zobaczyc natychmiast w terminalu.
        refreshLinkedNetworks(level);
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
            if (!nodeConnectsToPipe(level, terminalPos, d.getOpposite())) {
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
        // Postawienie rury moze POLACZYC dwie sieci (albo zamknieta strona je
        // rozciac) - graf musi to zobaczyc od razu, zeby terminal od razu
        // widzial nowa zawartosc.
        refreshLinkedNetworks(level);
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

            BlockEntity curBE = level.getBlockEntity(current);
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

                // 2. Neighbor is VeloceTomTerminalBlock or VeloceExtractorBlock
                if (neighborState.getBlock() instanceof VeloceTomTerminalBlock terminalBlock) {
                    if (terminalBlock.canConnectFrom(neighborState, dir.getOpposite())) {
                        discoveredTerminals.add(neighborPos);
                    }
                    continue;
                }
                if (neighborState.getBlock() instanceof com.craftingveloce.block.VeloceExtractorBlock extractorBlock) {
                    if (extractorBlock.canConnectFrom(neighborState, dir.getOpposite())) {
                        discoveredTerminals.add(neighborPos);
                    }
                    continue;
                }
                if (neighborState.getBlock() instanceof com.craftingveloce.block.VeloceCraftingTableBlock craftingTableBlock) {
                    if (craftingTableBlock.canConnectFrom(neighborState, dir.getOpposite())) {
                        discoveredTerminals.add(neighborPos);
                        // Bufor auto-craftera jest dodatkowo endpointem magazynu:
                        // dzieki temu nadwyzka produkcji (np. 3 deski z 1 logu,
                        // gdy gracz chcial 1) jest widoczna dla calej sieci i
                        // mozna ja wyciagnac terminalem, rura czy hopperem.
                        discoveredEndpoints.put(neighborPos, new CraftingBufferEndpoint(
                                neighborPos, dir.getOpposite()));
                    }
                    continue;
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
            graph.forget(oldId);
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
        VelocePipeNetwork newNet = networks.get(finalId);
        if (newNet == null) {
            newNet = new VelocePipeNetwork(finalId);
        } else {
            // Stara zawartosc zniknie - budujemy nowa liste od zera, ale
            // obiekt (a wiec UUID i cache) zostaje ten sam.
            newNet.getPipes().clear();
            newNet.getTerminals().clear();
            newNet.getEndpoints().clear();
        }
        newNet.getPipes().addAll(visitedPipes);
        newNet.getTerminals().addAll(discoveredTerminals);
        newNet.getEndpoints().putAll(discoveredEndpoints);
        newNet.updateTrackedChunks();

        networks.put(finalId, newNet);
        for (BlockPos p : visitedPipes) {
            pipeToNetwork.put(p, finalId);
        }
        for (BlockPos t : discoveredTerminals) {
            terminalToNetwork.put(t, finalId);
        }

        // Wyczysc wpisy grafu dla sieci, ktore juz nie istnieja.
        for (UUID oldId : new java.util.ArrayList<>(graph.allNodes())) {
            if (!networks.containsKey(oldId)) {
                graph.forget(oldId);
            }
        }

        refreshLinkedNetworks(level);
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

    /**
     * Wykrywa styki miedzy sieciami i wpisuje je do tablicy polaczen.
     *
     * <p>Dwie sieci stykaja sie, gdy rura jednej sasiaduje z rura drugiej -
     * czyli gdy leza w odleglosci 1 i ZADNA z nich nie ma zamknietej strony
     * w tym kierunku. Zamknieta strona (wrench) jest wiec ROZCIECIEM lacza,
     * zgodnie z tym jak dziala wrench w innych modach.
     *
     * <p>Wykrywanie jest lokalne: sprawdzamy tylko sasiadow rur, ktore juz
     * znamy. Nie ma tu zadnego BFS po calym swiecie, wiec nie zalezy od tego,
     * ktore chunki sa zaladowane.
     */
    private void refreshLinkedNetworks(ServerLevel level) {
        // Zbieramy wszystkie styki: para sieci -> pozycja styku.
        Map<String, UUID[]> pairs = new HashMap<>();
        Map<String, Set<Long>> found = new HashMap<>();

        for (Map.Entry<BlockPos, UUID> e : pipeToNetwork.entrySet()) {
            BlockPos pos = e.getKey();
            UUID mine = e.getValue();
            if (!level.isLoaded(pos)) {
                continue;
            }
            VelocePipeBlockEntity be = level.getBlockEntity(pos) instanceof VelocePipeBlockEntity p
                    ? p : null;
            for (Direction d : Direction.values()) {
                // Zamknieta strona = rozciecie lacza.
                if (be != null && be.isDisconnected(d)) {
                    continue;
                }
                BlockPos np = pos.relative(d);
                UUID theirs = pipeToNetwork.get(np);
                if (theirs == null || theirs.equals(mine)) {
                    continue;
                }
                // Sasiad tez musi byc otwarty w nasza strone.
                if (level.isLoaded(np)
                        && level.getBlockEntity(np) instanceof VelocePipeBlockEntity other
                        && other.isDisconnected(d.getOpposite())) {
                    continue;
                }
                String key = VeloceNetworkGraph.pairKey(mine, theirs);
                pairs.putIfAbsent(key, new UUID[]{mine, theirs});
                found.computeIfAbsent(key, k -> new HashSet<>()).add(np.asLong());
            }
        }

        // Wpisujemy nowe styki. Stare, ktorych juz nie ma, usuwamy.
        for (Map.Entry<String, UUID[]> e : pairs.entrySet()) {
            UUID[] pair = e.getValue();
            Set<Long> points = found.getOrDefault(e.getKey(), Set.of());
            for (long p : points) {
                graph.link(pair[0], pair[1], p);
            }
        }
        // Krawedzie, ktorych juz nie ma w swiecie (rura zniknela), usuwamy.
        //
        // Robimy to na podstawie FAKTYCZNYCH stykow, a nie nazw w logu:
        // dla kazdej pary w grafie sprawdzamy, czy ktorykolwiek z jej punktow
        // styku nadal istnieje w swiecie.
        for (UUID a : new java.util.ArrayList<>(graph.allNodes())) {
            for (UUID b : new java.util.ArrayList<>(graph.neighbours(a))) {
                if (a.compareTo(b) > 0) {
                    continue;   // ta sama krawedz widziana z drugiej strony
                }
                java.util.Set<Long> points = graph.contactPoints(a, b);
                boolean alive = false;
                for (long packed : points) {
                    BlockPos cp = BlockPos.of(packed);
                    // Styk zyje, jesli obie rury nadal sa i sa do siebie
                    // przypisane jako rozne sieci.
                    UUID atCp = pipeToNetwork.get(cp);
                    if (atCp != null && (atCp.equals(a) || atCp.equals(b))) {
                        alive = true;
                        break;
                    }
                }
                if (!alive) {
                    // Usuwamy po kolei wszystkie punkty - unlink sam zdecyduje,
                    // czy krawedz znikla (znika przy ostatnim).
                    for (long packed : points) {
                        graph.unlink(a, b, packed);
                    }
                }
            }
        }

        // Rozdajemy kazdej sieci liste polaczonych - to widzi terminal.
        for (VelocePipeNetwork net : networks.values()) {
            java.util.List<VelocePipeNetwork> linked = new java.util.ArrayList<>();
            for (UUID otherId : graph.neighbours(net.getId())) {
                VelocePipeNetwork other = networks.get(otherId);
                if (other != null) {
                    linked.add(other);
                }
            }
            net.setLinkedNetworks(linked);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag netList = new ListTag();
        for (VelocePipeNetwork net : networks.values()) {
            netList.add(net.toNbt());
        }
        tag.put("Networks", netList);
        return tag;
    }

    public static VelocePipeNetworkManager load(CompoundTag tag, HolderLookup.Provider provider) {
        VelocePipeNetworkManager manager = new VelocePipeNetworkManager();
        ListTag netList = tag.getList("Networks", Tag.TAG_COMPOUND);
        for (int i = 0; i < netList.size(); i++) {
            CompoundTag netTag = netList.getCompound(i);
            VelocePipeNetwork net = VelocePipeNetwork.fromNbt(netTag);
            manager.networks.put(net.getId(), net);
            for (BlockPos p : net.getPipes()) {
                manager.pipeToNetwork.put(p, net.getId());
            }
            for (BlockPos t : net.getTerminals()) {
                manager.terminalToNetwork.put(t, net.getId());
            }
        }
        return manager;
    }
}
