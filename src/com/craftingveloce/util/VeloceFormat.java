package com.craftingveloce.util;

import java.util.Locale;

/**
 * Formatowanie liczb do GUI - jedno miejsce na caly mod.
 *
 * <p><b>Po co osobna klasa.</b> Energia w GUI byla pokazywana surowa:
 * "7807000 / 25000000 FE". Gracz nie liczy zer w locie - w modach przyjmuje
 * sie jednostki kFE / MFE, dokladnie tak jak K/M przy liczbach sztuk
 * ({@link com.craftingveloce.client.gui.VeloceSlotOverlay#formatCount}).
 *
 * <p>Skoro te same jednostki sa potrzebne i w ekranie pieca, i (w przyszlosci)
 * w kontrolerze, trzymamy je tutaj - a nie w dwoch kopiach, ktore zawsze
 * w koncu sie rozjada.
 */
public final class VeloceFormat {

    private VeloceFormat() {
    }

    /** Prog, od ktorego przechodzimy na kFE. */
    private static final long KILO = 1_000L;

    /** Prog, od ktorego przechodzimy na MFE. */
    private static final long MEGA = 1_000_000L;

    /**
     * Energia czytelnie: "512 FE", "200 kFE", "7.8 MFE", "25 MFE".
     *
     * <p>Bez zbednego ".0" przy okraglych wartosciach - "25 MFE", nie
     * "25.00 MFE". Zera na koncu obcinamy WYLACZNIE w czesci ulamkowej, zeby
     * "100.00" nie zamienilo sie w "1".
     */
    public static String feCompact(long fe) {
        if (fe >= MEGA) {
            return trim(fe / (double) MEGA) + " MFE";
        }
        if (fe >= KILO) {
            double kilo = fe / (double) KILO;
            // "999 999 FE" zaokraglone do dwoch miejsc dawaloby "1000 kFE",
            // a to juz jest 1 MFE - promujemy, zeby nie pokazywac 1000 kFE.
            if (Math.round(kilo * 100.0) >= 100_000L) {
                return trim(fe / (double) MEGA) + " MFE";
            }
            return trim(kilo) + " kFE";
        }
        return fe + " FE";
    }

    private static String trim(double value) {
        String s = String.format(Locale.ROOT, "%.2f", value);
        if (!s.contains(".")) {
            return s;
        }
        // "200.00" -> "200.", "7.80" -> "7.8", "100.00" -> "100."
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
