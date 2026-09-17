package com.craftingveloce.network.pipe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flat structure of all pipes in the world - the source of truth about connections.
 *
 * <p><b>Why this way and not "networks".</b> The previous approach treated a
 * network as an OBJECT that merges and splits. That was the source of all the
 * problems: when joining, two objects had to be deleted and a new one built
 * (identity lost, plus cache and force-loads), and when cutting, one had to
 * GUESS into which pieces it fell apart - and that required a BFS over loaded
 * chunks, so the result depended on where the player was standing.
 *
 * <p>Here there are no "networks" to merge. There is one flat map:
 * <b>pipe position -> set of neighbours it is really connected to</b>.
 * Everything else (whether two pipes are in the same network, what the terminal
 * sees, which chunks to keep) is the RESULT OF A QUERY on that structure, and
 * not a state that has to be kept consistent.
 *
 * <p><b>Thanks to that:</b>
 * <ul>
 *   <li>joining two networks is just two pipes that become neighbours - nothing
 *       has to be merged,</li>
 *   <li>cutting is removing a pipe (or closing a side) - the components split
 *       BY THEMSELVES, without guessing,</li>
 *   <li>pipe length does not matter: the data is in this map, not in the world,</li>
 *   <li>independent of chunks: a pipe in an unloaded chunk is still here.</li>
 * </ul>
 *
 * <p><b>Performance.</b> The component (the set of pipes connected to each
 * other) is computed with a BFS over the coordinates alone - without touching
 * the world, without chunks. The result is CACHED, so the search runs once per
 * layout change, and not on every read. For 1000 pipes that is ~6000 cheap
 * operations - and only when something in the layout actually changed.
 */
public final class VelocePipeWorld {

    /**
     * Neighbours of each pipe.
     *
     * <p>We keep ONLY real connections: the direction in which the pipe
     * connects to another pipe. If the side is closed (wrench) or there is no
     * pipe there, there is no entry. Thanks to that the BFS does not have to
     * filter anything.
     */
    private final Map<BlockPos, Set<BlockPos>> links = new HashMap<>();

    /** Pipes belonging to the mod - regardless of whether they have connections. */
    private final Set<BlockPos> allPipes = new HashSet<>();

    /**
     * Component cache: pipe -> component identifier (the "root" position).
     *
     * <p>A component is the set of pipes connected to each other. We identify
     * it by the position of one of its pipes (deterministically the smallest),
     * so that the result is reproducible between runs.
     */
    private final Map<BlockPos, BlockPos> componentOf = new HashMap<>();

    /** Pipes of a given component - the inverse of {@link #componentOf}. */
    private final Map<BlockPos, Set<BlockPos>> componentMembers = new HashMap<>();

    /** Whether the component cache is up to date. */
    private boolean componentsDirty = true;

    /**
     * Component description: what stands around its pipes.
     *
     * <p><b>Why a second cache level.</b> The pipe set alone is not enough -
     * machines also need to know which nodes (terminal, crafter, extractor) and
     * storages (chests, barrels) are connected to them. Reading that requires
     * reaching into the world ({@code getBlockState}, {@code getBlockEntity})
     * for every neighbour of every pipe.
     *
     * <p>Without this cache the extractor did that from scratch every 10 ticks:
     * with 999 pipes and three machines that gave about 36,000 world lookups per
     * second. Now the description is computed ONCE per layout change, and a read
     * is a single map lookup.
     *
     * <p>Entries for storages in unloaded chunks REMAIN - together with the last
     * known contents. Since the chunk is not simulated, nobody touched those
     * items, so the remembered number is still true.
     */
    public static final class Component {
        /** Pipes belonging to the component. */
        public final Set<BlockPos> pipes = new LinkedHashSet<>();
        /** Nodes: terminals, crafters, extractors. They keep their chunk permanently. */
        public final Set<BlockPos> nodes = new LinkedHashSet<>();
        /** Storages: chests, barrels, RS. Loaded on demand for the duration of an operation. */
        public final Set<BlockPos> storages = new LinkedHashSet<>();
        /** Crafters - additionally as production buffers. */
        public final Set<BlockPos> crafters = new LinkedHashSet<>();
        // NOTE: there is no "energy" set here any more - our cables never carry FE.
        /** When the description was built (gameTime) - for diagnostics. */
        public long builtAtTick;
    }

    /** Description cache: component representative -> description. */
    private final Map<BlockPos, Component> componentCache = new HashMap<>();

    // ------------------------------------------------------------------
    // Building the structure
    // ------------------------------------------------------------------

    /**
     * Adds a pipe. Without connections - those are set by {@link #setNeighbours}.
     *
     * <p>NOTE: it dirties the component cache ONLY when the pipe really was
     * added. This is called on every synchronization, also for pipes that are
     * already known.
     */
    public void addPipe(BlockPos pos) {
        if (allPipes.add(pos.immutable())) {
            componentsDirty = true;
            version++;
        }
    }

    /**
     * Removes a pipe together with all of its connections.
     *
     * <p>It dirties the cache ONLY when the pipe really was there - otherwise
     * every check of an empty neighbour (and {@code syncAround} checks six of
     * them) would wipe all computed components even though the layout did not
     * change.
     */
    public void removePipe(BlockPos pos) {
        boolean had = allPipes.remove(pos);
        boolean hadLinks = links.remove(pos) != null;
        // We also remove references from the neighbours - otherwise there would
        // be "dangling" edges to a pipe that no longer exists.
        boolean removedFromOthers = false;
        for (Set<BlockPos> neighbours : links.values()) {
            if (neighbours.remove(pos)) {
                removedFromOthers = true;
            }
        }
        if (had || hadLinks || removedFromOthers) {
            componentsDirty = true;
            version++;
        }
    }

    /** Whether this pipe is known to the structure. */
    public boolean hasPipe(BlockPos pos) {
        return allPipes.contains(pos);
    }

    /**
     * Sets the neighbours of a pipe - the ONE place where a connection is created.
     *
     * <p>The neighbours are exclusively pipes that are known to the structure and
     * that connect in the given direction (the side is not closed). The caller
     * determines this from the block state.
     */
    public void setNeighbours(BlockPos pos, Set<BlockPos> neighbours) {
        if (!allPipes.contains(pos)) {
            return;
        }
        Set<BlockPos> filtered = new HashSet<>();
        for (BlockPos n : neighbours) {
            if (allPipes.contains(n)) {
                filtered.add(n.immutable());
            }
        }
        Set<BlockPos> previous = links.get(pos);
        if (previous != null && previous.equals(filtered)) {
            return;   // no change - we do not invalidate the cache needlessly
        }
        links.put(pos.immutable(), filtered);
        componentsDirty = true;
        version++;
    }

    /** Adjacent pipes (directly connected). */
    public Set<BlockPos> neighbours(BlockPos pos) {
        Set<BlockPos> set = links.get(pos);
        return set == null ? Set.of() : set;
    }

    /** All known pipes. */
    public Set<BlockPos> allPipes() {
        return java.util.Collections.unmodifiableSet(allPipes);
    }

    /** Number of pipes. */
    public int pipeCount() {
        return allPipes.size();
    }

    // ------------------------------------------------------------------
    // Components (connected groups of pipes)
    // ------------------------------------------------------------------

    /**
     * The identifier of the component this pipe belongs to.
     *
     * <p>This is the answer to the question "which network is this pipe in". We
     * return the position of the representative, which is stable as long as the
     * layout does not change.
     *
     * @return the component representative, or {@code null} when the pipe does not exist
     */
    public BlockPos componentOf(BlockPos pos) {
        if (!allPipes.contains(pos)) {
            return null;
        }
        rebuildIfDirty();
        return componentOf.get(pos);
    }

    /** All pipes in the same component as the given one (including it). */
    public Set<BlockPos> componentMembers(BlockPos pos) {
        BlockPos root = componentOf(pos);
        if (root == null) {
            return Set.of();
        }
        Set<BlockPos> members = componentMembers.get(root);
        return members == null ? Set.of() : members;
    }

    /**
     * The description of the component containing this pipe - from the cache.
     *
     * <p>It returns a ready description or {@code null} when it has to be
     * computed. The caller ({@code VelocePipeNetworkManager}) decides how to
     * build it - this class does not touch the world, because it is independent
     * of it.
     */
    public Component cachedComponent(BlockPos pipe) {
        rebuildIfDirty();
        BlockPos root = componentOf(pipe);
        return root == null ? null : componentCache.get(root);
    }

    /** Stores a computed component description. */
    public void storeComponent(BlockPos pipe, Component component) {
        rebuildIfDirty();
        BlockPos root = componentOf(pipe);
        if (root != null && component != null) {
            componentCache.put(root, component);
        }
    }

    /** Removes a component description (when the layout changed). */
    public void invalidateComponent(BlockPos pipe) {
        rebuildIfDirty();
        BlockPos root = componentOf(pipe);
        if (root != null) {
            componentCache.remove(root);
        }
    }

    /** Number of cached descriptions - for diagnostics. */
    public int cachedComponentCount() {
        rebuildIfDirty();
        return componentCache.size();
    }

    /**
     * The representative of each component - to walk over all networks.
     *
     * <p>It returns one pipe from each connected group, so that the description
     * of every network can be built without repeating the same pipes.
     */
    public java.util.Collection<BlockPos> componentRoots() {
        rebuildIfDirty();
        return java.util.Collections.unmodifiableSet(componentMembers.keySet());
    }

    /** Number of components - for diagnostics. */
    public int componentCount() {
        rebuildIfDirty();
        return componentMembers.size();
    }

    /**
     * Recomputes the components if the layout changed.
     *
     * <p>This is the ONLY place where we walk the graph - and we do it only
     * once someone actually asks about a component. Thanks to that, placing 100
     * pipes in a row does not trigger 100 searches.
     */
    private void rebuildIfDirty() {
        if (!componentsDirty) {
            return;
        }
        componentsDirty = false;
        componentOf.clear();
        componentMembers.clear();
        componentCache.clear();

        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos start : allPipes) {
            if (visited.contains(start)) {
                continue;
            }
            // BFS over the coordinates alone - without touching the world.
            Set<BlockPos> group = new LinkedHashSet<>();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);
            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                group.add(current);
                for (BlockPos next : neighbours(current)) {
                    if (visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
            // Representative: the deterministically smallest position in the
            // group. Thanks to that the identifier is stable between runs.
            BlockPos root = group.stream().min(VelocePipeWorld::comparePositions).orElse(start);
            componentMembers.put(root, group);
            for (BlockPos p : group) {
                componentOf.put(p, root);
            }
        }
    }

    /**
     * The network identifier for the component with the given representative.
     *
     * <p>The ONE place where the UUID is computed - used both by network
     * building and by cache reconciliation. If these two places computed it
     * differently, the cache would not be matched to the network.
     */
    public static java.util.UUID componentId(BlockPos root) {
        return java.util.UUID.nameUUIDFromBytes(
                root.toShortString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Version number of the component layout.
     *
     * <p>It increases on EVERY change of the connection layout. The manager
     * compares it to know when to reconcile the network caches with the live
     * components - without it every network split produced a new identifier,
     * and the old cache kept its force-loads forever.
     */
    public long version() {
        rebuildIfDirty();
        return version;
    }

    private long version = 0L;

    /** Fixed position ordering - for choosing the representative. */
    private static int comparePositions(BlockPos a, BlockPos b) {
        int c = Integer.compare(a.getY(), b.getY());
        if (c != 0) {
            return c;
        }
        c = Integer.compare(a.getX(), b.getX());
        if (c != 0) {
            return c;
        }
        return Integer.compare(a.getZ(), b.getZ());
    }

    /**
     * Adjacent positions that COULD be a pipe - for scanning the world.
     *
     * <p>Used when detecting what stands next to a given pipe. It does not
     * require a loaded chunk if we ask about pipes already known to the
     * structure.
     */
    public static List<BlockPos> neighbourPositions(BlockPos pos) {
        List<BlockPos> out = new ArrayList<>(6);
        for (Direction d : Direction.values()) {
            out.add(pos.relative(d));
        }
        return out;
    }

    /** Clears everything (world unload). */
    public void clear() {
        links.clear();
        allPipes.clear();
        componentOf.clear();
        componentMembers.clear();
        componentCache.clear();
        componentsDirty = true;
        version++;
    }

    /** Forces recomputation of the components (after a change that did not set the flag). */
    public void invalidate() {
        componentsDirty = true;
        version++;
    }

    /** Whether the structure is empty. */
    public boolean isEmpty() {
        return allPipes.isEmpty();
    }
}
