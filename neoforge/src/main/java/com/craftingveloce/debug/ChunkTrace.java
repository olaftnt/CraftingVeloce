package com.craftingveloce.debug;

import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Unified chunk and network debug logging - console ONLY.
 *
 * <p><b>Why a separate logger.</b> {@link com.craftingveloce.util.VeloceLog} filters
 * messages by level and category from the config. To diagnose "what happened"
 * we need a record that will not disappear after a config change or a level
 * switch - that is why this logger always writes, under a single fixed prefix
 * {@code [Veloce][CHUNKTRACE]}.
 *
 * <p><b>Why not in chat.</b> With 1000 blocks of network, the chunk report alone
 * runs to dozens of lines - in chat it would be unreadable and would vanish
 * after a moment. In the log it stays permanently, can be searched and compared
 * between sessions.
 *
 * <p><b>Format.</b> Every line has the same layout, so that it can be parsed:
 * <pre>
 *   [Veloce][CHUNKTRACE] &lt;SESSION&gt; | &lt;ACTION&gt; | &lt;details&gt;
 * </pre>
 * where {@code SESSION} is a short identifier of the tracing run, thanks to
 * which lines from one test do not mix with previous ones.
 */
public final class ChunkTrace {

    private static final Logger LOG = LoggerFactory.getLogger("craftingveloce-trace");

    private ChunkTrace() {
    }

    /** Whether tracing is active. */
    private static volatile boolean enabled = false;

    /** Identifier of the current tracing session (short, for readability in the log). */
    private static volatile String session = "-";

    /** Counter of consecutive events in the session - so the order is visible. */
    private static int seq = 0;

    /**
     * When (gameTime) we last reported the chunk state.
     *
     * <p>Weak keys on worlds - we do not keep a world in memory.
     */
    private static final Map<ServerLevel, Long> LAST_SNAPSHOT = new WeakHashMap<>();

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables tracing and starts a new session.
     *
     * @return the session identifier
     */
    public static String start(ServerLevel level) {
        session = Long.toHexString(System.currentTimeMillis() & 0xFFFFFFL);
        seq = 0;
        enabled = true;
        LOG.info("[Veloce][CHUNKTRACE] {} | SESSION START | dimension={} gameTime={}",
                session, level.dimension().location(), level.getGameTime());
        return session;
    }

    /** Disables tracing. */
    public static void stop() {
        if (enabled) {
            LOG.info("[Veloce][CHUNKTRACE] {} | SESSION STOP | events={}", session, seq);
        }
        enabled = false;
    }

    public static String session() {
        return session;
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    /**
     * General event.
     *
     * @param action  short action name (e.g. BUILD, EXTRACT)
     * @param details details in printf format
     */
    public static void event(String action, String details, Object... args) {
        if (!enabled) {
            return;
        }
        seq++;
        String msg = args.length == 0 ? details : String.format(details, args);
        LOG.info("[Veloce][CHUNKTRACE] {} | #{} {} | {}", session, seq, action, msg);
    }

    /**
     * Event at a specific block - with position, chunk and simulation state.
     *
     * <p>This is the most important format in this file: it lets you state
     * unambiguously WHETHER the chunk was unloaded at the moment of the action.
     */
    public static void at(String action, ServerLevel level, net.minecraft.core.BlockPos pos,
                          String details, Object... args) {
        if (!enabled) {
            return;
        }
        seq++;
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        boolean loaded = level.isLoaded(pos);
        String msg = args.length == 0 ? details : String.format(details, args);
        LOG.info("[Veloce][CHUNKTRACE] {} | #{} {} | {} @[{},{},{}] chunk[{},{}] {} | {}",
                session, seq, action,
                level.getBlockState(pos).getBlock().getClass().getSimpleName(),
                pos.getX(), pos.getY(), pos.getZ(), cx, cz,
                loaded ? "LOADED" : "UNLOADED",
                msg);
    }

    /**
     * Full dump of the network chunk state - for before/after comparison.
     *
     * <p>Prints EVERY network chunk separately, with the information:
     * <ul>
     *   <li>whether it is loaded,</li>
     *   <li>what stands in it (nodes, storage),</li>
     *   <li>whether it is force-loaded by us and for what reason.</li>
     * </ul>
     */
    public static void snapshotChunks(String label, ServerLevel level,
                                      com.craftingveloce.network.pipe.VelocePipeNetwork net) {
        if (!enabled) {
            return;
        }
        seq++;
        LOG.info("[Veloce][CHUNKTRACE] {} | #{} SNAPSHOT {} | network={} pipes={} nodes={} storage={}",
                session, seq, label,
                net.getId().toString().substring(0, 8),
                net.getPipes().size(), net.getTerminals().size(), net.getEndpoints().size());

        // Nodes: these are the ones that keep chunks loaded permanently.
        for (net.minecraft.core.BlockPos p : net.getTerminals()) {
            logOneChunk("NODE", level, p, "network=" + net.getId().toString().substring(0, 8));
        }
        // Storage: these are supposed to unload.
        for (net.minecraft.core.BlockPos p : net.getEndpoints().keySet()) {
            logOneChunk("STORAGE", level, p, "network=" + net.getId().toString().substring(0, 8));
        }
        // Forced chunks - with the reason.
        for (var hc : com.craftingveloce.network.pipe.VeloceChunkLoader.listHeld(level)) {
            LOG.info("[Veloce][CHUNKTRACE] {} | #{} FORCED chunk[{},{}] hits={} tickets={}",
                    session, seq, hc.x(), hc.z(), hc.hits(),
                    hc.tickets().isEmpty() ? "(none)" : hc.tickets().stream()
                            .map(t -> t.reason() + ":" + t.owner())
                            .collect(java.util.stream.Collectors.joining(",")));
        }
        LOG.info("[Veloce][CHUNKTRACE] {} | #{} SNAPSHOT {} | forced total: {}",
                session, seq, label,
                com.craftingveloce.network.pipe.VeloceChunkLoader.appliedCount(level));
    }

    /** One chunk entry with a description of its contents. */
    private static void logOneChunk(String role, ServerLevel level,
                                    net.minecraft.core.BlockPos pos, String extra) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        boolean loaded = level.isLoaded(pos);
        boolean held = com.craftingveloce.network.pipe.VeloceChunkLoader.isHeld(
                level, net.minecraft.world.level.ChunkPos.asLong(cx, cz));
        LOG.info("[Veloce][CHUNKTRACE] {} | {} chunk[{},{}] block[{},{},{}] {} {} {}",
                session, role, cx, cz, pos.getX(), pos.getY(), pos.getZ(),
                loaded ? "LOADED" : "UNLOADED",
                held ? "FORCED" : "not-forced",
                extra);
    }

    /** Dump of the network contents - what the network sees and from which source. */
    public static void snapshotStock(String label, ServerLevel level,
                                     com.craftingveloce.network.pipe.VelocePipeNetwork net) {
        if (!enabled) {
            return;
        }
        seq++;
        Map<net.minecraft.world.item.Item, Long> counts = net.getAllItemCounts(level, true);
        LOG.info("[Veloce][CHUNKTRACE] {} | #{} STOCK {} | types={}",
                session, seq, label, counts.size());
        List<String> lines = new ArrayList<>();
        for (Map.Entry<net.minecraft.world.item.Item, Long> e : counts.entrySet()) {
            lines.add(e.getKey().getDescription().getString() + "=" + e.getValue());
        }
        java.util.Collections.sort(lines);
        for (String line : lines) {
            LOG.info("[Veloce][CHUNKTRACE] {} | #{} STOCK {} |   {}",
                    session, seq, label, line);
        }

        // Broken down BY ENDPOINT - this shows which data comes from the cache
        // (chunk unloaded) and which from a fresh scan.
        for (Map.Entry<net.minecraft.core.BlockPos,
                com.craftingveloce.network.pipe.ConnectedEndpointInfo> e
                : net.getEndpoints().entrySet()) {
            var pos = e.getKey();
            var ep = e.getValue();
            boolean loaded = level.isLoaded(pos);
            LOG.info("[Veloce][CHUNKTRACE] {} | #{} STOCK {} |   endpoint {} [{}] {} types={}{}",
                    session, seq, label, ep.getType(), pos.toShortString(),
                    loaded ? "LOADED(fresh scan)" : "UNLOADED(from cache)",
                    ep.getCachedCounts().size(),
                    loaded ? "" : "  <-- data from cache, not from the world");
        }
    }

    // ------------------------------------------------------------------
    // Operation result
    // ------------------------------------------------------------------

    /**
     * Result of an operation on items - with an unambiguous verdict.
     *
     * @param ok        whether the operation succeeded
     * @param viaQueue  whether it went through the queue (the chunk was unloaded)
     * @param chunkWasLoaded whether the chunk was loaded at the moment of starting
     */
    public static void result(String operation, boolean ok, boolean viaQueue,
                              boolean chunkWasLoaded, String details, Object... args) {
        if (!enabled) {
            return;
        }
        seq++;
        String msg = args.length == 0 ? details : String.format(details, args);
        String verdict;
        if (ok && viaQueue) {
            verdict = "OK(from the queue - the chunk was UNLOADED)";
        } else if (ok) {
            verdict = "OK(the chunk was LOADED)";
        } else {
            verdict = "FAIL";
        }
        LOG.info("[Veloce][CHUNKTRACE] {} | #{} RESULT {} | {} | chunk={} | {}",
                session, seq, operation, verdict,
                chunkWasLoaded ? "LOADED" : "UNLOADED", msg);
    }
}
