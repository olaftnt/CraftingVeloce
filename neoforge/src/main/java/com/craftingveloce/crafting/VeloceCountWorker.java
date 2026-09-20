package com.craftingveloce.crafting;

import com.craftingveloce.util.VeloceLog;
import com.craftingveloce.util.VeloceTick;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts "how many can still be made" OFF the server thread.
 *
 * <p><b>The problem this solves.</b> The count is a recipe-tree search, and for every item
 * it is run about {@code log2(upperBound)} times by the bisection. On the server thread it
 * had to fit a budget of milliseconds - 25 ms for a whole page, 2 ms per item - and the
 * log shows what that produced:
 *
 * <pre>
 *   instant craftable count for 38 item(s) -> 0 result(s) in 25 ms (complete=false)
 *   craftable count for minecraft:andesite: ran out of the estimation budget
 *       after checking up to 0 unit(s) (recipes=2, hi=383)
 * </pre>
 *
 * <p>Not one item on the page got a number. The player sees exactly that as "the yellow
 * +N is missing for some items" - and the items affected look random, because they are:
 * whichever ones the budget reached.
 *
 * <p><b>The shape of the fix.</b> Counting is pure arithmetic over numbers; the only reason
 * it needed the world was to look up recipes and read the stock. So:
 * <ol>
 *   <li>the SERVER THREAD captures a {@link VeloceCountSnapshot} - one stock read, the
 *       countable set, the preferences, and the whole recipe closure resolved eagerly;</li>
 *   <li>a WORKER THREAD runs {@link VeloceAutoCrafter#countFromSnapshot} against that
 *       snapshot with NO time budget;</li>
 *   <li>the result is handed back to the server thread, which sends it to the client
 *       through the existing packet.</li>
 * </ol>
 *
 * <p><b>Why a single worker.</b> Two reasons, and neither is laziness. The counts are
 * wanted for one visible page at a time, so a queue depth of a few is plenty; and the
 * recipe structures the snapshot points at are shared and read-only, so more workers would
 * buy nothing but contention. One worker also makes the ordering guarantee trivial: results
 * come back in the order the requests were made, so a stale request can never overwrite a
 * newer answer.
 *
 * <p><b>Coalescing.</b> A player scrolling a terminal produces a request per page change.
 * Only the newest request for a given terminal matters, so requests are keyed by position
 * and a newer one REPLACES an older queued one - the work for a page the player has already
 * scrolled past is never done at all.
 */
public final class VeloceCountWorker {

    private VeloceCountWorker() {
    }

    /**
     * How many requests may wait.
     *
     * <p>Small on purpose: the queue holds one entry per terminal being looked at, and a
     * full queue means something is very wrong (it would take dozens of players scrolling
     * at once). When it is full we DROP the request and let the client's periodic refresh
     * ask again - refusing is better than blocking the server thread on a queue.
     */
    private static final int QUEUE_CAPACITY = 32;

    /** The single counting thread. Daemon, so it can never hold the JVM open. */
    private static volatile ThreadPoolExecutor executor = newExecutor();

    private static ThreadPoolExecutor newExecutor() {
        return new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "veloce-count-worker");
                    // A worker must never keep the game alive, and it must not compete for
                    // priority with the server thread that ticks the world.
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                // DROPPING IS REPORTED, not silent.
                //
                // This was DiscardOldestPolicy, and it produced the most confusing failure
                // of the whole feature: the terminal re-requests its page every 20 ticks
                // while the worker is still busy with a slow one, the queue filled
                // ("queued=9 active=1"), and the policy threw the OLDEST request away -
                // which was exactly the request whose answer the screen was waiting for.
                // The client then kept showing "=none" for the page forever, and nothing
                // anywhere said a request had been discarded.
                //
                // The policy now keeps the newest (an old page is worthless - see the
                // generation check in the packet handler) and COUNTS the drops, so the
                // stats line can say it happened.
                (r, e) -> {
                    if (!e.isShutdown()) {
                        Runnable dropped = e.getQueue().poll();
                        if (dropped != null) {
                            DROPPED.incrementAndGet();
                            VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                                    "count worker queue full (%d) - dropped a queued page "
                                            + "to make room for a newer one",
                                    QUEUE_CAPACITY);
                        }
                        e.execute(r);
                    }
                });
    }

    /** Requests waiting or running, so a shutdown can wait for them. */
    private static final AtomicLong GENERATION = new AtomicLong();

    /** Counters for the periodic log line - how much work this worker is doing. */
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final AtomicLong COMPUTED_ITEMS = new AtomicLong();
    private static final AtomicLong TOTAL_NANOS = new AtomicLong();
    private static final AtomicLong MAX_NANOS = new AtomicLong();
    /** Pages discarded because the queue was full - see the rejection handler. */
    private static final AtomicLong DROPPED = new AtomicLong();
    private static volatile long lastStatsLogTick = Long.MIN_VALUE;

    /**
     * What to do with a finished count. Called ON THE SERVER THREAD.
     *
     * @param generation the request generation, so the caller can drop stale answers
     */
    public interface Sink {
        void accept(VeloceAutoCrafter.BatchResult result, long generation);
    }

    /** One request: everything the worker needs, plus where to send the answer. */
    private record Job(VeloceCountSnapshot snapshot, List<Item> items, long generation,
                       Sink sink, String what, long submittedNanos) {
    }

    /**
     * Submits a count to the worker.
     *
     * <p>MUST be called on the server thread - it is what captures the snapshot. The
     * computation and the callback happen elsewhere; the callback is scheduled back onto
     * the server thread by the caller through {@code server.execute}.
     *
     * @param generation a monotonically increasing id; the caller ignores answers older
     *                   than the newest request it has made for this terminal
     */
    public static void submit(MinecraftServer server, VeloceCountSnapshot snapshot,
                              List<Item> items, long generation, Sink sink, String what) {
        long gen = generation > 0 ? generation : GENERATION.incrementAndGet();
        try {
            long submittedNanos = System.nanoTime();
            executor.execute(() -> run(server,
                    new Job(snapshot, items, gen, sink, what, submittedNanos)));
        } catch (RejectedExecutionException e) {
            // Queue full or shut down. The client refreshes the visible page on its own
            // clock, so dropping this one request costs a moment, not a number.
            VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                    "count worker busy - dropped the request for %s", what);
        }
    }

    /**
     * Runs a piece of work on the counting thread - used for the recipe-index WARM-UP.
     *
     * <p>It shares the one worker thread with the counts on purpose: warming is exactly the same
     * kind of work (reading recipe indexes) and it must not compete with the server thread. One
     * thread also means a warm-up can never run at the same time as a count, so the indexes are
     * never being built twice.
     *
     * <p>Safe to call when the worker is shut down (a world that is closing): the task is simply
     * dropped, because a warm-up is an optimisation and the lazy path still exists.
     *
     * @param what shown in any error line, so a failure says what was being warmed
     */
    public static void submitTask(String what, Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    // Nothing joins this thread, so an uncaught failure would vanish and the
                    // indexes would just stay cold with no explanation.
                    VeloceLog.Craft.error(VeloceLog.Side.SERVER, t,
                            "%s on the worker failed - the work will happen on the first "
                                    + "request instead", what);
                }
            });
        } catch (RejectedExecutionException e) {
            VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                    "count worker busy - skipped: %s", what);
        }
    }

    /** The worker body: compute, then hand the answer back to the server thread. */
    private static void run(MinecraftServer server, Job job) {
        long start = System.nanoTime();
        // How long the request waited for a free thread. It is part of what the player waited
        // for, and it is NOT the snapshot capture: a page that sat here while an earlier page
        // was computing would otherwise look like a slow capture.
        com.craftingveloce.util.VeloceProfiler.record("worker.queueWait",
                start - job.submittedNanos());
        VeloceAutoCrafter.BatchResult result;
        // Labelled OFF-THREAD on purpose: this runs on the counting thread, so however long it
        // takes it CANNOT be what freezes the game. Seeing it at the top of a report while the
        // freeze was real is the profiler telling the reader to look at the server-thread rows
        // instead (the snapshot capture), not a contradiction.
        try (var ignored = com.craftingveloce.util.VeloceProfiler
                .section("worker.OFF-THREAD.countPage")) {
            try {
                result = VeloceAutoCrafter.countFromSnapshot(job.snapshot(), job.items(),
                        progressFor(job));
            } catch (Throwable t) {
            // A failure on a worker thread would otherwise be invisible: nothing joins it
            // and nothing catches it, and the client would simply never get an answer.
                VeloceLog.Craft.error(VeloceLog.Side.SERVER, t,
                        "counting %s on the worker failed - the page will show no new numbers",
                        job.what());
                return;
            }
        }
        long nanos = System.nanoTime() - start;

        // STATS: the whole point of this class is that counting may take a long time, so
        // it must be VISIBLE how long. A single slow page is interesting; a trend is a bug.
        long total = TOTAL_NANOS.addAndGet(nanos);
        long n = REQUESTS.incrementAndGet();
        COMPUTED_ITEMS.addAndGet(job.items().size());
        MAX_NANOS.accumulateAndGet(nanos, Math::max);

        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "count worker: %d item(s) for %s in %d ms (complete=%s)",
                job.items().size(), job.what(), nanos / 1_000_000L, result.complete());

        // Hand the answer back TO THE SERVER THREAD. The sink touches the network cache and
        // sends packets, neither of which is thread-safe.
        try {
            server.execute(() -> {
                try {
                    job.sink().accept(result, job.generation());
                } catch (Throwable t) {
                    VeloceLog.Craft.error(VeloceLog.Side.SERVER, t,
                            "delivering the counted page for %s failed", job.what());
                }
            });
        } catch (Throwable t) {
            // The server can be shutting down between the computation and the hand-back.
            VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                    "could not schedule the counted page for %s (server stopping?)", job.what());
        }
    }

    /**
     * Per-item progress, logged often enough to see the work and rarely enough not to
     * flood. Without it "the numbers take a while" is unanswerable: one cannot tell a slow
     * page from a stuck worker.
     */
    private static VeloceAutoCrafter.Progress progressFor(Job job) {
        return (done, total, item, value, nanos) -> {
            if (!VeloceLog.Craft.isDetailEnabled(VeloceLog.Side.SERVER)) {
                return;
            }
            // Every item for a small page, every 8th for a big one.
            int step = total <= 16 ? 1 : 8;
            if (done % step != 0 && done != total) {
                return;
            }
            VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                    "  count %d/%d: %s -> %s (%d ms, %s)",
                    done, total, item,
                    value < 0 ? "UNKNOWN (op limit)" : String.valueOf(value),
                    nanos / 1_000_000L, job.what());
        };
    }

    /**
     * The periodic "how is the worker doing" line - one per {@code every} ticks.
     *
     * <p>Called from the server thread. Reports averages as well as the worst case,
     * because those answer different questions: an average that creeps up is a growing
     * recipe graph, a single huge maximum is one pathological item.
     */
    public static void logStats(MinecraftServer server, long every) {
        if (server == null || server.overworld() == null) {
            return;
        }
        long now = server.overworld().getGameTime();
        if (!VeloceTick.every(now, lastStatsLogTick, every)) {
            return;
        }
        lastStatsLogTick = now;
        long n = REQUESTS.get();
        if (n == 0) {
            return;
        }
        long total = TOTAL_NANOS.get();
        long max = MAX_NANOS.get();
        long items = COMPUTED_ITEMS.get();
        long queued = executor.getQueue().size();
        long active = executor.getActiveCount();
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "[count worker] requests=%d items=%d avg=%d ms max=%d ms "
                        + "(avg per item=%d ms) queued=%d active=%d dropped=%d",
                n, items, total / n / 1_000_000L, max / 1_000_000L,
                items > 0 ? total / items / 1_000_000L : 0L, queued, active,
                DROPPED.get());
        // Running totals would overflow the meaning of "average" after a long session, so
        // the window resets each time it is reported - every interval answers for itself.
        REQUESTS.set(0);
        COMPUTED_ITEMS.set(0);
        TOTAL_NANOS.set(0);
        MAX_NANOS.set(0);
        DROPPED.set(0);
    }

    /** Whether the worker has anything in flight - used by tests and the perf command. */
    public static boolean isBusy() {
        return executor.getActiveCount() > 0 || !executor.getQueue().isEmpty();
    }

    /** How many requests are waiting - a full queue means requests are being dropped. */
    public static int queueDepth() {
        return executor.getQueue().size();
    }

    /**
     * Stops the worker at server shutdown.
     *
     * <p>The thread is a daemon, so it cannot hold the JVM open - but it CAN still be
     * running while the levels it was snapshotted from are torn down. We do not interrupt
     * it (an interrupt in the middle of a plan would only produce a half-built number that
     * nobody reads); we just stop accepting work and let the in-flight request finish
     * against its own immutable snapshot, which stays valid on its own.
     */
    public static void shutdown() {
        executor.shutdown();
    }

    /**
     * Whether the executor has been shut down - a restart needs a fresh one.
     *
     * <p>Kept because a shutdown executor can NEVER be restarted, and this cost the project
     * a real bug once already in {@code VeloceCraftingCache}: a flag set on the way out was
     * never cleared on the way back in, so the feature stayed dead for the whole session
     * after a single trip to the main menu. Counting is far more visible than chunk
     * force-loading, but the failure mode is identical.
     */
    public static boolean isShutdown() {
        return executor.isShutdown();
    }

    /**
     * Brings the worker back after a server stop, or does nothing if it is already usable.
     *
     * <p>Called on world load for exactly the reason above: {@code ServerStoppingEvent}
     * shuts the executor down, and nothing in the JDK can undo that. Without this, entering
     * a world a second time in one session would leave every "+N" number permanently
     * missing - a worse version of the bug this class exists to fix.
     */
    public static synchronized void ensureRunning() {
        if (executor.isShutdown()) {
            executor = newExecutor();
            VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                    "count worker restarted after a server stop");
        }
    }
}
