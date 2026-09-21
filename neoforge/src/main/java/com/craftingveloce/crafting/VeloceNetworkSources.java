package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 *
 * <p><b>Why the per-tick memo exists (do not remove it).</b> This loop used to
 * run once per QUESTION, and the questions are asked per ITEM: the module
 * registries ask "is there a powered machine of this family" while walking the
 * whole registered item list (tens of thousands of items) and again while
 * planning. The debug log made the scale visible in a real session: 60 651
 * scans in 7 seconds, up to 50 823 log lines in a single second, 25 570 lines
 * per second on average and 37.5 MB of {@code debug.log} - all on the server
 * thread, which is felt in game as the window freezing for seconds while a
 * terminal is opened. Caching alone removes ~60 000 of those scans; the
 * diagnostics are now behind the debug switch on top of that.
 *
 * <p>The memo is kept PER THREAD and per game tick: the list is thrown away as
 * soon as {@code level.getGameTime()} moves on, so it can never serve a stale
 * machine list for longer than one tick, and a thread that only reads (the
 * counting worker) never sees another thread's cache.
 */
public final class VeloceNetworkSources {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("craftingveloce-sources");

    private VeloceNetworkSources() {
    }

    /** The scans of the current tick, one memo per thread - see the class comment. */
    private static final ThreadLocal<Memo> MEMO = ThreadLocal.withInitial(Memo::new);

    private static final class Memo {
        private long tick = Long.MIN_VALUE;
        private final Map<Key, List<?>> byKey = new HashMap<>();
    }

    /**
     * Identity key of one scan. Identity on purpose for the level and the
     * network: two equal-looking pipe networks are still two different
     * networks, and comparing them by value would be both wrong and slow.
     */
    private static final class Key {
        private final Object level;
        private final Object network;
        private final Class<?> type;
        private final int hash;

        private Key(Object level, Object network, Class<?> type) {
            this.level = level;
            this.network = network;
            this.type = type;
            this.hash = System.identityHashCode(level) * 31
                    + System.identityHashCode(network) * 7
                    + type.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key k)) {
                return false;
            }
            return this.level == k.level && this.network == k.network && this.type == k.type;
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }

    /** Machines of the given type standing in the network, in position order. */
    public static <T> List<T> scan(ServerLevel level, VelocePipeNetwork network, Class<T> type) {
        long tick = level.getGameTime();
        Memo memo = MEMO.get();
        if (memo.tick != tick) {
            memo.tick = tick;
            memo.byKey.clear();
        }
        Key key = new Key(level, network, type);
        List<?> cached = memo.byKey.get(key);
        if (cached == null) {
            cached = List.copyOf(collect(level, network, type));
            memo.byKey.put(key, cached);
        }
        // A FRESH MUTABLE copy every call: the callers sort the result by their own
        // priority, and the memo must not be reordered by whoever asked first.
        return new ArrayList<>((List<T>) cached);
    }

    /** The real loop - always runs at most once per tick and key. */
    private static <T> List<T> collect(ServerLevel level, VelocePipeNetwork network, Class<T> type) {
        List<T> out = new ArrayList<>();
        boolean debug = debugEnabled();
        if (network == null) {
            if (debug) {
                LOG.debug("[VELOCE-DEBUG] tile lookup for {}: no network", type.getSimpleName());
            }
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
                if (debug) {
                    LOG.debug("[VELOCE-DEBUG] tile lookup {}: position {} holds {} (wanted {})",
                            type.getSimpleName(), pos.toShortString(),
                            be == null ? "no block entity" : be.getClass().getSimpleName(),
                            type.getSimpleName());
                }
            }
        }
        if (debug) {
            LOG.debug("[VELOCE-DEBUG] tile lookup {}: {} terminal(s) -> {} match(es), "
                            + "{} unloaded, {} type mismatch",
                    type.getSimpleName(), nodes.size(), out.size(), unloaded, foreign);
        }
        return out;
    }

    /**
     * The debug switch, and never a throw - the config may not be loaded during
     * startup, and a logging helper must not be able to bring the mod down.
     */
    private static boolean debugEnabled() {
        try {
            return com.craftingveloce.config.VeloceConfig.DEBUG_ENABLED.get();
        } catch (Throwable notLoadedYet) {
            return false;
        }
    }
}
