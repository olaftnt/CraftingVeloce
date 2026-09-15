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

    /**
     * Skrocona liczba sztuk: {@code 15}, {@code 1K}, {@code 1.2K}, {@code 1M},
     * {@code 1.2B}.
     *
     * <p><b>Zasady ustalone z graczem:</b>
     * <ul>
     *   <li>ponizej 1000 - dokladna liczba, bez czesci ulamkowej,</li>
     *   <li>1000 - {@code "1K"}, 1200 - {@code "1.2K"} (bez zbednego ".0"),</li>
     *   <li>999999 - {@code "1M"}, a nie {@code "1000K"} - zaokraglenie musi
     *       przeskoczyc prog,</li>
     *   <li>tak samo dla M -&gt; B.</li>
     * </ul>
     */
    public static String compact(long number) {
        if (number < 0) {
            return "0";
        }
        if (number < 1000) {
            return Long.toString(number);
        }
        // Progi sprawdzamy po ZAOKRAGLENIU, inaczej 999999 dawaloby "1000K".
        if (number < 999_500L) {
            return oneDecimal(number / 1000.0) + "K";
        }
        if (number < 999_500_000L) {
            return oneDecimal(number / 1_000_000.0) + "M";
        }
        return oneDecimal(number / 1_000_000_000.0) + "B";
    }

    /**
     * Tempo przeplywu do tooltipa: {@code 15}, {@code 15.5}, {@code .5},
     * {@code 1.2K}.
     *
     * <p><b>Zasady ustalone z graczem:</b> nie pokazujemy zbednego ".0"
     * ("15", nie "15.0"), a liczby mniejsze od jedynki nie maja wiodacego zera
     * (".5", nie "0.5"). Powyzej tysiaca skracamy tak samo jak liczniki
     * ({@link #compact}), bo inaczej dlugi rzad cyfr rozjezdzalby tooltip.
     *
     * <p>Wartosc MUSI byc nieujemna - znak dokłada wolajacy ({@code signed}),
     * zeby znak byl czescia napisu, a nie tylko kolorem.
     */
    public static String rate(float value) {
        // Progi po ZAOKRAGLENIU: 999.96 wyswietlone jako "1000" bez K rozjechaloby
        // sie z reszta (a 999_999 jako "1000K" to juz 1M).
        if (value >= 999_500_000f) {
            return oneDecimal(value / 1_000_000_000.0) + "B";
        }
        if (value >= 999_500f) {
            return oneDecimal(value / (double) MEGA) + "M";
        }
        if (value >= 999.5f) {
            return oneDecimal(value / (double) KILO) + "K";
        }
        if (value >= 1f) {
            return oneDecimal(value);
        }
        if (value < 0.005f) {
            return "0";   // zaokraglenie do dwoch miejsc i tak daje zero
        }
        // Ponizej jedynki: dwa miejsca (zeby ".05" nie zniknelo), bez
        // wiodacego zera i bez zer na koncu (".50" -> ".5").
        return leadingZeroLess(twoDecimals(value));
    }

    /** Jedno miejsce po przecinku, ale bez zbednego ".0" przy okraglych. */
    private static String oneDecimal(double value) {
        String s = String.format(Locale.ROOT, "%.1f", value);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String twoDecimals(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * Usuwa wiodace zero i zera na koncu: "0.50" -&gt; ".5", "0.05" -&gt; ".05".
     *
     * <p>Zera obcinamy WYLACZNIE w czesci ulamkowej - "0.00" musi dac "0",
     * a nie pusty napis.
     */
    private static String leadingZeroLess(String s) {
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) {
                s = s + "0";
            }
        }
        if (s.startsWith("0.")) {
            s = s.substring(1);
        }
        return s;
    }
}
