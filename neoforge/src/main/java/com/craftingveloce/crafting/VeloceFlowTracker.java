package com.craftingveloce.crafting;

import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Measures how many units of each item ARRIVE and LEAVE the network per second.
 *
 * <p><b>Why this exists.</b> Storage state alone does not answer the question
 * "is this running away?". With a working factory the stock changes constantly,
 * and the player sees only one number and does not know whether it is a supply
 * that is growing or a line that is about to drain it. This tracker turns
 * successive snapshots into a RATE.
 *
 * <p><b>How often it samples.</b> Every {@link #SAMPLE_INTERVAL_TICKS} ticks
 * (5 s) we store one snapshot of the whole network's stock. From those
 * snapshots come TWO windows: a minute (13 samples every 5 s) and an hour
 * (61 samples every 60 s). The minute window says "what is happening NOW", the
 * hour window - "what is the long-term balance".
 *
 * <p><b>When the hourly window has data.</b> It does not wait an hour. The
 * anchor is the OLDEST remembered hourly sample (at startup the one from t=0,
 * after an hour the one from an hour ago), and the end of the window is the
 * CURRENT stock - which is why the number appears from the second snapshot
 * (5 s), and not from 60. An item that appeared in the network after the last
 * hourly sample anchors on its first minute sample, so it does not stay silent
 * for a minute. An earlier version required two hourly samples and showed
 * "collecting data" for the first minute; a player reported it as a bug ("the
 * data was not showing"), and he was right.
 *
 * <p><b>Net and gross.</b> The difference of the endpoints alone gives the NET,
 * which comes out as zero when the same items are inserted and extracted - and
 * the player sees "no change", even though the line is working. That is why we
 * also count the GROSS: how many units arrived and how many left, separately.
 * The net answers "is the supply growing", the gross - "how much flows through
 * it".
 *
 * <p><b>Why the ring has {@code intervals + 1} slots.</b> This is not an
 * oversight. To cover a full 60 s with samples every 5 s we need 13 samples -
 * because 13 points define 12 intervals. The version with 12 slots covered only
 * 55 s and silently understated every rate by 1/12.
 *
 * <p><b>The write counter is PER ITEM, not global.</b> That is not a detail
 * either: an item that has just appeared in the network has one sample, while
 * the tracker has hundreds behind it. A global counter would make it compute
 * the rate from one sample and the zeros filling the ring - that is, show a
 * number pulled out of thin air. Per item solves this honestly: fewer than two
 * samples means "I do not know" ({@code null}), not zero and not a guess.
 *
 * <p><b>Memory.</b> One {@code Item -> Series} map instead of four parallel
 * maps, and removal of items that disappeared from the network for a whole
 * window.
 */
public final class VeloceFlowTracker {

    /** How often we take a snapshot, in ticks. 100 ticks = 5 seconds. */
    public static final int SAMPLE_INTERVAL_TICKS = 100;

    /**
     * A break longer than this many ticks wipes the history.
     *
     * <p>The controller only ticks when its chunk is loaded. When the player
     * walks away there are no samples - and after returning, the ring still
     * holds the values from before the break. Without this reset the rate would
     * be computed from a difference that covers the time when nobody was
     * watching (and any process could have eaten part of that time), that is,
     * from a made-up number. We leave 20 s of tolerance for an ordinary server
     * hiccup.
     */
    private static final int GAP_RESET_TICKS = SAMPLE_INTERVAL_TICKS * 4;

    /** Seconds per sample in the minute window (100 ticks / 20). */
    private static final int SHORT_SECONDS = SAMPLE_INTERVAL_TICKS / 20;

    /** How many INTERVALS fit in the minute window (60 s / 5 s). */
    private static final int MINUTE_INTERVALS = 60 / SHORT_SECONDS;

    /** How many samples make up the minute window: intervals + 1 (see the class description). */
    private static final int SHORT_SLOTS = MINUTE_INTERVALS + 1;

    /** How many INTERVALS fit in the hour window (3600 s / 60 s). */
    private static final int HOUR_INTERVALS = 60;

    /** How many samples make up the hour window: intervals + 1. */
    private static final int HOUR_SLOTS = HOUR_INTERVALS + 1;

    /** Seconds per sample in the hour window. */
    private static final int HOUR_SECONDS = 60;

    /** Ticks per second - for converting a tick difference into seconds. */
    private static final float TICKS_PER_SECOND = 20f;

    /**
     * Minimum net share of the whole movement for a trend to count as
     * ONE-DIRECTIONAL.
     *
     * <p>0.8 = "at least 80% of the movement goes one way". Thanks to that an
     * item inserted and extracted alternately (net zero, gross large) is NOT
     * shown as a trend - the player does not want to see jitter, only
     * production.
     */
    private static final float STEADY_SHARE = 0.8f;

    /**
     * How many intervals had to move for this to be a TREND and not a one-off
     * jump.
     *
     * <p>Two, because a single insertion of an item into a chest is not a
     * "steady increase".
     */
    private static final int STEADY_MIN_STEPS = 2;

    /** A rate below this value counts as zero - we do not show noise. */
    private static final float EPSILON = 0.01f;

    /** History of one item: both rings and the counters of its own writes. */
    private static final class Series {
        final long[] minute = new long[SHORT_SLOTS];
        /** How many times THIS item entered the minute window. */
        int minuteWrites;
        final long[] hour = new long[HOUR_SLOTS];
        /** How many times THIS item entered the hour window. */
        int hourWrites;
    }

    private final Map<Item, Series> series = new HashMap<>();

    /**
     * Game tick of each sample - shared by all items.
     *
     * <p>We keep them SEPARATELY from the values, because the window time is
     * computed from real ticks and not from the number of samples: when the
     * server stalls or the chunk unloads, samples are missing and "13 samples"
     * no longer means 60 seconds.
     */
    private final long[] minuteTicks = new long[SHORT_SLOTS];
    private final long[] hourTicks = new long[HOUR_SLOTS];

    private int minuteCursor;
    private int hourCursor;
    /** How many snapshots have been taken in total (globally). */
    private int totalSamples;

    private long lastSampleTick = Long.MIN_VALUE;

    /** Whether the next snapshot is due. */
    public boolean due(long gameTime) {
        return lastSampleTick == Long.MIN_VALUE
                || gameTime - lastSampleTick >= SAMPLE_INTERVAL_TICKS;
    }

    /**
     * Stores a stock snapshot.
     *
     * <p>We iterate over the UNION: everything we already remember plus
     * everything that exists now. Thanks to that an item that has just
     * disappeared from the network gets a zero (instead of keeping its last
     * known value and pretending to be idle), and the ring cursors stay
     * synchronized for all items.
     */
    public void sample(long gameTime, Map<Item, Long> stock) {
        if (lastSampleTick != Long.MIN_VALUE
                && gameTime - lastSampleTick > GAP_RESET_TICKS) {
            reset();   // a break in ticking - the old history lies, we start over
        }
        lastSampleTick = gameTime;
        totalSamples++;

        // The hourly sample falls on the 1st, 13th, 25th snapshot - that is,
        // every MINUTE_INTERVALS short samples, counting from the first.
        boolean hourTick = (totalSamples - 1) % MINUTE_INTERVALS == 0;
        minuteTicks[minuteCursor] = gameTime;
        if (hourTick) {
            hourTicks[hourCursor] = gameTime;
        }

        Set<Item> items = new HashSet<>(series.keySet());
        items.addAll(stock.keySet());

        for (Item item : items) {
            Series s = series.computeIfAbsent(item, k -> new Series());
            long now = stock.getOrDefault(item, 0L);
            s.minute[minuteCursor] = now;
            s.minuteWrites++;
            if (hourTick) {
                s.hour[hourCursor] = now;
                s.hourWrites++;
            }
        }

        minuteCursor = (minuteCursor + 1) % SHORT_SLOTS;
        if (hourTick) {
            hourCursor = (hourCursor + 1) % HOUR_SLOTS;
            pruneEmpty();
        }
    }

    /**
     * Removes items that have zeros in the WHOLE of both windows.
     *
     * <p>Without this, a server where items circulate (appearing and
     * disappearing) would keep arrays for every item that ever passed through
     * it.
     */
    private void pruneEmpty() {
        series.values().removeIf(s -> allZero(s.minute) && allZero(s.hour));
    }

    private static boolean allZero(long[] arr) {
        for (long v : arr) {
            if (v != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Rates of items with a STEADY trend - in units per SECOND.
     *
     * <p>One number: the client converts it to a minute and an hour and shows
     * it in one line ("+2.0/min, +120/h"). An item that is idle or jitters does
     * NOT make it into the map - and then there is no line about it in the
     * tooltip.
     */
    public Map<Item, Float> steadyRates() {
        Map<Item, Float> out = new HashMap<>();
        for (Item item : series.keySet()) {
            float rate = steadyRate(item);
            if (!Float.isNaN(rate)) {
                out.put(item, rate);
            }
        }
        return out;
    }

    /** @return net (units/s) when the trend is one-directional, otherwise {@code NaN} */
    private float steadyRate(Item item) {
        Series s = series.get(item);
        if (s == null) {
            return Float.NaN;
        }
        // EARLY MODE: as long as the hour window has fewer than 3 samples (the
        // first ~2 minutes after the controller loads), we judge the trend on
        // the MINUTE window - samples every 5 s, so the line appears after
        // a dozen or so seconds instead of after two minutes. After that the
        // hour window takes over the assessment, as it is resistant to
        // momentary jitter.
        long now = currentValue(s);
        if (s.hourWrites < 3) {
            int valid = Math.min(s.minuteWrites, SHORT_SLOTS);
            return valid < 3 ? Float.NaN
                    : steadyRate(s.minute, minuteTicks, minuteCursor, valid, now);
        }
        int valid = Math.min(s.hourWrites, HOUR_SLOTS);
        return steadyRate(s.hour, hourTicks, hourCursor, valid, now);
    }

    /**
     * Trend from the ring of samples: the net rate or {@code NaN}.
     *
     * <p>The order matters for PERFORMANCE: first the cheap test (difference of
     * the extreme samples), and the walk over the samples only for items that
     * are moving - because it now serves only to split the movement into
     * directions. In a network with 100 thousand item types that is the
     * difference between "walk the list" and "walk the list and additionally 61
     * samples for every item".
     */
    private float steadyRate(long[] values, long[] ticks, int cursor, int valid, long now) {
        int oldestIdx = Math.floorMod(cursor - valid, values.length);
        float seconds = (lastSampleTick - ticks[oldestIdx]) / TICKS_PER_SECOND;
        if (seconds < 1f) {
            return Float.NaN;
        }
        long oldest = values[oldestIdx];

        // 1) CHEAP TEST: whether there is any net movement in the window at all.
        float net = (now - oldest) / seconds;
        if (Math.abs(net) < EPSILON) {
            return Float.NaN;
        }

        // 2) A walk over the samples - only for items that move.
        long prev = oldest;
        long gain = 0;
        long loss = 0;
        int up = 0;
        int down = 0;
        for (int i = 1; i < valid; i++) {
            long v = values[Math.floorMod(oldestIdx + i, values.length)];
            long d = v - prev;
            if (d > 0) {
                gain += d;
                up++;
            } else if (d < 0) {
                loss += -d;
                down++;
            }
            prev = v;
        }
        long step = now - prev;
        if (step > 0) {
            gain += step;
            up++;
        } else if (step < 0) {
            loss += -step;
            down++;
        }

        if ((net > 0 ? up : down) < STEADY_MIN_STEPS) {
            return Float.NaN;   // a one-off jump is not a trend
        }
        float gross = (gain + loss) / seconds;
        if (gross <= 0f || Math.abs(net) < STEADY_SHARE * gross) {
            return Float.NaN;   // too much movement the other way - this is not a trend
        }
        return net;
    }

    /** Current stock of an item: the last stored minute sample. */
    private long currentValue(Series s) {
        return s.minute[Math.floorMod(minuteCursor - 1, SHORT_SLOTS)];
    }

    /** Everything to the bin - e.g. when the controller was moved to a different network. */
    public void reset() {
        series.clear();
        java.util.Arrays.fill(minuteTicks, 0L);
        java.util.Arrays.fill(hourTicks, 0L);
        minuteCursor = 0;
        hourCursor = 0;
        totalSamples = 0;
        lastSampleTick = Long.MIN_VALUE;
    }
}
