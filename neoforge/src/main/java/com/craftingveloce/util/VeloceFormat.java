package com.craftingveloce.util;

import java.util.Locale;

/**
 * Number formatting for the GUI - one place for the whole mod.
 *
 * <p><b>Why a separate class.</b> Energy in the GUI used to be shown raw:
 * "7807000 / 25000000 FE". A player does not count zeros on the fly - mods
 * adopt the kFE / MFE units, exactly like K/M for item counts
 * ({@link com.craftingveloce.client.gui.VeloceSlotOverlay#formatCount}).
 *
 * <p>Since the same units are needed both in the furnace screen and (in the
 * future) in the controller, we keep them here - and not in two copies that
 * always end up drifting apart.
 */
public final class VeloceFormat {

    private VeloceFormat() {
    }

    /** The threshold from which we switch to kFE. */
    private static final long KILO = 1_000L;

    /** The threshold from which we switch to MFE. */
    private static final long MEGA = 1_000_000L;

    /**
     * Energy in a readable form: "512 FE", "200 kFE", "7.8 MFE", "25 MFE".
     *
     * <p>Without a superfluous ".0" on round values - "25 MFE", not
     * "25.00 MFE". We trim trailing zeros ONLY in the fractional part, so that
     * "100.00" does not turn into "1".
     */
    public static String feCompact(long fe) {
        if (fe >= MEGA) {
            return trim(fe / (double) MEGA) + " MFE";
        }
        if (fe >= KILO) {
            double kilo = fe / (double) KILO;
            // "999 999 FE" rounded to two places would give "1000 kFE",
            // and that is already 1 MFE - we promote it, so as not to show 1000 kFE.
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
     * Abbreviated item count: {@code 15}, {@code 1K}, {@code 1.2K}, {@code 1M},
     * {@code 1.2B}.
     *
     * <p><b>Rules agreed with the player:</b>
     * <ul>
     *   <li>below 1000 - the exact number, no fractional part,</li>
     *   <li>1000 - {@code "1K"}, 1200 - {@code "1.2K"} (no superfluous ".0"),</li>
     *   <li>999999 - {@code "1M"}, not {@code "1000K"} - rounding must
     *       jump the threshold,</li>
     *   <li>the same for M -&gt; B.</li>
     * </ul>
     */
    public static String compact(long number) {
        if (number < 0) {
            return "0";
        }
        if (number < 1000) {
            return Long.toString(number);
        }
        // We check the thresholds AFTER ROUNDING, otherwise 999999 would give "1000K".
        if (number < 999_500L) {
            return oneDecimal(number / 1000.0) + "K";
        }
        if (number < 999_500_000L) {
            return oneDecimal(number / 1_000_000.0) + "M";
        }
        return oneDecimal(number / 1_000_000_000.0) + "B";
    }

    /**
     * Flow rate for the tooltip: {@code 15}, {@code 15.5}, {@code .5},
     * {@code 1.2K}.
     *
     * <p><b>Rules agreed with the player:</b> we do not show a superfluous ".0"
     * ("15", not "15.0"), and numbers smaller than one have no leading zero
     * (".5", not "0.5"). Above a thousand we abbreviate the same way as counters
     * ({@link #compact}), because otherwise a long row of digits would break the tooltip.
     *
     * <p>The value MUST be non-negative - the caller adds the sign ({@code signed}),
     * so that the sign is part of the text, not just of the colour.
     */
    public static String rate(float value) {
        // Thresholds AFTER ROUNDING: 999.96 displayed as "1000" without K would
        // drift from the rest (and 999_999 as "1000K" is already 1M).
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
            return "0";   // rounding to two places gives zero anyway
        }
        // Below one: two places (so that ".05" does not disappear), without a
        // leading zero and without trailing zeros (".50" -> ".5").
        return leadingZeroLess(twoDecimals(value));
    }

    /** One decimal place, but without a superfluous ".0" on round values. */
    private static String oneDecimal(double value) {
        String s = String.format(Locale.ROOT, "%.1f", value);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String twoDecimals(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * Removes the leading zero and trailing zeros: "0.50" -&gt; ".5", "0.05" -&gt; ".05".
     *
     * <p>We trim zeros ONLY in the fractional part - "0.00" must give "0",
     * not an empty string.
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
