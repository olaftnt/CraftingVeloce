package com.craftingveloce.network.pipe;

import com.craftingveloce.block.VelocePipeBlock;
import com.craftingveloce.block.entity.VelocePipeBlockEntity;
import com.craftingveloce.rs.RefinedStorageHelper;
import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

public class VelocePipeNetworkManager extends SavedData {

    private final Map<UUID, VelocePipeNetwork> networks = new HashMap<>();
    /** Diagnostics for the number cache: whether saving/loading happens at all. */
    private static final org.slf4j.Logger CACHE_LOG =
            org.slf4j.LoggerFactory.getLogger("craftingveloce-cache");

    /**
     * The number cache survived a restart, keyed by ITEM.
     *
     * <p><b>Why saving the cache in the network NBT was not enough.</b> Player:
     * "the cache still is not saved when I save the game". The network
     * identifier is NOT persistent - after loading the world the topology is
     * rebuilt and a network with a new UUID appears, while the cache saved
     * under the old one was left orphaned. That is why we keep it here: on save
     * we collect the numbers from ALL networks, and on load we hand them to
     * every new network as a starting point.
     * The first recount of the visible page will correct it anyway.
     */
    private final Map<Item, Long> persistedCraftable = new HashMap<>();
    private final Map<BlockPos, UUID> pipeToNetwork = new HashMap<>();
    private final Map<BlockPos, UUID> terminalToNetwork = new HashMap<>();

    /**
     * Pipes waiting for a network rebuild.
     *
     * <p><b>Why a queue.</b> {@code Block.neighborChanged} fires on
     * EVERY neighbour update - and in a base with Create, hoppers or redstone
     * that can easily be several times per tick. Previously every such event
     * immediately did a full network BFS plus invalidation of the cache of
     * every affected network. A machine standing next to a pipe was choking the
     * server with its own work.
     *
     * <p>Now we only collect positions and rebuild at most once per
     * {@link #REBUILD_COOLDOWN_TICKS} ticks, from the server tick.
     */
    private final Set<BlockPos> pendingRebuilds = new java.util.LinkedHashSet<>();

    /**
     * Flat structure of all pipes: position -> real connections.
     *
     * <p>This is the SOURCE OF TRUTH about what is connected to what. Networks
     * ({@link VelocePipeNetwork}) are only the RESULT of a query on this
     * structure - there is no merging or splitting of objects any more.
     *
     * <p>Thanks to this, cutting a network requires no guessing of the split:
     * the components separate by themselves, because they follow directly from
     * the connections between pipes.
     */
    private final VelocePipeWorld world = new VelocePipeWorld();

    /**
     * Persistent registry of storages: position -> storage information.
     *
     * <p><b>Why a separate one.</b> A storage can stand in an unloaded chunk,
     * so it cannot be read from the world - and yet we must remember that it
     * exists and what was inside it. This registry keeps such entries between
     * network rebuilds.
     *
     * <p>We update an entry when the chunk is loaded (fresh read), and when it
     * is not - we keep the last known contents. Since the chunk is not
     * simulated, nobody has touched those items, so the number is still true.
     */
    private final Map<BlockPos, ConnectedEndpointInfo> knownEndpoints = new HashMap<>();

    /**
     * Persistent registry of nodes (terminal, crafter, extractor, controller).
     *
     * <p>Same reason as {@code knownEndpoints}: the component cache freezes the
     * set of nodes at the moment of construction, so a node added later would
     * not be seen by the cached path. Here we keep them independently
     * and compute membership through adjacency to pipes.
     */
    private final Set<BlockPos> knownNodes = new HashSet<>();

    /** Tick of the last rebuild - for the spacing between them. */
    private long lastRebuildTick = Long.MIN_VALUE;

    /**
     * Time budget for the whole batch of rebuilds in one tick.
     *
     * <p>It must be SMALLER than the tick budget (50 ms), so that there is room
     * left for the rest of the game. A single rebuild has its own budget
     * ({@link #SCAN_BUDGET_NS}), but without a shared limit several rebuilds
     * in a row added up to more than a whole tick.
     */
    private static final long REBUILD_BATCH_BUDGET_NS = 25_000_000L;

    /** Minimum spacing between network rebuilds, in ticks (5 per second). */
    private static final int REBUILD_COOLDOWN_TICKS = 4;

    /**
     * How many positions we rebuild per cooldown.
     *
     * <p>Previously it was ONE position per 4 ticks, that is 5 per second. With
     * a base where a machine next to pipes spews updates, the queue grew faster
     * than it drained - and untangling it took minutes. So we keep a limited
     * rate, but without a multi-minute backlog.
     */
    private static final int MAX_REBUILDS_PER_TICK = 4;

    /**
     * Hard limit on the length of the rebuild queue.
     *
     * <p>Memory fuse: during an explosion that demolishes a huge network the
     * number of positions can be really large. Better to drop part of the
     * rebuilds (the next neighbour update will add them anyway) than to hold
     * tens of thousands of positions.
     */
    private static final int MAX_PENDING_REBUILDS = 512;

    /**
     * Time budget for ONE network BFS, in nanoseconds.
     *
     * <p>{@code scanAndBuildNetwork} walks over every pipe of the network,
     * and for each one calls {@code getBlockState} and {@code getBlockEntity}.
     * Without a limit a huge network blocked the server thread for an arbitrarily
     * long time - exactly the class of bug that already froze the server once
     * in this mod.
     */
    private static final long SCAN_BUDGET_NS = 20_000_000L;

    public VelocePipeNetworkManager() {
    }

    /**
     * Handles deferred rebuilds. Called from the server tick.
     *
     * <p>One rebuild per tick and no more often than every
     * {@link #REBUILD_COOLDOWN_TICKS} - thanks to this, even an avalanche of
     * neighbour updates ends with at most five rebuilds per second.
     */
    public void tick(ServerLevel level) {
        // RECONCILING THE NETWORK CACHE WITH LIVE COMPONENTS.
        //
        // It MUST be before the early return below: the layout of connections
        // changes also without a queued rebuild (merely placing a pipe changes
        // the components), and then caches would be left orphaned.
        //
        // The BUG this fixes: we compute the network identifier from the
        // component representative (the smallest position), so splitting or
        // merging a network produces a NEW UUID. The old cache (with its own
        // force-loads) was never released - its chunks stayed forced FOREVER.
        reconcileCaches(level);

        // CLEANUP OF DEAD STORAGES.
        //
        // knownEndpoints had NO removal of individual entries at all -
        // the only cleanup was overwriting the whole map when rebuilding the
        // world. There were two consequences:
        //   1. memory leak - an entry for every storage a pipe had ever touched
        //      stayed forever,
        //   2. A GHOST IN THE NETWORK - a destroyed chest still bordered a pipe,
        //      so toNetwork kept pulling it into the network with the LAST known
        //      numbers. The terminal showed items that no longer exist, and the
        //      player could not take them out (because there is no container in
        //      the world).
        // We do this once every 5 seconds and only for loaded chunks: for
        // unloaded ones it is impossible to check whether the block still
        // stands, and the entry with the last contents is a design assumption
        // there.
        long pruneNow = level.getGameTime();
        if (com.craftingveloce.util.VeloceTick.every(
                pruneNow, lastEndpointPruneTick, ENDPOINT_PRUNE_INTERVAL_TICKS)) {
            lastEndpointPruneTick = pruneNow;
            pruneDeadEndpoints(level);
        }

        if (VeloceChunkLoader.isFrozen()) {
            pendingRebuilds.clear();
            return;
        }
        if (pendingRebuilds.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        // `now >= lastRebuildTick` - with a rewound world time the difference
        // would be negative, so the "cooldown" check would be satisfied
        // ENDLESSLY and the network would never be rebuilt (pipes would stop
        // connecting).
        if (lastRebuildTick != Long.MIN_VALUE
                && now >= lastRebuildTick
                && now - lastRebuildTick < REBUILD_COOLDOWN_TICKS) {
            return;
        }
        lastRebuildTick = now;

        // TIME BUDGET FOR THE WHOLE BATCH.
        //
        // The BUG that was here: the loop could perform MAX_REBUILDS_PER_TICK
        // rebuilds, and EACH one has its own SCAN_BUDGET_NS budget (20 ms).
        // With four rebuilds that gave up to 80 ms of choking in a single tick -
        // that is, exceeding the whole tick budget (50 ms).
        //
        // Now the sum counts: we break the batch when it exceeds the safe
        // limit, and the rest waits for the next cooldown.
        long batchDeadline = System.nanoTime() + REBUILD_BATCH_BUDGET_NS;
        for (int done = 0; done < MAX_REBUILDS_PER_TICK; done++) {
            if (System.nanoTime() > batchDeadline) {
                break;
            }
            java.util.Iterator<BlockPos> it = pendingRebuilds.iterator();
            if (!it.hasNext()) {
                break;
            }
            BlockPos pos = it.next();
            it.remove();
            rebuildAt(level, pos);
        }
    }

    /**
     * Adds a position to the rebuild queue, enforcing its length.
     *
     * <p>One common entry point for all sources of requests (neighbour change,
     * broken pipe), so that the limit is enforced everywhere and not only where
     * someone remembered about it.
     */
    private void queueRebuild(BlockPos pos) {
        if (pendingRebuilds.size() >= MAX_PENDING_REBUILDS) {
            return;
        }
        pendingRebuilds.add(pos.immutable());
    }

    /**
     * Does a node block (terminal, extractor, crafter) actually connect to the
     * pipe from the {@code pipeSide} side?
     *
     * <p><b>Why this exists.</b> The terminal has a front that is NOT connected.
     * Both {@code scanAndBuildNetwork} and {@code VelocePipeBlock.canConnectFrom}
     * check this through {@code canConnectFrom}, but node registration when a
     * block is placed looked only at whether there is a pipe next to it.
     *
     * <p>Effect: a terminal placed with its front towards a pipe (visually
     * unconnected) was still registered in that network - its GUI showed and
     * extracted stock it should not have access to.
     *
     * @param pipeSide direction FROM the pipe TO the node (that is
     *                 {@code dir.getOpposite()} when iterating directions from
     *                 the node position)
     */
    public static boolean nodeConnectsToPipe(ServerLevel level, BlockPos nodePos, Direction pipeSide) {
        // Preventive guard: getBlockState on the server LOADS the chunk.
        // We do not know - so we do not cut it out (the same rule as in
        // hasOpenPipeAdjacent).
        BlockState state = VeloceChunkLoader.blockStateIfLoaded(level, nodePos);
        if (state == null) {
            return true;
        }
        // All knowledge about node types lives in VeloceNodeBlocks - here we
        // only pass the question on. Previously there was a hand-written
        // instanceof chain here that knew neither the controller nor furnaces,
        // which is why the controller got null from getNetworkForTerminal
        // (empty stock + "auto-crafting disabled" for all items).
        return VeloceNodeBlocks.connectsFrom(state, state.getBlock(), pipeSide);
    }

    /**
     * Synchronizes the flat structure with what stands in the world around a
     * given pipe.
     *
     * <p>We do this LOCALLY: we check the six neighbours of that one pipe and
     * nothing more. There is no BFS over the world here, so it does not depend
     * on which chunks are loaded - and that was the source of the bug in which
     * the network "cut off" at the border of an unloaded chunk.
     *
     * <p>Called when a pipe is placed, on a neighbour change and on destruction.
     * A repeated call for the same pipe is cheap, because
     * {@code setNeighbours} compares the set and does nothing when it has not
     * changed.
     */
    public void syncPipe(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof VelocePipeBlock pipe)) {
            world.removePipe(pos);
            return;
        }
        world.addPipe(pos);

        VelocePipeBlockEntity be = level.getBlockEntity(pos) instanceof VelocePipeBlockEntity p
                ? p : null;

        Set<BlockPos> neighbours = new HashSet<>();
        for (Direction d : Direction.values()) {
            // A closed side (wrench) = no connection in that direction.
            // It is exactly here that a closed side CUTS the network.
            if (be != null && be.isDisconnected(d)) {
                continue;
            }
            BlockPos np = pos.relative(d);
            if (!level.isLoaded(np)) {
                continue;
            }
            BlockState ns = level.getBlockState(np);
            if (!(ns.getBlock() instanceof VelocePipeBlock)) {
                continue;
            }
            // The neighbour must be open towards us - otherwise the connection
            // would be one-sided.
            if (level.getBlockEntity(np) instanceof VelocePipeBlockEntity other
                    && other.isDisconnected(d.getOpposite())) {
                continue;
            }
            world.addPipe(np);
            neighbours.add(np);
        }
        boolean changed = !neighbours.equals(world.neighbours(pos));
        world.setNeighbours(pos, neighbours);
        if (changed) {
            // The layout changed - the component description is out of date.
            // This is the ONLY place where we invalidate the cache: everything
            // else (placing a pipe, breaking it, an explosion, the wrench) goes
            // through this method.
            world.invalidateComponent(pos);
        }
    }

    /**
     * Synchronizes the pipes around a given position - when a neighbour changed.
     *
     * <p>We check the position itself and its six neighbours. That is enough,
     * because the connection between two pipes depends ONLY on those two pipes.
     */
    public void syncAround(ServerLevel level, BlockPos pos) {
        syncPipe(level, pos);
        for (Direction d : Direction.values()) {
            syncPipe(level, pos.relative(d));
        }
    }

    /**
     * Builds a network description from a COMPONENT of the flat structure.
     *
     * <p><b>This is the place where a "network" stops being an object that
     * merges and splits.</b> There is no merging or splitting here: we take a
     * component (the set of pipes connected to each other, computed by
     * {@link VelocePipeWorld}) and read from it what it contains.
     *
     * <p>Thanks to this:
     * <ul>
     *   <li>connecting two networks = one bigger group of pipes,</li>
     *   <li>cutting = two smaller groups,</li>
     *   <li>nothing has to be guessed or moved.</li>
     * </ul>
     *
     * @param seed the pipe from which we start reading the component
     * @return the network description, or {@code null} when there is no pipe there
     */
    @Nullable
    public VelocePipeNetwork buildFromComponent(ServerLevel level, BlockPos seed) {
        if (!world.hasPipe(seed)) {
            // The structure may not be synchronized yet (e.g. after loading the
            // world) - we synchronize locally and try again.
            syncPipe(level, seed);
            if (!world.hasPipe(seed)) {
                return null;
            }
        }
        // COMPONENT DESCRIPTION CACHE.
        //
        // This is the place that saves the most. Without it every query
        // (extractor every 10 ticks, opening the terminal, GUI read) walked the
        // world checking six directions from EVERY pipe. With 999 pipes
        // and three machines that is about 36,000 world calls per second.
        //
        // Now we compute the description once, and later questions get a ready
        // result.
        VelocePipeWorld.Component cached = world.cachedComponent(seed);
        if (cached != null) {
            return toNetwork(level, seed, cached);
        }

        Set<BlockPos> members = world.componentMembers(seed);
        if (members.isEmpty()) {
            return null;
        }

        // We take the network identifier from the component representative. The
        // same pipe layout gives the same identifier, so the cache and
        // force-loads survive successive rebuilds without any moving around.
        UUID id = world.componentOf(seed) != null
                ? VelocePipeWorld.componentId(world.componentOf(seed))
                : UUID.randomUUID();

        VelocePipeNetwork net = persistentNetwork(id, true);
        net.getPipes().addAll(members);

        // We read what stands next to the pipes: nodes (terminal/crafter/extractor)
        // and storages. No BFS over the world - only six directions from each
        // pipe, and we know the pipes from the component (including those from
        // unloaded chunks).
        for (BlockPos pipePos : members) {
            if (!level.isLoaded(pipePos)) {
                // A pipe in an unloaded chunk: we cannot read its neighbours,
                // but the pipe itself IS part of the network. So we keep what
                // we already know about it from the previous pass.
                preserveKnownNeighbours(level, net, pipePos);
                continue;
            }
            collectNeighbours(level, net, pipePos);
        }
        refreshInsertModes(level, net, members);
        net.updateTrackedChunks();

        // We save the description so that further queries are free.
        VelocePipeWorld.Component component = new VelocePipeWorld.Component();
        component.pipes.addAll(net.getPipes());
        component.nodes.addAll(net.getTerminals());
                component.storages.addAll(net.getEndpoints().keySet());
        component.energy.addAll(net.getEnergyEndpoints());
        component.builtAtTick = level.getGameTime();
        world.storeComponent(seed, component);

        return net;
    }

    /** Builds a network object from a saved component description (without touching the world). */
    /**
     * The network with the given UUID from the PERSISTENT store (or a new one,
     * if it does not exist yet).
     *
     * <p><b>The BUG this fixes (player report: "the controller does not save
     * preferred").</b> The "on demand" paths - the component cache
     * ({@link #toNetwork}) and the read from the world - built a NEW
     * {@code VelocePipeNetwork} object EVERY TIME. Everything that the network
     * holds, including the "furnace or crafting" preference set by right-clicking
     * the controller, landed in an object that immediately became garbage
     * (and never made it into the world save). The player set the preference,
     * the GUI showed it - because the client toggles it optimistically on its
     * side - and after reopening it went back to the default.
     *
     * <p>Now the SAME rule applies as in {@link #scanAndBuildNetwork}:
     * one UUID = ONE object. A rebuild only refreshes its contents,
     * so writes and reads (including from the packet and from the world save)
     * concern the same place.
     */
    private VelocePipeNetwork persistentNetwork(UUID id, boolean clearTopology) {
        VelocePipeNetwork net = networks.get(id);
        if (net == null) {
            net = new VelocePipeNetwork(id);
            networks.put(id, net);
        } else if (clearTopology) {
            net.getPipes().clear();
            net.getTerminals().clear();
            net.getEndpoints().clear();
        }
                VelocePipeNetwork seeded = networks.get(id);
        if (seeded != null && seeded.getCraftableMemo().isEmpty() && !persistedCraftable.isEmpty()) {
            // The cache loaded from the save (keyed by ITEM) is given to EVERY
            // network that does not have its own yet - including the one
            // REBUILT after loading the world. The log showed: load=44 items,
            // and 0 networks at that moment, so seeding only in load() did not
            // reach the rebuilt networks.
            seeded.rememberCraftable(persistedCraftable);
        }
return net;
    }

    private VelocePipeNetwork toNetwork(ServerLevel level, BlockPos seed, VelocePipeWorld.Component component) {
        BlockPos root = world.componentOf(seed);
        UUID id = root != null
                ? VelocePipeWorld.componentId(root)
                : UUID.randomUUID();
        // A PERSISTENT object (see persistentNetwork) - otherwise the preference
        // set in the controller would vanish together with this object.
        VelocePipeNetwork net = persistentNetwork(id, true);
        net.getPipes().addAll(component.pipes);

        // Nodes: just like storages - through adjacency to the pipes of this
        // component, and not from the frozen component.nodes. Otherwise a node
        // added after the cache was built would not be seen by the terminal.
        // We compute the component's pipe set ONCE. Previously it was created
        // in every iteration of the loop over nodes - with 1000 pipes and a few
        // nodes that is thousands of redundant insertions per single network
        // build.
        Set<BlockPos> componentPipes = new HashSet<>(component.pipes);

        Set<BlockPos> nodeCandidates = new HashSet<>(component.nodes);
        nodeCandidates.addAll(knownNodes);
        for (BlockPos n : nodeCandidates) {
            // The same condition as for storages: mere proximity to a pipe does
            // not mean the connection exists. A side set to "Disconnected" does
            // not pull the node into the network - just as it does not pull in a
            // storage.
            if ((component.nodes.contains(n) || touchesAnyPipe(n, componentPipes))
                    && hasOpenPipeAdjacent(level, n, componentPipes)) {
                net.getTerminals().add(n);
            }
        }

        // Storages: we determine membership through ADJACENCY to the pipes of
        // this component, not from the frozen component.storages set.
        //
        // The BUG that was here: component.storages is a snapshot from the
        // moment the cache was built. If a barrel was then in an unloaded chunk
        // (or the cache was built before it was placed), the set was EMPTY -
        // and forever. Effect: the pipe saw the storage (the
        // scanAndBuildNetwork path), but the TERMINAL did not (the cached
        // path), because toNetwork filtered the endpoint out.
        //
        // Now: an endpoint belongs to the component if it lies next to any of
        // its pipes. The cost is 6 checks per remembered storage - and there are
        // few of them (one per chest).
        Set<BlockPos> pipes = componentPipes;
        for (Map.Entry<BlockPos, ConnectedEndpointInfo> e : knownEndpoints.entrySet()) {
            BlockPos ep = e.getKey();
            // THE SECOND CONDITION IS NECESSARY, NOT DECORATIVE.
            //
            // Mere adjacency to a pipe is not enough: the pipe's side may be set
            // by the wrench to "Disconnected", and then there is NO connection.
            // Without this check the fix in collectNeighbours would achieve
            // nothing - this path (from the component cache) runs in practice
            // and would still pull the disconnected storage into the network,
            // with its contents.
            //
            // It is the same lesson as with the Pull mode: there are TWO ways of
            // building the network and both must know the side mode.
            if ((component.storages.contains(ep) || touchesAnyPipe(ep, pipes))
                    && hasOpenPipeAdjacent(level, ep, pipes)) {
                // Chunk loaded -> refresh, so that the numbers are up to date.
                // Unloaded -> the last known contents remain.
                e.getValue().refreshIfLoaded(level);
                net.getEndpoints().put(ep, e.getValue());
            }
        }
        // Side modes CANNOT be taken from the component description - they have
        // to be read from the pipes. Without this the Pull mode would not work
        // in practice.
        refreshInsertModes(level, net, pipes);
        net.updateTrackedChunks();
        net.clearEnergyEndpoints();
        for (BlockPos ep : component.energy) {
            net.addEnergyEndpoint(ep);
        }
        return net;
    }

    /** Does this position border any pipe from the given set. */
    private static boolean touchesAnyPipe(BlockPos pos, Set<BlockPos> pipes) {
        for (Direction d : Direction.values()) {
            if (pipes.contains(pos.relative(d))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Does the endpoint have a pipe next to it that is NOT disconnected from it.
     *
     * <p>It answers the question "does this connection exist at all" - and not
     * "is it allowed to insert into it" (the latter is settled by
     * {@code refreshInsertModes}, which also takes the Pull mode into account).
     *
     * <p>When the pipe stands in an unloaded chunk, we do NOT know what mode it
     * has - and then we answer "yes". We prefer to keep the storage visible
     * based on missing data rather than cut it out of the network based on a
     * guess.
     */
    private boolean hasOpenPipeAdjacent(ServerLevel level, BlockPos endpointPos, Set<BlockPos> pipes) {
        for (Direction d : Direction.values()) {
            BlockPos pipePos = endpointPos.relative(d);
            if (!pipes.contains(pipePos)) {
                continue;
            }
            if (!level.isLoaded(pipePos)) {
                return true;   // we do not know - so we do not cut it out
            }
            if (!(level.getBlockEntity(pipePos) instanceof VelocePipeBlockEntity be)) {
                return true;   // no block entity - not our business
            }
            // The side of the pipe FACING the endpoint is d.getOpposite().
            if (!be.isDisconnected(d.getOpposite())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keeps what we already know about a pipe from an unloaded chunk.
     *
     * <p>Without this the contents of storages standing far away would vanish
     * from the GUI after every network recomputation - because there is nowhere
     * to read it from.
     */
    private void preserveKnownNeighbours(ServerLevel level, VelocePipeNetwork net, BlockPos pipePos) {
        UUID owner = pipeToNetwork.get(pipePos);
        if (owner == null) {
            return;
        }
        VelocePipeNetwork old = networks.get(owner);
        if (old == null) {
            return;
        }
        for (Direction d : Direction.values()) {
            BlockPos np = pipePos.relative(d);
            if (net.getEndpoints().containsKey(np) || net.getTerminals().contains(np)) {
                continue;
            }
            ConnectedEndpointInfo ep = old.getEndpoints().get(np);
            if (ep != null) {
                net.getEndpoints().put(np, ep);
            }
            if (old.getTerminals().contains(np)) {
                net.getTerminals().add(np);
            }
        }
    }

    /**
     * Collects the nodes and storages standing next to one pipe.
     *
     * <p>This is the only place that touches the world while building the
     * network - and it is local: six directions from one pipe.
     */
    private void collectNeighbours(ServerLevel level, VelocePipeNetwork net, BlockPos pipePos) {
        // The mode of this pipe (wrench): disconnected sides and Pull mode. We
        // read it ONCE.
        //
        // blockEntityIfLoaded, not getBlockEntity: the caller already checks
        // isLoaded, but this method is too easy to call from a new
        // place - and getBlockEntity on the server WOULD LOAD the chunk.
        VelocePipeBlockEntity pipeBe = VeloceChunkLoader.blockEntityIfLoaded(level, pipePos)
                instanceof VelocePipeBlockEntity p ? p : null;

        for (Direction d : Direction.values()) {
            // DISCONNECTED SIDE = NO CONNECTION. Also for storages and nodes.
            //
            // The BUG this fixes: this condition was checked ONLY when
            // connecting pipe-to-pipe (in syncPipe), and NOT here. Effect: a
            // side set with the wrench to "Disconnected" disappeared from the
            // flat structure, but collectNeighbours still registered the storage
            // standing behind it - so the network still saw it, showed it and
            // could push to and pull from it. Pull mode worked, because it is
            // checked separately; Disconnected did not work at all.
            //
            // Previously the comment next to syncPipe said "it is exactly here
            // that a closed side CUTS the network" - and that was true only for
            // pipes.
            if (pipeBe != null && pipeBe.isDisconnected(d)) {
                continue;
            }
            BlockPos np = pipePos.relative(d);
            if (!level.isLoaded(np)) {
                // A STORAGE IN AN UNLOADED CHUNK.
                //
                // The BUG that was here: `continue` skipped it entirely, so
                // such a barrel did NOT make it into the network. Effect:
                // net.extractItem() did not see its contents, returned EMPTY -
                // and the log had neither "took" nor an error, only silence
                // after "requested".
                //
                // We cannot read it from the world (the chunk is not
                // simulated), but we KNOW it from an earlier pass or from NBT.
                // Since the chunk is unloaded, nobody has touched those items -
                // so the remembered contents are still true.
                ConnectedEndpointInfo known = knownEndpoints.get(np);
                if (known != null) {
                    net.getEndpoints().put(np, known);
                }
                continue;
            }
            BlockState ns = level.getBlockState(np);
            Block nb = ns.getBlock();

            // NODES - all through one place (VeloceNodeBlocks), so that the type
            // list cannot drift apart from nodeConnectsToPipe again.
            //
            // NOTE the direction: we are at the PIPE, so we go to the node
            // through `d`, and the node looks at the pipe through
            // `d.getOpposite()`.
            if (VeloceNodeBlocks.isNode(nb)
                    && VeloceNodeBlocks.connectsFrom(ns, nb, d.getOpposite())) {
                registerNode(net, np, nb, ns, d.getOpposite());
                continue;
            }

            if (com.craftingveloce.compat.VeloceMods.REFINED_STORAGE.isLoaded()
                && RefinedStorageHelper.hasRSNetwork(level, np, d.getOpposite())) {
                registerEndpoint(level, net, knownEndpoints, np, d.getOpposite(),
                        ConnectedEndpointInfo.Type.REFINED_STORAGE);
            } else if (VelocePipeBlock.canConnectToInventory(level, np, d.getOpposite())) {
                BlockPos canonical = getCanonicalInventoryPos(np, ns);
                registerEndpoint(level, net, knownEndpoints, canonical, d.getOpposite(),
                        ConnectedEndpointInfo.Type.INVENTORY);
            }
        }
    }

    /**
     * Registers a storage as a network endpoint, with information about the
     * pipe mode.
     *
     * <p><b>Merging the flag, not overwriting it.</b> The same storage can be
     * touched by several pipes (or several sides), each in a different mode.
     * Inserting is to be allowed when AT LEAST ONE side permits it, so the flag
     * from successive calls is combined with a logical OR. Overwriting would
     * mean that the whole storage is decided by the pipe visited last - that
     * is, the result would depend on the order of network construction.
     *
     * <p>On the first registration in a given pass the flag is SET (not merged),
     * because the endpoint may be the same object as in the previous build and
     * would then carry an old value.
     */
    private void registerEndpoint(ServerLevel level, VelocePipeNetwork net,
                                  Map<BlockPos, ConnectedEndpointInfo> known,
                                  BlockPos key, Direction accessSide,
                                  ConnectedEndpointInfo.Type type) {
        ConnectedEndpointInfo ep = net.getEndpoints().get(key);
        if (ep == null) {
            ep = new ConnectedEndpointInfo(key, accessSide, type);
            net.getEndpoints().put(key, ep);
            known.put(key, ep);
        }
        ep.refreshIfLoaded(level);
    }

    /**
     * Determines which storages the network may NOT insert into (Pull mode from
     * the wrench).
     *
     * <p><b>Why as a separate pass, after the whole network is assembled.</b>
     * The network is built in two ways: a full scan ({@code collectNeighbours})
     * or from the component cache ({@link #toNetwork}, which does not read the
     * world). If the mode were determined during the scan, the cache path would
     * never refresh it - and that is the path that runs in practice. Exactly the
     * same way the network's node list once drifted apart.
     *
     * <p>That is why we determine the mode HERE, once, for both ways - and we
     * look at ALL pipes touching the storage, because one chest can have
     * several.
     *
     * <p><b>The rule.</b> Inserting is allowed when AT LEAST ONE readable side
     * permits it. When no side can be read (chunk unloaded), we leave inserting
     * ENABLED - we prefer not to block based on missing data.
     */
    private void refreshInsertModes(ServerLevel level, VelocePipeNetwork net,
                                    Collection<BlockPos> pipes) {
        for (ConnectedEndpointInfo ep : net.getEndpoints().values()) {
            // RS endpoints do not have pipe sides next to them in that sense,
            // but checking them costs nothing and breaks nothing.
            boolean sawReadablePull = false;
            boolean pushAllowed = false;
            for (Direction d : Direction.values()) {
                BlockPos pipePos = ep.getPos().relative(d);
                if (!pipes.contains(pipePos) || !level.isLoaded(pipePos)) {
                    continue;
                }
                if (!(level.getBlockEntity(pipePos) instanceof VelocePipeBlockEntity be)) {
                    continue;
                }
                // A DISCONNECTED SIDE IS NOT A CONNECTION - so it cannot grant
                // the right to insert either. The endpoint itself usually will
                // not end up here at all (collectNeighbours skips disconnected
                // sides), but the same storage may be touched by the SECOND side
                // of the same pipe.
                if (be.isDisconnected(d.getOpposite())) {
                    continue;
                }
                // The side of the pipe FACING the storage is d.getOpposite().
                if (be.isExtracting(d.getOpposite())) {
                    sawReadablePull = true;
                } else {
                    pushAllowed = true;
                    break;
                }
            }
            ep.resetAcceptsInsert(pushAllowed || !sawReadablePull);
        }
    }

    /**
     * Registers a node in the network.
     *
     * <p>Every node goes into {@code terminals} (that is the list of things that
     * have to be simulated - see {@code VeloceCraftingCache.collectChunksToKeep},
     * which takes the chunks to force-load from it) and into
     * {@code knownNodes} (so that it does not vanish from the network when its
     * chunk drops out).
     *
     * <p>A crafter additionally exposes its buffer as a network endpoint - that
     * is where the crafting output lands.
     */
    private void registerNode(VelocePipeNetwork net, BlockPos pos, Block block,
                              net.minecraft.world.level.block.state.BlockState state,
                              Direction towardPipe) {
        net.getTerminals().add(pos);
        knownNodes.add(pos.immutable());
        if (block instanceof VeloceNetworkNode node
                && node.exposesCraftingBuffer(state)) {
            net.getEndpoints().put(pos, new CraftingBufferEndpoint(pos, towardPipe));
        }
    }

    /** How often we prune dead storages from the registry (5 seconds). */
    private static final long ENDPOINT_PRUNE_INTERVAL_TICKS = 100L;

    private long lastEndpointPruneTick = Long.MIN_VALUE;

    /**
     * Removes from the registry storages that no longer exist in the world.
     *
     * <p>We check ONLY loaded chunks. For an unloaded chunk the world cannot be
     * read, and the whole idea of the registry is that it remembers the contents
     * of storages outside simulation - so such an entry stays.
     *
     * <p>We check with the SAME condition that decides about registration
     * ({@code canConnectToInventory} / {@code hasRSNetwork}). A different
     * condition would mean that an entry could be at the same time "too old"
     * and "too new" - depending on who asks.
     *
     * <p>Entries of type {@code CRAFTING_BUFFER} are crafter buffers that are
     * NOT storages in the world - that is why they have their own condition
     * (the crafter still stands) instead of an inventory test.
     */
    private void pruneDeadEndpoints(ServerLevel level) {
        if (knownEndpoints.isEmpty()) {
            return;
        }
        int removed = 0;
        java.util.Iterator<Map.Entry<BlockPos, ConnectedEndpointInfo>> it =
                knownEndpoints.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, ConnectedEndpointInfo> e = it.next();
            BlockPos pos = e.getKey();
            if (!level.isLoaded(pos)) {
                continue;   // we do not know - we keep the last known contents
            }
            if (endpointStillExists(level, pos, e.getValue())) {
                continue;
            }
            it.remove();
            removed++;
        }
        if (removed > 0) {
            VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                    "pruned %d dead storage(s) from the known-endpoint registry", removed);
        }
    }

    /**
     * Does the storage behind this entry still exist in the world.
     *
     * <p><b>The condition is deliberately CONSERVATIVE.</b> We do not ask "can a
     * pipe still be connected here" (as on registration), because that answer
     * depends on the side and on the block type - and a mistake in that
     * direction would cost us DELETING a live storage from the network: the
     * terminal would stop seeing a chest that is standing there. We prefer not
     * to remove rather than to remove too much.
     *
     * <p>So we remove only when there is really nothing there: air, or a block
     * without a block entity and without storage capability. A chest turned into
     * stone drops out; a chest still standing stays.
     */
    private static boolean endpointStillExists(ServerLevel level, BlockPos pos,
                                               ConnectedEndpointInfo ep) {
        // Internal guard (the caller checks too): without it, reading the block
        // in an unloaded chunk would load it, and this method runs in a loop
        // over all remembered storages of the network.
        if (!level.isLoaded(pos)) {
            return true;   // we do not know - we keep it (conservative rule)
        }
        return switch (ep.getType()) {
            // A foreign energy source: it exists as long as there is a block
            // with the Forge Energy capability (Energy Cube, generator) here.
            case ENERGY -> level.getBlockState(pos).isAir() == false
                    && level.getCapability(
                            net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                            pos, ep.getAccessSide()) != null;
            case REFINED_STORAGE ->
                    RefinedStorageHelper.hasRSNetwork(level, pos, ep.getAccessSide());
            // A crafter buffer is not a storage in the world - we ask about the
            // SAME rule as on registration (the block STATE decides about the
            // buffer). The block type alone is not enough: after swapping the
            // frame for a machine (or back) a storage ghost would remain.
            case CRAFTING_BUFFER -> {
                net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                yield state.getBlock() instanceof VeloceNetworkNode node
                        && node.exposesCraftingBuffer(state);
            }
            case INVENTORY -> {
                net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                if (state.isAir()) {
                    yield false;
                }
                net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
                yield be != null
                        || net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK
                        .getCapability(level, pos, state, null, ep.getAccessSide()) != null;
            }
        };
    }

    /**
     * Releases orphaned force-loads: chunks that are forced but nobody watches.
     *
     * <p>Minecraft saves {@code setChunkForced} PERSISTENTLY in the world data,
     * while our bookkeeping lives only in memory. An older version of the code
     * forced the chunk of every pipe - so after a restart there were chunks kept
     * forever, even though our report showed zero.
     *
     * @return how many orphans were released
     */
    public int releaseOrphanForceLoads(ServerLevel level) {
        Set<Long> tracked = new HashSet<>();
        for (VelocePipeNetwork net : networks.values()) {
            for (var cp : net.getTrackedChunks()) {
                tracked.add(ChunkPos.asLong(cp.x, cp.z));
            }
        }
        return VeloceChunkLoader.releaseOrphans(level, tracked);
    }

    /**
     * Release the cache of networks whose components no longer exist.
     *
     * <p>Called when the connection layout changes. Without this, every split or
     * merge of a network left an orphaned cache with its force-loads.
     */
    private void reconcileCaches(ServerLevel level) {
        long version = world.version();
        if (version == lastReconciledVersion) {
            return;   // the layout did not change - nothing to do
        }
        lastReconciledVersion = version;
        // A change of the connection layout - and therefore also a chunk reload
        // after which some storages disappeared or came back - changes the
        // numbers. We drop the cache of all live networks; the first count of
        // the visible page will fill it again right away.
        for (VelocePipeNetwork net : networks.values()) {
            net.clearCraftableMemo();
        }

        Set<UUID> live = new HashSet<>();
        for (BlockPos root : world.componentRoots()) {
            live.add(VelocePipeWorld.componentId(root));
        }
        int dropped = com.craftingveloce.crafting.VeloceCraftingCache
                .retainOnly(level, live);
        if (dropped > 0) {
            VeloceLog.Network.success(VeloceLog.Side.SERVER,
                    "reconciled network caches: released %d stale one(s) (with their force-loads)",
                    dropped);
        }
    }

    /** The layout version at which we last reconciled the caches. */
    private long lastReconciledVersion = Long.MIN_VALUE;

    /** Releases the queue on world shutdown/unload. */
    public void clearPendingRebuilds() {
        pendingRebuilds.clear();
    }

    /** How many rebuilds are waiting in the queue (diagnostics). */
    public int pendingRebuildCount() {
        return pendingRebuilds.size();
    }

    public static SavedData.Factory<VelocePipeNetworkManager> factory() {
        return new SavedData.Factory<>(
                VelocePipeNetworkManager::new,
                VelocePipeNetworkManager::load,
                null
        );
    }

    public static VelocePipeNetworkManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(factory(), "veloce_pipe_networks");
    }

    /** Flat pipe structure - the source of truth about connections. */
    public VelocePipeWorld getWorld() {
        return world;
    }

    public VelocePipeNetwork getNetworkById(UUID id) {
        return networks.get(id);
    }

    /**
     * Is there a Veloce pipe at this position - a CHEAP check.
     *
     * <p><b>Why separate from {@link #getNetworkForPipe}.</b> This is called in
     * a loop over all explosion blocks, so it must be O(1). Building a network
     * for every block would kill the tick.
     *
     * <p><b>Why not the {@code pipeToNetwork} map.</b> The BUG that was here:
     * the destruction and explosion handlers asked about the network through a
     * map filled by {@code scanAndBuildNetwork} - and that one, with 1100 pipes,
     * OFTEN exceeds the 20 ms budget and returns null (in the log: "exceeded
     * 20 ms budget after 302/482/664/746/844 pipe(s)"). The map was therefore
     * out of date and the destruction of a pipe could be MISSED - the flat world
     * kept a pipe that no longer exists, and the network looked connected
     * despite being cut.
     *
     * <p>Now we ask the flat structure, which is the source of truth and is
     * updated on every placement/neighbour change.
     */
    public boolean isPipe(ServerLevel level, BlockPos pos) {
        if (world.hasPipe(pos)) {
            return true;
        }
        // The structure may not know this pipe yet (e.g. placed at a moment when
        // it was not synchronized) - we check the world, if loaded.
        if (level.isLoaded(pos)
                && level.getBlockState(pos).getBlock() instanceof VelocePipeBlock) {
            syncPipe(level, pos);
            return world.hasPipe(pos);
        }
        return false;
    }

    /**
     * The network containing this pipe - computed from the flat structure.
     *
     * <p>Used by diagnostics ({@code /cv debug} on a pipe), so that the report
     * shows EXACTLY the same thing the terminal sees. Previously this method
     * read the old map and could show a different state than the terminal -
     * which was misleading during diagnosis.
     */
    @Nullable
    public VelocePipeNetwork getNetworkForPipe(ServerLevel level, BlockPos pos) {
        if (!world.hasPipe(pos)) {
            if (!isPipe(level, pos)) {
                return null;
            }
        }
        return buildFromComponent(level, pos);
    }

    /**
     * The network this node belongs to.
     *
     * <p><b>How it works now.</b> There is no "assigning a node to a network"
     * and no maintaining of that relation. We take the first pipe next to the
     * node (on the side from which the node can really connect) and read the
     * WHOLE component that pipe belongs to - {@link #buildFromComponent}.
     *
     * <p>Thanks to this, the relation "node belongs to network" is COMPUTED
     * rather than stored. There is nothing to synchronize or lose when
     * connecting and cutting - if the pipes are connected, the node sees the
     * whole group, and if you cut them, it sees only its part.
     */
    @Nullable
    /**
     * Drops the cache of "how much can still be crafted" numbers for this
     * node's network.
     *
     * <p>Point 3/5: placing or removing a storage/machine changes the result,
     * so the old cache would have to lie. We drop it - the client immediately
     * gets an empty snapshot, and the first count of the visible page fills it
     * right away.
     */
    /**
     * Marks the network data as dirty, that is, TO BE SAVED.
     *
     * <p>The BUG this fixes (player report: "I leave the game, come back
     * and everything is generated from scratch, nothing is in the cache"):
     * we save the number cache in save(), but SavedData only saves entries
     * marked as dirty (setDirty). The cache changed without any topology
     * change, so it was never saved.
     */
    public void markDirty() {
        setDirty();
    }

    public void clearCraftableMemo(ServerLevel level, BlockPos pos) {
        VelocePipeNetwork network = getNetworkForTerminal(level, pos);
        if (network != null) {
            network.clearCraftableMemo();
        }
    }

    public VelocePipeNetwork getNetworkForTerminal(ServerLevel level, BlockPos terminalPos) {
        // We look for a pipe next to the node, on the side from which a
        // connection is possible at all (the terminal has a front that does not
        // connect).
        for (Direction d : Direction.values()) {
            BlockPos pipePos = terminalPos.relative(d);
            if (!nodeConnectsToPipe(level, terminalPos, d)) {
                continue;
            }
            // The pipe may be in an unloaded chunk - then we synchronize it from
            // the saved state, not from the world.
            if (!world.hasPipe(pipePos) && !level.isLoaded(pipePos)) {
                continue;
            }
            if (!world.hasPipe(pipePos)) {
                syncPipe(level, pipePos);
            }
            if (!world.hasPipe(pipePos)) {
                continue;
            }
            VelocePipeNetwork net = buildFromComponent(level, pipePos);
            if (net != null) {
                return net;
            }
        }
        return null;
    }

    /**
     * All networks - COMPUTED from the flat structure.
     *
     * <p><b>Why not from the {@code networks} map.</b> That map is filled by
     * {@code scanAndBuildNetwork}, which with 1100 pipes often exceeds the
     * budget and returns null. Reports therefore showed a different state than
     * the terminal (which counts from the flat structure) - which was misleading
     * during diagnosis and hid real bugs.
     *
     * <p>The result is cached for one game tick, so that several reads in the
     * same command do not build the networks from scratch.
     */
    /**
     * Networks ALREADY BUILT in this dimension - without rebuilding from the
     * world.
     *
     * <p>Used by force-load maintenance ({@code VeloceCraftingCache
     * .tickAll}): that runs every tick, so it cannot afford to rebuild
     * networks, and at the same time it must know which networks exist -
     * otherwise there is no cache for them and chunks with machines unload.
     */
    public Collection<VelocePipeNetwork> knownNetworks() {
        return new java.util.ArrayList<>(networks.values());
    }

    public Collection<VelocePipeNetwork> getAllNetworks(ServerLevel level) {
        long now = level.getGameTime();
        if (derivedNetworks != null && derivedNetworksTick == now) {
            return derivedNetworks;
        }
        java.util.List<VelocePipeNetwork> out = new java.util.ArrayList<>();
        for (BlockPos root : world.componentRoots()) {
            VelocePipeNetwork net = buildFromComponent(level, root);
            if (net != null) {
                out.add(net);
            }
        }
        derivedNetworks = java.util.Collections.unmodifiableList(out);
        derivedNetworksTick = now;
        return derivedNetworks;
    }

    /** Cache of the network list computed from the flat structure. */
    private java.util.List<VelocePipeNetwork> derivedNetworks;
    private long derivedNetworksTick = Long.MIN_VALUE;

    public void rebuildAt(ServerLevel level, BlockPos startPos) {
        if (!level.isLoaded(startPos)) return;

        BlockState state = level.getBlockState(startPos);
        if (state.getBlock() instanceof VelocePipeBlock) {
            UUID existingId = pipeToNetwork.get(startPos);
            scanAndBuildNetwork(level, startPos, existingId);
        }
    }

    public void onPipePlaced(ServerLevel level, BlockPos pos) {
        // Structure first, rebuild after - otherwise the rebuild would not know
        // about the new pipe.
        syncAround(level, pos);
        rebuildAt(level, pos);
    }

    /**
     * Reaction to a chunk being loaded or unloaded.
     *
     * <p>When a chunk with network blocks enters or leaves simulation, the stock
     * of that network changes abruptly (chest contents are no longer read, or
     * are read again). The craftability cache must be notified about this,
     * otherwise the GUI shows out-of-date numbers - and that was visible as
     * "the numbers drift apart after walking away from the base".
     *
     * <p>We only scan networks that actually touch that chunk.
     */
    public void onChunkChanged(ServerLevel level, ChunkPos chunkPos, boolean loaded) {
        // Loop detector: if this chunk comes back within a few ticks after
        // unloading, then someone is loading it in a loop. We then print the
        // STACK TRACE, so that the culprit is immediately visible.
        VeloceChunkLoader.noteChunkEvent(level, ChunkPos.asLong(chunkPos.x, chunkPos.z), loaded);
        // On world shutdown/save we do nothing - it is all over in a moment
        // anyway. Without this, every chunk load/unload cycle produced a log
        // entry; in one world shutdown 8000+ lines accumulated, which by itself
        // choked the save.
        if (VeloceChunkLoader.isFrozen()) {
            return;
        }

        // WHEN A CHUNK LOADS: refresh the storages standing in it.
        //
        // This is the only moment when we can read the REAL state of the chest.
        // Without it the endpoint would wait for an occasional throttled scan,
        // and during that time the terminal would show stale numbers.
        //
        // On UNLOAD we do nothing - and that is deliberate: since the chunk is
        // not simulated, nobody has touched those items, so the last known
        // contents are still true. Thanks to this the terminal sees the contents
        // of a chest in an unloaded chunk.
        if (loaded) {
            for (Map.Entry<BlockPos, ConnectedEndpointInfo> e : knownEndpoints.entrySet()) {
                if (isInChunk(e.getKey(), chunkPos)) {
                    e.getValue().refreshIfLoaded(level);
                }
            }
        }

        // Reporting - only when the monitor is enabled or tracing is active.
        boolean wantDetails = com.craftingveloce.debug.ChunkDebugNotifier.isEnabled();
        if (!wantDetails && !com.craftingveloce.debug.ChunkTrace.isEnabled()) {
            return;
        }

        java.util.List<String> affectedThings = wantDetails
                ? new java.util.ArrayList<>()
                : java.util.List.of();
        int affected = 0;
        for (BlockPos p : knownNodes) {
            if (isInChunk(p, chunkPos)) {
                affected++;
                if (wantDetails) {
                    // On UNLOAD we do not read the world - reading a block in an
                    // unloaded chunk would load it back (see describeBlock). We
                    // take the description from what we know.
                    affectedThings.add(loaded
                            ? describeBlock(level, p)
                            : "(just unloaded) @ " + p.toShortString());
                }
            }
        }
        for (BlockPos p : knownEndpoints.keySet()) {
            if (isInChunk(p, chunkPos)) {
                affected++;
                if (wantDetails) {
                    // On UNLOAD we do not read the world - reading a block in an
                    // unloaded chunk would load it back (see describeBlock). We
                    // take the description from what we know.
                    affectedThings.add(loaded
                            ? describeBlock(level, p)
                            : "(just unloaded) @ " + p.toShortString());
                }
            }
        }
        if (affected == 0) {
            return;
        }

        // NOTE: the description MUST match what we do. Previously it said
        // "cache invalidated", even though nothing was being invalidated - and
        // that was misleading during diagnosis (we were looking for an
        // invalidation that did not happen).
        com.craftingveloce.util.VeloceLog.Network.detail(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "chunk %s %s: %d network element(s) %s",
                chunkPos, loaded ? "loaded" : "unloaded", affected,
                loaded ? "(refreshing storages)" : "(keeping last known contents)");

        com.craftingveloce.debug.ChunkTrace.event("CHUNK",
                "%s chunk[%d,%d] affects %d element(s)",
                loaded ? "LOAD" : "UNLOAD", chunkPos.x, chunkPos.z, affected);
        com.craftingveloce.debug.ChunkDebugNotifier.notifyChunkChange(
                level, chunkPos, loaded, affectedThings);
    }

    /**
     * Is the block in this chunk - without allocating a ChunkPos per block.
     *
     * <p>Previously there was {@code new ChunkPos(p).equals(chunkPos)} here, in
     * a loop over all terminals and endpoints of EVERY network, that is several
     * allocations per chunk event.
     */
    private static boolean isInChunk(BlockPos p, ChunkPos chunkPos) {
        return (p.getX() >> 4) == chunkPos.x && (p.getZ() >> 4) == chunkPos.z;
    }

    private static String describeBlock(ServerLevel level, BlockPos pos) {
        // We NEVER read the world for an unloaded chunk.
        //
        // ================================================================
        // The BUG this fixes (a REAL load/unload loop).
        //
        // This method is also called on the UNLOAD event - we then describe
        // blocks lying in the chunk that JUST unloaded. And on the server
        // `Level.getBlockEntity(pos)` does NOT return null for an unloaded
        // chunk: it LOADS it (it goes through
        // getChunk(x, z) -> ChunkStatus.FULL, requireChunk = true).
        //
        // The effect was exactly what the user reported: the chunk unloaded,
        // our own diagnostic message loaded it back, it unloaded again, and so
        // on in a loop - 10 times per second, endlessly. The chunk monitor was
        // therefore causing exactly what it was only supposed to observe.
        //
        // Neither the forcing counter (this is not a force-load) nor the
        // "loaded for the operation" counter (this is not our getChunk) saw it.
        // That is why the guard is here, at the source.
        // ================================================================
        if (!level.isLoaded(pos)) {
            return "(niezaladowany) @ " + pos.toShortString();
        }
        try {
            var be = level.getBlockEntity(pos);
            if (be != null) {
                String name = be.getClass().getSimpleName();
                if (name.endsWith("BlockEntity")) {
                    name = name.substring(0, name.length() - "BlockEntity".length());
                }
                return name + " @ " + pos.toShortString();
            }
            return level.getBlockState(pos).getBlock().getName().getString()
                    + " @ " + pos.toShortString();
        } catch (Throwable t) {
            return "? @ " + pos.toShortString();
        }
    }

    public void onPipeBroken(ServerLevel level, BlockPos pos) {
        // MOST IMPORTANT: removing a pipe from the structure cuts the network
        // immediately.
        //
        // There is no need to recompute anything or guess the split - the
        // components separate BY THEMSELVES, because they follow directly from
        // the connections between pipes. That is the whole advantage of the flat
        // structure: cutting is free and always correct.
        world.removePipe(pos);
        // The neighbours lose the connection with this pipe. syncPipe() also
        // invalidates the component description, so machines IMMEDIATELY stop
        // seeing the severed part of the network - without this the cache would
        // hold the old, connected layout and the terminal would show items from
        // a part that no longer exists.
        for (Direction d : Direction.values()) {
            BlockPos np = pos.relative(d);
            if (world.hasPipe(np)) {
                syncPipe(level, np);
            }
        }
        // The position itself could also have a remembered description.
        world.invalidateComponent(pos);

        UUID netId = pipeToNetwork.remove(pos);
        if (netId == null) return;

        VelocePipeNetwork oldNet = networks.get(netId);
        if (oldNet == null) return;

        oldNet.getPipes().remove(pos);

        List<BlockPos> remainingNeighbors = new ArrayList<>();
        for (Direction d : Direction.values()) {
            BlockPos np = pos.relative(d);
            if (oldNet.getPipes().contains(np)) {
                remainingNeighbors.add(np);
            }
        }

        // Remove old network references.
        // The cache MUST be released together with the network - otherwise it
        // holds its force-loads forever (see VeloceCraftingCache.drop).
        com.craftingveloce.crafting.VeloceCraftingCache.drop(level, netId);
        networks.remove(netId);
        for (BlockPos p : oldNet.getPipes()) {
            pipeToNetwork.remove(p);
        }
        for (BlockPos t : oldNet.getTerminals()) {
            terminalToNetwork.remove(t);
        }

        // Rebuild DEFERRED, not immediate.
        //
        // The BUG that was here: for EVERY surviving neighbour a full BFS
        // (scanAndBuildNetwork) ran immediately - without a budget, without a
        // limit on visited positions and without checking isFrozen(). And since
        // onPipeBroken is called per pipe from destroy(), from BreakEvent and -
        // worst of all - once per every touched pipe in the
        // ExplosionEvent.Detonate loop, demolishing a wall of K pipes cost up to
        // ~K*6 full traversals of the whole network in ONE tick. With a large
        // network and an explosion = server freeze.
        //
        // Now we only enqueue - the debounced tick does one rebuild per cooldown,
        // so the cost is spread over successive ticks.
        for (BlockPos np : remainingNeighbors) {
            if (!pipeToNetwork.containsKey(np) && level.isLoaded(np)
                    && level.getBlockState(np).getBlock() instanceof VelocePipeBlock) {
                queueRebuild(np);
            }
        }

        setDirty();
    }

    public void onTerminalPlaced(ServerLevel level, BlockPos terminalPos) {
        knownNodes.add(terminalPos.immutable());
        for (Direction d : Direction.values()) {
            BlockPos neighborPos = terminalPos.relative(d);
            UUID netId = pipeToNetwork.get(neighborPos);
            if (netId == null || !networks.containsKey(netId)) {
                continue;
            }
            // The direction must be the same as in scanAndBuildNetwork -
            // otherwise the node ended up in a network it is not connected to.
            if (!nodeConnectsToPipe(level, terminalPos, d)) {
                continue;
            }
            VelocePipeNetwork net = networks.get(netId);
            net.getTerminals().add(terminalPos);
            net.updateTrackedChunks();
            terminalToNetwork.put(terminalPos, netId);
            net.invalidateEndpointCache(level);
            setDirty();
            return;
        }
    }

    public void onTerminalRemoved(ServerLevel level, BlockPos terminalPos) {
        knownNodes.remove(terminalPos);
        UUID netId = terminalToNetwork.remove(terminalPos);
        if (netId != null) {
            VelocePipeNetwork net = networks.get(netId);
            if (net != null) {
                net.getTerminals().remove(terminalPos);
                // The node is gone - its data is no longer up to date. For
                // loaded chunks the cache will recompute; for unloaded ones the
                // last known contents remain (see invalidateCache).
                net.invalidateEndpointCache(level);
                net.updateTrackedChunks();
                setDirty();
            }
        }
    }

    /**
     * Reaction to a pipe neighbour change: an inventory being connected or
     * disconnected, a block next to it being placed or disappearing.
     *
     * <p>After the network rebuild the craftability cache has to be invalidated,
     * because the composition of storages changed. Without this the GUI would
     * show numbers from the previous layout - e.g. after disconnecting a chest
     * its contents are still visible.
     */
    public void onNeighborChanged(ServerLevel level, BlockPos pipePos, BlockPos neighborPos) {
        if (!level.isLoaded(pipePos)) return;
        // Structure first: a neighbour change may add a connection (a placed
        // pipe) or take one away (a closed side, a broken pipe). Without this
        // the network would not know about the change.
        syncAround(level, pipePos);
        // We DEFER the rebuild to the tick (see pendingRebuilds). Doing a full
        // BFS in every neighborChanged meant rebuilding the network several
        // times per tick when some machine was working next to it.
        queueRebuild(pipePos);

        // Invalidate the cache of the networks touched by this change. We search
        // by the pipe position and by the neighbour position - the change could
        // have added or removed an endpoint.
        Set<VelocePipeNetwork> touched = new HashSet<>();
        for (VelocePipeNetwork net : networks.values()) {
            if (net.getPipes().contains(pipePos)
                    || net.getEndpoints().containsKey(neighborPos)
                    || net.getTerminals().contains(neighborPos)) {
                touched.add(net);
            }
        }
        for (VelocePipeNetwork net : touched) {
            // The endpoint buffer could point at a removed chest - recompute from scratch.
            net.invalidateEndpointCache(level);
        }
        if (!touched.isEmpty()) {
            com.craftingveloce.util.VeloceLog.Network.detail(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "neighbor change at %s -> invalidated %d network(s)", neighborPos, touched.size());
        }
    }

    public static BlockPos getCanonicalInventoryPos(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock && state.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)) {
            net.minecraft.world.level.block.state.properties.ChestType type = state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE);
            if (type == net.minecraft.world.level.block.state.properties.ChestType.RIGHT) {
                return pos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
            }
        }
        return pos;
    }

    public VelocePipeNetwork scanAndBuildNetwork(ServerLevel level, BlockPos originPos, @Nullable UUID preferredId) {
        if (!level.isLoaded(originPos) || !(level.getBlockState(originPos).getBlock() instanceof VelocePipeBlock)) {
            return null;
        }
        // On world save/shutdown we do not build networks: it makes no sense,
        // and every getBlockEntity can pull in a chunk and hang the save.
        if (VeloceChunkLoader.isFrozen()) {
            return null;
        }

        // Time budget for the whole BFS. After it is exhausted we stop and do
        // NOT rebuild the network - the next neighbour update will report it
        // again. Better to have a temporarily out-of-date network layout than a
        // frozen tick.
        final long scanDeadline = System.nanoTime() + SCAN_BUDGET_NS;
        boolean budgetExceeded = false;

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visitedPipes = new HashSet<>();
        Set<BlockPos> discoveredTerminals = new HashSet<>();
        Map<BlockPos, ConnectedEndpointInfo> discoveredEndpoints = new HashMap<>();
        // Foreign energy sources (Energy Cube, generator) - a separate list,
        // because these are NOT item storages: we do not take anything out of
        // them with a pipe, our machines PULL power from them THEMSELVES.
        java.util.Set<BlockPos> discoveredEnergy = new java.util.HashSet<>();
        Set<UUID> intersectedOldNets = new HashSet<>();
        // Pipes that entered the network from unloaded chunks. Their block
        // entity is unavailable, so we do not know their closed sides.
        Set<BlockPos> unloadedPipes = new HashSet<>();

        queue.add(originPos);
        visitedPipes.add(originPos);

        while (!queue.isEmpty()) {
            // We check the time on EVERY step, not every N steps. Trying
            // "every 64" already made the budget dead once in this mod.
            if (System.nanoTime() > scanDeadline) {
                budgetExceeded = true;
                break;
            }
            BlockPos current = queue.poll();
            UUID oldNetAtPos = pipeToNetwork.get(current);
            if (oldNetAtPos != null) {
                intersectedOldNets.add(oldNetAtPos);
            }

            // Without loading the chunk: the BFS deliberately queues also pipes
            // from unloaded chunks, so getBlockEntity would load each of them
            // (and that is exactly the load/unload loop the user reported). For
            // an unloaded pipe we simply do not know its closed sides - and the
            // comment below assumes that anyway.
            BlockEntity curBE = VeloceChunkLoader.blockEntityIfLoaded(level, current);
            VelocePipeBlockEntity curPipeBE = (curBE instanceof VelocePipeBlockEntity p) ? p : null;

            for (Direction dir : Direction.values()) {
                if (curPipeBE != null && curPipeBE.isDisconnected(dir)) {
                    continue;
                }

                BlockPos neighborPos = current.relative(dir);
                if (!level.isLoaded(neighborPos)) {
                    // A PIPE IN AN UNLOADED CHUNK.
                    //
                    // The BUG that was here: we added such a pipe to
                    // visitedPipes, but did NOT put it into the BFS queue -
                    // so the search STOPPED at the chunk border. Pipes on the
                    // other side were not visited, and so they vanished from the
                    // network: sometimes a terminal was missing, sometimes a
                    // barrel, sometimes 86 pipes (913 instead of 999) -
                    // depending on which end the BFS started from and which
                    // chunks were loaded.
                    //
                    // The symptom in game: the network "sometimes sees" the
                    // storage and sometimes not.
                    //
                    // Now such a pipe IS queued, so that the traversal can go on.
                    //
                    // NOTE about closed sides: the "closed side" state lives in
                    // the block entity (NBT), and for an unloaded chunk there is
                    // nowhere to read it from. The pipe would therefore be
                    // treated as fully open and the BFS would pass through a
                    // closed connection. That is why we do NOT enter it as a
                    // branch: we queue it, but when we reach it, its neighbours
                    // are checked only if the block entity can be read.
                    UUID existingNetId = pipeToNetwork.get(neighborPos);
                    if (existingNetId != null) {
                        intersectedOldNets.add(existingNetId);
                    }
                    if (visitedPipes.add(neighborPos)) {
                        unloadedPipes.add(neighborPos);
                        queue.add(neighborPos);
                    }
                    continue;
                }

                BlockState neighborState = level.getBlockState(neighborPos);

                // 1. Neighbor is VelocePipeBlock
                if (neighborState.getBlock() instanceof VelocePipeBlock) {
                    BlockEntity nbe = level.getBlockEntity(neighborPos);
                    if (nbe instanceof VelocePipeBlockEntity otherPipe && otherPipe.isDisconnected(dir.getOpposite())) {
                        continue;
                    }
                    if (visitedPipes.add(neighborPos)) {
                        queue.add(neighborPos);
                    }
                    continue;
                }

                // 2. NETWORK NODE - terminal, extractor, crafter, controller,
                //    sensor, furnace or a node from a compat/* module.
                //
                // The BUG this fixes: this loop had THREE types HAND-WRITTEN
                // (terminal, extractor, crafter). The controller, sensor and
                // furnaces were not recognized here as nodes, so a full network
                // scan did not add them to the terminals - even though the
                // neighbouring, local path (collectNeighbours) already knew them.
                // Now both ask the same interface, so they cannot drift apart,
                // and a new node from compat/* works without a change in the core.
                if (neighborState.getBlock() instanceof VeloceNetworkNode node) {
                    if (node.canConnectFrom(neighborState, dir.getOpposite())) {
                        discoveredTerminals.add(neighborPos);
                        // The auto-crafter buffer is additionally a storage
                        // endpoint: thanks to this the production surplus (e.g.
                        // 3 planks from 1 log, when the player wanted 1) is
                        // visible to the whole network and can be taken out with
                        // a terminal, a pipe or a hopper.
                        if (node.exposesCraftingBuffer(neighborState)) {
                            discoveredEndpoints.put(neighborPos, new CraftingBufferEndpoint(
                                    neighborPos, dir.getOpposite()));
                        }
                    }
                    continue;
                }

                // 2b. A foreign block with Forge Energy (Energy Cube, generator).
                //
                // We SKIP our own blocks: machines are only receivers, so they
                // cannot be a source for each other (otherwise they would pull
                // power from one another). Our pipe does not expose
                // EnergyStorage, so nothing foreign can draw power from it -
                // the direction is one-way.
                if (!(neighborState.getBlock() instanceof VeloceNetworkNode)
                        && level.getCapability(
                                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                                neighborPos, dir.getOpposite()) != null) {
                    discoveredEnergy.add(neighborPos.immutable());
                }

                // 3. Neighbor is Refined Storage
                if (com.craftingveloce.compat.VeloceMods.REFINED_STORAGE.isLoaded()
                && RefinedStorageHelper.hasRSNetwork(level, neighborPos, dir.getOpposite())) {
                    ConnectedEndpointInfo ep = new ConnectedEndpointInfo(neighborPos, dir.getOpposite(), ConnectedEndpointInfo.Type.REFINED_STORAGE);
                    ep.refreshIfLoaded(level);
                    discoveredEndpoints.put(neighborPos, ep);
                    continue;
                }

                // 4. Neighbor is regular inventory
                if (VelocePipeBlock.canConnectToInventory(level, neighborPos, dir.getOpposite())) {
                    BlockPos canonicalPos = getCanonicalInventoryPos(neighborPos, neighborState);
                    ConnectedEndpointInfo ep = new ConnectedEndpointInfo(canonicalPos, dir.getOpposite(), ConnectedEndpointInfo.Type.INVENTORY);
                    ep.refreshIfLoaded(level);
                    discoveredEndpoints.put(canonicalPos, ep);
                    continue;
                }
            }
        }

        if (budgetExceeded) {
            // We do NOT build a network from an incomplete traversal.
            //
            // Continuing on partially collected data would be worse than
            // nothing: the resulting network would have fewer pipes than it
            // really has, and the merge loop below would delete old networks
            // deemed "vanished" - that is, we would break a working network just
            // because there was not enough time to count it in full. The next
            // neighbour update will report it again.
            VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                    "network scan at %s exceeded %d ms budget after %d pipe(s) - deferred",
                    originPos, SCAN_BUDGET_NS / 1_000_000L, visitedPipes.size());
            queueRebuild(originPos);
            return null;
        }

        VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                "built network at %s: %d pipe(s) (%d z niezaladowanych chunkow), "
                        + "%d node(s), %d storage(s)",
                originPos, visitedPipes.size(), unloadedPipes.size(),
                discoveredTerminals.size(), discoveredEndpoints.size());

        // We compute the ID of the resulting network BEFORE the merge loop -
        // needed so that (see below)
        // we know which old caches NOT to drop.
        //
        // A rebuild keeps the same UUID (preferredId = the existing network),
        // and its cache simply gets a refreshed reference to the new object.
        // Dropping it released force-loads, so every rebuild unloaded and loaded
        // chunks from scratch - even though the network is the same.
        // NETWORK IDENTITY - one of the absorbed networks is NOT destroyed.
        //
        // The BUG that was here: when two networks met, BOTH were dropped
        // (networks.remove + drop cache) and one new object was created.
        // Effects visible in game:
        //   - the caches and force-loads of both networks were lost, so a
        //     terminal standing far away lost the keeping of its chunk,
        //   - they could not be split back, because the information that there
        //     were TWO networks no longer existed,
        //   - when both had a terminal, one lost its identity.
        //
        // Now the continuity network (preferredId or the one the traversal
        // started from) is REBUILT in place: it keeps the UUID, and therefore
        // the cache and the force-loads. The remaining absorbed networks
        // disappear as separate entities, but their contents (pipes, nodes,
        // storages) are carried over.
        UUID finalId = preferredId != null
                ? preferredId
                : (intersectedOldNets.isEmpty()
                        ? UUID.randomUUID()
                        : preferredIdOf(intersectedOldNets, originPos));

        // Moving contents from the networks that were absorbed.
        for (UUID oldId : intersectedOldNets) {
            if (oldId.equals(finalId)) {
                continue;   // this one we rebuild, we do not move it
            }
            VelocePipeNetwork oldNet = networks.get(oldId);
            if (oldNet == null) {
                continue;
            }
            // Remembered numbers from unloaded storages - without this the
            // contents of a chest standing far away would vanish from the GUI.
            for (Map.Entry<BlockPos, ConnectedEndpointInfo> oldEp : oldNet.getEndpoints().entrySet()) {
                if (discoveredEndpoints.containsKey(oldEp.getKey())) {
                    ConnectedEndpointInfo newEp = discoveredEndpoints.get(oldEp.getKey());
                    if (newEp.getCachedCounts().isEmpty()
                            && !oldEp.getValue().getCachedCounts().isEmpty()) {
                        newEp.getCachedCounts().putAll(oldEp.getValue().getCachedCounts());
                    }
                } else if (!level.isLoaded(oldEp.getKey())) {
                    discoveredEndpoints.put(oldEp.getKey(), oldEp.getValue());
                }
            }
            // This network stops existing as a separate entity - we release its
            // force-loads, because its pipes and nodes move to finalId.
            com.craftingveloce.crafting.VeloceCraftingCache.drop(level, oldId);
            networks.remove(oldId);
            for (BlockPos p : oldNet.getPipes()) {
                pipeToNetwork.remove(p);
            }
            for (BlockPos t : oldNet.getTerminals()) {
                terminalToNetwork.remove(t);
            }
        }

        // Continuity network: we keep it if it exists. Then its cache and
        // force-loads are NOT touched (see VeloceCraftingCache.get, which only
        // refreshes the reference to the object under the same UUID).
        // The old contents will disappear - we build a new list from scratch,
        // but the object (and therefore the UUID, cache and preferences) stays
        // the same.
        VelocePipeNetwork newNet = persistentNetwork(finalId, true);
        newNet.getPipes().addAll(visitedPipes);
        newNet.getTerminals().addAll(discoveredTerminals);
        newNet.clearEnergyEndpoints();
        for (BlockPos energyPos : discoveredEnergy) {
            newNet.addEnergyEndpoint(energyPos);
        }
        newNet.getEndpoints().putAll(discoveredEndpoints);
        newNet.updateTrackedChunks();

        // KLUCZOWE: synchronizuj knownEndpoints z nowo odkrytymi.
        //
        // The BUG that was here: scanAndBuildNetwork built new endpoints (with
        // refreshIfLoaded when the chunk is loaded), but did NOT update
        // knownEndpoints. Effect: after teleporting next to a barrel (chunk
        // loaded -> rebuild with a correct read) and back to the start (chunk
        // unloaded) - the terminal asked buildFromComponent, which took from
        // knownEndpoints, but there was an old empty endpoint from the NBT load.
        // The terminal saw 0 types even though the barrel had items.
        knownNodes.addAll(discoveredTerminals);
        for (Map.Entry<BlockPos, ConnectedEndpointInfo> e : discoveredEndpoints.entrySet()) {
            if (level.isLoaded(e.getKey())) {
                // A fresh read from a loaded chunk - it replaces the old one.
                knownEndpoints.put(e.getKey(), e.getValue());
            } else {
                // Unloaded - keep it only if we have nothing better.
                knownEndpoints.putIfAbsent(e.getKey(), e.getValue());
            }
        }

        networks.put(finalId, newNet);
        for (BlockPos p : visitedPipes) {
            pipeToNetwork.put(p, finalId);
        }
        for (BlockPos t : discoveredTerminals) {
            terminalToNetwork.put(t, finalId);
        }

        // Clean up graph entries for networks that no longer exist.
        setDirty();
        return newNet;
    }

    /**
     * Chooses which of the absorbed networks keeps its identity.
     *
     * <p>We prefer the network containing the rebuild's starting position - that
     * is natural continuity: we rebuild the network "from this pipe". When there
     * is none, we take the first one deterministically (by UUID), so that the
     * result is repeatable between runs.
     */
    private UUID preferredIdOf(Set<UUID> candidates, BlockPos originPos) {
        UUID byOrigin = pipeToNetwork.get(originPos);
        if (byOrigin != null && candidates.contains(byOrigin)) {
            return byOrigin;
        }
        return candidates.stream().min(UUID::compareTo).orElseGet(UUID::randomUUID);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag netList = new ListTag();
        CompoundTag memoTag = new CompoundTag();
        for (VelocePipeNetwork net : networks.values()) {
            // The number cache travels together with the network into the save -
            // after a world restart the player immediately sees the numbers
            // instead of looking at empty digits.
            CompoundTag netTag = net.toNbt();
            net.saveCraftableMemo(netTag);
            netList.add(netTag);
            for (Map.Entry<Item, Long> e : net.getCraftableMemo().entrySet()) {
                persistedCraftable.put(e.getKey(), e.getValue());
            }
        }
        // A copy keyed by ITEM - it will survive the network rebuild after
        // loading the world.
        CompoundTag persisted = new CompoundTag();
        for (Map.Entry<Item, Long> e : persistedCraftable.entrySet()) {
            persisted.putLong(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(e.getKey()).toString(), e.getValue());
        }
        tag.put("CraftableByItem", persisted);
        CACHE_LOG.info("[Veloce][CACHE] save: networks={}, items in cache={}",
                networks.size(), persistedCraftable.size());
        tag.put("Networks", netList);
        return tag;
    }

    /**
     * Reconstructs the flat pipe structure from the saved networks.
     *
     * <p><b>Why.</b> The connection structure is NOT saved separately - and
     * rightly so, because it would duplicate the same data. The networks are
     * saved (with their pipe and node lists), and from them we reconstruct the
     * connections: for each pipe we look for neighbours in the same pipe set.
     *
     * <p>Thanks to this the structure is complete IMMEDIATELY after entering the
     * world, without waiting for the player to approach every pipe - and that
     * was necessary for the terminal to see the contents of storages standing in
     * unloaded chunks.
     *
     * <p>We determine the connections from the positions alone (distance 1 along
     * one axis), without touching the world. Closed sides (wrench) are not known
     * here - they will be taken into account on the first synchronization with
     * the world, when the chunk loads. Until then we treat the pipes as
     * connected, which is safer than considering them separate.
     */
    private void rebuildWorldFromNetworks() {
        world.clear();
        for (VelocePipeNetwork net : networks.values()) {
            for (BlockPos pipe : net.getPipes()) {
                world.addPipe(pipe);
            }
        }
        // Registry of known storages: from NBT we know about them together with
        // their contents, so after a world restart we immediately see the
        // storages in unloaded chunks - without waiting for the player to
        // approach them.
        knownEndpoints.clear();
        knownNodes.clear();
        for (VelocePipeNetwork net : networks.values()) {
            knownEndpoints.putAll(net.getEndpoints());
            knownNodes.addAll(net.getTerminals());
        }

        // Second phase: connections. A pipe connects to a neighbour if both are
        // in the same set and touch wall to wall.
        for (VelocePipeNetwork net : networks.values()) {
            for (BlockPos pipe : net.getPipes()) {
                Set<BlockPos> neighbours = new HashSet<>();
                for (Direction d : Direction.values()) {
                    BlockPos np = pipe.relative(d);
                    if (world.hasPipe(np)) {
                        neighbours.add(np);
                    }
                }
                world.setNeighbours(pipe, neighbours);
            }
        }
        VeloceLog.Network.success(VeloceLog.Side.SERVER,
                "rebuilt pipe structure: %d pipe(s), %d component(s)",
                world.pipeCount(), world.componentCount());
    }

    public static VelocePipeNetworkManager load(CompoundTag tag, HolderLookup.Provider provider) {
        VelocePipeNetworkManager manager = new VelocePipeNetworkManager();
        CompoundTag persisted = tag.getCompound("CraftableByItem");
        for (String key : persisted.getAllKeys()) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(key);
            if (id == null) {
                continue;
            }
            manager.persistedCraftable.put(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id),
                    persisted.getLong(key));
        }
        CACHE_LOG.info("[Veloce][CACHE] load: items in cache={}, networks={}",
                manager.persistedCraftable.size(), manager.networks.size());
        ListTag netList = tag.getList("Networks", Tag.TAG_COMPOUND);
        for (int i = 0; i < netList.size(); i++) {
            CompoundTag netTag = netList.getCompound(i);
            VelocePipeNetwork net = VelocePipeNetwork.fromNbt(netTag);
            net.restoreCraftableMemo(netTag);
            if (net.getCraftableMemo().isEmpty() && !manager.persistedCraftable.isEmpty()) {
                net.rememberCraftable(manager.persistedCraftable);
            }
            manager.networks.put(net.getId(), net);
            for (BlockPos p : net.getPipes()) {
                manager.pipeToNetwork.put(p, net.getId());
            }
            for (BlockPos t : net.getTerminals()) {
                manager.terminalToNetwork.put(t, net.getId());
            }
        }
        // The connection structure is complete right away - otherwise the
        // terminal would not see the contents of storages in unloaded chunks
        // until the player approaches every pipe.
        manager.rebuildWorldFromNetworks();
        return manager;
    }
}
