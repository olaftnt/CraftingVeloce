package com.craftingveloce.network.pipe;

import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.annotation.Nullable;

/**
 * Global, reference-counted owner of force-loaded chunks.
 *
 * <p><b>Why this exists.</b> {@code ServerLevel.setChunkForced(x, z, bool)}
 * is one GLOBAL flag per chunk, not a counter. Previously every network kept
 * its own {@code forcedChunks} set and called {@code setChunkForced} on its
 * own. When two networks shared the same chunk and disagreed about it (one
 * wanted it, the other did not), a tug of war ensued:
 *
 * <pre>
 *   network A: setChunkForced(X, true)    -> the chunk loads
 *   network B: setChunkForced(X, false)   -> the chunk unloads
 *   network A: setChunkForced(X, true)    -> again...
 * </pre>
 *
 * <p>In practice that meant ~41,000 load/unload cycles of a single chunk over
 * five minutes. Every cycle triggered a network rebuild, endpoint cache
 * invalidation and a debug-log event, which ate the server thread and produced
 * exactly the lag that was visible in game.
 *
 * <p><b>Tickets instead of a bare counter.</b> A reference counter alone does
 * not answer the two questions needed for diagnosis: WHO holds this chunk and
 * WHY. That is why every hold is a ticket ({@link Ticket}) with an owner,
 * a reason and the position of the block that asked for it.
 *
 * <p><b>Frequency of use.</b> A chunk that network operations reach into
 * without pause is held LONGER. Without this there was a cycle: an operation
 * forces the chunk, the operation ends, the chunk drops out, and in a moment it
 * has to be forced again. Every such round trip is a full chunk load from disk.
 * The {@code hits} counter makes it possible to tell a chunk "used once a
 * minute" from one "used several times per second".
 */
public final class VeloceChunkLoader {

    private VeloceChunkLoader() {
    }

    /**
     * Why the chunk is held.
     *
     * <p>The order matters only for the readability of the report - it does not
     * affect behaviour.
     */
    public enum Reason {
        /** Pipe network: pipes and nodes must be simulated when the player leaves. */
        NETWORK,
        /** An item operation reached into a chunk outside simulation. */
        OPERATION,
        /** A pipe that is currently being rebuilt or examining its surroundings. */
        SCAN,
        /** Unknown reason - a fallback entry. */
        OTHER
    }

    /**
     * Ticket: one owner holding one chunk.
     *
     * @param owner     who holds it (network name, operation name)
     * @param reason    why
     * @param ownerPos  the block that asked for it (for the report and teleport)
     * @param since     the game tick from which the ticket is in effect
     */
    public record Ticket(String owner, Reason reason, BlockPos ownerPos, long since) {
    }

    /**
     * The state of one chunk in the bookkeeping.
     *
     * <p>Mutable on purpose: tickets come and go, while the usage counter lives
     * longer than a single ticket.
     */
    private static final class Entry {
        /** Active tickets. The same owner can have only one. */
        final Map<String, Ticket> tickets = new HashMap<>();
        /** How many times data was reached for here - for deciding on a longer hold. */
        int hits;
        /** Until when (gameTime) we hold this chunk because of frequent use. */
        long holdUntilTick = Long.MIN_VALUE;
    }

    /** level -> (chunk -> state). Weak keys, so as not to hold worlds. */
    private static final Map<ServerLevel, Map<Long, Entry>> REFS = new WeakHashMap<>();

    /** We want to know what we really forced, in order to clean up on shutdown. */
    private static final Map<ServerLevel, Set<Long>> APPLIED = new WeakHashMap<>();

    /**
     * Is the world shutting down/saving.
     *
     * <p><b>Why this exists.</b> While saving the world, Minecraft unloads
     * chunks. If at that moment anything forces them again, the save cannot
     * finish: the chunk comes back, is unloaded, comes back... In the log this
     * shows up as thousands of "chunk [x, z] loaded / unloaded" cycles during
     * "Saving worlds", that is, a hung world save.
     *
     * <p>The flag lives here, because {@link #retain} is the common entry point
     * for ALL force-loads - including the fallback ones from item retrieval,
     * which previously bypassed the guard in the craftability cache.
     */
    private static volatile boolean frozen = false;

    // ------------------------------------------------------------------
    // Bilety - utrzymywanie w pamieci
    // ------------------------------------------------------------------

    /** Freezes force-loads (the server is shutting down / saving the world). */
    public static void freeze() {
        frozen = true;
    }

    /** Unfreezes force-loads (we have entered the world). */
    public static void unfreeze() {
        frozen = false;
    }

    public static boolean isFrozen() {
        return frozen;
    }

    /**
     * Reports that a given owner wants to hold this chunk.
     *
     * @return true if it was us who physically forced the load
     */
    public static boolean retain(ServerLevel level, long chunkKey) {
        return retain(level, chunkKey, "network", Reason.NETWORK, null);
    }

    /**
     * Reports a chunk hold, recording WHO and WHY.
     *
     * <p>The same owner can report repeatedly - the ticket is one, so the count
     * does not grow from mere repetition. Thanks to this, a failure in one place
     * does not leave the chunk held forever.
     *
     * @return true if it was us who physically forced the load
     */
    public static boolean retain(ServerLevel level, long chunkKey, String owner,
                                 Reason reason, BlockPos ownerPos) {
        if (frozen) {
            // The world is saving - forcing the chunk would hang the save.
            return false;
        }
        Entry entry = REFS.computeIfAbsent(level, k -> new HashMap<>())
                .computeIfAbsent(chunkKey, k -> new Entry());

        entry.tickets.put(owner, new Ticket(owner, reason, ownerPos, level.getGameTime()));

        if (entry.tickets.size() > 1) {
            return false;   // someone else already holds it - we do nothing
        }
        // HAVE WE ALREADY FORCED THIS?
        //
        // The BUG that was here: the same owner reported every few dozen ticks
        // (the craftability cache refreshes over and over) and EVERY TIME we
        // went to setChunkForced(true). For vanilla, a repeat on an
        // already-forced chunk is a no-op, so the chunk was NOT reloaded - but
        // our own loop counter (watchForce) counted those calls as "forcings"
        // and after the fifth repeat within a 200-tick window printed
        // [FAIL] "... this is the load/unload loop signature".
        //
        // Effect: 78 scary errors in one session, all of them UNTRUE (three
        // chunks 26 times each), and a real loop would have drowned in that
        // noise.
        //
        // So we ask VANILLA about the actual state (getForcedChunks is a plain
        // reference to a set, O(1)), instead of our own bookkeeping: if anyone
        // (including another mod) removed that forcing, we force again, and then
        // the counter really has something to report.
        boolean weApplied = APPLIED.computeIfAbsent(level, k -> new HashSet<>()).contains(chunkKey);
        if (weApplied && level.getForcedChunks().contains(chunkKey)) {
            return false;   // already forced - a repeat adds nothing
        }
        level.setChunkForced(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), true);
        APPLIED.get(level).add(chunkKey);
        watchForce(level, chunkKey, owner, reason, ownerPos);
        return true;
    }

    /**
     * Detects the load/unload LOOP SIGNATURE and says so outright.
     *
     * <p><b>Why.</b> In game a loop looks like "the chunk keeps reloading", but
     * it left no trace in the log - unless someone deliberately enabled tracing.
     * Without this, reported symptoms ("it falls into a loop") had to be
     * reproduced from the code instead of being read from the log.
     *
     * <p>We count every PHYSICAL forcing of the same chunk within a window of
     * {@link #FORCE_THROTTLE_WINDOW_TICKS}. After crossing the threshold we log
     * ONCE per window, giving the last owner and reason - that is, we point
     * straight at WHO keeps ordering this chunk to be loaded.
     *
     * <p>A forcing is not the same as a use: {@code retain} for an existing
     * ticket does nothing, so this counter catches exactly the flipping of
     * {@code setChunkForced}, not an ordinary reach into an already loaded
     * chunk.
     */
    private static void watchForce(ServerLevel level, long chunkKey, String owner,
                                   Reason reason, BlockPos ownerPos) {
        Map<Long, ForceWatch> watch = FORCE_WATCH.computeIfAbsent(level, k -> new HashMap<>());
        long now = level.getGameTime();
        ForceWatch w = watch.get(chunkKey);
        if (w == null || now < w.windowStart || now - w.windowStart > FORCE_THROTTLE_WINDOW_TICKS) {
            w = new ForceWatch();
            w.windowStart = now;
            watch.put(chunkKey, w);
        }
        w.count++;
        if (w.count == FORCE_THROTTLE_LIMIT) {
            VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                    "chunk %s forced %d times within %d ticks (last: owner=%s, reason=%s, at=%s)"
                            + " - this is the load/unload loop signature",
                    new ChunkPos(chunkKey), w.count, FORCE_THROTTLE_WINDOW_TICKS,
                    owner, reason, ownerPos);
        }
    }

    /**
     * Detects a loop of LOADS WITHOUT FORCING and says so outright.
     *
     * <p><b>Why a separate counter.</b> {@code watchForce} watches only
     * {@code setChunkForced}, while a real load/unload loop can run purely on
     * {@code getChunk(..., true)} - that is, on loads "for the operation",
     * without any forcing. Exactly such a loop went through our system unnoticed:
     * a storage with a stale cache ordered the same chunk to be loaded 220 times
     * in 22 seconds, while the forcing counter stayed silent, because
     * {@code isHeld()} was false.
     *
     * <p>So we count loads per chunk and, after crossing the threshold, we say
     * WHO is doing them and WHY. It is the same idea as with forcings, only for
     * the other path.
     */
    public static void noteOpLoad(ServerLevel level, long chunkKey, BlockPos pos, String reason) {
        Map<Long, ForceWatch> watch = OP_LOAD_WATCH.computeIfAbsent(level, k -> new HashMap<>());
        long now = level.getGameTime();
        ForceWatch w = watch.get(chunkKey);
        if (w == null || now < w.windowStart || now - w.windowStart > FORCE_THROTTLE_WINDOW_TICKS) {
            w = new ForceWatch();
            w.windowStart = now;
            watch.put(chunkKey, w);
        }
        w.count++;
        if (w.count == FORCE_THROTTLE_LIMIT) {
            VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                    "chunk %s loaded %d times within %d ticks WITHOUT forcing (%s @%s)"
                            + " - this is a load/unload loop; the most common cause: a storage"
                            + " with a stale cache that cannot be read",
                    new ChunkPos(chunkKey), w.count, FORCE_THROTTLE_WINDOW_TICKS,
                    reason, pos);
        }
    }

    /**
     * Detects a CYCLE: a chunk unloaded and loaded again right away.
     *
     * <p><b>Why a third counter.</b> We already had a forcing counter
     * ({@code setChunkForced}) and a counter of loads "for the operation"
     * ({@code getChunk(..., true)}). And a real load/unload loop slipped past
     * both unnoticed, because it arose from a third thing: from an ordinary
     * READ of a block in an unloaded chunk ({@code getBlockEntity} /
     * {@code getBlockState} on the server loads the chunk by itself).
     *
     * <p>This counter does not ask "who loaded it", only "is this already a
     * cycle": if the same chunk comes back within a few ticks after unloading,
     * that means someone is loading it over and over. We then log it TOGETHER
     * WITH THE CALL STACK - because the stack shows exactly the method that
     * loaded it (loading is synchronous, so our frame is on the stack).
     *
     * <p>Without this one had to read logs and guess - as with the two previous
     * approaches to the same symptom.
     */
    public static void noteChunkEvent(ServerLevel level, long chunkKey, boolean loaded) {
        long now = level.getGameTime();
        Map<Long, Long> unloaded = RECENT_UNLOAD.computeIfAbsent(level, k -> new HashMap<>());
        if (!loaded) {
            unloaded.put(chunkKey, now);
            return;
        }
        Long lastUnload = unloaded.remove(chunkKey);
        if (lastUnload == null || now < lastUnload || now - lastUnload > RELOAD_SUSPICION_TICKS) {
            return;   // a normal load, not a cycle
        }
        if (isHeld(level, chunkKey)) {
            return;   // our own force-load - that is normal
        }
        Map<Long, ForceWatch> watch = RELOAD_WATCH.computeIfAbsent(level, k -> new HashMap<>());
        ForceWatch w = watch.get(chunkKey);
        if (w == null || now < w.windowStart || now - w.windowStart > FORCE_THROTTLE_WINDOW_TICKS) {
            w = new ForceWatch();
            w.windowStart = now;
            watch.put(chunkKey, w);
        }
        w.count++;
        if (w.count == 3) {
            VeloceLog.Network.error(VeloceLog.Side.SERVER,
                    new Throwable("load/unload loop"),
                    "chunk %s came back %d ticks after unloading (%d times in a window of %d ticks)"
                            + " WITHOUT a force-load - this is a load/unload loop. The stack below"
                            + " shows WHO is loading it (look for a com.craftingveloce frame):",
                    new ChunkPos(chunkKey), now - lastUnload, w.count,
                    FORCE_THROTTLE_WINDOW_TICKS);
        }
    }

    /** How many ticks after unloading we consider a load suspicious. */
    private static final long RELOAD_SUSPICION_TICKS = 10L;

    /** The window in which we count forcings of the same chunk. */
    private static final long FORCE_THROTTLE_WINDOW_TICKS = 200L;

    /** After how many forcings in the window we consider it a loop. */
    private static final int FORCE_THROTTLE_LIMIT = 5;

    /** Counter of forcings of one chunk in the current window. */
    private static final class ForceWatch {
        long windowStart;
        int count;
    }

    private static final Map<ServerLevel, Map<Long, ForceWatch>> FORCE_WATCH = new WeakHashMap<>();

    /**
     * Counter of loads WITHOUT forcing, per chunk - see {@link #noteOpLoad}.
     *
     * <p>Weak keys for worlds (like the other tracking maps) plus pruning in
     * {@link #pruneTrackingMaps}, so that a long session does not grow in
     * memory.
     */
    private static final Map<ServerLevel, Map<Long, ForceWatch>> OP_LOAD_WATCH = new WeakHashMap<>();

    /** Tick of the last chunk unload - for cycle detection. */
    private static final Map<ServerLevel, Map<Long, Long>> RECENT_UNLOAD = new WeakHashMap<>();

    /** Counter of suspicious chunk returns, per chunk. */
    private static final Map<ServerLevel, Map<Long, ForceWatch>> RELOAD_WATCH = new WeakHashMap<>();

    /**
     * Releases the ticket of one owner.
     *
     * <p>The chunk is really unloaded only when there is NO ticket at all and it
     * is not held because of frequent use. It is exactly this rule that cuts off
     * the tug-of-war loop between networks.
     */
    public static void release(ServerLevel level, long chunkKey) {
        release(level, chunkKey, "network");
    }

    /** Releases the ticket of a specific owner. */
    public static void release(ServerLevel level, long chunkKey, String owner) {
        Map<Long, Entry> byChunk = REFS.get(level);
        if (byChunk == null) {
            return;
        }
        Entry entry = byChunk.get(chunkKey);
        if (entry == null) {
            return;
        }
        entry.tickets.remove(owner);
        // Tickets of other owners stay - someone still needs the chunk.
        if (!entry.tickets.isEmpty()) {
            return;
        }
        // No tickets, but the chunk was used often - we hold it for another
        // HOLD_TICKS, so as not to enter a loading cycle.
        if (entry.holdUntilTick != Long.MIN_VALUE
                && level.getGameTime() < entry.holdUntilTick) {
            return;
        }
        unforce(level, chunkKey);
        byChunk.remove(chunkKey);
        if (byChunk.isEmpty()) {
            REFS.remove(level);
        }
    }

    /** Physically removes the forcing and cleans up the bookkeeping. */
    private static void unforce(ServerLevel level, long chunkKey) {
        // We remove the forcing ONLY when it was us who applied it.
        //
        // setChunkForced(false) knows no notion of ownership - it would remove a
        // forcing applied by someone else (e.g. a player through /forceload or
        // another mod). Without this condition our bookkeeping said "this is
        // ours" while vanilla lost someone else's forcing.
        Set<Long> applied = APPLIED.get(level);
        if (applied != null && applied.contains(chunkKey)) {
            level.setChunkForced(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), false);
            applied.remove(chunkKey);
            if (applied.isEmpty()) {
                APPLIED.remove(level);
            }
        }
    }

    // ------------------------------------------------------------------
    // Czestotliwosc uzycia - inteligentne trzymanie
    // ------------------------------------------------------------------

    /**
     * How many ticks we hold a chunk after use, before letting it drop out.
     *
     * <p>One minute. Short network operations (pulling a stack, inserting into a
     * chest) happen in bursts - without this each of them would end with a chunk
     * unload and another load from disk.
     */
    private static final long HOLD_TICKS = 1200L;

    /**
     * From how many uses we consider a chunk "hot".
     *
     * <p>Three uses within a window of {@link #HOT_WINDOW_TICKS} is already a
     * burst, not a chance single operation.
     */
    private static final int HOT_THRESHOLD = 3;

    /** Length of the window in which we count uses. */
    private static final long HOT_WINDOW_TICKS = 200L;

    /** chunk -> (the last tick in which it was counted) - for letting the counter expire. */
    private static final Map<ServerLevel, Map<Long, Long>> HIT_WINDOW = new WeakHashMap<>();

    /**
     * Records that someone just reached into this chunk.
     *
     * <p>Called on every operation on the contents of a chunk. When there are
     * many uses in a short time, the chunk gets a longer hold - this is exactly
     * the "smart priority list": frequently used chunks stay in memory, while
     * those touched once may drop out.
     */
    public static void noteUse(ServerLevel level, long chunkKey) {
        if (frozen) {
            return;
        }
        Entry entry = REFS.computeIfAbsent(level, k -> new HashMap<>())
                .computeIfAbsent(chunkKey, k -> new Entry());

        long now = level.getGameTime();
        Map<Long, Long> windows = HIT_WINDOW.computeIfAbsent(level, k -> new HashMap<>());
        Long lastCounted = windows.get(chunkKey);
        if (lastCounted == null || now < lastCounted || now - lastCounted > HOT_WINDOW_TICKS) {
            // A new window - the counter starts over.
            entry.hits = 1;
            windows.put(chunkKey, now);
        } else {
            entry.hits++;
        }

        if (entry.hits >= HOT_THRESHOLD) {
            entry.holdUntilTick = now + HOLD_TICKS;
            // A hot chunk MUST be really forced - otherwise the "hold" is only
            // an entry in the bookkeeping.
            if (entry.tickets.isEmpty()) {
                retain(level, chunkKey, "hot", Reason.OPERATION, null);
            }
        }
    }

    /**
     * Releases expired tickets of "hot" chunks.
     *
     * <p>Called once per second from the server tick. Without this a chunk once
     * deemed hot stayed forced forever.
     */
    public static void expireHotTickets(ServerLevel level) {
        long now = level.getGameTime();

        // We do the cleanup of the auxiliary maps BEFORE returning on an empty
        // REFS - otherwise, after all tickets are released, the maps would never
        // be cleared, because the method would return earlier.
        pruneTrackingMaps(level, now);

        Map<Long, Entry> byChunk = REFS.get(level);
        if (byChunk == null || byChunk.isEmpty()) {
            return;
        }
        List<Long> expired = new ArrayList<>();
        for (Map.Entry<Long, Entry> e : byChunk.entrySet()) {
            Entry entry = e.getValue();
            if (entry.holdUntilTick == Long.MIN_VALUE) {
                continue;
            }
            // now < holdUntilTick with a rewound world time: we then consider
            // that we still hold it - otherwise we would release the chunk
            // immediately.
            if (now >= entry.holdUntilTick) {
                entry.holdUntilTick = Long.MIN_VALUE;
                entry.hits = 0;
                if (entry.tickets.isEmpty()
                        || (entry.tickets.size() == 1 && entry.tickets.containsKey("hot"))) {
                    entry.tickets.remove("hot");
                    expired.add(e.getKey());
                }
            }
        }
        for (long key : expired) {
            if (byChunk.get(key).tickets.isEmpty()) {
                unforce(level, key);
                byChunk.remove(key);
            }
        }
    }

    // ------------------------------------------------------------------
    // Diagnostyka
    // ------------------------------------------------------------------

    /**
     * Removes entries about chunks whose windows expired long ago.
     *
     * <p><b>The BUG (a memory leak) this fixes.</b> Both auxiliary maps -
     * {@code HIT_WINDOW} (the use counter) and {@code FORCE_WATCH} (the forcing
     * counter) - are keyed by chunk number and got an entry for EVERY chunk we
     * ever touched. They were not cleared at all: the only place that removed
     * them was {@code releaseAll} on leaving the world. Effect: in a long session
     * with a network spanning many chunks (or with a player who flies around the
     * world) the number of dead entries grew - hundreds of thousands over a long
     * game.
     *
     * <p>An entry is useless once the window it referred to has passed: the use
     * counter starts over anyway, and the forcing counter only serves the report.
     * That is why we discard them once a second, together with expiring hot
     * tickets - that is, where this cleanup has its natural place.
     *
     * <p>The case {@code now < storedTick} (a rewound world time) is treated the
     * same as expiry: it cannot be assessed, and keeping such an entry forever is
     * worse than losing the counter.
     */
    private static void pruneTrackingMaps(ServerLevel level, long now) {
        Map<Long, Long> windows = HIT_WINDOW.get(level);
        if (windows != null) {
            windows.entrySet().removeIf(e -> now < e.getValue()
                    || now - e.getValue() > HOT_WINDOW_TICKS);
            if (windows.isEmpty()) {
                HIT_WINDOW.remove(level);
            }
        }
        Map<Long, ForceWatch> watch = FORCE_WATCH.get(level);
        if (watch != null) {
            watch.entrySet().removeIf(e -> now < e.getValue().windowStart
                    || now - e.getValue().windowStart > FORCE_THROTTLE_WINDOW_TICKS);
            if (watch.isEmpty()) {
                FORCE_WATCH.remove(level);
            }
        }
        Map<Long, Long> recentUnload = RECENT_UNLOAD.get(level);
        if (recentUnload != null) {
            recentUnload.entrySet().removeIf(e -> now < e.getValue()
                    || now - e.getValue() > FORCE_THROTTLE_WINDOW_TICKS);
            if (recentUnload.isEmpty()) {
                RECENT_UNLOAD.remove(level);
            }
        }
        Map<Long, ForceWatch> reloads = RELOAD_WATCH.get(level);
        if (reloads != null) {
            reloads.entrySet().removeIf(e -> now < e.getValue().windowStart
                    || now - e.getValue().windowStart > FORCE_THROTTLE_WINDOW_TICKS);
            if (reloads.isEmpty()) {
                RELOAD_WATCH.remove(level);
            }
        }
        // The same pruning approach for the counter of loads without forcing -
        // without it a long session would hold an entry for every touched chunk.
        Map<Long, ForceWatch> opLoads = OP_LOAD_WATCH.get(level);
        if (opLoads != null) {
            opLoads.entrySet().removeIf(e -> now < e.getValue().windowStart
                    || now - e.getValue().windowStart > FORCE_THROTTLE_WINDOW_TICKS);
            if (opLoads.isEmpty()) {
                OP_LOAD_WATCH.remove(level);
            }
        }
    }

    /** Diagnostics: how many entries the auxiliary maps hold (for leak detection). */
    public static String trackingMapSizes(ServerLevel level) {
        Map<Long, Long> windows = HIT_WINDOW.get(level);
        Map<Long, ForceWatch> watch = FORCE_WATCH.get(level);
        Map<Long, ForceWatch> opLoads = OP_LOAD_WATCH.get(level);
        Map<Long, Entry> refs = REFS.get(level);
        return "hits=" + (windows == null ? 0 : windows.size())
                + ", forceWatch=" + (watch == null ? 0 : watch.size())
                + ", opLoadWatch=" + (opLoads == null ? 0 : opLoads.size())
                + ", reloadWatch=" + (RELOAD_WATCH.get(level) == null ? 0 : RELOAD_WATCH.get(level).size())
                + ", refs=" + (refs == null ? 0 : refs.size());
    }

    /** Description of one held chunk - for the chat report. */
    public record HeldChunk(long chunkKey, int x, int z, List<Ticket> tickets, int hits) {
    }

    /**
     * The list of all chunks that this loader instance really forced.
     *
     * <p>It returns data from the loader's bookkeeping rather than from caches -
     * thanks to this you can see ALSO chunks forced as a fallback, outside the
     * normal network upkeep.
     */
    public static List<HeldChunk> listHeld(ServerLevel level) {
        List<HeldChunk> out = new ArrayList<>();
        Set<Long> applied = APPLIED.get(level);
        if (applied == null || applied.isEmpty()) {
            return out;
        }
        Map<Long, Entry> byChunk = REFS.get(level);
        for (long key : applied) {
            Entry entry = byChunk != null ? byChunk.get(key) : null;
            List<Ticket> tickets = entry == null
                    ? List.of()
                    : new ArrayList<>(entry.tickets.values());
            out.add(new HeldChunk(key, ChunkPos.getX(key), ChunkPos.getZ(key), tickets,
                    entry == null ? 0 : entry.hits));
        }
        // A stable order, so that the report does not jump between calls.
        out.sort((a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x())
                : Integer.compare(a.z(), b.z()));
        return out;
    }

    /**
     * The block entity ONLY if the chunk is loaded - otherwise {@code null},
     * WITHOUT loading the chunk.
     *
     * <p><b>Why this exists.</b> On the server {@code Level.getBlockEntity(pos)}
     * is NOT a safe "only if present" read: it LOADS the chunk (vanilla
     * bytecode: getBlockEntity -> getChunkAt -> getChunk(x, z) ->
     * ChunkStatus.FULL, requireChunk = TRUE). The same applies to
     * {@code getBlockState(pos)}.
     *
     * <p>This is what produced a real load/unload loop: our diagnostic report
     * described blocks in a chunk that had just unloaded, and the description
     * itself loaded it back. That is why every read of a position that may be in
     * an unloaded chunk (and therefore every position from the pipe network) MUST
     * go through these methods - then the rule is in one place, instead of a
     * dozen or so conditions to remember.
     */
    @Nullable
    public static net.minecraft.world.level.block.entity.BlockEntity blockEntityIfLoaded(
            net.minecraft.world.level.Level level, BlockPos pos) {
        return level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
    }

    /** The block state ONLY if the chunk is loaded - otherwise {@code null}. */
    @Nullable
    public static net.minecraft.world.level.block.state.BlockState blockStateIfLoaded(
            net.minecraft.world.level.Level level, BlockPos pos) {
        return level.isLoaded(pos) ? level.getBlockState(pos) : null;
    }

    /**
     * Is this chunk really forced by us in this world.
     *
     * <p>Needed for reconciling the bookkeeping: a cache may think it holds a
     * chunk that the loader has already released (e.g. on a world unload).
     * Without this check we would either not force it again, or - worse - count
     * a second reference to the chunk that will never disappear.
     */

    public static boolean isHeld(ServerLevel level, long chunkKey) {
        Set<Long> applied = APPLIED.get(level);
        return applied != null && applied.contains(chunkKey);
    }

    /** Diagnostics: how many chunks we really hold in this world. */
    public static int appliedCount(ServerLevel level) {
        Set<Long> applied = APPLIED.get(level);
        return applied == null ? 0 : applied.size();
    }

    /**
     * How many BLOCKING chunk loads may be performed in one tick.
     *
     * <p>Inserting into the network must be synchronous (see
     * {@link ConnectedEndpointInfo#insertItemLeftover} - otherwise duplication
     * occurs), but it CANNOT be unlimited. A single chunk load from disk takes
     * a few to a few dozen milliseconds, and inserting loops over all storages
     * of the network - without a limit, a burst of deposits into distant chests
     * would blow the tick and freeze the server.
     *
     * <p>Once the budget is exhausted, inserting is REFUSED (the stack returns
     * to the caller, which keeps the items with the player). That is safe:
     * nothing is lost and nothing is duplicated, the player gets a message, and
     * the next tick has a full budget again.
     */
    public static final int MAX_OP_LOADS_PER_TICK = 4;

    /** The budget is counted SEPARATELY for each world - otherwise the Nether would eat the Overworld's limit. */
    private static final class OpLoadBudget {
        long tick = Long.MIN_VALUE;
        int used;
    }

    private static final Map<ServerLevel, OpLoadBudget> OP_BUDGET = new WeakHashMap<>();

    /**
     * Reserves one synchronous chunk load for this tick.
     *
     * @return {@code true} when loading is allowed; {@code false} when the
     *         budget for this tick is exhausted (then we do NOT load and do not
     *         insert)
     */
    public static boolean tryReserveOpLoad(ServerLevel level) {
        OpLoadBudget budget = OP_BUDGET.computeIfAbsent(level, l -> new OpLoadBudget());
        long now = level.getGameTime();
        if (budget.tick != now) {
            // A new tick - the budget starts over. We use gameTime rather than
            // our own counter, so that no tick hook has to be wired up.
            budget.tick = now;
            budget.used = 0;
        }
        if (budget.used >= MAX_OP_LOADS_PER_TICK) {
            return false;
        }
        budget.used++;
        return true;
    }

    /**
     * Releases "orphans": forced chunks that nobody watches any more.
     *
     * <p><b>Where they come from.</b> {@code ServerLevel.setChunkForced()} is
     * saved by Minecraft PERSISTENTLY, in the world data. Our bookkeeping
     * ({@code APPLIED}, {@code REFS}) lives only in memory and resets on
     * restart. So if we ever forced a chunk and the world was saved, and then we
     * lost knowledge of it, that chunk stays forced FOREVER.
     *
     * <p>Exactly that happened: an older version of the code forced the chunk of
     * EVERY pipe. After a restart we saw "forced chunks: 0", while the game held
     * several dozen chunks along the whole network - and nothing could release
     * them, because they belonged to nobody.
     *
     * <p><b>How we recognize them.</b> We take the list of chunks that the world
     * REALLY forced ({@code level.getForcedChunks()}), subtract the ones in our
     * bookkeeping, and keep only those lying within our network. A chunk forced
     * outside our network may have been set by the player's {@code /forceload} -
     * we do NOT touch that.
     *
     * @param candidateChunks chunks belonging to our networks
     * @return how many orphans were released
     */
    public static int releaseOrphans(ServerLevel level, Set<Long> candidateChunks) {
        if (frozen) {
            return 0;
        }
        Set<Long> applied = APPLIED.get(level);
        List<Long> orphans = new ArrayList<>();
        for (long key : level.getForcedChunks()) {
            if (applied != null && applied.contains(key)) {
                continue;   // ours, deliberately held
            }
            if (!candidateChunks.contains(key)) {
                continue;   // outside our network - could be the player's /forceload
            }
            orphans.add(key);
        }
        for (long key : orphans) {
            level.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), false);
            VeloceLog.Network.success(VeloceLog.Side.SERVER,
                    "released ORPHAN: chunk[%d, %d] was forced without an owner "
                            + "(a leftover from an older version)",
                    ChunkPos.getX(key), ChunkPos.getZ(key));
        }
        if (!orphans.isEmpty()) {
            VeloceLog.Network.success(VeloceLog.Side.SERVER,
                    "cleaned up %d orphaned force-load(s)", orphans.size());
        }
        return orphans.size();
    }

    /** How many chunks are forced in this world (the game state, not ours). */
    public static int gameForcedCount(ServerLevel level) {
        int n = 0;
        for (long ignored : level.getForcedChunks()) {
            n++;
        }
        return n;
    }

    /** Releases everything we ever forced in this world. */
    public static void releaseAll(ServerLevel level) {
        Set<Long> applied = APPLIED.get(level);
        int released = applied == null ? 0 : applied.size();
        if (applied != null) {
            for (long key : applied) {
                level.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), false);
            }
        }
        REFS.remove(level);
        APPLIED.remove(level);
        HIT_WINDOW.remove(level);
        if (released > 0) {
            VeloceLog.Network.success(VeloceLog.Side.SERVER,
                    "chunk loader: released all %d forced chunk(s) for level %s",
                    released, level.dimension().location());
        }
    }
}
