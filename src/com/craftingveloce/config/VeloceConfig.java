package com.craftingveloce.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Konfiguracja moda Veloce.
 *
 * <p>Plik: {@code config/craftingveloce-common.toml}. Zmiany dzialaja po
 * restarcie gry (NeoForge przelicza spec przy wczytaniu).
 *
 * <p>Sekcja DEBUG sluzy do diagnozowania problemow: wlacz {@code debugEnabled}
 * i ustaw {@code debugLevel} na DETAIL, zeby zobaczyc pelny przeplyw operacji -
 * co gracz probowal zrobic, co system zrobil, co dostal i dlaczego.
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

    /** Poziomy szczegolowosci - im wyzej, tym wiecej linii. */
    public enum LogLevel {
        /** Tylko bledy i operacje ktore sie nie udaly. */
        ERRORS,
        /** Bledy + skutki operacji (co sie stalo). */
        NORMAL,
        /** + co gracz probowal zrobic i co dostal. */
        VERBOSE,
        /** + kroki posrednie (planowanie, skanowanie sieci, pakiety). */
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

        b.pop();

        SPEC = b.build();
    }

    private VeloceConfig() {
    }

    /** Czy logowac zdarzenia o danym poziomie (lub wazniejsze). */
    /**
     * Czy logowac zdarzenia o danym poziomie (lub wazniejsze).
     *
     * <p>Odczyt configu jest zabezpieczony: przed jego wczytaniem NeoForge
     * rzuca {@code IllegalStateException}. Logowanie nie moze z tego powodu
     * wywalic moda, wiec w razie problemu zwracamy bezpieczna domyslna wartosc
     * (logujemy tylko bledy).
     */
    public static boolean allows(LogLevel level) {
        try {
            if (!DEBUG_ENABLED.get()) {
                // Bez debugowania pokazujemy tylko bledy.
                return level == LogLevel.ERRORS;
            }
            return level.ordinal() <= DEBUG_LEVEL.get().ordinal();
        } catch (Throwable notLoadedYet) {
            return level == LogLevel.ERRORS;
        }
    }
}
