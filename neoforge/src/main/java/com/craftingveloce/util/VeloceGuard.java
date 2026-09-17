package com.craftingveloce.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Guard for the mod's periodic work invoked from the level tick.
 *
 * <p><b>Why it exists.</b> Three things happen on every world tick: the pipe
 * network step, broadcasting crafter states and keeping force-loads alive. Each
 * of them reaches into code that is not ours: Tom's block entities, Refined
 * Storage, vanilla containers. A single exception from a foreign library (a bad
 * capability, a container returning null) is enough to abort the level tick -
 * and then Minecraft dies with an "Exception ticking world" crash, with no
 * indication that it was our doing.
 *
 * <p><b>Why this does not hide errors.</b> The exception is NOT swallowed
 * silently: it always goes to the log (also when the mod is configured to log
 * less) with a full stack trace, the operation name and a note that without
 * this guard the server would have died. We only throttle REPEATING the same
 * error - otherwise a stuck operation would flood the log with thousands of
 * identical entries and hide the real cause.
 *
 * <p><b>Why only the level tick.</b> For block entity ticks vanilla itself
 * produces a readable report ("Ticking block entity" plus the block name), so
 * there is a better mechanism than ours. For the level tick there is none - and
 * that is exactly why this guard lives here.
 */
public final class VeloceGuard {

    /** This channel is not filtered by config: this is the case where the server would crash. */
    private static final Logger LOGGER = LoggerFactory.getLogger("craftingveloce");

    /** Minimum gap (in ticks) between logs of the same error. */
    private static final long LOG_THROTTLE_TICKS = 200L;

    /**
     * Last tick in which we logged a given error.
     *
     * <p>The map is bounded BY CONSTRUCTION: the keys are a handful of constant
     * operation names passed in code, not world data.
     */
    private static final Map<String, Long> LAST_LOG = new HashMap<>();

    private VeloceGuard() {
    }

    /** Runs the work; an exception is logged and throttled so it does not kill the world tick. */
    public static void run(String what, long gameTime, Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            Long last = LAST_LOG.get(what);
            boolean log = last == null || gameTime < last || gameTime - last >= LOG_THROTTLE_TICKS;
            if (!log) {
                return;
            }
            LAST_LOG.put(what, gameTime);
            LOGGER.error("[Veloce] operation '{}' threw an exception - skipping it for this "
                    + "tick so the world does not die. Please report this as a mod bug.", what, t);
            LOGGER.error("[Veloce] (the same error will not be repeated more often than once "
                    + "per {} ticks)", LOG_THROTTLE_TICKS);
        }
    }
}
