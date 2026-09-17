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

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("craftingveloce-sources");

    private VeloceNetworkSources() {
    }

    /** Machines of the given type standing in the network, in position order. */
    public static <T> List<T> scan(ServerLevel level, VelocePipeNetwork network, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (network == null) {
            LOG.debug("[VELOCE-DEBUG] tile lookup for {}: no network", type.getSimpleName());
            return out;
        }
        List<BlockPos> nodes = new ArrayList<>(network.getTerminals());
        nodes.sort(Comparator.comparingLong(BlockPos::asLong));
        int unloaded = 0;
        int foreign = 0;
        for (BlockPos pos : nodes) {
            if (!level.isLoaded(pos)) {
                unloaded++;
                continue;
            }
            // This is THE world-position -> tile lookup. When a machine "disappears"
            // from the network, the useful question is whether its position was
            // unloaded, whether the block entity was missing, or whether the type did
            // not match - three very different causes that used to look identical.
            BlockEntity be = level.getBlockEntity(pos);
            if (type.isInstance(be)) {
                out.add(type.cast(be));
            } else {
                foreign++;
                LOG.debug("[VELOCE-DEBUG] tile lookup {}: position {} holds {} (wanted {})",
                        type.getSimpleName(), pos.toShortString(),
                        be == null ? "no block entity" : be.getClass().getSimpleName(),
                        type.getSimpleName());
            }
        }
        LOG.debug("[VELOCE-DEBUG] tile lookup {}: {} terminal(s) -> {} match(es), "
                        + "{} unloaded, {} type mismatch",
                type.getSimpleName(), nodes.size(), out.size(), unloaded, foreign);
        return out;
    }
}
