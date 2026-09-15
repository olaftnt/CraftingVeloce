package com.craftingveloce.util;

import com.craftingveloce.config.VeloceConfig;
import com.craftingveloce.config.VeloceConfig.LogLevel;
import org.slf4j.Logger;

/**
 * Centralny logger Veloce.
 *
 * <p>Kazda linia ma wspolny prefiks {@code [Veloce]} i krotki tag kategorii
 * w nawiasie, np. {@code [Veloce][CRAFT]} - mozna je filtrowac w logu.
 *
 * <p>Wzorzec komunikatow jest celowo jednakowy dla kazdej operacji, zeby dalo
 * sie je czytac jak przebieg zdarzen:
 * <pre>
 *   TRY    - co gracz/serwer probuje zrobic
 *   OK     - co sie udalo i co zostalo zwrocone
 *   FAIL   - co sie nie udalo
 *   WHY    - dlaczego (przyczyna, liczby, brakujace elementy)
 *   DETAIL - kroki posrednie
 * </pre>
 *
 * <p>Wszystkie komunikaty po angielsku: sluza do grepowania i wklejania
 * w zgloszeniach, a nie do czytania przez gracza.
 */
public final class VeloceLog {

    public enum Side {
        /** Log wywolany po stronie serwera (logika swiata). */
        SERVER,
        /** Log wywolany po stronie klienta (GUI, render). */
        CLIENT
    }

    private VeloceLog() {
    }

    // ------------------------------------------------------------------
    // Rdzen
    // ------------------------------------------------------------------

    private static void log(Logger log, Side side, LogLevel level, String tag,
                            String action, String message, Object... args) {
        logThrown(log, side, level, tag, action, null, message, args);
    }

    /**
     * Rdzen logowania, z opcjonalnym wyjatkiem.
     *
     * <p><b>Po co osobny wariant.</b> Wczesniej wyjatki w module lecialy przez
     * {@code t.printStackTrace()} - czyli na stderr, obok systemu logow, bez
     * poziomu i bez kategorii. Efekt: nie dalo sie ich wylaczyc configiem,
     * nie mialy prefiksu [Veloce] i nie bylo ich w pliku loga w tym samym
     * formacie co reszta. Podanie wyjatku do log4j wypisuje komunikat RAZEM
     * ze stosem, we wlasciwym miejscu.
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
     * Blad z wyjatkiem: komunikat plus STOS, w kategorii {@code tag}.
     *
     * <p>Uzywane tam, gdzie wczesniej bylo {@code printStackTrace()} - zeby
     * wyjatek trafil do loga moda, a nie obok niego.
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
            // Config moze nie byc jeszcze wczytany (wczesna faza startu).
            return false;
        }
    }

    private static Logger logger() {
        return com.craftingveloce.CraftingVeloceMod.LOGGER;
    }

    // ------------------------------------------------------------------
    // Wzorce dla typowych sytuacji
    // ------------------------------------------------------------------

    /** Co ktos probuje zrobic. */
    public static void attempt(Side side, String tag, String what, Object... args) {
        log(logger(), side, LogLevel.VERBOSE, tag, "TRY", what, args);
    }

    /** Co sie udalo i co zostalo zwrocone. */
    public static void success(Side side, String tag, String what, Object... args) {
        log(logger(), side, LogLevel.NORMAL, tag, "OK", what, args);
    }

    /** Co sie nie udalo. */
    public static void failure(Side side, String tag, String what, Object... args) {
        log(logger(), side, LogLevel.ERRORS, tag, "FAIL", what, args);
    }

    /** Dlaczego - przyczyny, liczby, brakujace elementy. */
    public static void why(Side side, String tag, String what, Object... args) {
        log(logger(), side, LogLevel.NORMAL, tag, "WHY", what, args);
    }

    /** Krok posredni. */
    public static void detail(Side side, String tag, String what, Object... args) {
        log(logger(), side, LogLevel.DETAIL, tag, "DETAIL", what, args);
    }

    /**
     * Czy szczegolowe logi w ogole pojda - tanie sprawdzenie przed budowaniem.
     *
     * <p>Po to, zeby wolajacy mogl pominac kosztowne zbieranie informacji,
     * ktore i tak trafilyby do kosza.
     */
    public static boolean isDetailEnabled(Side side) {
        try {
            return enabled(side) && VeloceConfig.allows(LogLevel.DETAIL);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Zmiana stanu (np. wlaczenie auto-craftingu). */
    public static void state(Side side, String tag, String what, Object... args) {
        log(logger(), side, LogLevel.VERBOSE, tag, "STATE", what, args);
    }

    // ------------------------------------------------------------------
    // Skroty dla czesto uzywanych kategorii
    // ------------------------------------------------------------------

    /** Siec rur: skanowanie, endpointy, ekstrakcja. */
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

        /** Blad z wyjatkiem - stos trafia do loga. */
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

    /** Auto-crafting: planowanie, wykonanie, wybor receptur. */
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

        /** Blad z wyjatkiem - stos trafia do loga. */
        public static void error(Side s, Throwable t, String what, Object... a) {
            if (checkCraft()) VeloceLog.error(s, TAG, t, what, a);
        }

        public static void why(Side s, String what, Object... a) {
            if (checkCraft()) VeloceLog.why(s, TAG, what, a);
        }

        public static void detail(Side s, String what, Object... a) {
            if (checkCraft()) VeloceLog.detail(s, TAG, what, a);
        }

        /** Czy szczegolowe logi tej kategorii w ogole pojda. */
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

    /** Bloki: stawianie, niszczenie, polaczenia. */
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

        /** Blad z wyjatkiem - stos trafia do loga. */
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

    /** GUI: otwieranie, klikniecia, filtry. */
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

        /** Blad z wyjatkiem - stos trafia do loga. */
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
