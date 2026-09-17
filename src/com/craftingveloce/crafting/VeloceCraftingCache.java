package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps chunks with network blocks loaded.
 *
 * <p><b>History.</b> This class used to be a craftability cache: it computed in
 * the background "how many more can be made" for every item and fed those numbers
 * to the GUI. That turned out to be the source of the worst problems in the mod -
 * the computation is recursive and expensive, and with several thousand recipes
 * no "let us recompute regularly" could be kept within the tick budget. It was
 * removed: the numbers are now computed on demand by
 * {@link VeloceAutoCrafter} (the visible terminal page), with its own time
 * budget.
 *
 * <p><b>What is left.</b> Exactly one thing: chunk force-loading. Without it
 * extractors and crafters would stop working when the player walks away from the
 * base, and terminals would not see the contents of the network.
 *
 * <p><b>Rules this class follows:</b>
 * <ol>
 *   <li><b>Once per tick.</b> Every terminal in the network calls {@link #tickIdle}, but
 *       the work happens only once per game tick ({@code claimTick}).</li>
 *   <li><b>Startup grace period.</b> After the world is loaded we do not touch chunks
 *       right away - loading a save is the most loaded moment in the server
 *       lifecycle.</li>
 *   <li><b>Server shutdown.</b> When the server is shutting down, we stop forcing
 *       and release everything. Otherwise the world save hangs (the chunk comes back,
 *       is unloaded again, and so on in a loop).</li>
 * </ol>
 */
public final class VeloceCraftingCache {

    /**
     * How many ticks after world start we wait before the first scan.
     *
     * <p>Crucial: loading a save is the most loaded moment in the server lifecycle
     * (chunk generation, entity loading, lighting). If we started computing then,
     * we would end up hanging - which already happened once with 2118 queued
     * items.
     */
    private static final int STARTUP_GRACE_TICKS = 200;   // 10 s

    /**
     * Maximum number of CHUNKS that we force-load for a single network.
     *
     * <p>A safety valve: an extensive network (hundreds of pipes) must not be able
     * to force-load the entire map. Above the limit we load only chunks with nodes
     * (terminals, crafters, extractors) - those are the ones that must work.
     *
     * <p><b>It counts CHUNKS, not block positions.</b> This distinction matters:
     * 218 pipes standing in a line are 218 positions, but as many as 14 chunks -
     * and a network stretched across a base can easily produce several dozen chunks
     * against a limit that looks safe. Previously the limit counted positions, so it
     * let through exactly the cases it was supposed to block.
     */
    private static final int MAX_FORCED_CHUNKS = 64;

    /**
     * Cache key: DIMENSION + network identifier.
     *
     * <p><b>The BUG this fixes (cross-dimensional).</b> The key used to be the
     * network UUID alone, and that is computed from the POSITION of the component
     * representative: {@code UUID.nameUUIDFromBytes(root.toShortString())}. A network
     * in the Nether standing at the same coordinates as a network in the Overworld
     * therefore got an IDENTICAL identifier - and {@code CACHES} is a static map,
     * shared across all dimensions. Both networks thus shared ONE entry:
     * <ul>
     *   <li>the cache held the chunks of whichever network reported last,</li>
     *   <li>force-loads went to the wrong dimension (or did not work at all),</li>
     *   <li>{@code retainOnly} of one dimension deleted the other's cache
     *       - together with its force-loads.</li>
     * </ul>
     *
     * <p>Aligned portals (a base in the same place in both dimensions) are a typical
     * setup, so this is not a theory.
     */
    private record CacheKey(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                            UUID networkId) {
    }

    private static final Map<CacheKey, VeloceCraftingCache> CACHES = new HashMap<>();

    private static CacheKey keyOf(ServerLevel level, UUID networkId) {
        return new CacheKey(level.dimension(), networkId);
    }

    /**
     * The network this cache pertains to.
     *
     * <p><b>It is NOT final and that is deliberate.</b> A network rebuild creates a
     * NEW {@link VelocePipeNetwork} object under the SAME UUID. If the cache held
     * the old reference, {@code maintainForcedChunks} would count chunks from a
     * network that no longer exists. Previously this was worked around by deleting
     * the cache on every rebuild - and deleting the cache releases its force-loads,
     * so EVERY rebuild unloaded and loaded the chunks all over again.
     */
    private volatile VelocePipeNetwork network;

    /** Chunks this network keeps loaded. */
    private final Set<Long> forcedChunks = new HashSet<>();

    private boolean forceLoadInitialized = false;

    /** Whether the grace period after world start has passed. */
    private boolean startupGracePassed = false;

    /**
     * The tick in which this session first touched the cache.
     *
     * <p>NOTE: {@code level.getGameTime()} after loading a save already has thousands
     * of ticks (the world has existed for a long time), so the startup grace period
     * cannot be based on it - the condition "gameTime < 200" is immediately false and
     * the grace period never works. The previous version had exactly this bug.
     * We therefore measure time from the first tick of this session.
     */
    private long firstSeenTick = -1;

    /** Last game tick in which we did a step. */
    private long lastTickedGameTime = Long.MIN_VALUE;

    /**
     * Whether the server is shutting down.
     *
     * <p>CRUCIAL for the world save. When the server is shutting down, Minecraft
     * tries to unload chunks. If our force-loads are still active, the chunk comes
     * back, is unloaded again, and so on in a loop - the world save hangs.
     *
     * <p>That is why on shutdown: we stop forcing AND release everything we
     * were holding.
     */
    private static volatile boolean shuttingDown = false;

    private VeloceCraftingCache(VelocePipeNetwork network) {
        this.network = network;
    }

    public static VeloceCraftingCache get(ServerLevel level, VelocePipeNetwork network) {
        VeloceCraftingCache cache = CACHES.computeIfAbsent(
                keyOf(level, network.getId()), id -> new VeloceCraftingCache(network));
        // The network may have been rebuilt under the same UUID - we refresh the
        // reference instead of deleting the cache (which would cost us force-loads).
        if (cache.network != network) {
            cache.network = network;
        }
        return cache;
    }

    /**
     * Removes a network's cache and <b>releases its force-loads</b>.
     *
     * <p>The previous version only removed the entry from the map. The cache then
     * kept its chunks in the global loader forever: the network disappeared, but the
     * chunks stayed forced until the end of the session. When networks are placed and
     * torn down (or on every change of the pipe layout) this accumulated.
     */
    public static void drop(ServerLevel level, UUID networkId) {
        VeloceCraftingCache cache = CACHES.remove(keyOf(level, networkId));
        if (cache != null) {
            cache.release(level);
        }
    }

    /**
     * Releases caches of networks that are not on the list of live ones.
     *
     * <p>We compute the network identifier from the component representative, so
     * splitting or merging networks produces a new UUID. The old cache then stayed in
     * the map forever - together with its force-loads, which were never released.
     *
     * @return how many stale caches were released
     */
    public static int retainOnly(ServerLevel level, java.util.Set<UUID> liveIds) {
        java.util.List<UUID> stale = new java.util.ArrayList<>();
        // Only caches of THIS dimension. Previously the loop went over all of them, so
        // reconciliation in the Overworld deleted the caches of networks in the Nether
        // (their UUIDs may be identical - see CacheKey).
        for (Map.Entry<CacheKey, VeloceCraftingCache> e : CACHES.entrySet()) {
            if (e.getKey().dimension().equals(level.dimension())
                    && !liveIds.contains(e.getKey().networkId())) {
                stale.add(e.getKey().networkId());
            }
        }
        for (UUID id : stale) {
            drop(level, id);
        }
        return stale.size();
    }

    /** Number of live caches - for leak detection. */
    public static int liveCount() {
        return CACHES.size();
    }

    /**
     * A work step when nobody is looking.
     *
     * <p>We then do EXACTLY one thing: keep force-loads of chunks with network
     * blocks. This must always work, because without it extractors and crafters stop
     * working when the player walks away.
     *
     * <p>Why a separate method: thanks to it the caller does not have to prepare
     * arguments ({@code getAllEnabledItems} copies the set of all craftable items,
     * {@code getPreferredRecipes} builds a map) only for {@code tick} to return on
     * the first statement.
     */
    public void tickIdle(ServerLevel level) {
        if (claimTick(level)) {
            return;
        }

        if (!startupGracePassed) {
            if (firstSeenTick < 0) {
                firstSeenTick = level.getGameTime();
            }
            if (level.getGameTime() >= firstSeenTick
                    && level.getGameTime() - firstSeenTick < STARTUP_GRACE_TICKS) {
                return;
            }
            startupGracePassed = true;
        }

        if (shuttingDown) {
            return;
        }

        // The BUG that used to be here: the condition `gameTime % 20 == 0` checked
        // EXACT equality with a multiple of 20. And tickIdle is called only once per
        // tick (claimTick), by whichever terminal happened to win - so if the terminal
        // calls on ticks 1, 6, 11, 16, 21..., it will NEVER hit a multiple of 20 and
        // chunk forcing will not happen EVEN ONCE.
        //
        // Symptom: "forced chunks: 0" even though the terminal is there and is detected
        // as a node - and without a force-load the terminal and crafter stop working
        // when the player walks away from the base.
        //
        // Measure the interval, not equality: this works regardless of the phase in
        // which the caller arrives.
        long now = level.getGameTime();
        if (!com.craftingveloce.util.VeloceTick.every(
                now, lastMaintainTick, FORCE_MAINTAIN_INTERVAL_TICKS)) {
            return;
        }
        lastMaintainTick = now;
        maintainForcedChunks(level);
        // Expired "hot" chunk tickets have to be removed, otherwise a chunk once
        // considered frequently used stayed forced forever.
        com.craftingveloce.network.pipe.VeloceChunkLoader.expireHotTickets(level);
    }

    /**
     * How often we maintain the force-loads.
     *
     * <p>Measured as an INTERVAL since the last time, not as equality with a multiple -
     * see the comment in {@link #tickIdle}.
     */
    private static final long FORCE_MAINTAIN_INTERVAL_TICKS = 20L;

    /** Tick of the last force-load maintenance. */
    private long lastMaintainTick = Long.MIN_VALUE;

    /**
     * The game tick in which we last did a work step.
     *
     * <p><b>Why.</b> This step used to be called by every terminal in the network
     * separately, every 5 ticks. With two terminals the cache performed two steps in
     * the same tick. The "once per tick" guard keeps it where it belongs, regardless
     * of the number of terminals.
     *
     * @return true when we have already worked in this tick (the caller should exit)
     */
    private boolean claimTick(ServerLevel level) {
        long now = level.getGameTime();
        if (now == lastTickedGameTime) {
            return true;
        }
        lastTickedGameTime = now;
        return false;
    }

    /**
     * Keeps chunks with network blocks loaded.
     *
     * <p>Thanks to this the crafter can work and the terminal can gather data even
     * when the player is far away. Without it the network "loses" its contents once
     * out of simulation range and the numbers in the GUI are stale.
     *
     * <p>Chunks are released when the network ceases to exist (see {@link #release}).
     */
    private void maintainForcedChunks(ServerLevel level) {
        if (shuttingDown) {
            return;
        }
        // Double guard. If shuttingDown and frozen ever diverged,
        // VeloceChunkLoader.retain() would not take the reference, but the loop
        // below would still add the chunk to forcedChunks - and a later release()
        // would then drop a reference BELONGING TO SOMEONE ELSE. A local condition
        // is cheaper than that class of errors.
        if (com.craftingveloce.network.pipe.VeloceChunkLoader.isFrozen()) {
            return;
        }

        // Map: chunk -> block that is the reason for keeping it (for the report).
        // The limit is already applied to CHUNKS in collectChunksToKeep.
        Map<Long, BlockPos> wanted = collectChunksToKeep(level);

        releaseUnwantedChunks(level, wanted.keySet());
        int added = retainWantedChunks(level, wanted);
        logForceLoad(added);
    }

    /** Name of the ticket owner for this network - visible in the report. */
    private String ticketOwner() {
        return "net:" + network.getId().toString().substring(0, 8);
    }

    /**
     * Chunks this network should keep in memory.
     *
     * <p><b>We keep ONLY chunks with nodes.</b> Previously we loaded every chunk in
     * which a PIPE stood - and that was a design error.
     *
     * <p>A pipe is an ordinary connector block. It has no block entity with logic
     * that must tick, and it loses nothing when its chunk drops out of simulation:
     * we keep the network connections in our OWN in-memory structures
     * ({@code VelocePipeNetwork.pipes}), not in the world. Unloading a chunk with a
     * pipe neither tears the network apart nor loses data.
     *
     * <p>What does have to be kept, however, are chunks with what actually WORKS:
     * a terminal, a crafter and an extractor. They have a block entity that stops
     * working without simulation - and that is exactly what this mechanism is for.
     *
     * <p>The effect of the previous version was visible to the naked eye: a pipe run
     * pulled far from the base, with a single barrel at the end, kept its whole chunk
     * loaded - even though there was nothing in that chunk requiring simulation.
     * With a long network (218 pipes in the log) this produced over a dozen chunks
     * forced "along the way", and without a single operation on items at that.
     *
     * <p>Nodes have absolute priority; the {@link #MAX_FORCED_CHUNKS} limit only
     * truncates extreme cases (a base with hundreds of nodes).
     */
    private Map<Long, BlockPos> collectChunksToKeep(ServerLevel level) {
        List<BlockPos> nodes = new java.util.ArrayList<>(network.getTerminals());
        nodes.sort(POSITION_ORDER);

        Map<Long, BlockPos> chosen = new LinkedHashMap<>();
        for (BlockPos p : nodes) {
            // DECORATIVE nodes (e.g. a frame) do not keep the chunk - see
            // VeloceNetworkNode.keepChunkLoaded. We check this AFTER the block,
            // because the block itself decides it, not the type list here.
            if (!keepsChunkLoaded(level, p)) {
                continue;
            }
            if (chosen.size() >= MAX_FORCED_CHUNKS) {
                VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                        "network %s has more than %d node chunk(s) - rest NOT force-loaded",
                        network.getId().toString().substring(0, 8), MAX_FORCED_CHUNKS);
                break;
            }
            chosen.putIfAbsent(chunkKeyOf(p), p);
        }
        return chosen;
    }

    /**
     * Whether the node at this position has its chunk kept.
     *
     * <p>An unreadable node (chunk unloaded) is treated as "keep": we cannot read
     * its decision then, and ceasing to keep it would make it impossible to ever
     * read it again.
     */
    private static boolean keepsChunkLoaded(ServerLevel level, BlockPos pos) {
        BlockState state = com.craftingveloce.network.pipe.VeloceChunkLoader
                .blockStateIfLoaded(level, pos);
        if (state == null) {
            return true;
        }
        return !(state.getBlock()
                instanceof com.craftingveloce.network.pipe.VeloceNetworkNode node)
                || node.keepChunkLoaded(state);
    }

    /** Chunk key for a block position. */
    private static long chunkKeyOf(BlockPos p) {
        return ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4);
    }

    /** Fixed position order - so that the result is repeatable. */
    private static final java.util.Comparator<BlockPos> POSITION_ORDER =
            java.util.Comparator.comparingInt((BlockPos p) -> p.getX())
                    .thenComparingInt((BlockPos p) -> p.getZ())
                    .thenComparingInt((BlockPos p) -> p.getY());

    /**
     * Releases what we no longer want OR what the loader no longer holds.
     *
     * <p>The second condition matters: after a world unload the loader releases its
     * chunks, while the cache still has them in its bookkeeping. Without this check it
     * would assume it already holds them and would NEVER force them again.
     * release() on a non-existent reference is a safe no-op.
     */
    private void releaseUnwantedChunks(ServerLevel level, Set<Long> wanted) {
        String owner = ticketOwner();
        for (long key : new HashSet<>(forcedChunks)) {
            if (!wanted.contains(key)
                    || !com.craftingveloce.network.pipe.VeloceChunkLoader.isHeld(level, key)) {
                com.craftingveloce.network.pipe.VeloceChunkLoader.release(level, key, owner);
                forcedChunks.remove(key);
            }
        }
    }

    /** Loads what is missing. Returns the number of newly forced chunks. */
    private int retainWantedChunks(ServerLevel level, Map<Long, BlockPos> wanted) {
        int added = 0;
        String owner = ticketOwner();
        for (Map.Entry<Long, BlockPos> e : wanted.entrySet()) {
            long key = e.getKey();
            // We report the ticket ALWAYS, not only on the first addition.
            //
            // retain() is idempotent for the same owner (one ticket per owner), and
            // reporting on every pass recreates the ticket if it disappeared - e.g.
            // after unloading one dimension, where the loader's bookkeeping is cleared
            // while the cache deliberately does not clear its own.
            com.craftingveloce.network.pipe.VeloceChunkLoader.retain(
                    level, key, owner,
                    com.craftingveloce.network.pipe.VeloceChunkLoader.Reason.NETWORK,
                    e.getValue());
            if (forcedChunks.add(key)) {
                added++;
            }
        }
        return added;
    }

    private void logForceLoad(int added) {
        if (!forceLoadInitialized) {
            forceLoadInitialized = true;
            VeloceLog.Network.success(VeloceLog.Side.SERVER,
                    "force-loaded %d chunk(s) for network (%d pipe(s))",
                    forcedChunks.size(), network.getPipes().size());
        } else if (added > 0) {
            VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                    "force-loaded %d more chunk(s), total %d", added, forcedChunks.size());
        }
    }

    /**
     * We are entering the world - forcing chunks is allowed again.
     *
     * <p><b>Without this there was a heavy, silent bug.</b> {@code releaseAll} set
     * {@code shuttingDown = true} and NOBODY ever reset it. In single-player it was
     * enough to quit to the menu and enter again (LevelEvent.Unload -> releaseAll)
     * for chunk force-loading to stop working UNTIL THE END OF THE SESSION:
     * extractors, crafters and terminals in distant chunks stopped being ticked,
     * and the numbers in the GUI stayed stale.
     */
    public static void onLevelLoaded() {
        shuttingDown = false;
        com.craftingveloce.network.pipe.VeloceChunkLoader.unfreeze();
    }

    /**
     * Unloading of a single world (e.g. leaving the Nether).
     *
     * <p><b>Deliberately does NOT set the "server is shutting down" flag.</b>
     * Previously this case went the same way as a server shutdown, so unloading one
     * dimension disabled force-loading for all the remaining ones - and nothing reset
     * it, because {@code LevelEvent.Load} for the Overworld would not fire again.
     *
     * <p>We do NOT clear the {@code forcedChunks} bookkeeping: CACHES are shared
     * across all dimensions, and {@code releaseAll} releases chunks of ONLY that one
     * world. Clearing it would mean that caches from other dimensions lose information
     * about chunks the loader still holds - and on the next
     * {@code maintainForcedChunks} they would count a second reference.
     * Reconciliation is now done by {@code maintainForcedChunks} itself through
     * {@code isHeld()}.
     */
    public static void onLevelUnloaded(ServerLevel level) {
        int released = com.craftingveloce.network.pipe.VeloceChunkLoader.appliedCount(level);
        // Caches of this dimension MUST disappear together with it.
        //
        // They hold a reference to the ServerLevel object, which ceases to exist after
        // quitting to the menu - and on re-entry a NEW object is created. The old cache
        // would then force chunks in a dead world.
        CACHES.keySet().removeIf(k -> k.dimension().equals(level.dimension()));
        com.craftingveloce.network.pipe.VeloceChunkLoader.releaseAll(level);
        VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                "level unloaded: released %d forced chunk(s), caches reconciled later", released);
    }

    /** Releases the force-loads of all networks. Called on server shutdown. */
    public static void releaseAll(ServerLevel level) {
        shuttingDown = true;
        // Freezes ALL force-loads, not just those from the cache. Without this the
        // emergency chunk reload when extracting an item (extractItem) bypassed the
        // guard and hung the world save.
        com.craftingveloce.network.pipe.VeloceChunkLoader.freeze();
        int released = 0;
        // Only THIS dimension: on server stop this method runs for every level in turn,
        // and previously the first call cleared the WHOLE map - so the remaining
        // dimensions no longer released anything on the cache side.
        java.util.List<CacheKey> mine = new java.util.ArrayList<>();
        for (Map.Entry<CacheKey, VeloceCraftingCache> e : CACHES.entrySet()) {
            if (e.getKey().dimension().equals(level.dimension())) {
                mine.add(e.getKey());
                released += e.getValue().forcedChunks.size();
                e.getValue().release(level);
            }
        }
        mine.forEach(CACHES::remove);
        // Cleanup at the loader level: this also catches chunks forced outside our own
        // sets (e.g. the emergency reload when extracting an item).
        com.craftingveloce.network.pipe.VeloceChunkLoader.releaseAll(level);
        VeloceLog.Network.success(VeloceLog.Side.SERVER,
                "server stopping: released %d forced chunk(s) across %d network(s)",
                released, mine.size());
    }

    /**
     * A maintenance step for ALL networks of this dimension.
     *
     * <p><b>The BUG this fixes.</b> {@code tickIdle} was called EXCLUSIVELY by the
     * terminal ({@code VeloceTomTerminalBlockEntity.tickCraftingCache}). A network
     * without a terminal - e.g. just a crafter with a furnace and chests - therefore
     * did not maintain its chunks EVEN ONCE: the crafter and the furnaces stopped
     * working when the player walked away, and the expiry of "hot" tickets did not
     * work at all. In other words, the automation failed in exactly the situation it
     * exists for.
     *
     * <p>Now the driver is the LEVEL TICK, so it works regardless of which blocks
     * stand in the network. The terminal no longer calls this itself - one driver,
     * one place.
     */
    public static void tickAll(ServerLevel level) {
        if (shuttingDown) {
            return;
        }
        com.craftingveloce.network.pipe.VelocePipeNetworkManager manager =
                com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(level);

        // A CACHE MUST BE CREATED FOR EVERY KNOWN NETWORK - otherwise there is nothing
        // to maintain and force-loads do not work AT ALL.
        //
        // The BUG this fixes (player report: "I cannot craft either glass or a
        // crushing wheel"; in the log "2 node(s) of the network could not be read
        // (chunk not loaded)"): after moving force-load maintenance from the terminal
        // to the level tick, all that was left was a loop over EXISTING caches, and
        // NOBODY created a cache anymore. The map was empty, so chunks with the
        // furnace, the crafter and the module machines unloaded when the player walked
        // away to the terminal - and then the network could not see them and nothing
        // was craftable, even though the GUI showed numbers computed earlier, while
        // the chunks were still loaded.
        java.util.Set<UUID> live = new java.util.HashSet<>();
        for (VelocePipeNetwork network : manager.knownNetworks()) {
            live.add(network.getId());
            get(level, network).tickIdle(level);

            // NO ENERGY STEP HERE - DELIBERATELY.
            //
            // There used to be one: FE was drawn from foreign sources into a buffer
            // kept on the network and then pushed to every terminal of the network.
            // That made our pipes an energy conduit between other mods' machines,
            // which is exactly what they must NOT be. Our cables are a carrier for
            // the Veloce network (item logistics) and nothing else: ZERO FE on a
            // Veloce cable.
            //
            // Consequence, by design: a machine is powered only by
            //   1. the energy ITEM in its own battery slot (see chargeFromItem), or
            //   2. a foreign energy cable attached DIRECTLY to the machine
            //      (the machine still exposes IEnergyStorage.receiveEnergy).
            // Nothing is routed through our network.
        }

        // Caches for networks that disappeared from the manager - release the chunks,
        // so they are not left forced until the end of the session.
        for (CacheKey key : new java.util.ArrayList<>(CACHES.keySet())) {
            if (!key.dimension().equals(level.dimension()) || live.contains(key.networkId())) {
                continue;
            }
            VeloceCraftingCache dead = CACHES.remove(key);
            if (dead != null) {
                dead.release(level);
            }
        }
    }

    /** Releases all chunks held by this network. */
    public void release(ServerLevel level) {
        String owner = ticketOwner();
        for (long key : forcedChunks) {
            com.craftingveloce.network.pipe.VeloceChunkLoader.release(level, key, owner);
        }
        VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                "released %d forced chunk(s) for network", forcedChunks.size());
        forcedChunks.clear();
    }
}
