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

    /** Tick ostatniej przebudowy - do odstepu miedzy nimi. */
    private long lastRebuildTick = Long.MIN_VALUE;

    /** Minimalny odstep miedzy przebudowami sieci, w tickach (5 na sekunde). */
    private static final int REBUILD_COOLDOWN_TICKS = 4;

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
        if (lastRebuildTick != Long.MIN_VALUE && now - lastRebuildTick < REBUILD_COOLDOWN_TICKS) {
            return;
        }
        lastRebuildTick = now;

        java.util.Iterator<BlockPos> it = pendingRebuilds.iterator();
        BlockPos pos = it.next();
        it.remove();
        rebuildAt(level, pos);
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

        // Check adjacent blocks for pipe networks
        for (Direction d : Direction.values()) {
            BlockPos neighborPos = terminalPos.relative(d);
            UUID netId = pipeToNetwork.get(neighborPos);
            if (netId != null && networks.containsKey(netId)) {
                VelocePipeNetwork net = networks.get(netId);
                net.getTerminals().add(terminalPos);
                // Nowy wezel = nowe dane w GUI (i nowy crafter moze craftowac).
                com.craftingveloce.crafting.VeloceCraftingCache.get(net)
                        .onEndpointChanged(level);
                net.updateTrackedChunks();
                terminalToNetwork.put(terminalPos, netId);
                setDirty();
                return net;
            }
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
        java.util.List<String> affectedThings = new java.util.ArrayList<>();
        int affected = 0;
        for (VelocePipeNetwork net : networks.values()) {
            if (!net.getTrackedChunks().contains(chunkPos)) {
                continue;
            }
            affected++;

            // Zbierz CO dokladnie lezy w tym chunku - do raportu na czacie.
            for (BlockPos p : net.getTerminals()) {
                if (new ChunkPos(p).equals(chunkPos)) {
                    affectedThings.add(describeBlock(level, p));
                }
            }
            for (BlockPos p : net.getEndpoints().keySet()) {
                if (new ChunkPos(p).equals(chunkPos)) {
                    affectedThings.add(describeBlock(level, p));
                }
            }

            com.craftingveloce.crafting.VeloceCraftingCache.get(net)
                    .onEndpointChanged(level);
        }
        if (affected > 0) {
            com.craftingveloce.util.VeloceLog.Network.detail(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "chunk %s %s affects %d network(s) - cache invalidated",
                    chunkPos, loaded ? "loaded" : "unloaded", affected);

            // Debug na czacie: pokaz graczom, co zniknelo/pojawilo sie w sieci.
            com.craftingveloce.debug.ChunkDebugNotifier.notifyChunkChange(
                    level, chunkPos, loaded, affectedThings);
        }
    }

    /** Krotki opis bloku w chunku - do komunikatu debugowego. */
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

        // Rescan each remaining connected component
        for (BlockPos np : remainingNeighbors) {
            if (!pipeToNetwork.containsKey(np) && level.isLoaded(np) && level.getBlockState(np).getBlock() instanceof VelocePipeBlock) {
                scanAndBuildNetwork(level, np, null);
            }
        }
        setDirty();
    }

    public void onTerminalPlaced(ServerLevel level, BlockPos terminalPos) {
        for (Direction d : Direction.values()) {
            BlockPos neighborPos = terminalPos.relative(d);
            UUID netId = pipeToNetwork.get(neighborPos);
            if (netId != null && networks.containsKey(netId)) {
                VelocePipeNetwork net = networks.get(netId);
                net.getTerminals().add(terminalPos);
                net.updateTrackedChunks();
                terminalToNetwork.put(terminalPos, netId);
                setDirty();
                return;
            }
        }
    }

    public void onTerminalRemoved(BlockPos terminalPos) {
        UUID netId = terminalToNetwork.remove(terminalPos);
        if (netId != null) {
            VelocePipeNetwork net = networks.get(netId);
            if (net != null) {
                net.getTerminals().remove(terminalPos);
                // Wezel zniknal - jego dane nie sa juz aktualne. Cache dostanie
                // pelny skan przy najblizszym ticku (lastFullStockScan).
                net.invalidateEndpointCache();
                com.craftingveloce.crafting.VeloceCraftingCache.get(net)
                        .forceRefreshOnNextTick();
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
        pendingRebuilds.add(pipePos.immutable());

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
            com.craftingveloce.crafting.VeloceCraftingCache.get(net).onEndpointChanged(level);
            // Bufor endpointu mogl wskazywac na usunieta skrzynie - przelicz od nowa.
            net.invalidateEndpointCache();
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

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visitedPipes = new HashSet<>();
        Set<BlockPos> discoveredTerminals = new HashSet<>();
        Map<BlockPos, ConnectedEndpointInfo> discoveredEndpoints = new HashMap<>();
        Set<UUID> intersectedOldNets = new HashSet<>();

        queue.add(originPos);
        visitedPipes.add(originPos);

        while (!queue.isEmpty()) {
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
                    // Check if neighbor was an already-known pipe in an old network
                    UUID existingNetId = pipeToNetwork.get(neighborPos);
                    if (existingNetId != null && intersectedOldNets.contains(existingNetId)) {
                        visitedPipes.add(neighborPos);
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

        VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                "built network at %s: %d pipe(s), %d terminal(s), %d endpoint(s)",
                originPos, visitedPipes.size(), discoveredTerminals.size(),
                discoveredEndpoints.size());

        // ID wynikowej sieci liczymy PRZED petla scalania - potrzebne, zeby
        // wiedziec, ktorych starych cache'y NIE kasowac.
        //
        // Przebudowa zachowuje to samo UUID (preferredId = istniejaca siec),
        // a jej cache ma po prostu odswiezona referencje do nowego obiektu.
        // Kasowanie go zwalnialo force-loady, wiec kazda przebudowa wykladowywala
        // i ladowala chunki od nowa - mimo ze siec jest ta sama.
        UUID finalId = preferredId != null
                ? preferredId
                : (intersectedOldNets.size() == 1
                        ? intersectedOldNets.iterator().next()
                        : UUID.randomUUID());

        // Preserve cached endpoints from old networks (crucial for unloaded chunks)
        for (UUID oldId : intersectedOldNets) {
            VelocePipeNetwork oldNet = networks.get(oldId);
            if (oldNet != null) {
                for (Map.Entry<BlockPos, ConnectedEndpointInfo> oldEp : oldNet.getEndpoints().entrySet()) {
                    if (!discoveredEndpoints.containsKey(oldEp.getKey())) {
                        // If unloaded, preserve it in the network
                        if (!level.isLoaded(oldEp.getKey())) {
                            discoveredEndpoints.put(oldEp.getKey(), oldEp.getValue());
                        }
                    } else {
                        ConnectedEndpointInfo newEp = discoveredEndpoints.get(oldEp.getKey());
                        if (newEp.getCachedCounts().isEmpty() && !oldEp.getValue().getCachedCounts().isEmpty()) {
                            newEp.getCachedCounts().putAll(oldEp.getValue().getCachedCounts());
                        }
                    }
                }
                // Sprzatamy TYLKO sieci, ktore naprawde znikaja. Siec o ID
                // wynikowym jest przebudowywana, nie usuwana - jej cache
                // (i force-loady) zostaja.
                if (!oldId.equals(finalId)) {
                    com.craftingveloce.crafting.VeloceCraftingCache.drop(level, oldId);
                }
                networks.remove(oldId);
                for (BlockPos p : oldNet.getPipes()) {
                    pipeToNetwork.remove(p);
                }
                for (BlockPos t : oldNet.getTerminals()) {
                    terminalToNetwork.remove(t);
                }
            }
        }

        VelocePipeNetwork newNet = new VelocePipeNetwork(finalId);
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

        setDirty();
        return newNet;
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
