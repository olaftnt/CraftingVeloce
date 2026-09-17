package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The single place that answers the question "which machines stand in this
 * network".
 *
 * <p><b>Why.</b> Every source registry ({@code VeloceHeatSources},
 * {@code VeloceProcessingSources}) started with the same loop: copy the
 * terminals, sort by position, skip unloaded chunks, read the block entity and
 * pick out the interface of interest. Three copies of the same loop are three
 * places where {@code isLoaded} or the sorting can be forgotten - and precisely
 * such drift was the source of the "sometimes it sees it, sometimes it does
 * not" bugs in this project.
 *
 * <p><b>Three things this loop does right</b> (and which must stay):
 * <ol>
 *   <li>{@code network == null} is an empty list, not an exception,</li>
 *   <li>sorting by {@link BlockPos#asLong()} gives a repeatable order
 *       ({@code getTerminals()} is a set with no ordering guarantee),</li>
 *   <li>{@code level.isLoaded(pos)} skips chunks that are not loaded instead
 *       of loading them during a tick.</li>
 * </ol>
 */
public final class VeloceNetworkSources {

    private VeloceNetworkSources() {
    }

    /** Machines of the given type standing in the network, in position order. */
    public static <T> List<T> scan(ServerLevel level, VelocePipeNetwork network, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (network == null) {
            return out;
        }
        List<BlockPos> nodes = new ArrayList<>(network.getTerminals());
        nodes.sort(Comparator.comparingLong(BlockPos::asLong));
        for (BlockPos pos : nodes) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (type.isInstance(be)) {
                out.add(type.cast(be));
            }
        }
        return out;
    }
}
