package com.craftingveloce.network.pipe;

import com.craftingveloce.rs.RefinedStorageHelper;
import com.craftingveloce.debug.ChunkTrace;

import javax.annotation.Nullable;
import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.HashMap;
import java.util.Map;

public class ConnectedEndpointInfo {

    public enum Type {
        INVENTORY,
        REFINED_STORAGE,
    /**
     * Auto-crafter buffer - a block's own cache, not a container in the world.
     * Recognized by type, so that after a world restart the correct endpoint
     * implementation is restored (see {@link CraftingBufferEndpoint}).
     */
        CRAFTING_BUFFER,

        /**
         * A foreign block with Forge Energy (Energy Cube, generator, energy bank).
         *
         * <p>Our machines PULL power from it THEMSELVES (see VeloceEnergyPull):
         * that is the only direction in which energy flows through our pipes.
         * Our pipes are not a conductor for other mods - nothing can draw from
         * them, and our machines are exclusively receivers.
         */
        ENERGY
    }

    private final BlockPos pos;
    private final Direction accessSide;
    private final ChunkPos chunkPos;
    private final Type type;
    private final Map<Item, Long> cachedCounts = new HashMap<>();

    /**
     * How many FREE slots this storage has (from the last scan).
     *
     * <p>{@code -1} = we do not know (e.g. Refined Storage, where capacity is
     * not a number of slots). The "do not know" value is important: the client
     * does NOT block the action then, because we prefer to let the operation
     * through and let the server decide rather than block something that could
     * succeed.
     *
     * <p>We only count COMPLETELY empty slots. A partially filled slot can only
     * accept the same item, so it is not a "free slot" for an arbitrary item -
     * the client checks that separately, by looking at whether the item is
     * already in the network.
     */
    private int cachedFreeSlots = -1;

    /**
     * Free space in INCOMPLETE stacks, per item type.
     *
     * <p><b>Why this exists.</b> The empty-slot counter alone LIES. Imagine a
     * chest with 27 slots, where each slot holds 40 stone. The number of empty
     * slots is ZERO, so {@link #cachedFreeSlots} says "0" and the network
     * reported itself as FULL. Yet each of those stacks can still take 24 more
     * stone - that is 648 items!
     *
     * <p>The symptom was exactly what the player reported: the terminal shouts
     * "network full", even though at the end of the network there is a chest
     * with free space. And permanently, because until the stacks are topped up
     * an empty slot will not appear - so the counter never moves off zero.
     *
     * <p>That is why we remember SEPARATELY how many items of each type still
     * fit before its stacks are topped up. Thanks to this we compute capacity
     * FOR A SPECIFIC ITEM ({@link #capacityFor}), and not "in general" - because
     * the space left by stone does not help when we want to insert dirt.
     */
    private final Map<Item, Integer> cachedPartialSpace = new HashMap<>();

    /**
     * The tick in which we last scanned this inventory.
     *
     * <p>Scanning means walking over ALL slots and calling
     * {@code getStackInSlot} on each. For modded storages that can copy stacks
     * together with their NBT, so it is an expensive operation. Previously we
     * refreshed on EVERY query about the network state (and that happens
     * several times per second), which choked the server thread.
     */
    private long lastScanTick = Long.MIN_VALUE;

    /** Minimum spacing between scans of the same inventory, in ticks. */
    public static final int SCAN_INTERVAL_TICKS = 10;

    /** Has the scan error already been reported (so as not to spam the log). */
    private boolean scanFailureLogged;

    public ConnectedEndpointInfo(BlockPos pos, Direction accessSide, Type type) {
        this.pos = pos;
        this.accessSide = accessSide;
        this.chunkPos = new ChunkPos(pos);
        this.type = type;
    }

    public BlockPos getPos() {
        return pos;
    }

    public Direction getAccessSide() {
        return accessSide;
    }

    public ChunkPos getChunkPos() {
        return chunkPos;
    }

    public Type getType() {
        return type;
    }

    /**
     * Is it allowed to INSERT items into this storage.
     *
     * <p><b>Why this exists.</b> The wrench sets a pipe side to one of three
     * modes: Push/Pull, Pull, Disconnected. In <b>Pull</b> mode the storage is
     * to be EXCLUSIVELY a source - the network is only to take from it (e.g.
     * from a furnace that produces on its own, or from a chest into which we do
     * not want anything to fall). Previously this mode was ignored when
     * inserting: the pipe knew about it only in order to draw the nozzle, and
     * the network still threw everything it wanted to deposit into that storage.
     *
     * <p>Defaults to {@code true}, so that a storage without any pipe in Pull
     * mode behaves exactly as before.
     */
    public boolean acceptsInsert() {
        return acceptsInsert;
    }

    /** Sets whether this storage accepts inserted items. */
    public void setAcceptsInsert(boolean accepts) {
        this.acceptsInsert = accepts;
    }

    /**
     * Merges in information from ANOTHER pipe touching the same storage.
     *
     * <p>One storage can have several pipes, each in a different mode. The
     * network is to insert into it when <b>at least one</b> side permits it -
     * so Pull mode wins only when it covers all connections. Without this the
     * last pipe in construction order would decide about the whole storage and
     * the result would depend on the order of traversal of the network.
     */
    public void mergeAcceptsInsert(boolean accepts) {
        this.acceptsInsert = this.acceptsInsert || accepts;
    }

    /**
     * Marks that this is the first pipe registering this storage in this pass -
     * that is, the flag has to be set rather than merged.
     */
    public void resetAcceptsInsert(boolean accepts) {
        this.acceptsInsert = accepts;
    }

    private boolean acceptsInsert = true;

    public Map<Item, Long> getCachedCounts() {
        return cachedCounts;
    }

    /**
     * Refreshes the counters, but no more often than once every
     * {@link #SCAN_INTERVAL_TICKS} ticks.
     *
     * <p>This is the variant for ALL background reads (cache, GUI, displays).
     * A state half a second old is entirely sufficient for them, and it saves
     * scanning the whole network several times per second.
     */
    public void refreshIfLoadedThrottled(ServerLevel level, long gameTime) {
        // `gameTime >= lastScanTick` is not redundant: if the world time were
        // rewound (loading an older save), the difference would be NEGATIVE,
        // and therefore smaller than the interval - and the scan would be
        // skipped endlessly.
        if (lastScanTick != Long.MIN_VALUE
                && gameTime >= lastScanTick
                && gameTime - lastScanTick < SCAN_INTERVAL_TICKS) {
            return;
        }
        lastScanTick = gameTime;
        refreshIfLoaded(level);
    }

    /** Forces a scan right now (for operations that must see the live state). */
    public void forceRefresh(ServerLevel level, long gameTime) {
        lastScanTick = gameTime;
        refreshIfLoaded(level);
    }

    /**
     * Invalidates the remembered counters AND allows an immediate rescan.
     *
     * <p><b>NOTE: this must NOT clear the numbers for an unloaded chunk.</b>
     *
     * <p>The BUG that was here and that produced the main reported symptom
     * ("in an unloaded chunk I do not have the items that are there"): the
     * method cleared {@code cachedCounts}, and {@link #refreshIfLoaded} for an
     * unloaded chunk <b>does nothing</b> - there is nothing to rebuild the
     * contents from.
     *
     * <p>So: the cache was left zeroed and had no way to recover, because the
     * chunk is outside simulation. And since invalidation happens on every
     * change of network adjacency and on every rebuild, it was enough to move
     * anything in the base or walk away from the chest - and its contents
     * vanished from the GUI permanently.
     *
     * <p>Now we distinguish two cases:
     * <ul>
     *   <li><b>chunk loaded</b> - we clear, because in a moment we will read
     *       the real state from the world,</li>
     *   <li><b>chunk unloaded</b> - we keep the last known contents.
     *       It is still true: since the chunk is not simulated, nobody has
     *       touched those items. That is exactly the assumption of the whole
     *       mechanism.</li>
     * </ul>
     *
     * <p>Zeroing {@code lastScanTick} stays in both cases - it says
     * "this entry is invalid, scan it on the next question".
     */
    public void invalidateCache(ServerLevel level) {
        if (level == null || level.isLoaded(pos)) {
            cachedCounts.clear();
            // We clear the counters TOGETHER - otherwise free space would be
            // computed against stacks we no longer remember.
            cachedPartialSpace.clear();
        }
        lastScanTick = Long.MIN_VALUE;
    }

    /**
     * How many units of EXACTLY THIS item this storage will still accept.
     *
     * <p>We count two things, because each alone is wrong:
     * <ul>
     *   <li>empty slots - each of them will accept a full stack (we estimate
     *       with {@link Item#getDefaultMaxStackSize()}; an overestimate is safe,
     *       because then we merely do not block too early),</li>
     *   <li>free space in INCOMPLETE stacks OF THE SAME item - that is exactly
     *       the case of "the chest looks full, yet it will fit".</li>
     * </ul>
     *
     * <p>Space left by another item is deliberately IGNORED: 10 free units in a
     * stone stack do not help when we are inserting dirt.
     *
     * @return the number of units (can be 0 = genuinely full) or {@code -1} when
     *         we do not know (chunk unloaded, Refined Storage, no read).
     *         The caller MUST distinguish 0 from -1: 0 is proof, -1 is lack of
     *         knowledge.
     */
    public long capacityFor(Item item) {
        // A storage in Pull mode will accept NOTHING, so it contributes no
        // capacity. Without this the network's free-space counter would show the
        // free slots of a storage into which inserting is forbidden - and the
        // player would get "there is space", and then nothing would be silently
        // deposited.
        if (!acceptsInsert) {
            return 0;
        }
        if (cachedFreeSlots < 0) {
            return -1;
        }
        long total = (long) cachedFreeSlots * item.getDefaultMaxStackSize();
        total += cachedPartialSpace.getOrDefault(item, 0);
        return total;
    }

    /**
     * Records that the endpoint cannot be read right now.
     *
     * <p>We log ONCE per endpoint, so as not to clutter the log on every chunk
     * load, but so that it can be seen at all.
     */
    private void noteNotReadable() {
        if (!notReadableLogged) {
            notReadableLogged = true;
            VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                    "endpoint at %s not readable yet (chunk loading?) - keeping known counts",
                    pos);
        }
    }

    /** Have we already logged that the endpoint cannot be read. */
    private boolean notReadableLogged = false;

    /**
     * Until when we do NOT load the chunk for this storage after a failed
     * operation.
     *
     * <p><b>The BUG this fixes (a load/unload loop).</b> When the storage lies
     * in an unloaded chunk, the operation loads it briefly
     * ({@code getChunk(..., true)} - without force). If the cache of that
     * storage was STALE (it said "I have this item", but it was not inside),
     * then after loading nothing could be taken - and the cache was NOT
     * corrected, because refreshing was only done on success. The caller
     * (extractors, furnace, crafter) therefore kept asking, and EVERY question
     * loaded the chunk once more: a real load/unload loop, in practice 10 times
     * per second for whole minutes.
     *
     * <p>Our loop counter did not see this, because it only watched
     * {@code setChunkForced} - and these are ordinary loads without forcing.
     *
     * <p>So after a failed attempt we wait the full time and only then try
     * again. A failed attempt means "the cache lied", not "try again in two
     * seconds".
     */
    private long opLoadBlockedUntilTick = Long.MIN_VALUE;

    /** How long we do not load the chunk after a failed operation (ticks). */
    private static final long FAILED_OP_LOAD_COOLDOWN_TICKS = 200L;

    /** Result of reading one container: counts, free slots, space in partial stacks. */
    private record SlotScan(Map<Item, Long> counts, int freeSlots, Map<Item, Integer> partialSpace) {
    }

    /**
     * ONE rule for reading a container - for every kind of storage.
     *
     * <p><b>Why it is extracted.</b> The same loop was copied in two branches
     * (NeoForge ItemHandler and the vanilla Container) line for line. That is
     * exactly the pattern that has drifted apart several times in this project:
     * the fix "we compute capacity per item, not from empty slots" had to reach
     * BOTH copies, and with a third kind of storage it would have had to be
     * repeated once more. Now the rule is in one place.
     *
     * @param slots   how many slots the container has
     * @param getSlot where to get the stack from a given slot
     */
    private static SlotScan scanSlots(int slots, java.util.function.IntFunction<ItemStack> getSlot) {
        Map<Item, Long> counts = new HashMap<>();
        Map<Item, Integer> partialSpace = new HashMap<>();
        int freeSlots = 0;
        for (int i = 0; i < slots; i++) {
            ItemStack stack = getSlot.apply(i);
            if (stack.isEmpty()) {
                freeSlots++;
                continue;
            }
            Item mappedItem = com.craftingveloce.util.VelocePotionMapper.getProxy(stack);
            counts.merge(mappedItem, (long) stack.getCount(), Long::sum);
            int room = stack.getMaxStackSize() - stack.getCount();
            if (room > 0) {
                partialSpace.merge(mappedItem, room, Integer::sum);
            }
        }
        return new SlotScan(counts, freeSlots, partialSpace);
    }

    public void refreshIfLoaded(ServerLevel level) {
        if (!level.isLoaded(pos)) {
            return;
        }

        if (type == Type.REFINED_STORAGE) {
            // The same safeguard as for INVENTORY: when the block entity does
            // not exist yet (chunk still loading), the RS counter is temporarily
            // unavailable - we do not overwrite the known numbers with zero.
            if (level.getBlockEntity(pos) == null) {
                noteNotReadable();
                return;
            }
            Map<Item, Long> rsCounts = RefinedStorageHelper.getRSItemCounts(level, pos, accessSide);
            cachedCounts.clear();
            cachedCounts.putAll(rsCounts);
            // RS has no capacity expressed in slots - we do not pretend to know.
            cachedFreeSlots = -1;
            return;
        }

        // Standard INVENTORY
        Map<Item, Long> newCounts = new HashMap<>();
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(level, pos, state, be, accessSide);

            // DID WE SEE A CONTAINER?
            //
            // The BUG that was here: the `cachedCounts.clear()` below ran
            // ALWAYS, also when NO container could be read. Then `newCounts` was
            // empty and the known numbers were WIPED TO ZERO.
            //
            // When this happens in practice: the player teleports next to a
            // chest, its chunk starts loading, but the block entity has not been
            // created yet (`getBlockEntity` returns null). Our scan then reads
            // emptiness and writes zero - and since the chunk unloads again right
            // away, there is no way to correct it. Symptom: "the terminal lost
            // the network", because the storage reports 0 types.
            boolean sawContainer = false;
            int freeSlots = 0;
            Map<Item, Integer> partialSpace = new HashMap<>();
            if (handler != null) {
                sawContainer = true;
                SlotScan scan = scanSlots(handler.getSlots(), handler::getStackInSlot);
                newCounts.putAll(scan.counts());
                freeSlots = scan.freeSlots();
                partialSpace = scan.partialSpace();
            } else if (be instanceof Container container) {
                sawContainer = true;
                SlotScan scan = scanSlots(container.getContainerSize(), container::getItem);
                newCounts.putAll(scan.counts());
                freeSlots = scan.freeSlots();
                partialSpace = scan.partialSpace();
            }

            if (!sawContainer) {
                // There is nothing to read - the chunk is still loading or the
                // block disappeared. We KEEP the last known numbers instead of
                // clearing them: a stale number is better than a false zero.
                noteNotReadable();
                return;
            }

            cachedCounts.clear();
            cachedCounts.putAll(newCounts);
            cachedFreeSlots = freeSlots;
            cachedPartialSpace.clear();
            cachedPartialSpace.putAll(partialSpace);
            scanFailureLogged = false;
            notReadableLogged = false;
        } catch (Throwable t) {
            // We do NOT swallow this silently.
            //
            // There used to be `catch (Throwable ignored) {}` here. When the
            // inventory scan threw (e.g. a broken storage from another mod),
            // cachedCounts was left with the OLD values - the player saw stale
            // numbers and had no way to guess why. Now we at least mention it in
            // the log once per endpoint.
            if (!scanFailureLogged) {
                scanFailureLogged = true;
                VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                        "endpoint scan failed at %s (type=%s) - counts stay stale: %s",
                        pos, type, t);
            }
        }
    }

    /**
     * Physically takes the item - WITHOUT checking or loading the chunk.
     *
     * <p>Extracted from {@link #extractItem} as the "chunk is already loaded"
     * path - it does not check or load the chunk by itself.
     */
    public ItemStack extractNow(ServerLevel level, Item item, int maxCount) {
        if (type == Type.REFINED_STORAGE) {
            return RefinedStorageHelper.extractItem(level, pos, accessSide,
                    com.craftingveloce.util.VelocePotionMapper.toRealPotion(item, 1), maxCount);
        }
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(
                    level, pos, state, be, accessSide);
            if (handler != null) {
                ItemStack result = ItemStack.EMPTY;
                int needed = maxCount;
                for (int i = 0; i < handler.getSlots() && needed > 0; i++) {
                    ItemStack inSlot = handler.getStackInSlot(i);
                    if (!inSlot.isEmpty() && com.craftingveloce.util.VelocePotionMapper.getProxy(inSlot) == item) {
                        ItemStack extracted = handler.extractItem(i, needed, false);
                        if (!extracted.isEmpty()) {
                            result = result.isEmpty() ? extracted.copy() : grow(result, extracted);
                            needed -= extracted.getCount();
                        }
                    }
                }
                return result;
            }
            if (be instanceof Container container) {
                ItemStack result = ItemStack.EMPTY;
                int needed = maxCount;
                for (int i = 0; i < container.getContainerSize() && needed > 0; i++) {
                    ItemStack inSlot = container.getItem(i);
                    if (!inSlot.isEmpty() && com.craftingveloce.util.VelocePotionMapper.getProxy(inSlot) == item) {
                        int toTake = Math.min(needed, inSlot.getCount());
                        ItemStack taken = container.removeItem(i, toTake);
                        if (!taken.isEmpty()) {
                            result = result.isEmpty() ? taken.copy() : grow(result, taken);
                            needed -= taken.getCount();
                        }
                    }
                }
                return result;
            }
        } catch (Throwable t) {
            VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                    "deferred extract at %s failed: %s", pos, t);
        }
        return ItemStack.EMPTY;
    }

    /** Adds the contents to the result stack. */
    private static ItemStack grow(ItemStack into, ItemStack from) {
        into.grow(from.getCount());
        return into;
    }

    public ItemStack extractItem(ServerLevel level, Item item, int maxCount) {
        if (cachedCounts.getOrDefault(item, 0L) <= 0) {
            return ItemStack.EMPTY;
        }

        // The ONE place that physically takes items - {@link #extractNow}.
        // Previously this method had its own copy-pasted version of the same
        // loop, so a fix in one of them never reached the other.
        if (type == Type.REFINED_STORAGE) {
            ItemStack extracted = extractNow(level, item, maxCount);
            if (!extracted.isEmpty()) {
                refreshIfLoaded(level);
            }
            return extracted;
        }

        // INVENTORY
        boolean wasLoaded = level.isLoaded(pos);
        long chunkKey = ChunkPos.asLong(chunkPos.x, chunkPos.z);
        if (!wasLoaded) {
            // EXTRACTION MUST BE SYNCHRONOUS - and that is critical.
            //
            // ============================================================
            // The BUG that was here (DUPLICATION): the queued version returned
            // the STACK TO THE CALLER IMMEDIATELY, "on credit":
            //
            //     scheduleExtract(...);                  // do it later
            //     return new ItemStack(item, cached);    // and take it now
            //
            // while the queue, once it had physically taken the items, DISCARDED
            // the result (it was only logged). As long as everything went
            // according to assumption, the sum added up. But it was enough for
            // the promise not to be kept, and items multiplied out of nothing:
            //   - the queue was full (MAX_QUEUE) and the task was REJECTED,
            //   - the chunk could not be loaded,
            //   - the chest held less than the cache said (hopper, another
            //     player, a machine from another mod) - a promise bigger than the
            //     state,
            //   - the container disappeared.
            // In each of those cases the player, the crafter or the furnace got
            // items that had NOT been taken from anyone.
            //
            // The caller MUST know the true result - otherwise the rule "take
            // exactly as much as you gave out" cannot be kept. That is why we
            // load the chunk here and now, exactly as when inserting.
            // ============================================================
            //
            // Without a force-load: getChunk(..., true) loads the chunk for the
            // duration of this call, and the operation finishes in the same tick,
            // so the chunk drops out later through the NORMAL game mechanism.
            // The per-tick budget protects against choking when the caller loops
            // (crafter, extractor).
            // Blocking after a failed attempt - see opLoadBlockedUntilTick.
            // Without it the same storage with a stale cache would load the
            // chunk in a loop (load/unload every few dozen ticks).
            if (level.getGameTime() < opLoadBlockedUntilTick) {
                return ItemStack.EMPTY;
            }
            if (VeloceChunkLoader.isFrozen()) {
                return ItemStack.EMPTY;
            }
            if (!VeloceChunkLoader.tryReserveOpLoad(level)) {
                com.craftingveloce.debug.ChunkTrace.at("EXTRACT", level, pos,
                        "load budget exhausted (%d/tick) -> nothing was taken",
                        VeloceChunkLoader.MAX_OP_LOADS_PER_TICK);
                return ItemStack.EMPTY;
            }
            com.craftingveloce.debug.ChunkOpNotifier.reportLoad(level, chunkKey, pos,
                    com.craftingveloce.debug.ChunkOpNotifier.Op.EXTRACT);
            VeloceChunkLoader.noteOpLoad(level, chunkKey, pos, "extraction");
            com.craftingveloce.debug.ChunkTrace.at("EXTRACT", level, pos,
                    "chunk UNLOADED -> loading for the operation (no force); item=%s requested=%d",
                    item, maxCount);
            level.getChunkSource().getChunk(
                    chunkPos.x, chunkPos.z,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
        } else {
            // The chunk was already loaded - we count this as a use, so that
            // frequently visited chunks stay in memory longer.
            VeloceChunkLoader.noteUse(level, chunkKey);
            com.craftingveloce.debug.ChunkTrace.at("EXTRACT", level, pos,
                    "chunk LOADED -> taking immediately; item=%s requested=%d cached=%d",
                    item, maxCount, cachedCounts.getOrDefault(item, 0L));
        }

        ItemStack result = extractNow(level, item, maxCount);
        if (!result.isEmpty()) {
            refreshIfLoaded(level);
        } else if (!wasLoaded) {
            // The cache lied: we loaded the chunk and the item was not there.
            // We correct the truth (the chunk is already loaded, so it is a
            // cheap read) AND we set this storage aside for a while. Without
            // this the caller would keep asking, and every question loaded the
            // chunk again - a load/unload loop exactly like the one visible in
            // the log.
            refreshIfLoaded(level);
            opLoadBlockedUntilTick = level.getGameTime() + FAILED_OP_LOAD_COOLDOWN_TICKS;
        }
        return result;
    }


    /**
     * Physically inserts the stack - WITHOUT checking or loading the chunk.
     *
     * @return what could NOT be inserted
     */
    public ItemStack insertNow(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack originalStack = stack;
        stack = com.craftingveloce.util.VelocePotionMapper.toRealPotion(stack);
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (type == Type.REFINED_STORAGE) {
            ItemStack rsRem = RefinedStorageHelper.insertItemLeftover(level, pos, accessSide, stack);
            return rsRem.isEmpty() ? ItemStack.EMPTY : new ItemStack(originalStack.getItem(), rsRem.getCount());
        }
        ItemStack remaining = stack.copy();
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(
                    level, pos, state, be, accessSide);
            if (handler != null) {
                for (int i = 0; i < handler.getSlots() && !remaining.isEmpty(); i++) {
                    remaining = handler.insertItem(i, remaining, false);
                }
            } else if (be instanceof Container container) {
                for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
                    ItemStack inSlot = container.getItem(i);
                    int max = Math.min(container.getMaxStackSize(), remaining.getMaxStackSize());
                    if (inSlot.isEmpty()) {
                        int move = Math.min(max, remaining.getCount());
                        container.setItem(i, remaining.split(move));
                        container.setChanged();
                    } else if (ItemStack.isSameItemSameComponents(inSlot, remaining)) {
                        int space = max - inSlot.getCount();
                        if (space > 0) {
                            int move = Math.min(space, remaining.getCount());
                            ItemStack merged = inSlot.copy();
                            merged.grow(move);
                            container.setItem(i, merged);
                            remaining.shrink(move);
                            container.setChanged();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                    "deferred insert at %s failed: %s", pos, t);
        }
        return remaining.isEmpty() ? ItemStack.EMPTY : new ItemStack(originalStack.getItem(), remaining.getCount());
    }

    /**
     * Inserts as much as possible and returns the LEFTOVER.
     *
     * <p><b>Why return the remaining stack instead of a plain boolean.</b> The
     * previous version returned {@code remaining.isEmpty()}, so on a PARTIAL
     * acceptance (e.g. a nearly full barrel) it said "failed" - even though part
     * of the items had already physically gone in. The caller then took nothing
     * from the player, while the items were already in the storage:
     * DUPLICATION.
     *
     * @return what could NOT be inserted (EMPTY when everything was accepted)
     */
    public ItemStack insertItemLeftover(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack originalStack = stack;
        stack = com.craftingveloce.util.VelocePotionMapper.toRealPotion(stack);
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        // PULL MODE: a pipe side set to "Pull" means that this storage may ONLY
        // be taken from. We check this as the FIRST thing, before loading or
        // touching anything - and we return the WHOLE stack, so the caller keeps
        // the items on its side (nothing is lost and nothing is duplicated).
        //
        // One place for the whole rule: this path is taken by the terminal,
        // the crafter (depositing the output) and the furnace (returning the
        // fuel surplus). Checking it separately in each of them would guarantee
        // that one day one of them gets skipped.
        if (!acceptsInsert) {
            ChunkTrace.at("INSERT", level, pos,
                    "PULL mode - inserting forbidden; stack=%dx %s",
                    stack.getCount(), stack.getItem());
            return stack;
        }

        if (type == Type.REFINED_STORAGE) {
            ItemStack left = RefinedStorageHelper.insertItemLeftover(level, pos, accessSide, stack);
            if (left.getCount() != stack.getCount()) {
                refreshIfLoaded(level);
            }
            return left.isEmpty() ? ItemStack.EMPTY : new ItemStack(originalStack.getItem(), left.getCount());
        }

        boolean wasLoaded = level.isLoaded(pos);
        long chunkKey = ChunkPos.asLong(chunkPos.x, chunkPos.z);
        if (!wasLoaded) {
            // No loading during a world save - see extractItem.
            if (VeloceChunkLoader.isFrozen()) {
                return stack;
            }
            com.craftingveloce.debug.ChunkOpNotifier.reportLoad(level, chunkKey, pos,
                    com.craftingveloce.debug.ChunkOpNotifier.Op.INSERT);
            // INSERTING MUST BE SYNCHRONOUS - and that is critical.
            //
            // The BUG that was here (DUPLICATION + a false "network full"):
            // the queued version did this:
            //     scheduleInsert(stack.copy());   // the queue INSERTS the whole stack
            //     return stack;                   // and we tell the caller "not accepted"
            // The caller (storeFromPlayer) therefore saw "nothing went in", did
            // NOT take the items from the player - while the queue inserted them
            // into the network on its tick. Effect: the items were SIMULTANEOUSLY
            // in the network and with the player.
            //
            // In addition, when the storage stood in an unloaded chunk (and only
            // NODES are force-loaded, not storages), inserting ALWAYS went down
            // this path - so the terminal permanently shouted "network full",
            // even though there was plenty of space.
            //
            // Conclusion: the caller MUST know the true result, in order to take
            // from the player EXACTLY as much as went in. That is why we load
            // the chunk here and now.
            //
            // ============================================================
            // retain()/release() MUST NOT BE USED HERE - and that is a separate
            // bug that was here. retain() does setChunkForced(true), and
            // release() does setChunkForced(false), that is, in ONE tick we flip
            // the forcing flag in BOTH directions.
            //
            // The effect was visible in game as a loop: loaded -> unloaded ->
            // loaded -> ... on EVERY insert. And since inserting is often
            // repeated (the crafter deposits its output, the furnace returns the
            // fuel surplus, the player clicks), the loop never ended. On top of
            // that, setChunkForced is saved PERSISTENTLY in the world data, so
            // every flip is also a save.
            //
            // That forcing was entirely unnecessary. getChunk(..., true) loads
            // the chunk synchronously by itself for the duration of this call,
            // and the whole operation finishes in the SAME tick - there is no
            // window in which the chunk could be unloaded under our hands. After
            // returning, the chunk has no forcing ticket, so it drops out
            // NORMALLY, through the ordinary game mechanism - exactly as it
            // should be.
            // ============================================================
            // Blocking after a failed attempt - see opLoadBlockedUntilTick.
            // The same load/unload loop as with extraction: a storage with a
            // stale cache would accept (or reject) the stack over and over.
            if (level.getGameTime() < opLoadBlockedUntilTick) {
                return stack;
            }
            ChunkTrace.at("INSERT", level, pos,
                    "chunk UNLOADED -> loading for the operation (no force); stack=%dx %s",
                    stack.getCount(), stack.getItem());
            if (!VeloceChunkLoader.tryReserveOpLoad(level)) {
                // The blocking-load budget for this tick is exhausted. We
                // refuse the WHOLE stack - the caller keeps the items with the
                // player. That is safe (zero duplication), and the next tick has
                // a budget again.
                ChunkTrace.at("INSERT", level, pos,
                        "load budget exhausted (%d/tick) -> refusing, stack stays with the player",
                        VeloceChunkLoader.MAX_OP_LOADS_PER_TICK);
                // A regular log (without /cv trace), because this is the ONLY
                // situation in which inserting refuses despite free space.
                // Without this trace it would look exactly like the old
                // "network full" bug.
                VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                        "insert at %s deferred: blocking-load budget used (%d/tick), stack kept by caller",
                        pos, VeloceChunkLoader.MAX_OP_LOADS_PER_TICK);
                return stack;
            }
            VeloceChunkLoader.noteOpLoad(level, chunkKey, pos, "insertion");
            level.getChunkSource().getChunk(
                    chunkPos.x, chunkPos.z,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
        } else {
            VeloceChunkLoader.noteUse(level, chunkKey);
        }

        ItemStack remaining = stack.copy();
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(level, pos, state, be, accessSide);
            if (handler != null) {
                for (int i = 0; i < handler.getSlots() && !remaining.isEmpty(); i++) {
                    remaining = handler.insertItem(i, remaining, false);
                }
            } else if (be instanceof Container container) {
                for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
                    ItemStack inSlot = container.getItem(i);
                    int max = Math.min(container.getMaxStackSize(), remaining.getMaxStackSize());
                    if (inSlot.isEmpty()) {
                        int move = Math.min(max, remaining.getCount());
                        container.setItem(i, remaining.split(move));
                        container.setChanged();
                    } else if (ItemStack.isSameItemSameComponents(inSlot, remaining)) {
                        int space = max - inSlot.getCount();
                        if (space > 0) {
                            int move = Math.min(space, remaining.getCount());
                            // The BUG that was here: we modified the stack
                            // RETURNED by getItem(i) and only shrank `remaining`.
                            // A container that returns a COPY (and that is exactly
                            // this fallback case - there is no capability here, so
                            // we hit a plain Container) never saved that change.
                            // Effect: `remaining` shrank while the item was NOT
                            // inserted - that is, silent item loss.
                            //
                            // That is why we build a NEW stack and save it via
                            // setItem, instead of modifying whatever came back.
                            ItemStack merged = inSlot.copy();
                            merged.grow(move);
                            container.setItem(i, merged);
                            remaining.shrink(move);
                            container.setChanged();
                        }
                    }
                }
            }

            refreshIfLoaded(level);
        } catch (Throwable t) {
            // The exception goes into the mod's log TOGETHER with the stack -
            // previously it went to stderr, outside the logging system (without
            // a category and without the ability to disable it via config).
            VeloceLog.Network.error(VeloceLog.Side.SERVER, t,
                    "insert at %s failed", pos);
        }
        // NO release() - see the comment above. The chunk has no forcing
        // ticket, so it drops out normally whenever the game sees fit.
        return remaining.isEmpty() ? ItemStack.EMPTY : new ItemStack(originalStack.getItem(), remaining.getCount());
    }

    /** Compatibility: true when everything was accepted. */
    public boolean insertItem(ServerLevel level, ItemStack stack) {
        return insertItemLeftover(level, stack).isEmpty();
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Pos", pos.asLong());
        tag.putInt("Side", accessSide.ordinal());
        tag.putString("Type", type.name());

        ListTag list = new ListTag();
        for (Map.Entry<Item, Long> entry : cachedCounts.entrySet()) {
            if (entry.getValue() <= 0) continue;
            CompoundTag itemTag = new CompoundTag();
            itemTag.putString("id", BuiltInRegistries.ITEM.getKey(entry.getKey()).toString());
            itemTag.putLong("cnt", entry.getValue());
            list.add(itemTag);
        }
        tag.put("Cache", list);
        return tag;
    }

    /**
     * Restores an endpoint from NBT.
     *
     * <p><b>Never throws.</b> This code runs while loading saved data, and
     * {@code VelocePipeNetworkManager.load} deserializes ALL networks in one
     * pass. An exception here therefore aborted the whole load - that is, the
     * world would not load at all because of one broken entry.
     *
     * <p>This was real: {@code Direction.values()[side]} threw
     * ArrayIndexOutOfBoundsException for an out-of-range side (an older save, a
     * different enum order), and {@code Type.valueOf} threw
     * IllegalArgumentException for an unknown type name.
     *
     * @return the restored endpoint, or {@code null} when the entry cannot be
     *         understood (the caller is simply to skip it)
     */
    @Nullable
    public static ConnectedEndpointInfo fromNbt(CompoundTag tag) {
        BlockPos pos = BlockPos.of(tag.getLong("Pos"));

        Direction[] sides = Direction.values();
        int sideIndex = tag.getInt("Side");
        if (sideIndex < 0 || sideIndex >= sides.length) {
            VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                    "endpoint at %s has invalid side %d - entry skipped", pos, sideIndex);
            return null;
        }
        Direction side = sides[sideIndex];

        Type type;
        String typeName = tag.getString("Type");
        try {
            type = Type.valueOf(typeName);
        } catch (IllegalArgumentException ex) {
            VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                    "endpoint at %s has unknown type '%s' - entry skipped", pos, typeName);
            return null;
        }

        // The crafter buffer has its own implementation (it reads from the
        // block's buffer, not from a container in the world) - it has to be
        // restored after a restart.
        ConnectedEndpointInfo info = type == Type.CRAFTING_BUFFER
                ? new CraftingBufferEndpoint(pos, side)
                : new ConnectedEndpointInfo(pos, side, type);
        ListTag list = tag.getList("Cache", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag itemTag = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(itemTag.getString("id"));
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                Item item = BuiltInRegistries.ITEM.get(id);
                long count = itemTag.getLong("cnt");
                info.cachedCounts.put(item, count);
            }
        }
        return info;
    }
}
