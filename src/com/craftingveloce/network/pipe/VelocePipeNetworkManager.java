package com.craftingveloce.network.pipe;

import com.craftingveloce.block.VelocePipeBlock;
import com.craftingveloce.block.VeloceTomTerminalBlock;
import com.craftingveloce.block.entity.VelocePipeBlockEntity;
import com.craftingveloce.rs.RefinedStorageHelper;
import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.BlockPos;
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

    public VelocePipeNetworkManager() {
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

        // Remove old network references
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
                net.updateTrackedChunks();
                setDirty();
            }
        }
    }

    public void onNeighborChanged(ServerLevel level, BlockPos pipePos, BlockPos neighborPos) {
        if (!level.isLoaded(pipePos)) return;
        rebuildAt(level, pipePos);
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
                // Clean up old network
                networks.remove(oldId);
                for (BlockPos p : oldNet.getPipes()) {
                    pipeToNetwork.remove(p);
                }
                for (BlockPos t : oldNet.getTerminals()) {
                    terminalToNetwork.remove(t);
                }
            }
        }

        UUID finalId = preferredId != null ? preferredId : (intersectedOldNets.size() == 1 ? intersectedOldNets.iterator().next() : UUID.randomUUID());
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
