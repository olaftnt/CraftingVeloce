package com.craftingveloce.util;

import com.craftingveloce.config.VeloceConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures how long THIS MOD's own operations take, and writes a breakdown to the log.
 *
 * <p><b>The problem it exists for.</b> In a large modpack, opening the terminal freezes the
 * game for a noticeable moment, and nothing anywhere said which part of the mod was
 * responsible. The existing diagnostics say WHAT happened ("count snapshot: 45 item(s)"),
 * never how long any of it took, so finding the cost meant guessing - and guessing produced a
 * wrong answer more than once in this project.
 *
 * <p><b>Independent of the debug switch, on purpose.</b> {@code debugEnabled} gates the
 * ordinary diagnostics, and turning it on in a 339-mod pack floods the log with other mods'
 * output as well. A profiler is used exactly when the pack is big and something is slow, so it
 * gets its OWN switch ({@link VeloceConfig#PROFILER_ENABLED}) and writes through the plain
 * logger. That way the numbers appear in {@code latest.log} next to everything else, without
 * enabling anything that would bury them.
 *
 * <p><b>What a "session" is.</b> A session is opened, the measured operations accumulate, and
 * {@link #report} prints the breakdown and clears again. One session therefore describes ONE
 * thing the player did - the useful unit when the question is "what happened when I opened the
 * terminal", rather than a running average that hides the spike. The terminal opening is opened
 * and closed <b>by the client</b> ({@link #beginClientSession()}), because the freeze the player
 * feels begins when the screen is built, before the count request ever reaches the server.
 *
 * <p><b>Threads.</b> Sections are recorded from the server thread, the counting worker and the
 * client thread, and the map is concurrent so none of them can corrupt another. In singleplayer
 * that means ONE report can contain all three at once, which is the whole point: the report of a
 * terminal opening shows the client rows, the server's snapshot capture and the worker's
 * arithmetic in one place, sorted by cost. On a dedicated server the two sides are separate JVMs
 * and each reports its own half. Every label says which thread it belongs to, and the worker's
 * are prefixed {@code OFF-THREAD} because work there cannot be what freezes the game.
 *
 * <p><b>Nesting.</b> A measured block may contain other measured blocks (the snapshot capture
 * contains the closure, which contains the recipe lookup), so child totals are also counted in
 * their parent. The labels are written with dots to make the nesting readable
 * ({@code snapshot.closure} / {@code snapshot.closure.resolveRecipes}), and the share column is
 * "share of the sum of all rows", not "share of the whole request" - two nested rows together
 * therefore add up to more than 100% by design.
 *
 * <p><b>How to read the report.</b> Rows are sorted by total time, worst first, and each row is:
 *
 * <pre>
 *   [Veloce][PROF]   client.applyItemFilter.scan   620 ms  31.4%    20 call(s) worst  41 ms  50000 unit(s)  12 us/unit
 * </pre>
 *
 * <ul>
 *   <li><b>ms</b> - the total across the session; <b>%</b> - its share of the sum of all rows.</li>
 *   <li><b>call(s)</b> - how often it ran. This is what separates the two kinds of problem:
 *       a one-off cost on opening a screen runs once, while a stall that eats the frame rate
 *       runs twenty times a second. Neither number alone says which it is.</li>
 *   <li><b>worst</b> - the slowest single call. An operation that is cheap on average and
 *       occasionally scans a registry only shows up here.</li>
 *   <li><b>unit(s) / us per unit</b> - how much WORK it did, for the labels that count it
 *       (items scanned, endpoints walked). This is the number that travels between packs: "62
 *       thousand items, twelve times a second" is comparable, "30 ms" on its own is not.</li>
 * </ul>
 *
 * <p><b>Label families.</b> The prefix says where the code runs, which is the first thing to
 * check, because only two of the three can freeze the game:
 * {@code client.*} - client thread; {@code server.*} and {@code snapshot.*} - server thread;
 * {@code worker.OFF-THREAD.*} - the counting thread, measured for completeness only.
 *
 * <p><b>{@code load.*} is the exception.</b> Those labels belong to the game's LOADING phase,
 * which is a different question with a different answer: they are measured even when the switch
 * is off (the config is not readable during mod construction, see
 * {@link #beginStartupSession()}) and they produce two reports of their own, at
 * {@code FMLLoadCompleteEvent} and at world start. A load report says how much of the wait is
 * OURS - it cannot say why a 300-mod pack takes minutes, because that time belongs to other
 * mods, and the profiler only ever measures this mod's own functions.
 *
 * <p><b>Cost when off.</b> {@link #section} returns a shared no-op scope, so the disabled path
 * allocates nothing and reads one config value - a profiler that costs something when nobody
 * asked for it would be its own bug.
 */
public final class VeloceProfiler {

    private VeloceProfiler() {
    }

    /** One measured label: how many times it ran, total and worst time, and how much work. */
    private static final class Entry {
        private final AtomicLong totalNanos = new AtomicLong();
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong worstNanos = new AtomicLong();
        /** Units of work - items scanned, ingredients walked. 0 = "not applicable". */
        private final AtomicLong units = new AtomicLong();

        void add(long nanos) {
            totalNanos.addAndGet(nanos);
            calls.incrementAndGet();
            worstNanos.accumulateAndGet(nanos, Math::max);
        }
    }

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    /** Start of the current session (System.nanoTime), or 0 when none is open. */
    private static final AtomicLong SESSION_START = new AtomicLong(0L);

    /**
     * Total time spent in TOP-LEVEL sections only.
     *
     * <p>Needed because sections nest: {@code snapshot.closure} contains
     * {@code snapshot.closure.resolveRecipes}, so adding up every row counts the same
     * millisecond several times. The first real run made that visible - "wall clock 684 ms (sum
     * of sections 2582 ms)" reads like a contradiction. This counter answers it: the sum that is
     * comparable with the wall clock is the top-level one, and the rest is the nesting.
     */
    private static final AtomicLong ROOT_NANOS = new AtomicLong();

    /**
     * Per-thread {@code [depth, startNanosOfTheOutermostSection]} for {@link #ROOT_NANOS}.
     *
     * <p>Reused array rather than a fresh object per section, because this runs on the hot paths
     * that are being measured.
     */
    private static final ThreadLocal<long[]> DEPTH = ThreadLocal.withInitial(() -> new long[2]);

    /**
     * Who owns the open session, i.e. who is allowed to close it with a report.
     *
     * <p><b>Why this is needed.</b> The client and the server both have work worth reporting,
     * and in singleplayer they share one JVM and one set of counters - so BOTH would print a
     * report for the same click, and the server's would arrive before the client had even
     * drawn the numbers. The owner decides which of them reports:
     *
     * <ul>
     *   <li>{@link #beginInteractionSession()} - the terminal being right-clicked, on the server
     *       thread. It opens the session and clears it, so the window starts at the click, and
     *       the server's own work in that same moment (the network scan and the counts sync)
     *       is inside it.</li>
     *   <li>{@link #beginClientSession()} - the terminal screen being built. It TAKES OVER the
     *       session the click opened without clearing it, and then owns it: the client reports,
     *       when the numbers arrive, and that one report also contains the server and worker
     *       rows - they are the same JVM in singleplayer. On a dedicated server there is no
     *       click session (the client's own JVM never sees the server's block interaction), so
     *       the client's session starts here and reports its half alone.</li>
     *   <li>{@link #beginRequestSession()} - the count request arriving on the server with
     *       nothing already open (a dedicated server, a request from a test, a screen opened
     *       without a click). The server owns that one and reports it.</li>
     * </ul>
     */
    private enum Owner { NONE, CLIENT, SERVER }

    private static volatile Owner sessionOwner = Owner.NONE;

    /**
     * Whether the measurements being taken belong to the game's LOADING phase.
     *
     * <p><b>Why load-time sections ignore the config switch.</b> The switch lives in the common
     * config, and the common config is not readable yet while the mod is being constructed -
     * {@code enabled()} answers "off" for that whole phase, so a switch-gated section would
     * measure nothing at exactly the moment the player is waiting. The load-time sections are
     * therefore always recorded (there are a few dozen of them in total, so the cost is
     * nothing) and it is the REPORT that is gated: no switch, no output, and the buffer is
     * dropped.
     */
    private static volatile boolean startupRecording;

    /** A single measurement that took longer than this is called out the moment it ends. */
    private static final long SLOW_CALL_NANOS = 50_000_000L;   // 50 ms

    /**
     * A session shorter than this prints NOTHING automatically.
     *
     * <p><b>The bug this fixes, measured.</b> The breakdown used to be printed for every page the
     * terminal asked for - on BOTH sides, so two reports per request, 36 lines each. With the
     * terminal open that was <b>67-88 log lines per second</b> (about 5 MB per minute), written
     * synchronously, and typing a letter in the search bar asks for a new page and therefore
     * produced another burst. The player feels that as lag on every keypress - and it was
     * entirely this diagnostic, which is supposed to be invisible until something is wrong.
     *
     * <p>A page that took 12-30 ms has nothing to explain, so it now says nothing. A page that
     * took hundreds of milliseconds still prints its breakdown by itself, which is exactly when
     * it is wanted. {@code /cv profile report} prints on demand regardless of the timing.
     */
    private static final long REPORT_MIN_NANOS = 150_000_000L;   // 150 ms

    /**
     * How many labels a report prints, worst first.
     *
     * <p>LEAVE ROOM for the reader: a report is read while looking for one or two rows, and the
     * old limit of 96 printed the whole table on every page. 28 keeps the worst offenders -
     * which is what a report is for - and a session that needs the rest can be read with
     * {@code /cv profile report} after the fact.
     */
    private static final int REPORT_LIMIT = 28;

    /**
     * The switch, cached.
     *
     * <p>Cached because {@link #enabled()} sits on hot paths: per frame, per tick, and - for the
     * item-filter labels - per ITEM, tens of thousands of times per tick. Asking the config
     * machinery there would make the profiler part of what it is measuring. A FAILED read is
     * never cached: the first calls can happen before the config is loaded, and caching "off"
     * at that point would silently disable the profiler for the whole session.
     */
    private static volatile Boolean enabledCache;

    /** Whether profiling is on. Never throws - an unloaded config means "off". */
    public static boolean enabled() {
        Boolean cached = enabledCache;
        if (cached != null) {
            return cached;
        }
        try {
            boolean value = VeloceConfig.PROFILER_ENABLED.get();
            enabledCache = value;
            return value;
        } catch (Throwable notLoadedYet) {
            return false;   // deliberately NOT cached - see above
        }
    }

    /**
     * Drops the cached switch so the next call reads the config again.
     *
     * <p>Called when the value changes from inside the game ({@code /cv profile}) and when the
     * config is (re)loaded - a player who edits the file and reloads must not keep the old
     * setting because of a cache.
     */
    public static void refreshEnabled() {
        enabledCache = null;
    }

    /**
     * Clears the counters and starts the clock for a new session.
     *
     * @return false when profiling is off, so a caller can skip the setup around it
     */
    public static boolean beginSession() {
        if (!enabled()) {
            return false;
        }
        ENTRIES.clear();
        ROOT_NANOS.set(0L);
        sessionOwner = Owner.NONE;
        SESSION_START.set(System.nanoTime());
        return true;
    }

    /**
     * Opens the session for "the player opened the terminal", <b>on the client thread</b>.
     *
     * <p>This is the right place for the session to begin, and the reason is the whole bug the
     * profiler exists for: the freeze the player feels starts when the screen is built, which
     * happens BEFORE the count request reaches the server. A session opened by the server would
     * therefore miss the client-side half - measured, and it did: the client rows were wiped by
     * the server opening the session, so a report could never say whether the freeze was in
     * {@code Screen.init}, in the vanilla item refresh, or in our snapshot capture.
     *
     * <p>It also makes the window honest in the other direction: the per-tick rows
     * ({@code server.tick.*}, {@code client.containerTick}) only accumulate while a session is
     * open, so the totals in the report describe THIS terminal opening rather than however long
     * the profiler happened to be switched on.
     *
     * @return true when this call opened the session
     */
    public static boolean beginClientSession() {
        if (!enabled()) {
            return false;
        }
        // A terminal being opened means the loading phase is over: stop attributing
        // always-measured load sections to whatever the player does now.
        startupRecording = false;
        long started = SESSION_START.get();
        if (started != 0L && System.nanoTime() - started < SESSION_ABANDON_NANOS) {
            // ADOPT the session the server already opened when the block was right-clicked, and
            // do NOT clear it. The order of a terminal opening is: the click is handled on the
            // server (which scans the network and syncs the counts) and only THEN does the
            // client receive the "open the screen" packet and build it. Clearing here would
            // erase exactly the server half that ran at the moment of the click - the part most
            // likely to be the freeze - and the report would show a fast, innocent client.
            sessionOwner = Owner.CLIENT;
            return true;
        }
        if (started != 0L) {
            report("abandoned interaction (no terminal was opened within "
                    + (SESSION_ABANDON_NANOS / 1_000_000L) + " ms)");
        }
        ENTRIES.clear();
        ROOT_NANOS.set(0L);
        sessionOwner = Owner.CLIENT;
        SESSION_START.set(System.nanoTime());
        return true;
    }

    /**
     * Opens the session for "the player right-clicked the terminal", <b>on the server thread</b>.
     *
     * <p>This is the earliest moment of the whole interaction and it clears the counters, so the
     * report describes one click - not however long the profiler happened to be switched on.
     * The server does real work right here, before the client has even built the screen: it
     * scans the network for its stored items and sends them. Opening the session at the click is
     * what makes that work visible instead of being cut off by the client opening its own.
     *
     * <p>The client then takes the session over without clearing it - see
     * {@link #beginClientSession()}.
     */
    public static boolean beginInteractionSession() {
        if (!enabled()) {
            return false;
        }
        // See beginClientSession: the player is in the world, so loading is over.
        startupRecording = false;
        long started = SESSION_START.get();
        if (started != 0L && System.nanoTime() - started >= SESSION_ABANDON_NANOS) {
            // The previous click never produced a report (the screen was never built, the answer
            // was dropped). Its numbers are printed here rather than silently thrown away by the
            // clear below - a measurement that is discarded is worse than no measurement,
            // because it looks like the profiler found nothing.
            report("abandoned interaction (no report arrived within "
                    + (SESSION_ABANDON_NANOS / 1_000_000L) + " ms)");
        }
        ENTRIES.clear();
        ROOT_NANOS.set(0L);
        sessionOwner = Owner.SERVER;
        SESSION_START.set(System.nanoTime());
        return true;
    }

    /**
     * Opens a session for a count request that arrived on the server with no client session
     * behind it.
     *
     * <p>When a client session IS open this does nothing: the client owns that session and its
     * report already covers the server rows, because in singleplayer they are the same JVM.
     * Only the dedicated-server case starts a session here.
     *
     * <p>The timeout is what keeps an abandoned session from leaking: if a session never gets
     * its report (its answer was dropped as stale, the player closed the screen), its numbers
     * would otherwise land in the NEXT report - a freeze attributed to the wrong click. After
     * {@link #SESSION_ABANDON_NANOS} the stale session is reported and closed instead.
     *
     * @return true when this call opened the session
     */
    public static boolean beginRequestSession() {
        if (!enabled()) {
            return false;
        }
        long started = SESSION_START.get();
        if (started != 0L) {
            if (System.nanoTime() - started < SESSION_ABANDON_NANOS) {
                return false;   // someone (the client) is already measuring this request
            }
            report("abandoned session (no answer arrived within "
                    + (SESSION_ABANDON_NANOS / 1_000_000L) + " ms)");
        }
        ENTRIES.clear();
        ROOT_NANOS.set(0L);
        sessionOwner = Owner.SERVER;
        SESSION_START.set(System.nanoTime());
        return true;
    }

    /** How long a request session may stay open before it is reported and dropped. */
    private static final long SESSION_ABANDON_NANOS = 30_000_000_000L;   // 30 s

    /** Clears the counters without starting a session - for tests and manual resets. */
    public static void reset() {
        ENTRIES.clear();
        ROOT_NANOS.set(0L);
        sessionOwner = Owner.NONE;
        SESSION_START.set(0L);
    }

    /**
     * Opens a measured section. <b>Use it in a try-with-resources</b>, so the measurement ends
     * even when the block throws:
     *
     * <pre>
     *   try (var ignored = VeloceProfiler.section("client.init.restoreViewState")) {
     *       ...
     *   }
     * </pre>
     *
     * <p>The returned scope is only ever used as {@code AutoCloseable} - the type is exposed
     * so the caller can name it, and nothing about it is meant to be called.
     */
    public static Scope section(String label) {
        if (!enabled()) {
            return Scope.NONE;
        }
        return new Scope(label, System.nanoTime());
    }

    /**
     * Opens the session that covers the game's LOADING phase.
     *
     * <p>Called from the mod's constructor, i.e. before the config exists, so it cannot ask
     * whether profiling is on - see {@link #sectionAlways}. The report is gated instead
     * ({@link #reportLoad}), and when it is printed it answers the only question a loader
     * profile can answer about this mod: how much of the waiting is OURS.
     *
     * @return true when a load session is now recording
     */
    public static boolean beginStartupSession() {
        ENTRIES.clear();
        ROOT_NANOS.set(0L);
        sessionOwner = Owner.NONE;
        SESSION_START.set(System.nanoTime());
        startupRecording = true;
        return true;
    }

    /**
     * A load-time section: measured even when the config switch is off.
     *
     * <p>See {@link #startupRecording} for why. Outside a load session this is a no-op, so it
     * cannot leak measurements into a terminal report.
     */
    public static Scope sectionAlways(String label) {
        if (!startupRecording) {
            return Scope.NONE;
        }
        return new Scope(label, System.nanoTime());
    }

    /**
     * Ends the load session: prints the breakdown when profiling is on, keeps quiet otherwise.
     *
     * <p>Called twice, because "loading" is two waits the player experiences separately: the
     * mod-loading phase (our registration and the compat setup) and the world coming up (the
     * recipe indexes being built for the first time). The two are reported under different
     * contexts so the log says which wait each number belongs to.
     *
     * @param context what finished loading
     */
    public static void reportLoad(String context) {
        startupRecording = false;
        if (enabled()) {
            // Forced: a load report happens once and somebody is waiting for it.
            print(context, true, true);
        } else {
            // The switch may only be readable now, and it says off: drop the buffer rather than
            // let a load session's rows appear inside a later terminal report.
            ENTRIES.clear();
            ROOT_NANOS.set(0L);
            sessionOwner = Owner.NONE;
            SESSION_START.set(0L);
        }
    }

    /**
     * Records a measurement that was already taken, for spans that do not fit a
     * try-with-resources block - the queue wait of a job, which begins in one method and ends
     * in another.
     *
     * @param nanos the elapsed time; a negative value is ignored rather than recorded
     */
    public static void record(String label, long nanos) {
        if (!enabled() || nanos < 0L) {
            return;
        }
        ENTRIES.computeIfAbsent(label, k -> new Entry()).add(nanos);
    }

    /**
     * Adds to a label's unit counter without measuring time - "how much work did it do".
     *
     * <p>Time alone cannot tell "one 40 ms pass" from "twenty 2 ms passes", and those are
     * different bugs: the first is a one-off cost on opening a screen, the second is a stall
     * that repeats every tick and is what a low frame rate actually looks like. The unit column
     * is what makes that visible - e.g. {@code client.applyItemFilter.scan  12 ms  20 call(s)
     * 62000 unit(s)} reads as "62 thousand items per pass, twenty passes".
     *
     * @param units a count in whatever unit the label works in (items, ingredients, recipes)
     */
    public static void count(String label, long units) {
        if (!enabled() || units <= 0L) {
            return;
        }
        ENTRIES.computeIfAbsent(label, k -> new Entry()).units.addAndGet(units);
    }

    /** One measured block. Closing it records the elapsed time. */
    public static final class Scope implements AutoCloseable {

        /** The disabled-path scope: shared, allocation-free, does nothing. */
        static final Scope NONE = new Scope(null, 0L);

        private final String label;
        private final long startNanos;

        private Scope(String label, long startNanos) {
            this.label = label;
            this.startNanos = startNanos;
            if (label != null) {
                long[] depth = DEPTH.get();
                if (depth[0]++ == 0L) {
                    depth[1] = startNanos;   // this is the outermost section on this thread
                }
            }
        }

        @Override
        public void close() {
            if (label == null) {
                return;
            }
            long now = System.nanoTime();
            long elapsed = now - startNanos;
            ENTRIES.computeIfAbsent(label, k -> new Entry()).add(elapsed);
            long[] depth = DEPTH.get();
            if (--depth[0] <= 0L) {
                depth[0] = 0L;   // never go negative if a scope is closed on another thread
                ROOT_NANOS.addAndGet(now - depth[1]);
            }
            // A single slow call is reported IMMEDIATELY, because that is the case where the
            // player felt a freeze: waiting for a session report would place the number far
            // from the moment it happened, and a freeze that never produced a report (a crash,
            // a closed screen) would leave no trace at all.
            if (elapsed >= SLOW_CALL_NANOS) {
                com.craftingveloce.CraftingVeloceMod.LOGGER.warn(
                        "[Veloce][PROF] SLOW {} took {} ms (thread {})",
                        label, elapsed / 1_000_000L, Thread.currentThread().getName());
            }
        }
    }

    /**
     * Writes the breakdown only when the open session belongs to the side calling this.
     *
     * <p>Both the client and the server finish something worth reporting for the same click, and
     * exactly one of them should print it - see {@link #beginClientSession()}.
     *
     * @param fromClient true for the client thread, false for the server thread
     * @param context    what the player did - the line the reader searches for
     */
    public static void reportIfOwner(boolean fromClient, String context) {
        if (!owns(fromClient)) {
            return;
        }
        report(context);
    }

    /**
     * Like {@link #reportIfOwner}, but the session stays open and keeps accumulating.
     *
     * <p><b>Why this exists.</b> The terminal's answer can arrive in two parts: the server sends
     * the network's cached numbers the instant the screen asks (a partial answer), and the
     * freshly computed page follows. Reporting and clearing on the first one would end the
     * session before the work being investigated had even started - which is exactly the trap
     * that made an earlier version of this profiler report a fast client and nothing else.
     *
     * <p>So a partial answer PRINTS what is known so far and carries on, and only the final
     * answer closes the session. The contexts differ ("partial answer" / "terminal open"), so
     * the two lines are told apart in the log by eye and by grep.
     */
    public static void flushIfOwner(boolean fromClient, String context) {
        if (!owns(fromClient)) {
            return;
        }
        print(context, false);
    }

    /** Whether the open session belongs to the side asking - see {@link #beginClientSession()}. */
    private static boolean owns(boolean fromClient) {
        if (!enabled()) {
            return false;
        }
        Owner owner = sessionOwner;
        return fromClient ? (owner == Owner.CLIENT || owner == Owner.NONE)
                          : (owner == Owner.SERVER);
    }

    /**
     * Writes the breakdown for the current session, worst first, then clears it.
     *
     * @param context what the player did, e.g. "terminal page request" - it is the line the
     *                reader searches for
     */
    public static void report(String context) {
        print(context, true);
    }

    /**
     * Prints the breakdown NOW, whatever it costs - for {@code /cv profile report}, where the
     * player explicitly asked for the numbers and silence would look like a broken command.
     */
    public static void reportNow(String context) {
        print(context, true, true);
    }

    /**
     * The body of a report.
     *
     * @param clear true = the session ends here (counters and clock are reset); false = the
     *              breakdown is printed as a snapshot and the session keeps running
     */
    private static void print(String context, boolean clear) {
        print(context, clear, false);
    }

    /**
     * The body of a report.
     *
     * @param forced true for an explicit request ({@code /cv profile report}, the load reports):
     *               those print even when the numbers are small, because somebody asked
     */
    private static void print(String context, boolean clear, boolean forced) {
        if (!enabled()) {
            // Still clear: a session that began while profiling was on and ended after it was
            // switched off must not leak its numbers into the next one.
            ENTRIES.clear();
            ROOT_NANOS.set(0L);
            sessionOwner = Owner.NONE;
            SESSION_START.set(0L);
            return;
        }
        // The clock is only consumed when the session really ends; a snapshot needs the START to
        // survive so the wall clock of the final report covers the whole interaction.
        long sessionStart = clear ? SESSION_START.getAndSet(0L) : SESSION_START.get();
        if (clear) {
            sessionOwner = Owner.NONE;
        }
        if (ENTRIES.isEmpty()) {
            com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                    "[Veloce][PROF] {}: nothing measured", context);
            if (clear) {
                ENTRIES.clear();
                sessionOwner = Owner.NONE;
            }
            return;
        }

        long totalNanos = 0;
        List<Map.Entry<String, Entry>> rows = new ArrayList<>(ENTRIES.entrySet());
        for (Map.Entry<String, Entry> e : rows) {
            totalNanos += e.getValue().totalNanos.get();
        }
        // Worst total first: the reader wants the cost, not the alphabetical order.
        rows.sort((a, b) -> Long.compare(
                b.getValue().totalNanos.get(), a.getValue().totalNanos.get()));

        long wallNanos = sessionStart > 0 ? System.nanoTime() - sessionStart : totalNanos;
        long rootNanos = ROOT_NANOS.get();
        if (!forced && rootNanos < REPORT_MIN_NANOS) {
            // Nothing worth explaining. This early return is what keeps the profiler from
            // becoming the problem it was written to find - see REPORT_MIN_NANOS.
            if (clear) {
                ENTRIES.clear();
                ROOT_NANOS.set(0L);
                sessionOwner = Owner.NONE;
            }
            return;
        }
        com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                "[Veloce][PROF] ===== {}{} =====", context,
                clear ? "" : " [SNAPSHOT - session continues]");
        // Two sums on purpose. OURS is the top-level one - it is the part that can be compared
        // with the wall clock. The nested sum is kept because it is what the per-row percentages
        // are relative to, so a row's share can be checked against the table; without the note
        // the larger number looks like a bug.
        com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                "[Veloce][PROF] wall clock {} ms | our top-level sections {} ms | "
                        + "all rows incl. nesting {} ms",
                wallNanos / 1_000_000L, rootNanos / 1_000_000L, totalNanos / 1_000_000L);

        int shown = 0;
        for (Map.Entry<String, Entry> e : rows) {
            if (shown++ >= REPORT_LIMIT) {
                com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                        "[Veloce][PROF]   ... and {} more label(s)", rows.size() - REPORT_LIMIT);
                break;
            }
            Entry v = e.getValue();
            long total = v.totalNanos.get();
            long calls = v.calls.get();
            long units = v.units.get();
            double share = totalNanos > 0 ? (100.0 * total / totalNanos) : 0.0;
            // String.format, and NOT an SLF4J format string.
            //
            // THE BUG THIS FIXES: these rows were written with "{:<38}" and passed to
            // LOGGER.info(message, args). SLF4J only understands "{}", so every argument was
            // dropped and the log was filled with the literal pattern - hundreds of identical
            // lines saying "{:<38} {:>7} ms" and not one number in them. The first real run of
            // this profiler produced exactly that. The row is now built by String.format (which
            // does take "{:<38}") and logged as one finished string.
            if (units > 0L) {
                // With units: the per-unit cost is the number that travels between packs, and
                // the total alone cannot be judged without it.
                logRow(String.format(java.util.Locale.ROOT,
                        "%-40s %7d ms %6.1f%% %5d call(s) worst %6d ms"
                                + "  %9d unit(s) %9d us/unit",
                        e.getKey(), total / 1_000_000L, share, calls,
                        v.worstNanos.get() / 1_000_000L, units, (total / 1_000L) / units));
            } else {
                logRow(String.format(java.util.Locale.ROOT,
                        "%-40s %7d ms %6.1f%% %5d call(s) worst %6d ms",
                        e.getKey(), total / 1_000_000L, share, calls,
                        v.worstNanos.get() / 1_000_000L));
            }
        }
        com.craftingveloce.CraftingVeloceMod.LOGGER.info("[Veloce][PROF] =====================");

        if (clear) {
            ENTRIES.clear();
            ROOT_NANOS.set(0L);
            sessionOwner = Owner.NONE;
        }
    }

    /** One already-formatted report row - see the note in {@link #print} about SLF4J patterns. */
    private static void logRow(String row) {
        com.craftingveloce.CraftingVeloceMod.LOGGER.info("[Veloce][PROF]   " + row);
    }
}
