package com.craftingveloce.util;

import net.minecraft.core.BlockPos;

/**
 * Checking "is it time for periodic work yet" - resistant to the tick phase.
 *
 * <p><b>The problem this solves.</b> The mod had several places that did it
 * like this:
 *
 * <pre>
 *   if (level.getGameTime() % 20 == 0) { doSomething(); }
 * </pre>
 *
 * <p>That looks innocent, but it is wrong everywhere the caller does <b>not</b>
 * hit every tick. The concrete case that revealed it: {@code tickIdle} in the
 * cache is called only once per tick, and only by whichever terminal won the
 * race (the {@code claimTick} lock). If a terminal calls on ticks 1, 6, 11, 16,
 * 21..., it will <b>never</b> hit a multiple of 20 - and chunk keeping did not
 * happen EVEN ONCE. The symptom in game: "forced chunks: 0" despite a terminal
 * standing there, and without force-load the terminal and the crafter stop
 * working when the player walks away from the base.
 *
 * <p><b>The solution.</b> We measure the INTERVAL since the last execution
 * rather than equality with a multiple. It works regardless of which ticks the
 * caller hits.
 *
 * <p><b>Spreading.</b> The second problem with {@code % N == 0} is that ALL
 * blocks do their periodic work on the SAME tick - with several dozen blocks
 * that is one load spike per second instead of work spread evenly. That is why
 * we also offer a variant spread by block position: every block gets its own
 * phase, but its own interval is preserved.
 */
public final class VeloceTick {

    private VeloceTick() {
    }

    /**
     * Whether {@code interval} ticks have already passed since {@code lastRun}.
     *
     * <p>The stateless variant - the caller keeps the timestamp itself. Use it
     * when the class already has a field for the last tick.
     *
     * <p>Watch out for a world time that went backwards (loading an older save):
     * when {@code now < lastRun}, we assume time was "rewound" and let the work
     * run immediately, instead of waiting forever.
     */
    public static boolean every(long now, long lastRun, long interval) {
        if (interval <= 0) {
            return true;
        }
        if (lastRun == Long.MIN_VALUE) {
            return true;   // never ran - do it now
        }
        if (now < lastRun) {
            return true;   // time went backwards - do not block forever
        }
        return now - lastRun >= interval;
    }

    /**
     * Whether {@code interval} ticks have already passed - with spreading by
     * position.
     *
     * <p>Spread makes blocks not do their work on the same tick. The phase is
     * computed from the position, so it is stable between runs and the same for
     * the same block.
     *
     * <p>The interval is preserved: after doing the work a block waits a full
     * {@code interval} ticks, rather than "waiting for the next matching phase".
     */
    public static boolean everySpread(long now, long lastRun, long interval, BlockPos pos) {
        // A guard BEFORE `now % interval`: for an interval of 0 or less this is
        // normally a division by zero. `every()` has the same condition, and this
        // variant used to call modulo EARLIER, so it was less resistant than it.
        if (interval <= 0) {
            return true;
        }
        if (lastRun == Long.MIN_VALUE) {
            // First time: we spread the start over time, so that not all blocks
            // do their work on one tick.
            return now % interval == phase(pos, interval);
        }
        return every(now, lastRun, interval);
    }

    /**
     * The phase of this block in a cycle of length {@code interval}.
     *
     * <p>The position hash is mixed, so that neighbouring blocks do not land in
     * the same phase - otherwise a whole wall of pipes would do its work at once.
     */
    private static long phase(BlockPos pos, long interval) {
        int h = pos.getX() * 73856093 ^ pos.getY() * 19349663 ^ pos.getZ() * 83492791;
        // floorMod, not %, because the hash may be negative - and the phase must
        // be in range.
        //
        // THE CAST TO int MUST be checked: for an interval larger than
        // Integer.MAX_VALUE it would give 0, and then floorMod throws
        // ArithmeticException (division by zero). All current callers pass small
        // constants (20), but this method is on the hot path of the pipes - an
        // exception here would kill the server tick, not just one operation.
        if (interval <= 0 || interval > Integer.MAX_VALUE) {
            return 0L;
        }
        return Math.floorMod(h, (int) interval);
    }
}
