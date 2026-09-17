package com.craftingveloce.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Chat debug: reports item operations in UNLOADED chunks.
 *
 * <p><b>Why.</b> When the network reaches into a chest standing in a chunk that
 * is not simulated, the server has to load that chunk first. That is exactly the
 * operation that can freeze a tick or hang a world save - and that is invisible
 * from the player's perspective. This notifier shows it in chat: which chunk, at
 * which block, what operation and how long it took.
 *
 * <p><b>It does not spam.</b> The same position with the same operation is
 * reported at most once per {@link #THROTTLE_TICKS} ticks. Without that, one
 * batch of item extraction would flood the chat with hundreds of lines - and
 * that is not a diagnosis, just noise.
 *
 * <p>DISABLED by default. Enable it with the command {@code /cv chunkdebug ops on}.
 */
public final class ChunkOpNotifier {

    /** Kind of operation on the contents of a chunk. */
    public enum Op {
        EXTRACT("extraction"),
        INSERT("insertion");

        private final String label;

        Op(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Whether to report operations. */
    private static boolean enabled = false;

    /** How many ticks of spacing for the same position and operation. */
    private static final int THROTTLE_TICKS = 100;

    /** (level, pos, op) -> tick of the last report. Weak keys for the worlds. */
    private static final Map<ServerLevel, Map<String, Long>> LAST_REPORT =
            new WeakHashMap<>();

    private ChunkOpNotifier() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            LAST_REPORT.clear();
        }
    }

    /**
     * Reports that an operation forced a chunk load.
     *
     * @param chunkKey the chunk key ({@link ChunkPos#asLong})
     * @param pos      the position of the block we reach into
     */
    public static void reportLoad(ServerLevel level, long chunkKey, BlockPos pos, Op op) {
        if (!enabled) {
            return;
        }
        if (isThrottled(level, pos, op)) {
            return;
        }
        int cx = ChunkPos.getX(chunkKey);
        int cz = ChunkPos.getZ(chunkKey);

        boolean frozen = com.craftingveloce.network.pipe.VeloceChunkLoader.isFrozen();

        MutableComponent head = Component.literal("§8[§6Veloce§8] §e" + op.label()
                + " §7in §cUNLOADED §7chunk §f[" + cx + ", " + cz + "]");
        for (ServerPlayer player : level.players()) {
            player.displayClientMessage(head, false);
            player.displayClientMessage(coordsLine(pos), false);
            if (frozen) {
                // This is the most important case: an operation during a world
                // save is what hangs the save.
                player.displayClientMessage(Component.literal(
                        "§8    ! §4the world is FROZEN (save/shutdown) - "
                                + "this operation may hang the save"), false);
            }
        }
    }

    /** A line with clickable block coordinates - click = teleport. */
    private static MutableComponent coordsLine(BlockPos pos) {
        String plain = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        MutableComponent coords = Component.literal("§8    block: §f" + plain)
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/tp @s " + plain))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to teleport there"))));
        return coords;
    }

    /**
     * Whether this report is too recent to repeat.
     *
     * <p>The key contains the position AND the operation, so extraction and
     * insertion at the same place are reported separately - those are two
     * different things to diagnose.
     */
    private static boolean isThrottled(ServerLevel level, BlockPos pos, Op op) {
        String key = pos.asLong() + ":" + op.name();
        Map<String, Long> seen = LAST_REPORT.computeIfAbsent(level, k -> new HashMap<>());
        long now = level.getGameTime();
        Long last = seen.get(key);
        if (last != null && now >= last && now - last < THROTTLE_TICKS) {
            return true;
        }
        seen.put(key, now);
        // With the world time turned back the counter makes no sense either - we
        // clear it so that the map does not grow without bound.
        if (last != null && now < last) {
            seen.clear();
            seen.put(key, now);
        }
        // MEMORY SAFETY CATCH. The key is "position:operation", so a long
        // diagnostic session flying around the world would add an entry for
        // every new place - and nothing would ever remove them. The counter
        // serves ONLY to limit spam, so clearing it breaks nothing: at most a
        // few messages will get through twice.
        if (seen.size() > MAX_TRACKED_KEYS) {
            seen.clear();
            seen.put(key, now);
        }
        return false;
    }

    /** Above this many entries we clear the position counter (see {@link #isThrottled}). */
    private static final int MAX_TRACKED_KEYS = 4096;
}
