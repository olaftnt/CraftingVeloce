package com.craftingveloce.block.entity;

import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.inventory.VeloceExtractorMenu;
import com.craftingveloce.network.SyncExtractorFiltersPKT;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainer;

public class VeloceExtractorBlockEntity extends BlockEntity
        implements MenuProvider, WorldlyContainer, VeloceFilterHost {

    /** How many filters the extractor has (matches the layout in the GUI and in the menu). */
    public static final int FILTER_SLOTS = 9;

    private static final int[] OUTPUT_SLOTS = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8};

    private final NonNullList<ItemStack> filterSlots = NonNullList.withSize(FILTER_SLOTS, ItemStack.EMPTY);
    private final SimpleContainer outputInventory = new SimpleContainer(9);

    /**
     * Tick of the last pull from the network.
     *
     * <p>Replaces the old counter with the condition {@code tickCounter % 10 == 0}.
     * That pattern had two problems: the counter started from zero at EVERY
     * extractor (so they all pulled in the same tick - a load spike instead of
     * spread work), and incidentally it was blind to the tick phase. A phase
     * computed from the block position spreads those pulls across successive
     * ticks.
     */
    private long lastPullTick = Long.MIN_VALUE;

    /**
     * Whether a given filter slot may use auto-crafting.
     *
     * <p>Default true (the extractor produces the missing item itself, if some
     * crafter in the network has it enabled). A right click on an occupied slot
     * toggles this: a disabled slot gets ONLY what is already in the network.
     */
    private final boolean[] allowCrafting = new boolean[FILTER_SLOTS];

    {
        java.util.Arrays.fill(allowCrafting, true);
    }

    public boolean isCraftingAllowed(int index) {
        return index >= 0 && index < FILTER_SLOTS && allowCrafting[index];
    }

    public void setCraftingAllowed(int index, boolean allowed) {
        if (index >= 0 && index < FILTER_SLOTS) {
            allowCrafting[index] = allowed;
            setChanged();
            syncFiltersToWatchers();
        }
    }

    /** Toggles auto-crafting for the slot and returns the new state. */
    public boolean toggleCraftingAllowed(int index) {
        boolean next = !isCraftingAllowed(index);
        setCraftingAllowed(index, next);
        return next;
    }

    public VeloceExtractorBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.VELOCE_EXTRACTOR_BE.get(), pos, state);
        outputInventory.addListener(c -> setChanged());
    }

    public Container getOutputInventory() {
        return outputInventory;
    }

    public NonNullList<ItemStack> getFilterSlots() {
        return filterSlots;
    }

    @Override
    public int filterCount() {
        return FILTER_SLOTS;
    }

    @Override
    public ItemStack getFilterAt(int index) {
        return getFilter(index);
    }

    @Override
    public void setFilterAt(int index, ItemStack stack) {
        setFilter(index, stack);
    }

    public ItemStack getFilter(int index) {
        if (index >= 0 && index < FILTER_SLOTS) {
            return filterSlots.get(index);
        }
        return ItemStack.EMPTY;
    }

    public void setFilter(int index, ItemStack filterItem) {
        if (index >= 0 && index < FILTER_SLOTS) {
            if (filterItem.isEmpty()) {
                filterSlots.set(index, ItemStack.EMPTY);
            } else {
                ItemStack copy = filterItem.copy();
                copy.setCount(1);
                filterSlots.set(index, copy);
            }
            setChanged();
            syncFiltersToWatchers();
        }
    }

    public void syncFiltersToWatchers() {
        if (level != null && !level.isClientSide && level instanceof ServerLevel sl) {
            SyncExtractorFiltersPKT pkt = buildFilterPacket();
            for (ServerPlayer player : sl.players()) {
                if (player.containerMenu instanceof VeloceExtractorMenu menu && menu.getPos().equals(worldPosition)) {
                    PacketDistributor.sendToPlayer(player, pkt);
                }
            }
        }
    }

    public void syncFiltersToPlayer(ServerPlayer player) {
        if (level != null && !level.isClientSide) {
            PacketDistributor.sendToPlayer(player, buildFilterPacket());
        }
    }

    /** Filters + auto-crafting flags in one packet. */
    private SyncExtractorFiltersPKT buildFilterPacket() {
        List<Boolean> flags = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            flags.add(allowCrafting[i]);
        }
        return new SyncExtractorFiltersPKT(worldPosition, new ArrayList<>(filterSlots), flags);
    }

    public void serverTick() {
        // Guard: we now read the game time from the level, and the ticker should
        // not reach here before setLevel() - but an NPE in a tick would kill the
        // server, so we do not assume it away.
        if (level == null) {
            return;
        }
        // REDSTONE STOPS THE EXTRACTOR.
        //
        // Player: "let it react to redstone - when it gets redstone it must not work."
        //
        // The gate is the FIRST thing in the tick, so a powered extractor does nothing at
        // all: it does not pull the filters from the network, does not auto-craft the
        // items it is missing and does not deposit anything. Gating only the pull would
        // have left the deposit half running, which looks like "mostly works" and is worse
        // than either answer.
        //
        // `hasNeighborSignal` is the same test a piston or a dispenser uses for "is this
        // block powered", so it matches what a player means by putting a lever or a piece
        // of redstone dust against the machine - it covers all six sides, and both a
        // signal fed directly into the block and one running past it.
        //
        // `lastPullTick` is deliberately NOT advanced while powered. The interval is
        // phase-spread by block position (see VeloceTick.everySpread), so burning the
        // window while switched off would make the extractor wait out a phase it never
        // used; leaving the timer alone means it resumes on the first tick the signal
        // drops.
        if (level.hasNeighborSignal(worldPosition)) {
            return;
        }
        // A pull from the network every 10 ticks (0.5 s), but with a phase
        // depending on the block position - so that several extractors do not
        // hit the network at once.
        if (com.craftingveloce.util.VeloceTick.everySpread(
                level.getGameTime(), lastPullTick, PULL_INTERVAL_TICKS, worldPosition)) {
            lastPullTick = level.getGameTime();
            pullFilteredItemsFromNetwork();
        }
    }

    /** Every how many ticks the extractor tries to pull the filters from the network. */
    private static final int PULL_INTERVAL_TICKS = 10;

    private void pullFilteredItemsFromNetwork() {
        if (level == null || level.isClientSide || !(level instanceof ServerLevel sl)) return;

        VelocePipeNetworkManager manager = VelocePipeNetworkManager.get(sl);
        VelocePipeNetwork net = manager.getNetworkForTerminal(sl, worldPosition);
        if (net == null) return;

        // PHASE 1: just the pull from the network - cheap, for all 9 slots.
        //
        // The order matters. Previously every slot went straight through the full
        // "pull or craft" path, and that meant up to 9 recipe-tree plans per cycle
        // (every 10 ticks, per extractor) plus 9 forced network scans. The server
        // had no chance.
        boolean[] needsCraft = new boolean[9];
        for (int i = 0; i < 9; i++) {
            ItemStack filter = filterSlots.get(i);
            if (filter.isEmpty()) continue;

            ItemStack currentOutput = outputInventory.getItem(i);
            int maxStack = filter.getMaxStackSize();
            int needed;

            if (currentOutput.isEmpty()) {
                needed = maxStack;
            } else if (ItemStack.isSameItemSameComponents(currentOutput, filter)) {
                needed = maxStack - currentOutput.getCount();
            } else {
                continue;   // slot occupied by something else
            }
            if (needed <= 0) continue;

            ItemStack direct = net.extractItem(sl, filter.getItem(), needed);
            if (!direct.isEmpty()) {
                depositIntoSlot(i, currentOutput, direct);
            } else {
                needsCraft[i] = true;
            }
        }

        // PHASE 2: crafting only for slots that really ran short of something,
        // and only within ONE shared budget for the whole cycle. A single free
        // craft must not eat the time allotted to the remaining slots.
        long deadline = System.nanoTime() + PULL_CRAFT_BUDGET_NS;
        for (int i = 0; i < 9; i++) {
            if (!needsCraft[i]) continue;
            // A slot with auto-crafting disabled gets only what is already in
            // the network - we never order a craft for it.
            if (!allowCrafting[i]) continue;
            if (System.nanoTime() > deadline) {
                break;   // the rest in the next cycle
            }
            ItemStack filter = filterSlots.get(i);
            ItemStack currentOutput = outputInventory.getItem(i);
            int maxStack = filter.getMaxStackSize();
            // The same condition as in phase 1: the slot may be occupied by
            // another item (nothing else writes to it, but we do not assume it away).
            int needed;
            if (currentOutput.isEmpty()) {
                needed = maxStack;
            } else if (ItemStack.isSameItemSameComponents(currentOutput, filter)) {
                needed = maxStack - currentOutput.getCount();
            } else {
                continue;
            }
            if (needed <= 0) continue;

            ItemStack crafted = craftFromNetwork(sl, net, filter.getItem(), needed);
            if (!crafted.isEmpty()) {
                depositIntoSlot(i, currentOutput, crafted);
            }
        }
    }

    /** Inserts the pulled stack into the output slot (new, or topping up an existing one). */
    private void depositIntoSlot(int slot, ItemStack currentOutput, ItemStack pulled) {
        if (currentOutput.isEmpty()) {
            outputInventory.setItem(slot, pulled);
        } else {
            currentOutput.grow(pulled.getCount());
            outputInventory.setItem(slot, currentOutput);
        }
        setChanged();
    }

    /**
     * How long auto-crafting may take in total in one extractor cycle.
     *
     * <p>The cycle runs every 10 ticks per extractor, so 10 ms is already 20% of
     * the tick budget for a single device. The rest waits for the next cycle.
     */
    private static final long PULL_CRAFT_BUDGET_NS = 10_000_000L;

    /**
     * Auto-crafts the missing item and takes it out of the buffer/network.
     *
     * <p>Called ONLY when the direct pull failed (see phase 2 in
     * {@link #pullFilteredItemsFromNetwork}) and only within the shared cycle
     * budget. Previously every slot went down this path unconditionally.
     */
    private ItemStack craftFromNetwork(ServerLevel sl, VelocePipeNetwork net, Item item, int count) {
        // Auto-crafting: only if some crafter has the recipe for the item enabled.
        var crafter = com.craftingveloce.crafting.VeloceCraftingRegistry
                .findEnabledCrafter(sl, net, item);
        if (crafter == null) {
            return ItemStack.EMPTY;
        }

        var enabled = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getAllEnabledItems(sl, net);
        var preferred = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getPreferredRecipes(sl, net);
        var buffers = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getBuffers(sl, net);
        // The block position = the emergency drop location, in case the network were full.
        var ctx = new com.craftingveloce.crafting.VeloceAutoCrafter.Context(
                sl, net, enabled, preferred, null, buffers, this.getBlockPos());
        // A small planning budget: this is background work, not a player request.
        // The rest will wait for the next cycle, instead of blocking the server thread.
        var result = com.craftingveloce.crafting.VeloceAutoCrafter
                .ensureAvailable(sl, net, item, count, ctx, CRAFT_PLAN_BUDGET_NS);
        if (!result.success()) {
            return ItemStack.EMPTY;
        }

        // We take EXACTLY as much as actually came into being - not as much as we
        // asked for. When the material sufficed for 12 out of 64, ensureAvailable
        // makes 12 and that much should go to the slot; requesting 64 would
        // incidentally pull items that lay in the network for other reasons.
        int got = Math.max(1, Math.min(count, result.produced()));

        // The result goes first to the crafter's buffer, which is not a network
        // endpoint - that is why we first pull from the buffers, then from the network.
        ItemStack fromBuffer = extractFromBuffers(buffers, item, got);
        if (!fromBuffer.isEmpty()) {
            return fromBuffer;
        }
        return net.extractItem(sl, item, got);
    }

    /** The planning budget for one missing item in an extractor cycle. */
    private static final long CRAFT_PLAN_BUDGET_NS = 3_000_000L;

    /** Pulls the item from the auto-crafter buffers. */
    private static ItemStack extractFromBuffers(
            java.util.List<com.craftingveloce.inventory.VeloceCraftingBuffer> buffers,
            Item item, int count) {
        ItemStack result = ItemStack.EMPTY;
        int remaining = count;
        for (var buf : buffers) {
            if (remaining <= 0) {
                break;
            }
            for (int slot = 0; slot < buf.getContainerSize() && remaining > 0; slot++) {
                ItemStack inSlot = buf.getItem(slot);
                if (inSlot.isEmpty() || inSlot.getItem() != item) {
                    continue;
                }
                int take = Math.min(remaining, inSlot.getCount());
                ItemStack taken = buf.removeItem(slot, take);
                if (taken.isEmpty()) {
                    continue;
                }
                if (result.isEmpty()) {
                    result = taken.copy();
                } else {
                    result.grow(taken.getCount());
                }
                remaining -= taken.getCount();
            }
        }
        return result;
    }

    /**
     * Builds the NBT entry of one stack, together with the slot number.
     *
     * <p><b>The BUG this fixes (filters and inventory were not being saved).</b>
     * In 1.21.1 {@code ItemStack.save} has the form:
     * <pre>
     *   public Tag save(HolderLookup.Provider provider, Tag prefix)
     * </pre>
     * that is, it <b>RETURNS a new tag</b> (using the one passed in as a base),
     * and does NOT mutate it in place. The previous code called:
     * <pre>
     *   itemTag.putByte("Slot", (byte) i);
     *   stack.save(registries, itemTag);      // result DISCARDED
     *   filterList.add(itemTag);
     * </pre>
     * so only the slot number reached the save, without the item. In the world
     * file it looked literally like this:
     * <pre>
     *   Filters: [{'Slot': 6}]      // no "id" and no "count"
     *   Outputs: [{'Slot': 6}]      // no "id" and no "count"
     * </pre>
     * Effect: after re-entering, the extractor's filters and inventory were empty.
     *
     * <p>Now we take the RETURNED tag. We add the slot number to the result, so
     * it works regardless of whether the implementation keeps the passed-in base.
     */
    private static CompoundTag saveStackEntry(ItemStack stack, int slot,
                                              HolderLookup.Provider registries) {
        // save() throws for an empty stack - the caller must check for that.
        CompoundTag base = new CompoundTag();
        base.putByte("Slot", (byte) slot);
        net.minecraft.nbt.Tag saved = stack.save(registries, base);
        CompoundTag entry = saved instanceof CompoundTag ct ? ct : base;
        entry.putByte("Slot", (byte) slot);
        return entry;
    }

    /**
     * Reads a stack from an NBT entry.
     *
     * <p>We remove our own {@code Slot} key from the copy before parsing, so that
     * the item codec has no chance to stumble over it.
     */
    private static ItemStack loadStackEntry(CompoundTag entry,
                                            HolderLookup.Provider registries) {
        CompoundTag clean = entry.copy();
        clean.remove("Slot");
        return ItemStack.parse(registries, clean).orElse(ItemStack.EMPTY);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag filterList = new ListTag();
        for (int i = 0; i < 9; i++) {
            if (!filterSlots.get(i).isEmpty()) {
                filterList.add(saveStackEntry(filterSlots.get(i), i, registries));
            }
        }
        tag.put("Filters", filterList);

        // The per-slot auto-crafting flags. We save the list of DISABLED indices,
        // so that old worlds (without this tag) get the default "enabled".
        ListTag noCraft = new ListTag();
        for (int i = 0; i < 9; i++) {
            if (!allowCrafting[i]) {
                noCraft.add(net.minecraft.nbt.IntTag.valueOf(i));
            }
        }
        tag.put("NoCraft", noCraft);

        ListTag outputList = new ListTag();
        for (int i = 0; i < outputInventory.getContainerSize(); i++) {
            ItemStack stack = outputInventory.getItem(i);
            if (!stack.isEmpty()) {
                outputList.add(saveStackEntry(stack, i, registries));
            }
        }
        tag.put("Outputs", outputList);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        java.util.Collections.fill(filterSlots, ItemStack.EMPTY);
        ListTag filterList = tag.getList("Filters", Tag.TAG_COMPOUND);
        for (int i = 0; i < filterList.size(); i++) {
            CompoundTag itemTag = filterList.getCompound(i);
            int slot = itemTag.getByte("Slot") & 255;
            if (slot < 9) {
                ItemStack loaded = loadStackEntry(itemTag, registries);
                if (!loaded.isEmpty()) {
                    filterSlots.set(slot, loaded);
                }
            }
        }

        java.util.Arrays.fill(allowCrafting, true);
        ListTag noCraft = tag.getList("NoCraft", Tag.TAG_INT);
        for (int i = 0; i < noCraft.size(); i++) {
            int slot = noCraft.getInt(i);
            if (slot >= 0 && slot < 9) {
                allowCrafting[slot] = false;
            }
        }

        outputInventory.clearContent();
        ListTag outputList = tag.getList("Outputs", Tag.TAG_COMPOUND);
        for (int i = 0; i < outputList.size(); i++) {
            CompoundTag itemTag = outputList.getCompound(i);
            int slot = itemTag.getByte("Slot") & 255;
            if (slot < outputInventory.getContainerSize()) {
                ItemStack loaded = loadStackEntry(itemTag, registries);
                if (!loaded.isEmpty()) {
                    outputInventory.setItem(slot, loaded);
                }
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Veloce Extractor");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new VeloceExtractorMenu(containerId, playerInventory, worldPosition, this);
    }

    // --- WorldlyContainer implementation (for Hoppers & vanilla extraction) ---

    @Override
    public int[] getSlotsForFace(Direction side) {
        return OUTPUT_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction dir) {
        return false; // Prevent external insertion into extractor
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction dir) {
        return true; // Allow hoppers and pipes to extract items
    }

    @Override
    public int getContainerSize() {
        return outputInventory.getContainerSize();
    }

    @Override
    public boolean isEmpty() {
        return outputInventory.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return outputInventory.getItem(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack res = outputInventory.removeItem(slot, amount);
        setChanged();
        return res;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack res = outputInventory.removeItemNoUpdate(slot);
        setChanged();
        return res;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        outputInventory.setItem(slot, stack);
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return outputInventory.stillValid(player);
    }

    @Override
    public void clearContent() {
        outputInventory.clearContent();
        setChanged();
    }
}
