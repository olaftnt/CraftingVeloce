package com.craftingveloce.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Veloce mod configuration.
 *
 * <p>File: {@code config/craftingveloce-common.toml}. Changes take effect after
 * a game restart (NeoForge re-evaluates the spec on load).
 *
 * <p>The DEBUG section is used to diagnose problems: enable {@code debugEnabled}
 * and set {@code debugLevel} to DETAIL to see the full flow of operations -
 * what the player tried to do, what the system did, what it received and why.
 */
public final class VeloceConfig {

    public static final ModConfigSpec SPEC;

    // --- Debug / logging ---
    public static final ModConfigSpec.BooleanValue DEBUG_ENABLED;
    public static final ModConfigSpec.EnumValue<LogLevel> DEBUG_LEVEL;
    public static final ModConfigSpec.BooleanValue LOG_SERVER;
    public static final ModConfigSpec.BooleanValue LOG_CLIENT;
    public static final ModConfigSpec.BooleanValue LOG_NETWORK;
    public static final ModConfigSpec.BooleanValue LOG_CRAFTING;
    public static final ModConfigSpec.BooleanValue LOG_BLOCKS;
    public static final ModConfigSpec.BooleanValue LOG_GUI;

    /**
     * The profiler's own switch - deliberately NOT part of {@code debugEnabled}.
     *
     * <p>See {@link com.craftingveloce.util.VeloceProfiler}: a profiler is wanted exactly when
     * the pack is large and something is slow, and turning on the ordinary debug logging in a
     * 339-mod pack would bury its numbers under every other mod's output. It writes through the
     * plain logger instead, so the breakdown lands in {@code latest.log} on its own.
     */
    public static final ModConfigSpec.BooleanValue PROFILER_ENABLED;

    /** Detail levels - the higher, the more lines. */
    public enum LogLevel {
        /** Only errors and operations that did not succeed. */
        ERRORS,
        /** Errors + operation outcomes (what happened). */
        NORMAL,
        /** + what the player tried to do and what they received. */
        VERBOSE,
        /** + intermediate steps (planning, network scans, packets). */
        DETAIL
    }

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Debug logging. Turn this on when something misbehaves, then read",
                  "logs/latest.log - every line is prefixed with [Veloce] and tagged",
                  "with a category so it can be filtered.",
                  "All messages are in English on purpose: they are meant to be",
                  "grepped, pasted and read by a developer.")
                .push("debug");

        DEBUG_ENABLED = b
                .comment("Master switch for Veloce debug logging.",
                         "false = only the few startup lines are logged.")
                .define("debugEnabled", false);

        DEBUG_LEVEL = b
                .comment("How much detail to log:",
                         "ERRORS  - failures only",
                         "NORMAL  - failures + outcomes",
                         "VERBOSE - + what the player tried and received",
                         "DETAIL  - + intermediate steps (planning, scans, packets)")
                .defineEnum("debugLevel", LogLevel.VERBOSE);

        LOG_SERVER = b.comment("Log server-side activity (world, block entities, crafting).")
                .define("logServer", true);
        LOG_CLIENT = b.comment("Log client-side activity (screens, clicks, rendering).")
                .define("logClient", true);
        LOG_NETWORK = b.comment("Log packets sent and received, and network scans.")
                .define("logNetwork", true);
        LOG_CRAFTING = b.comment("Log recipe lookup and auto-crafting decisions in detail.")
                .define("logCrafting", true);
        LOG_BLOCKS = b.comment("Log block placement, removal and connection updates.")
                .define("logBlocks", false);
        LOG_GUI = b.comment("Log GUI open/close, clicks and filter changes.")
                .define("logGui", false);

        PROFILER_ENABLED = b
                .comment("Measure how long this mod's own operations take, and write the",
                         "breakdown to logs/latest.log (look for [Veloce][PROF]).",
                         "",
                         "A separate switch from debugEnabled on purpose: profiling is used",
                         "exactly when the pack is big and something is slow, and there the",
                         "ordinary debug log is drowned out by other mods. Nothing is written",
                         "unless this is on, and when it is off the measurements cost nothing.",
                         "",
                         "A session covers one thing the player did - opening the terminal,",
                         "requesting a page - and the report lists every measured operation",
                         "with its total time, share of the whole, call count and worst single",
                         "call. An operation that takes longer than 50 ms is also reported the",
                         "moment it finishes, because that is the case the player felt as a",
                         "freeze and a session that never completes would otherwise leave no",
                         "trace at all.")
                .define("profilerEnabled", false);

        b.pop();

        SPEC = b.build();
    }

    private VeloceConfig() {
    }

    /**
     * Whether to log events at the given level (or more important ones).
     *
     * <p>Reading the config is guarded: before it is loaded NeoForge
     * throws {@code IllegalStateException}. Logging must not bring the mod down
     * for that reason, so on any problem we return a safe default value
     * (we log errors only).
     */
    public static boolean allows(LogLevel level) {
        try {
            if (!DEBUG_ENABLED.get()) {
                // Without debugging we show errors only.
                return level == LogLevel.ERRORS;
            }
            return level.ordinal() <= DEBUG_LEVEL.get().ordinal();
        } catch (Throwable notLoadedYet) {
            return level == LogLevel.ERRORS;
        }
    }
}
