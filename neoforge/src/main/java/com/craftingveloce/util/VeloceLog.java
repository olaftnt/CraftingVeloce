package com.craftingveloce.util;

import com.craftingveloce.config.VeloceConfig;
import com.craftingveloce.config.VeloceConfig.LogLevel;
import org.slf4j.Logger;

/**
 * Central Veloce logger.
 *
 * <p>Every line shares the common prefix {@code [Veloce]} and a short category
 * tag in brackets, e.g. {@code [Veloce][CRAFT]} - they can be filtered in the log.
 *
 * <p>The message pattern is deliberately identical for every operation, so that
 * they can be read as a sequence of events:
 * <pre>
 *   TRY    - what the player/server is trying to do
 *   OK     - what succeeded and what was returned
 *   FAIL   - what did not succeed
 *   WHY    - why (cause, numbers, missing elements)
 *   DETAIL - intermediate steps
 * </pre>
 *
 * <p>All messages are in English: they are there for grepping and pasting into
 * reports, not for reading by a player.
 */
public final class VeloceLog {

    public enum Side {
        /** Log called on the server side (world logic). */
        SERVER,
        /** Log called on the client side (GUI, render). */
        CLIENT
    }

    private VeloceLog() {
    }

    // ------------------------------------------------------------------
    // Core
    // ------------------------------------------------------------------

    private static void log(Logger log, Side side, LogLevel level, String tag,
                            String action, String message, Object... args) {
        logThrown(log, side, level, tag, action, null, message, args);
    }

    /**
     * Logging core, with an optional exception.
     *
     * <p><b>Why a separate variant.</b> Previously exceptions in the module went
     * through {@code t.printStackTrace()} - that is, to stderr, next to the
     * logging system, without a level and without a category. The effect: they
     * could not be turned off via config, they had no [Veloce] prefix and they
     * were not in the log file in the same format as the rest. Passing the
     * exception to log4j prints the message TOGETHER with the stack, in the
     * right place.
     */
    private static void logThrown(Logger log, Side side, LogLevel level, String tag,
                                  String action, Throwable thrown,
                                  String message, Object... args) {
        if (!enabled(side)) {
            return;
        }
        if (!VeloceConfig.allows(level)) {
            return;
        }
        String body = args.length == 0 ? message : String.format(message, args);
        String line = "[Veloce][" + tag + "][" + action + "] " + body;
        if (thrown != null) {
            if (level == LogLevel.ERRORS) {
                log.warn(line, thrown);
            } else {
                log.info(line, thrown);
            }
        } else if (level == LogLevel.ERRORS) {
            log.warn(line);
        } else {
            log.info(line);
        }
    }

    /**
     * Error with an exception: the message plus the STACK, in the category
     * {@code tag}.
     *
     * <p>Used where {@code printStackTrace()} used to be - so that the exception
     * lands in the mod's log, and not next to it.
     */
    public static void error(Side side, String tag, Throwable thrown,
                             String what, Object... args) {
        logThrown(logger(), side, LogLevel.ERRORS, tag, "ERR", thrown, what, args);
    }

    private static boolean enabled(Side side) {
        try {
            return side == Side.SERVER
                    ? VeloceConfig.LOG_SERVER.get()
                    : VeloceConfig.LOG_CLIENT.get();
        } catch (Throwable t) {
            // The config may not be loaded yet (early start-up phase).
            return false;
        }
    }

    private static Logger logger() {
        return com.craftingveloce.CraftingVeloceMod.LOGGER;
    }

    // ------------------------------------------------------------------
    // Patterns for typical situations
    // ------------------------------------------------------------------

    /** What someone is trying to do. */
    public static void attempt(Side side, String tag, String what, Object... args) {
        log(logger(), side, LogLevel.VERBOSE, tag, "TRY", what, args);
    }

    /** What succeeded and what was returned. */
    public static void success(Side side, String tag, String what, Object... args) {
        log(logger(), side, LogLevel.NORMAL, tag, "OK", what, args);
    }

    /** What did not succeed. */
    public static void failure(Side side, String tag, String what, Object... args) {
        log(logger(), side, LogLevel.ERRORS, tag, "FAIL", what, args);
    }

    /** Why - causes, numbers, missing elements. */
    public static void why(Side side, String tag, String what, Object... args) {
        log(logger(), side, LogLevel.NORMAL, tag, "WHY", what, args);
    }

    /** An intermediate step. */
    public static void detail(Side side, String tag, String what, Object... args) {
        log(logger(), side, LogLevel.DETAIL, tag, "DETAIL", what, args);
    }

    /**
     * Whether detailed logs will be emitted at all - a cheap check before
     * building them.
     *
     * <p>This is so that the caller can skip the costly gathering of
     * information that would have been thrown away anyway.
     */
    public static boolean isDetailEnabled(Side side) {
        try {
            return enabled(side) && VeloceConfig.allows(LogLevel.DETAIL);
        } catch (Throwable t) {
            return false;
        }
    }

    /** A state change (e.g. enabling auto-crafting). */
    public static void state(Side side, String tag, String what, Object... args) {
        log(logger(), side, LogLevel.VERBOSE, tag, "STATE", what, args);
    }

    // ------------------------------------------------------------------
    // Shorthands for frequently used categories
    // ------------------------------------------------------------------

    /** Pipe network: scanning, endpoints, extraction. */
    public static final class Network {
        private static final String TAG = "NET";

        public static void attempt(Side s, String what, Object... a) {
            if (checkNetwork()) VeloceLog.attempt(s, TAG, what, a);
        }

        public static void success(Side s, String what, Object... a) {
            if (checkNetwork()) VeloceLog.success(s, TAG, what, a);
        }

        public static void failure(Side s, String what, Object... a) {
            if (checkNetwork()) VeloceLog.failure(s, TAG, what, a);
        }

        /** Error with an exception - the stack lands in the log. */
        public static void error(Side s, Throwable t, String what, Object... a) {
            if (checkNetwork()) VeloceLog.error(s, TAG, t, what, a);
        }

        public static void why(Side s, String what, Object... a) {
            if (checkNetwork()) VeloceLog.why(s, TAG, what, a);
        }

        public static void detail(Side s, String what, Object... a) {
            if (checkNetwork()) VeloceLog.detail(s, TAG, what, a);
        }

        private static boolean checkNetwork() {
            try {
                return VeloceConfig.LOG_NETWORK.get();
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /** Auto-crafting: planning, execution, recipe selection. */
    public static final class Craft {
        private static final String TAG = "CRAFT";

        public static void attempt(Side s, String what, Object... a) {
            if (checkCraft()) VeloceLog.attempt(s, TAG, what, a);
        }

        public static void success(Side s, String what, Object... a) {
            if (checkCraft()) VeloceLog.success(s, TAG, what, a);
        }

        public static void failure(Side s, String what, Object... a) {
            if (checkCraft()) VeloceLog.failure(s, TAG, what, a);
        }

        /** Error with an exception - the stack lands in the log. */
        public static void error(Side s, Throwable t, String what, Object... a) {
            if (checkCraft()) VeloceLog.error(s, TAG, t, what, a);
        }

        public static void why(Side s, String what, Object... a) {
            if (checkCraft()) VeloceLog.why(s, TAG, what, a);
        }

        public static void detail(Side s, String what, Object... a) {
            if (checkCraft()) VeloceLog.detail(s, TAG, what, a);
        }

        /** Whether detailed logs of this category will be emitted at all. */
        public static boolean isDetailEnabled(Side s) {
            return checkCraft() && VeloceLog.isDetailEnabled(s);
        }

        private static boolean checkCraft() {
            try {
                return VeloceConfig.LOG_CRAFTING.get();
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /** Blocks: placing, breaking, connections. */
    public static final class Block {
        private static final String TAG = "BLOCK";

        public static void attempt(Side s, String what, Object... a) {
            if (checkBlocks()) VeloceLog.attempt(s, TAG, what, a);
        }

        public static void success(Side s, String what, Object... a) {
            if (checkBlocks()) VeloceLog.success(s, TAG, what, a);
        }

        public static void failure(Side s, String what, Object... a) {
            if (checkBlocks()) VeloceLog.failure(s, TAG, what, a);
        }

        /** Error with an exception - the stack lands in the log. */
        public static void error(Side s, Throwable t, String what, Object... a) {
            if (checkBlocks()) VeloceLog.error(s, TAG, t, what, a);
        }

        public static void why(Side s, String what, Object... a) {
            if (checkBlocks()) VeloceLog.why(s, TAG, what, a);
        }

        public static void detail(Side s, String what, Object... a) {
            if (checkBlocks()) VeloceLog.detail(s, TAG, what, a);
        }

        private static boolean checkBlocks() {
            try {
                return VeloceConfig.LOG_BLOCKS.get();
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /** GUI: opening, clicks, filters. */
    public static final class Gui {
        private static final String TAG = "GUI";

        public static void attempt(Side s, String what, Object... a) {
            if (checkGui()) VeloceLog.attempt(s, TAG, what, a);
        }

        public static void success(Side s, String what, Object... a) {
            if (checkGui()) VeloceLog.success(s, TAG, what, a);
        }

        public static void failure(Side s, String what, Object... a) {
            if (checkGui()) VeloceLog.failure(s, TAG, what, a);
        }

        /** Error with an exception - the stack lands in the log. */
        public static void error(Side s, Throwable t, String what, Object... a) {
            if (checkGui()) VeloceLog.error(s, TAG, t, what, a);
        }

        public static void why(Side s, String what, Object... a) {
            if (checkGui()) VeloceLog.why(s, TAG, what, a);
        }

        public static void detail(Side s, String what, Object... a) {
            if (checkGui()) VeloceLog.detail(s, TAG, what, a);
        }

        private static boolean checkGui() {
            try {
                return VeloceConfig.LOG_GUI.get();
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
