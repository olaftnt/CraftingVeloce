package com.craftingveloce.network.pipe;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class VelocePipeNetwork {

    private final UUID id;
    private final Set<BlockPos> pipes = new HashSet<>();
    private final Set<BlockPos> terminals = new HashSet<>();
    private final Map<BlockPos, ConnectedEndpointInfo> endpoints = new HashMap<>();
    private final Set<ChunkPos> trackedChunks = new HashSet<>();

    public VelocePipeNetwork(UUID id) {
        this.id = id != null ? id : UUID.randomUUID();
    }

    public UUID getId() {
        return id;
    }

    public Set<BlockPos> getPipes() {
        return pipes;
    }

    public Set<BlockPos> getTerminals() {
        return terminals;
    }

    public Map<BlockPos, ConnectedEndpointInfo> getEndpoints() {
        return endpoints;
    }

    public Set<ChunkPos> getTrackedChunks() {
        return Collections.unmodifiableSet(trackedChunks);
    }

    public void updateTrackedChunks() {
        trackedChunks.clear();
        for (BlockPos p : pipes) {
            trackedChunks.add(new ChunkPos(p));
        }
        for (BlockPos p : terminals) {
            trackedChunks.add(new ChunkPos(p));
        }
        for (BlockPos p : endpoints.keySet()) {
            trackedChunks.add(new ChunkPos(p));
        }
    }

    /**
     * Czysci zapamietane liczby wszystkich endpointow.
     *
     * <p>Wywolywane gdy zmienil sie uklad sieci (podlaczenie/odlaczenie
     * inventory, zaladowanie chunka). Bez tego endpoint usunietej skrzyni
     * nadal raportowalby swoja dawna zawartosc.
     */
    public void invalidateEndpointCache() {
        for (ConnectedEndpointInfo ep : endpoints.values()) {
            ep.getCachedCounts().clear();
        }
    }

    public Map<Item, Long> getAllItemCounts(ServerLevel level) {
        Map<Item, Long> total = new HashMap<>();
        for (ConnectedEndpointInfo endpoint : endpoints.values()) {
            endpoint.refreshIfLoaded(level);
            for (Map.Entry<Item, Long> entry : endpoint.getCachedCounts().entrySet()) {
                if (entry.getValue() > 0) {
                    total.merge(entry.getKey(), entry.getValue(), Long::sum);
                }
            }
        }
        return total;
    }

    public ItemStack extractItem(ServerLevel level, Item item, int maxCount) {
        for (ConnectedEndpointInfo endpoint : endpoints.values()) {
            if (endpoint.getCachedCounts().getOrDefault(item, 0L) > 0) {
                ItemStack extracted = endpoint.extractItem(level, item, maxCount);
                if (!extracted.isEmpty()) {
                    return extracted;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);

        long[] pipeArr = new long[pipes.size()];
        int i = 0;
        for (BlockPos p : pipes) {
            pipeArr[i++] = p.asLong();
        }
        tag.putLongArray("Pipes", pipeArr);

        long[] termArr = new long[terminals.size()];
        i = 0;
        for (BlockPos p : terminals) {
            termArr[i++] = p.asLong();
        }
        tag.putLongArray("Terminals", termArr);

        ListTag endList = new ListTag();
        for (ConnectedEndpointInfo ep : endpoints.values()) {
            endList.add(ep.toNbt());
        }
        tag.put("Endpoints", endList);

        return tag;
    }

    public static VelocePipeNetwork fromNbt(CompoundTag tag) {
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        VelocePipeNetwork net = new VelocePipeNetwork(id);

        long[] pipeArr = tag.getLongArray("Pipes");
        for (long l : pipeArr) {
            net.pipes.add(BlockPos.of(l));
        }

        long[] termArr = tag.getLongArray("Terminals");
        for (long l : termArr) {
            net.terminals.add(BlockPos.of(l));
        }

        ListTag endList = tag.getList("Endpoints", Tag.TAG_COMPOUND);
        for (int j = 0; j < endList.size(); j++) {
            ConnectedEndpointInfo ep = ConnectedEndpointInfo.fromNbt(endList.getCompound(j));
            net.endpoints.put(ep.getPos(), ep);
        }

        net.updateTrackedChunks();
        return net;
    }
}
