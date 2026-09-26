package com.craftingveloce.network.pipe;

import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class VelocePipeNetwork {
    private final UUID id;
    private final Set<BlockPos> terminals = new HashSet<>();
    private final Map<BlockPos, ConnectedEndpointInfo> endpoints = new HashMap<>();

    // REMOVED: the network energy buffer.
    //
    // The network used to keep an EnergyStorage that the level tick filled from
    // foreign sources and then pushed to every terminal, i.e. our pipes conducted
    // Forge Energy between machines. They must not: a Veloce cable is a carrier for
    // the Veloce network (item logistics) only - ZERO FE travels on it. A machine is
    // powered by the energy item in its own battery slot, or by a foreign energy
    // cable attached directly to the machine.

    private final Set<BlockPos> pipes = new HashSet<>();

    /**
     * CACHE of the "how many more can be made" numbers - ONE per network,
     * shared by the terminal and the controller.
     *
     * <p>Player: "the numbers load slowly from left to right ... I would like
     * them to be cached on the server for this terminal ... the client gets a
     * ready cache instantly when the GUI opens, and only then does the logic
     * for counting what is visible kick in". The cache lives with the NETWORK
     * (not with the terminal or the controller), because both GUIs show
     * practically the same data - two separate caches would drift apart.
     *
     * <p>It is filled by every count that already happens (the visible page of
     * the terminal/controller) - the cache teaches itself, without separate
     * background building and without loading the server.
     */
    private final Map<Item, Long> craftableMemo = new HashMap<>();

    /**
     * Items for which the player prefers SMELTING over crafting.
     *
     * <p><b>This is a PREFERENCE, not a filter.</b> It only says which route
     * comes first - the second one is still available if the first one runs
     * out (no ingredients or no heat).
     *
     * <p>We keep this on the NETWORK, not on a single furnace or crafter: the
     * player chooses "crafting or furnace", not "that particular furnace".
     * Incidentally, the network is the one place the autocrafter always has at
     * hand, so the planner does not have to look anything up additionally.
     */
    private final Set<Item> preferFurnace = new HashSet<>();

    /** Whether smelting should be attempted BEFORE crafting for this item. */
    public boolean prefersFurnace(Item item) {
        return preferFurnace.contains(item);
    }

    /** Sets the preference for an item (true = furnace first). */
    public void setPrefersFurnace(Item item, boolean value) {
        if (value) {
            preferFurnace.add(item);
        } else {
            preferFurnace.remove(item);
        }
    }

    /** Copy of the set - for the GUI (so nobody writes to the live map). */
    public Set<Item> getFurnacePreferred() {
        return Set.copyOf(preferFurnace);
    }
    private final Set<ChunkPos> trackedChunks = new HashSet<>();

    /**
     * Cache of the aggregated network state.
     *
     * <p>Several consumers (the craftability cache, the terminal GUI, packets,
     * displays) ask for the same state in the same tick. Without this cache
     * each of them ordered its own full scan of all inventories.
     */
    private Map<Item, Long> aggregateCache;

    /** The tick in which {@link #aggregateCache} was computed. */
    private long aggregateCacheTick = Long.MIN_VALUE;

    /** How long the aggregate is considered fresh, in ticks. */
    private static final int AGGREGATE_TTL_TICKS = 10;

    /** Above this time a single scan is reported as slow. */
    private static final long SLOW_ENDPOINT_NS = 20_000_000L;

    /**
     * Budget for scanning ALL endpoints in one pass.
     *
     * <p>After it is exhausted the remaining endpoints use the remembered
     * numbers (stale, but they do not disappear from the GUI). Scanning is
     * throttled to once per 10 ticks per inventory anyway - this is a fuse for
     * a single slow storage that would otherwise block the tick.
     */
    private static final long SCAN_BUDGET_NS = 20_000_000L;

    public VelocePipeNetwork(UUID id) {
        this.id = id != null ? id : UUID.randomUUID();
    }

    public UUID getId() {
        return id;
    }

    public Set<BlockPos> getPipes() {
        return pipes;
    }

    /**
     * Network nodes: terminals, crafters AND extractors.
     *
     * <p><b>Mind the name.</b> The method is called "terminals" historically,
     * but it returns ALL node blocks, not only terminals. In reports the count
     * "Terminals: 2" therefore often meant crafter + extractor, and not two
     * terminals - which was confusing during diagnosis as well.
     *
     * <p>It is exactly these blocks that have working block entities, so their
     * chunks are permanently force-loaded (see {@code VeloceCraftingCache}).
     */
    public Set<BlockPos> getTerminals() {
        return terminals;
    }

    public Map<BlockPos, ConnectedEndpointInfo> getEndpoints() {
        return endpoints;
    }

    public Set<ChunkPos> getTrackedChunks() {
        return Collections.unmodifiableSet(trackedChunks);
    }

    public void updateTrackedChunks() {
        trackedChunks.clear();
        for (BlockPos p : pipes) {
            trackedChunks.add(new ChunkPos(p));
        }
        for (BlockPos p : terminals) {
            trackedChunks.add(new ChunkPos(p));
        }
        for (BlockPos p : endpoints.keySet()) {
            trackedChunks.add(new ChunkPos(p));
        }
    }

    /**
     * Clears the remembered numbers of all endpoints.
     *
     * <p>Called when the network layout changed (inventory connected or
     * disconnected, chunk loaded). Without it the endpoint of a removed chest
     * would still report its former contents.
     */
    public void invalidateEndpointCache(ServerLevel level) {
        for (ConnectedEndpointInfo ep : endpoints.values()) {
            // invalidateCache(), not just getCachedCounts().clear():
            // clearing without releasing the throttling meant that for the
            // next 10 ticks the endpoint reported ZERO items.
            //
            // level is needed so we do NOT clear the numbers for a chunk
            // outside the simulation - there is no way to recreate them there
            // (see invalidateCache).
            ep.invalidateCache(level);
        }
        aggregateCache = null;
        aggregateCacheTick = Long.MIN_VALUE;
    }

    /**
     * Inserts an item into the first storage of the network that accepts it.
     *
     * <p><b>Skips crafter buffers.</b> A buffer is working memory for the
     * intermediate results of auto-crafting, not a player storage - putting an
     * item there would muddle the planning (the planner sees the buffer as part
     * of the stock, so a player item would pretend to be material produced by
     * the crafter).
     *
     * <p>Extractors are not network endpoints, so they do not have to be
     * excluded separately - they only output.
     *
     * @return what could NOT be inserted (EMPTY when everything was accepted)
     */
    public ItemStack insertIntoStorage(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        for (ConnectedEndpointInfo endpoint : endpoints.values()) {
            if (remaining.isEmpty()) {
                break;
            }
            if (endpoint.getType() == ConnectedEndpointInfo.Type.CRAFTING_BUFFER) {
                continue;   // a crafter buffer is not a storage
            }
            remaining = endpoint.insertItemLeftover(level, remaining);
        }
        if (remaining.getCount() < stack.getCount()) {
            invalidateAggregateCache();
        }
        return remaining;
    }

    public void invalidateAggregateCache() {
        aggregateCache = null;
        aggregateCacheTick = Long.MIN_VALUE;
    }

    /**
     * The total contents of the network, refreshed with throttling (once per
     * {@link ConnectedEndpointInfo#SCAN_INTERVAL_TICKS} ticks per inventory).
     *
     * <p>This is the default variant, used by everything that only READS the
     * state: the background cache, the GUI, displays, tooltips. Previously
     * every such query scanned all network inventories from scratch, and since
     * the cache asked about it every few ticks, the server thread burned
     * itself out on pure reading.
     */

    /** Appends freshly computed numbers to the network cache (overwrites older ones). */
    public void rememberCraftable(Map<Item, Long> counts) {
        if (counts == null || counts.isEmpty()) {
            return;
        }
        craftableMemo.putAll(counts);
    }

    /**
     * After a SUCCESSFUL craft: the "how many more can I make" number decreases
     * by what just left the network.
     *
     * <p>Player: "when I craft an item from the autocrafter, that number does
     * not decrease - make it -1, and if I craft a stack then -64". Without this
     * the GUI showed stale numbers until someone forced a recount.
     *
     * <p>An entry that is not in the cache stays unchanged - we do not guess.
     */
    public void noteCrafted(Item item, long amount) {
        if (item == null || amount <= 0) {
            return;
        }
        Long known = craftableMemo.get(item);
        if (known == null) {
            return;
        }
        long left = known - amount;
        craftableMemo.put(item, Math.max(0L, left));
    }

    /** Snapshot of the cache - this goes to the client IMMEDIATELY after the GUI opens. */
    public Map<Item, Long> getCraftableMemo() {
        return new HashMap<>(craftableMemo);
    }

    /**
     * Clears the cache when something that changes the result changed.
     *
     * <p>Called on network changes (storage, machine, auto-crafting toggles,
     * chunk reloads) - otherwise the player would see stale numbers.
     */
    public void clearCraftableMemo() {
        craftableMemo.clear();
    }

    public Map<Item, Long> getAllItemCounts(ServerLevel level) {
        return getAllItemCounts(level, false);
    }

    /**
     * @param force when true, scans the inventories even if it did so a moment
     *              ago. Used ONLY before an operation that must see the live
     *              state (pulling an item, planning a real craft) - never in a
     *              background loop.
     */
    public Map<Item, Long> getAllItemCounts(ServerLevel level, boolean force) {
        // This is the full "how much of everything does the network hold" walk, and it is
        // called at least twice for one terminal opening: once by the terminal's own sync on the
        // right-click and once by the snapshot capture. It is measured as a whole and the SCAN
        // is measured apart from the cheap cached answer, because those two are told apart by
        // the call count and the totals: "20 call(s), 3 ms" is the cache working, "1 call, 40
        // ms" is a real scan.
        try (var ignored = com.craftingveloce.util.VeloceProfiler.section("server.network.getAllItemCounts")) {
            return getAllItemCountsMeasured(level, force);
        }
    }

    private Map<Item, Long> getAllItemCountsMeasured(ServerLevel level, boolean force) {
        long now = level.getGameTime();

        // The aggregate is cached at the network level. Without it each of the
        // several consumers (the background cache, the GUI, displays, packets)
        // ordered its own full scan of the same network - in the same tick.
        // `now >= aggregateCacheTick` - as in ConnectedEndpointInfo: with a
        // rewound world time the difference would be negative, so the
        // "younger than TTL" condition would hold and the aggregate would
        // never refresh.
        if (!force && aggregateCache != null
                && now >= aggregateCacheTick
                && now - aggregateCacheTick < AGGREGATE_TTL_TICKS) {
            return new HashMap<>(aggregateCache);
        }

        long scanStart = System.nanoTime();
        Map<Item, Long> total = new HashMap<>();
        int skipped = 0;

        // NOTE: there is no longer any iteration over "linked networks" here.
        //
        // It was a leftover from the old model in which networks merged and
        // split (there was even a class VeloceNetworkGraph that no longer
        // exists - the comment referred to it). The flat structure handles
        // this differently: the player joins pipes, so ONE component and one
        // network come into being. The `linked` list was NEVER filled (setLinkedNetworks
        // did not have a single caller), so this loop was dead - and if anyone
        // ever revived it, two networks pointing at each other would produce
        // infinite recursion and a StackOverflowError in the tick. Removed
        // together with the field.

        try (var ignored = com.craftingveloce.util.VeloceProfiler
                .section("server.network.getAllItemCounts.scan")) {
        for (ConnectedEndpointInfo endpoint : endpoints.values()) {
            // SCAN BUDGET. This was the last unbudgeted heavy operation on the
            // server thread: scanning ONE slow inventory (e.g. a huge Refined
            // Storage network) blocked the tick without a limit.
            //
            // After the budget is exhausted we do NOT scan further endpoints -
            // but we still sum their REMEMBERED numbers, so nothing disappears
            // from the GUI. The exception is force=true (before a real item
            // pull), where correctness matters more than time - and that is the
            // player path.
            if (!force && System.nanoTime() - scanStart > SCAN_BUDGET_NS) {
                skipped++;
            } else {
                long epStart = System.nanoTime();
                if (force) {
                    endpoint.forceRefresh(level, now);
                } else {
                    endpoint.refreshIfLoadedThrottled(level, now);
                }
                long epNanos = System.nanoTime() - epStart;
                if (epNanos > SLOW_ENDPOINT_NS) {
                    // A named culprit instead of "the network is slow".
                    VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                            "slow endpoint scan: %d ms for %s (type=%s)",
                            epNanos / 1_000_000L, endpoint.getPos(), endpoint.getType());
                }
            }
            for (Map.Entry<Item, Long> entry : endpoint.getCachedCounts().entrySet()) {
                if (entry.getValue() > 0) {
                    total.merge(entry.getKey(), entry.getValue(), Long::sum);
                }
            }
        }
        }
        com.craftingveloce.util.VeloceProfiler.count(
                "server.network.getAllItemCounts.scan", endpoints.size());
        Map<Item, Long> oldAggregate = aggregateCache;
        aggregateCache = total;
        aggregateCacheTick = now;
        
        if (oldAggregate != null) {
            java.util.Set<Item> changedItems = new java.util.HashSet<>();
            for (Map.Entry<Item, Long> entry : total.entrySet()) {
                long oldCount = oldAggregate.getOrDefault(entry.getKey(), 0L);
                if (oldCount != entry.getValue()) {
                    changedItems.add(entry.getKey());
                }
            }
            for (Map.Entry<Item, Long> entry : oldAggregate.entrySet()) {
                if (!total.containsKey(entry.getKey())) {
                    changedItems.add(entry.getKey());
                }
            }
            
            if (!changedItems.isEmpty()) {
                // Invalidate affected items in the craftable memo
                java.util.Set<Item> affected = com.craftingveloce.crafting.VeloceRecipeGraph.get(level).affectedBy(changedItems, 2000);
                craftableMemo.keySet().removeAll(affected);
            }
        }

        long scanNanos = System.nanoTime() - scanStart;
        if (scanNanos > SLOW_ENDPOINT_NS || skipped > 0) {
            VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                    "slow network stock scan: %d ms for %d endpoint(s), %d item type(s)"
                            + "%s",
                    scanNanos / 1_000_000L, endpoints.size(), total.size(),
                    skipped > 0
                            ? " - budget exhausted, " + skipped + " endpoint(s) used cached counts"
                            : "");
        }
        return new HashMap<>(total);
    }

    /**
     * How many units of SPECIFICALLY THIS item the whole network will accept.
     *
     * <p>This is the right question when we want to deposit an item - and not
     * "how many slots are free". The difference is real: a chest filled with
     * partial stacks of stone has ZERO empty slots, but it will still accept
     * hundreds of stones. The empty-slot counter reported it as full and the
     * terminal showed "network full" permanently.
     *
     * <p>We count the room in partial stacks PER TYPE, so stone and dirt get
     * separate answers - as it should be.
     *
     * @return the number of units (0 = truly not a single one will fit) or
     *         {@code -1} when we do not know. <b>0 is proof, -1 is lack of
     *         knowledge</b> - the caller must not treat them interchangeably.
     */
    public long capacityFor(ServerLevel level, Item item) {
        long total = 0;
        for (ConnectedEndpointInfo ep : endpoints.values()) {
            if (level != null && !level.isLoaded(ep.getPos())) {
                return -1;   // unloaded chunk - its capacity is stale
            }
            long cap = ep.capacityFor(item);
            if (cap < 0) {
                return -1;   // unknown capacity - we do not guess
            }
            total += cap;
        }
        return total;
    }

    public Map<Item, Long> getAllStoredItemCounts(ServerLevel level) {
        VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                "collecting stored counts across %d endpoint(s)", endpoints.size());
        Map<Item, Long> out = new HashMap<>();
        for (ConnectedEndpointInfo ep : endpoints.values()) {
            if (ep.getType() != ConnectedEndpointInfo.Type.INVENTORY) {
                continue;
            }
            ep.refreshIfLoadedThrottled(level, level.getGameTime());
            for (Map.Entry<Item, Long> e : ep.getCachedCounts().entrySet()) {
                if (e.getValue() > 0) {
                    out.merge(e.getKey(), e.getValue(), Long::sum);
                }
            }
        }
        return out;
    }

    public ItemStack extractItem(ServerLevel level, net.minecraft.world.item.ItemStack requested, int maxCount) {
        Item item = requested.getItem();
        for (ConnectedEndpointInfo endpoint : endpoints.values()) {
            if (endpoint.getCachedCounts().getOrDefault(item, 0L) > 0) {
                ItemStack extracted = endpoint.extractItem(level, requested, maxCount);
                if (!extracted.isEmpty()) {
                    invalidateAggregateCache();
                    return extracted;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    public ItemStack extractItem(ServerLevel level, Item item, int maxCount) {
        for (ConnectedEndpointInfo endpoint : endpoints.values()) {
            if (endpoint.getCachedCounts().getOrDefault(item, 0L) > 0) {
                ItemStack extracted = endpoint.extractItem(level, item, maxCount);
                if (!extracted.isEmpty()) {
                    invalidateAggregateCache();
                    return extracted;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** Saves the number cache to the network NBT - it must survive a world restart. */
    public void saveCraftableMemo(CompoundTag netTag) {
        CompoundTag memo = new CompoundTag();
        for (Map.Entry<Item, Long> entry : craftableMemo.entrySet()) {
            memo.putLong(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(entry.getKey()).toString(), entry.getValue());
        }
        netTag.put("CraftableMemo", memo);
    }

    /** Reads the number cache from the network NBT (after loading the world). */
    public void restoreCraftableMemo(CompoundTag netTag) {
        craftableMemo.clear();
        CompoundTag memo = netTag.getCompound("CraftableMemo");
        for (String key : memo.getAllKeys()) {
            net.minecraft.resources.ResourceLocation id =
                    net.minecraft.resources.ResourceLocation.tryParse(key);
            if (id == null) {
                continue;
            }
            craftableMemo.put(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id),
                    memo.getLong(key));
        }
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);

        long[] pipeArr = new long[pipes.size()];
        int i = 0;
        for (BlockPos p : pipes) {
            pipeArr[i++] = p.asLong();
        }
        tag.putLongArray("Pipes", pipeArr);

        long[] termArr = new long[terminals.size()];
        i = 0;
        for (BlockPos p : terminals) {
            termArr[i++] = p.asLong();
        }
        tag.putLongArray("Terminals", termArr);

        ListTag endList = new ListTag();
        for (ConnectedEndpointInfo ep : endpoints.values()) {
            endList.add(ep.toNbt());
        }
        tag.put("Endpoints", endList);

        // The "furnace or crafting" preference must survive a world restart -
        // otherwise the player would have to set it after every join.
        ListTag prefList = new ListTag();
        for (Item it : preferFurnace) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it);
            if (rl != null) {
                prefList.add(StringTag.valueOf(rl.toString()));
            }
        }
        tag.put("PreferFurnace", prefList);
        // NOTE: "EnergyStored" is no longer written - our cables carry zero FE, so
        // there is no network accumulator to persist. An old save's key is ignored
        // on load below.
        return tag;
    }

    public static VelocePipeNetwork fromNbt(CompoundTag tag) {
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        VelocePipeNetwork net = new VelocePipeNetwork(id);

        long[] pipeArr = tag.getLongArray("Pipes");
        for (long l : pipeArr) {
            net.pipes.add(BlockPos.of(l));
        }

        long[] termArr = tag.getLongArray("Terminals");
        for (long l : termArr) {
            net.terminals.add(BlockPos.of(l));
        }

        ListTag endList = tag.getList("Endpoints", Tag.TAG_COMPOUND);

        // Preferences: item names by registry identifiers.
        for (Tag el : tag.getList("PreferFurnace", Tag.TAG_STRING)) {
            ResourceLocation rl = ResourceLocation.tryParse(el.getAsString());
            if (rl == null) {
                continue;
            }
            Item resolved = BuiltInRegistries.ITEM.get(rl);
            if (resolved != null && resolved != Items.AIR) {
                net.preferFurnace.add(resolved);
            }
        }
        for (int j = 0; j < endList.size(); j++) {
            ConnectedEndpointInfo ep = ConnectedEndpointInfo.fromNbt(endList.getCompound(j));
            // null = unreadable entry (wrong side or unknown type). We SKIP it,
            // instead of blowing up the loading of the whole world save - a
            // single broken endpoint must not block entering the game.
            if (ep != null) {
                net.endpoints.put(ep.getPos(), ep);
            }
        }
        // NOTE: a legacy "EnergyStored" key from an older save is deliberately
        // IGNORED - there is no network energy buffer any more.
        net.updateTrackedChunks();
        return net;
    }
}
