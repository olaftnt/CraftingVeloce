package com.craftingveloce.block.entity;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Velocity Furnace - an "instant" furnace for the auto-crafter.
 *
 * <p><b>How it works.</b> This is NOT a furnace that physically smelts the raw
 * material. It is a <b>heat source</b> for the crafter:
 * <ol>
 *   <li>It burns ALL THE TIME, regardless of whether anything is being crafted.
 *       That is the cost the player pays for instant smelting.</li>
 *   <li>It pulls fuel from the network according to 6 filters, in priority
 *       order: first it exhausts filter 1, then 2, then 3...</li>
 *   <li>A crafter that finds this furnace in the network and sees that it is
 *       burning may use furnace recipes (smelting/blasting/smoking) INSTANTLY.</li>
 *   <li>Each such smelt takes {@link #SMELT_HEAT_COST} heat units from the
 *       furnace. When the heat drops to zero, the furnace must pull another
 *       fuel item - so the more the crafter smelts, the faster the furnace
 *       devours fuel.</li>
 * </ol>
 *
 * <p><b>Fuel cost.</b> Vanilla smelts 1 item in 200 ticks, so coal (1600 t)
 * lasts for 8 pieces. Here a smelt costs {@link #SMELT_HEAT_COST} = 300 ticks,
 * that is <b>1.5x more fuel</b>: coal lasts for 5 pieces. The multiplier sits
 * in the cost, and not in the burn value - thanks to that it works uniformly
 * for every fuel (including modded ones), because we take the burn values from
 * vanilla.
 */
public class VeloceVelocityFurnaceBlockEntity extends BlockEntity
        implements MenuProvider, VeloceHeatSource, VeloceFilterHost {

    /** How many fuel filters there are (priority from 0 upwards). */
    public static final int FUEL_FILTERS = 6;

    /**
     * The cost of one instant smelt, in burn ticks.
     *
     * <p>Vanilla: 200 ticks per item. Here 300 = a 1.5x multiplier.
     */
    public static final long SMELT_HEAT_COST = 300L;

    /** Every how many ticks we try to pull fuel from the network. */
    private static final int PULL_INTERVAL_TICKS = 10;

    /** How many fuel items we pull at once (a reserve, so we do not pull every tick). */
    private static final int PULL_BATCH = 8;

    /** Fuel filters - which items the furnace may burn and in what order. */
    private final NonNullList<ItemStack> fuelFilters =
            NonNullList.withSize(FUEL_FILTERS, ItemStack.EMPTY);

    /** The slot holding the fuel the furnace is currently processing. */
    private final SimpleContainer fuelSlot = new SimpleContainer(1);

    /** How many burn ticks are left in the heat buffer. */
    private long burnTicksRemaining;

    /**
     * How many burn ticks the last burned item gave.
     *
     * <p>Needed only for the bar/flame - it shows the full range.
     */
    private long burnTicksTotal;

    /**
     * The tick in which we last pulled fuel from the network (periodic schedule).
     *
     * <p>{@code Long.MIN_VALUE} = not even once yet. Then the first pull is
     * decided by a phase computed from the furnace position, and not by a
     * common start from zero - otherwise all furnaces would pick up fuel in the
     * same tick.
     */
    private long lastFuelPullTick = Long.MIN_VALUE;

    /**
     * Whether the furnace should skip the countdown and pull fuel in this tick.
     *
     * <p>Set by {@link #consumeOperations(long)}, when the crafter eats the heat
     * down to zero. One-shot - after pulling, the flag goes out.
     */
    private boolean wantImmediatePull = false;

    /** Every how many ticks we send the heat state to the client (the flame in the GUI). */
    private static final int CLIENT_SYNC_INTERVAL_TICKS = 10;

    private int clientSyncCooldown = CLIENT_SYNC_INTERVAL_TICKS;

    public VeloceVelocityFurnaceBlockEntity(BlockPos pos, BlockState state) {
        super(com.craftingveloce.init.VeloceRegistry.VELOCITY_FURNACE_BE.get(), pos, state);
        // Every change in the fuel slot must reach the save.
        this.fuelSlot.addListener(c -> setChanged());
    }

    // ------------------------------------------------------------------
    // VeloceHeatSource - this is what the crafter sees
    // ------------------------------------------------------------------

    @Override
    public long availableOperations() {
        return burnTicksRemaining / SMELT_HEAT_COST;
    }

    @Override
    public void consumeOperations(long operations) {
        if (operations <= 0) {
            return;
        }
        burnTicksRemaining = Math.max(0L, burnTicksRemaining - operations * SMELT_HEAT_COST);
        // The buffer dropped below one smelt - the furnace must reach for more
        // fuel as soon as possible, otherwise it stops being "powered".
        if (burnTicksRemaining < SMELT_HEAT_COST) {
            wantImmediatePull = true;
        }
        setChanged();
    }

    @Override
    public boolean isPowered() {
        // "Powered" = the flame is burning. A furnace with an empty heat buffer
        // does not unlock recipes, even if it has fuel in the slot - it must
        // burn it first.
        return burnTicksRemaining > 0;
    }

    @Override
    public int heatPriority() {
        return 1;   // fuel-based - fallback after the electric one
    }

    @Override
    public String heatSourceName() {
        return "Velocity Furnace";
    }

    // ------------------------------------------------------------------
    // Slot access (menu and GUI)
    // ------------------------------------------------------------------

    /** The slot holding the fuel to be burned. */
    public Container getFuelSlot() {
        return fuelSlot;
    }

    /** The fuel filter with the given index (0..5). */
    @Override
    public int filterCount() {
        return FUEL_FILTERS;
    }

    @Override
    public ItemStack getFilterAt(int index) {
        return getFuelFilter(index);
    }

    @Override
    public void setFilterAt(int index, ItemStack stack) {
        setFuelFilter(index, stack);
    }

    public ItemStack getFuelFilter(int index) {
        return index >= 0 && index < FUEL_FILTERS ? fuelFilters.get(index) : ItemStack.EMPTY;
    }

    /** Sets the fuel filter. The change reaches the save. */
    public void setFuelFilter(int index, ItemStack stack) {
        if (index < 0 || index >= FUEL_FILTERS) {
            return;
        }
        fuelFilters.set(index, stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        setChanged();
    }

    /** Whether the flame is burning - for drawing the icon. */
    public boolean isLit() {
        return burnTicksRemaining > 0;
    }

    /** How much heat is left (burn ticks). */
    public long getBurnTicksRemaining() {
        return burnTicksRemaining;
    }

    /** The full range of the last burn - for the bar. */
    public long getBurnTicksTotal() {
        return burnTicksTotal;
    }

    // ------------------------------------------------------------------
    // Background work
    // ------------------------------------------------------------------

    public void serverTick() {
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        boolean wasLit = isLit();

        // 0. The crafter has just eaten our heat - pick up fuel IMMEDIATELY.
        //
        // This is directly a specification requirement: "when the Crafter
        // performs a smelting operation, it sends a tick to the furnace and cuts
        // its burn time, which forces a faster pickup of the next fuel". Without
        // this the furnace would wait until PULL_INTERVAL_TICKS, and during that
        // time it would be "unpowered" - and the crafter that just paid with
        // heat could not pay next time, even though the fuel lies in the network.
        //
        // The flag is handled in step 3 below (together with the spread
        // schedule), so that there are not two places deciding about the pull.

        // 1. The furnace burns ALL THE TIME - this is the cost of instant crafting.
        //    The heat buffer drains regardless of whether anyone is crafting.
        if (burnTicksRemaining > 0) {
            burnTicksRemaining--;
        }

        // 2. Buffer empty -> burn one item of fuel.
        if (burnTicksRemaining <= 0) {
            tryBurnFuel();
        }

        // 3. Fuel reserve low -> pull from the network according to the filters.
        //
        // SPREAD ACROSS TICKS: a cooldown counted from zero at every furnace meant
        // that ALL furnaces picked up fuel in the SAME tick (each started with
        // pullCooldown == 0), so instead of work spread evenly there was a load
        // spike every 10 ticks. Now each furnace's phase follows from its position.
        //
        // The IMMEDIATE pickup (after the crafter ate the heat) stays unchanged -
        // and deliberately does NOT shift the schedule, so that several furnaces
        // serving one craft do not re-synchronise.
        long nowTick = sl.getGameTime();
        if (wantImmediatePull) {
            wantImmediatePull = false;
            pullFuelFromNetwork(sl);
        } else if (com.craftingveloce.util.VeloceTick.everySpread(
                nowTick, lastFuelPullTick, PULL_INTERVAL_TICKS, worldPosition)) {
            lastFuelPullTick = nowTick;
            pullFuelFromNetwork(sl);
        }

        if (wasLit != isLit()) {
            // Flame state changed - the client must see it.
            setChanged();
            syncToClients();
        }

        // The flame in the GUI should show the CONSUMPTION of the buffer, so the
        // "burning" state alone is not enough - the value must be sent too. Once
        // every 10 ticks is twice per second: smooth for the eye, and negligible
        // for the network (a few bytes).
        if (--clientSyncCooldown <= 0) {
            clientSyncCooldown = CLIENT_SYNC_INTERVAL_TICKS;
            syncToClients();
        }
    }

    /** Burns one item from the fuel reserve, if it is fuel at all. */
    private void tryBurnFuel() {
        ItemStack fuel = fuelSlot.getItem(0);
        if (fuel.isEmpty()) {
            return;
        }
        long burn = burnTicksOf(fuel);
        if (burn <= 0) {
            // It is not fuel - we do not burn it and we do not block the slot.
            return;
        }
        ItemStack burned = fuel.copyWithCount(1);
        fuelSlot.removeItem(0, 1);
        burnTicksTotal = burn;
        burnTicksRemaining = burn;
        returnContainerRemainder(burned);
    }

    /**
     * Returns whatever is left after burning the fuel - e.g. an EMPTY BUCKET after lava.
     *
     * <p><b>The BUG this fixes (player question: "what will it do with the bucket?").</b>
     * The furnace only did {@code removeItem(0, 1)} and that was it - the bucket
     * disappeared. Vanilla returns the item's crafting remainder
     * ({@code getCraftingRemainingItem}), and the fuel arrives to us from the
     * network, so the remainder goes back there too. When the network is full,
     * the rest is dropped on the ground next to the furnace (see
     * {@link #depositBack}) - it is NEVER deleted.
     */
    private void returnContainerRemainder(ItemStack burnedFuel) {
        if (!burnedFuel.hasCraftingRemainingItem()) {
            return;
        }
        ItemStack remainder = burnedFuel.getCraftingRemainingItem();
        if (remainder.isEmpty()) {
            return;
        }
        depositBack(remainder);
    }

    /**
     * Whether this item can be smelted as fuel at all.
     *
     * <p>The ONE place for the whole "what is fuel" question - used by the server
     * (accepting fuel) and by the client (filters, filter selector). Otherwise
     * the client and the server could have two different answers to the same
     * question.
     */
    public static boolean isFuel(ItemStack stack) {
        return burnTicksOf(stack) > 0;
    }

    /**
     * Whether the fuel data is available on this side at all.
     *
     * <p><b>Canary.</b> We ask about coal - that is fuel in every modpack. If the
     * answer is "no", it means this side lacks the fuel data and we MUST NOT
     * block or colour anything based on it: in the filter selector it would
     * colour ALL items red, and the player could not even pick coal. In such a
     * situation we simply do not enforce the fuel filter (the server checks them
     * on its side anyway).
     */
    private static Boolean fuelDataAvailable;

    public static boolean fuelDataAvailable() {
        if (fuelDataAvailable == null) {
            fuelDataAvailable = burnTicksOf(new ItemStack(net.minecraft.world.item.Items.COAL)) > 0;
            if (!fuelDataAvailable) {
                com.craftingveloce.util.VeloceLog.Network.failure(
                        com.craftingveloce.util.VeloceLog.Side.CLIENT,
                        "no fuel data on this side (coal came out as non-fuel) -"
                                + " the fuel filter will NOT be enforced in the GUI");
            }
        }
        return fuelDataAvailable;
    }

    /**
     * Whether this item MUST NOT be placed into the fuel filter.
     *
     * <p>One place for the GUI (selector + furnace screen): the red highlight
     * and the click rejection must ask about the same thing.
     */
    public static boolean isUnusableFuelFilter(ItemStack stack) {
        return fuelDataAvailable() && !isFuel(stack);
    }

    /** How many burn ticks this stack gives (0 = it is not fuel). */
    public static long burnTicksOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0L;
        }
        // THE SAME PATH AS THE VANILLA FURNACE.
        //
        // Vanilla computes the burn time like this: AbstractFurnaceBlockEntity
        // .getBurnDuration() -> ItemStack.getBurnTime(recipeType). We follow
        // exactly that route, because it goes through the fuel values from data
        // (FuelValues) and UNROLLS TAGS.
        //
        // The BUG that was here: we asked an obsolete static map
        // AbstractFurnaceBlockEntity.getFuel(), which only knows fuels entered
        // per-item. Fuel defined by a tag or added by another mod through data
        // had no entry there, so our fallback gave it exactly 200 ticks - that
        // is, such fuel burned in our furnace many times faster than it should
        // (coal is 1600 ticks).
        int ticks = stack.getBurnTime(net.minecraft.world.item.crafting.RecipeType.SMELTING);
        return Math.max(0L, ticks);
    }

    /**
     * Pulls fuel from the network according to the filters, in priority order.
     *
     * <p>First we exhaust filter 1, then 2, then 3... - as agreed. An empty
     * filter is skipped, so the player may set only the ones they want.
     */
    private void pullFuelFromNetwork(ServerLevel sl) {
        ItemStack current = fuelSlot.getItem(0);
        int space = 64 - current.getCount();
        if (space <= 0) {
            return;   // reserve full
        }
        VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                .getNetworkForTerminal(sl, worldPosition);
        if (net == null) {
            return;
        }
        int wanted = Math.min(space, PULL_BATCH);

        // NO FILTERS = any fuel.
        //
        // Thanks to that the furnace works right after being placed, with no
        // configuration - and the filters serve to NARROW the choice, not to
        // enable it.
        boolean anyFilter = false;
        for (int i = 0; i < FUEL_FILTERS; i++) {
            if (!fuelFilters.get(i).isEmpty()) {
                anyFilter = true;
                break;
            }
        }

        if (!anyFilter) {
            pullAnyFuel(sl, net, wanted);
            return;
        }

        for (int i = 0; i < FUEL_FILTERS && wanted > 0; i++) {
            ItemStack filter = fuelFilters.get(i);
            if (filter.isEmpty()) {
                continue;
            }
            if (!canMergeIntoFuelSlot(filter)) {
                continue;
            }
            ItemStack got = net.extractItem(sl, filter.getItem(), wanted);
            if (got.isEmpty()) {
                continue;   // this filter ran out - on to the next in order
            }
            wanted -= got.getCount();
            mergeIntoFuelSlot(got);
        }
    }

    /** The no-filter mode: it takes anything that is fuel. */
    private void pullAnyFuel(ServerLevel sl, VelocePipeNetwork net, int wanted) {
        for (var entry : net.getAllItemCounts(sl).entrySet()) {
            if (wanted <= 0) {
                break;
            }
            ItemStack probe = new ItemStack(entry.getKey());
            if (burnTicksOf(probe) <= 0) {
                continue;
            }
            if (!canMergeIntoFuelSlot(probe)) {
                continue;
            }
            ItemStack got = net.extractItem(sl, entry.getKey(), wanted);
            if (!got.isEmpty()) {
                wanted -= got.getCount();
                mergeIntoFuelSlot(got);
            }
        }
    }

    /**
     * Whether this fuel fits in the slot - checked BEFORE pulling.
     *
     * <p><b>The BUG this fixes (a pull-return loop).</b> Previously the furnace
     * pulled the fuel first, and only then did {@link #mergeIntoFuelSlot}
     * discover that a DIFFERENT kind of fuel lay in the slot and they could not
     * be merged - so it gave it back to the network via {@code depositBack}.
     *
     * <p>It looked like this, every PULL_INTERVAL_TICKS, endlessly:
     * <pre>
     *   pull charcoal from network -> does not match the coal in the slot -> return charcoal
     *   pull charcoal from network -> does not match the coal in the slot -> return charcoal
     *   ...
     * </pre>
     * And since returning to storage in an unloaded chunk loads that chunk, every
     * turn of the loop meant another chunk load - that is, exactly the
     * load/unload loop that could be seen in the game.
     *
     * <p>Now we simply do NOT pull what we cannot accept: the furnace first
     * burns what it has in the slot, and only then reaches for the next kind of
     * fuel.
     */
    private boolean canMergeIntoFuelSlot(ItemStack fuel) {
        ItemStack current = fuelSlot.getItem(0);
        if (current.isEmpty()) {
            return true;   // an empty slot will accept anything
        }
        return ItemStack.isSameItemSameComponents(current, fuel);
    }

    /** Adds the pulled fuel to the slot (or leaves it, when it does not fit). */
    private void mergeIntoFuelSlot(ItemStack got) {
        ItemStack current = fuelSlot.getItem(0);
        if (current.isEmpty()) {
            fuelSlot.setItem(0, got);
            return;
        }
        if (ItemStack.isSameItemSameComponents(current, got)) {
            int space = current.getMaxStackSize() - current.getCount();
            int move = Math.min(space, got.getCount());
            current.grow(move);
            // The surplus returns to the network - we do not lose items.
            if (move < got.getCount()) {
                ItemStack rest = got.copyWithCount(got.getCount() - move);
                depositBack(rest);
            }
            fuelSlot.setChanged();
        } else {
            depositBack(got);
        }
    }

    /**
     * Returns the item to the network, and whatever the network will not accept
     * is dropped next to the furnace.
     *
     * <p><b>The BUG this fixes (player question: "what if the network has no
     * room?").</b> {@code insertIntoStorage} returns the LEFTOVER that could not
     * be squeezed in - and we ignored it, so with a full network the item simply
     * DISAPPEARED (the fuel surplus, the empty bucket after lava, anything).
     * Now the leftover lands on the ground in front of the furnace, just as a
     * player does when filling a chest. No item is lost.
     */
    private void depositBack(ItemStack stack) {
        if (stack.isEmpty() || !(level instanceof ServerLevel sl)) {
            return;
        }
        // NOTE: the variable MUST NOT be named `net` - it would shadow the `net`
        // package and `net.minecraft...` would stop compiling (the same trap
        // that already happened once in VelocePipeNetwork).
        VelocePipeNetwork network = VelocePipeNetworkManager.get(sl)
                .getNetworkForTerminal(sl, worldPosition);
        ItemStack leftover = stack;
        if (network != null) {
            leftover = network.insertIntoStorage(sl, stack);
        }
        if (!leftover.isEmpty()) {
            net.minecraft.world.level.block.Block.popResource(sl, worldPosition, leftover);
            com.craftingveloce.util.VeloceLog.Block.failure(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "furnace: network does not accept (%s) - dropping next to the furnace", leftover);
        }
    }

    private void syncToClients() {
        if (level instanceof ServerLevel sl) {
            sl.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ------------------------------------------------------------------
    // Save / load
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        net.minecraft.nbt.ListTag filters = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < FUEL_FILTERS; i++) {
            ItemStack f = fuelFilters.get(i);
            if (!f.isEmpty()) {
                CompoundTag e = new CompoundTag();
                e.putByte("Slot", (byte) i);
                // NOTE: save() RETURNS a new tag, it does not mutate the one passed in.
                CompoundTag saved = (CompoundTag) f.save(registries, new CompoundTag());
                saved.putByte("Slot", (byte) i);
                filters.add(saved);
            }
        }
        tag.put("FuelFilters", filters);

        ItemStack fuel = fuelSlot.getItem(0);
        if (!fuel.isEmpty()) {
            CompoundTag saved = (CompoundTag) fuel.save(registries, new CompoundTag());
            tag.put("Fuel", saved);
        }
        tag.putLong("Heat", burnTicksRemaining);
        tag.putLong("HeatTotal", burnTicksTotal);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        for (int i = 0; i < FUEL_FILTERS; i++) {
            fuelFilters.set(i, ItemStack.EMPTY);
        }
        net.minecraft.nbt.ListTag filters =
                tag.getList("FuelFilters", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < filters.size(); i++) {
            CompoundTag e = filters.getCompound(i);
            int slot = e.getByte("Slot") & 255;
            if (slot >= 0 && slot < FUEL_FILTERS) {
                CompoundTag clean = e.copy();
                clean.remove("Slot");
                ItemStack.parse(registries, clean).ifPresent(s -> fuelFilters.set(slot, s));
            }
        }

        fuelSlot.setItem(0, tag.contains("Fuel")
                ? ItemStack.parse(registries, tag.getCompound("Fuel")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY);
        burnTicksRemaining = tag.getLong("Heat");
        burnTicksTotal = tag.getLong("HeatTotal");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.craftingveloce.velocity_furnace");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new com.craftingveloce.inventory.VeloceVelocityFurnaceMenu(
                id, inv, getBlockPos(), this);
    }
}
