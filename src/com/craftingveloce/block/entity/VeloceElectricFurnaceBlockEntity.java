package com.craftingveloce.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Velocity Electric Furnace - a heat source powered by Forge Energy.
 *
 * <p><b>Difference from the fuel-powered one.</b> It has no filter and no fuel
 * slot and does not burn in the background - it keeps energy in an internal
 * accumulator, and every instant smelt takes a fixed portion of FE from it.
 * Thanks to that it does not eat anything when nobody is crafting.
 *
 * <p><b>PRIORITY.</b> {@link #heatPriority()} returns 0, i.e. less than the
 * fuel-powered one (1) - the crafter picks the source with the LOWEST priority,
 * so the electric one is used FIRST, and the fuel-powered one is the fallback.
 * Exactly as agreed.
 *
 * <p><b>Numbers (agreed with the user).</b>
 * <ul>
 *   <li>accumulator capacity: {@link #ENERGY_CAPACITY} = 25 000 000 FE</li>
 *   <li>cost of one smelt: {@link #FE_PER_SMELT} = 200 000 FE</li>
 * </ul>
 * So a full accumulator is enough for 125 smelts, and a stack (64 items)
 * costs 12 800 000 FE.
 */
public class VeloceElectricFurnaceBlockEntity extends BlockEntity
        implements MenuProvider, VeloceHeatSource, IEnergyStorage {

    /** Capacity of the internal accumulator. */
    public static final int ENERGY_CAPACITY = 25_000_000;

    /** Cost of one instant smelt. */
    public static final int FE_PER_SMELT = 200_000;

    /** How much FE is in the accumulator right now. */
    private int energy;

    /**
     * Slot for an ITEM WITH ENERGY (battery, energy cube, tablet from another mod).
     *
     * <p>The furnace draws power from it like from a cable - one way only. The
     * item stays in the slot until the player takes it out; it cannot be
     * "burned up" here.
     */
    private final net.minecraft.world.SimpleContainer batterySlot =
            new net.minecraft.world.SimpleContainer(1);

    /**
     * How much FE per tick we extract from the item at most.
     *
     * <p>Without a limit a full battery would pour itself into the accumulator
     * in a single tick. One second (20 ticks) is 20 million FE, i.e. almost a
     * full accumulator - fast enough, and at the same time you can see the
     * battery draining.
     */
    public static final int MAX_ITEM_DRAIN_PER_TICK = 1_000_000;

    /**
     * Until when (gameTime) to show "freshly powered" - for the bar in the GUI.
     *
     * <p>For now it is only diagnostics via chat; the field stays so that the
     * GUI can show the last change without constant syncing.
     */
    private long lastEnergyChangeTick;

    public VeloceElectricFurnaceBlockEntity(BlockPos pos, BlockState state) {
        super(com.craftingveloce.init.VeloceRegistry.ELECTRIC_FURNACE_BE.get(), pos, state);
        // Every change in the battery slot must make it into the save.
        this.batterySlot.addListener(c -> setChanged());
    }

    /** The battery slot - for the menu (and for the screen that shows the hint). */
    public net.minecraft.world.Container getBatterySlot() {
        return batterySlot;
    }

    /**
     * Whether this item can be "discharged" here - i.e. whether it has energy to give.
     *
     * <p>We ask for the standard NeoForge capability (Forge Energy on an item).
     * That is exactly what other mods do: Energy Cube, batteries, tablets. An item
     * that stores energy in NBT but does NOT expose that capability will not work -
     * and we say so plainly in the slot hint instead of pretending that it works.
     */
    public static boolean isEnergyItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        net.neoforged.neoforge.energy.IEnergyStorage st = stack.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);
        return st != null && st.canExtract() && st.getMaxEnergyStored() > 0;
    }

    /**
     * Takes power from the item in the battery slot and pours it into the accumulator.
     *
     * <p>The order matters: FIRST we check dry how much the item can give, then we
     * pour it into the accumulator, and ONLY THEN do we take from the item what
     * actually went in. The reverse order (take, then pour) would lose energy if
     * the accumulator were already full.
     */
    private void chargeFromItem() {
        ItemStack stack = batterySlot.getItem(0);
        if (stack.isEmpty()) {
            return;
        }
        int space = ENERGY_CAPACITY - energy;
        if (space <= 0) {
            return;   // accumulator full - we leave the item alone
        }
        net.neoforged.neoforge.energy.IEnergyStorage itemEnergy = stack.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);
        if (itemEnergy == null || !itemEnergy.canExtract()) {
            return;
        }
        int available = itemEnergy.extractEnergy(Math.min(space, MAX_ITEM_DRAIN_PER_TICK), true);
        if (available <= 0) {
            return;   // the item is empty
        }
        // We take what the item REALLY gave - not what we asked for.
        // Some implementations can give less than they declared in the
        // simulation; in that case pouring "available" into the accumulator
        // would create energy out of nothing.
        int taken = itemEnergy.extractEnergy(available, false);
        if (taken <= 0) {
            return;
        }
        int accepted = receiveEnergy(taken, false);
        if (accepted < taken) {
            // The accumulator did not take all of it (e.g. it ran out of room
            // midway) - we give the surplus BACK to the item so that nothing is lost.
            itemEnergy.receiveEnergy(taken - accepted, false);
        }
        if (accepted > 0) {
            batterySlot.setChanged();
        }
    }

    // ------------------------------------------------------------------
    // VeloceHeatSource
    // ------------------------------------------------------------------

    @Override
    public long availableOperations() {
        return energy / FE_PER_SMELT;
    }

    @Override
    public void consumeOperations(long operations) {
        if (operations <= 0) {
            return;
        }
        long cost = operations * FE_PER_SMELT;
        energy = (int) Math.max(0L, energy - cost);
        if (level != null) {
            lastEnergyChangeTick = level.getGameTime();
        }
        setChanged();
    }

    @Override
    public boolean isPowered() {
        // "Powered" = it can afford at least one smelt. A furnace with a leftover
        // of energy below the cost does not unlock recipes, because it would not
        // be able to perform them anyway.
        return energy >= FE_PER_SMELT;
    }

    @Override
    public int heatPriority() {
        return 0;   // the electric one beats the fuel-powered one
    }

    @Override
    public String heatSourceName() {
        return "Velocity Electric Furnace";
    }

    // ------------------------------------------------------------------
    // IEnergyStorage - accepting energy from cables
    // ------------------------------------------------------------------

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (toReceive <= 0) {
            return 0;
        }
        int space = ENERGY_CAPACITY - energy;
        int accepted = Math.min(space, toReceive);
        if (!simulate && accepted > 0) {
            energy += accepted;
            setChanged();
        }
        return accepted;
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        // The accumulator is INTERNAL - energy leaves only through smelting
        // in the crafter, not through a cable. Otherwise a cable could "suck the furnace dry".
        return 0;
    }

    @Override
    public int getEnergyStored() {
        return energy;
    }

    @Override
    public int getMaxEnergyStored() {
        return ENERGY_CAPACITY;
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public boolean canReceive() {
        return true;
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    /**
     * Sends the accumulator state to the client when it changed.
     *
     * <p>The furnace has no per-tick logic of its own - it ticks ONLY so that the
     * energy bar in the GUI is alive. That is why we send only on a real change
     * (and at most once every half a second): a cable from another mod can charge
     * every tick, and a packet every tick would be a waste.
     */
    /** How much FE per tick we accept from the network at most (besides the source's own limit). */
    public static final int MAX_PULL_PER_TICK = 1_000_000;


    /**
     * The network this machine REALLY belongs to.
     *
     * <p>BUG from the log: the furnace asked for the network via {@code getNetworkForTerminal}
     * and got SOMEONE ELSE'S network - in the log its only pipe was adjacent to
     * grass and air, so neither the Energy Cube nor the furnace was there and the
     * draw had nothing to work from. Now we first look for a pipe NEXT TO the
     * machine and ask for that pipe's network; only when there is none do we fall
     * back to the old path.
     */
    private com.craftingveloce.network.pipe.VelocePipeNetwork networkFor(net.minecraft.server.level.ServerLevel sl) {
        var manager = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(sl);
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            net.minecraft.core.BlockPos side = worldPosition.relative(dir);
            if (sl.isLoaded(side)
                    && sl.getBlockState(side).getBlock() instanceof com.craftingveloce.block.VelocePipeBlock) {
                var net = manager.getNetworkForPipe(sl, side);
                if (net != null) {
                    return net;
                }
            }
        }
        return manager.getNetworkForTerminal(sl, worldPosition);
    }

    public void serverTick() {
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) {
            return;
        }
        // NOTE: this must happen BEFORE the early return for syncing.
        // Otherwise charging from the item would only work once every 10 ticks
        // (i.e. 10x slower), because the cooldown exits this method.
        chargeFromItem();
        // The same PULLING from the network as in the modules: the furnace draws
        // power on its own from foreign sources (Energy Cube, generator) hooked up
        // to the pipes. Full accumulator = zero attempts, the source limit and our
        // limit are respected.
        com.craftingveloce.network.pipe.VeloceEnergyPull.pull(sl,
                networkFor(sl),
                this, MAX_PULL_PER_TICK);
        if (--clientSyncCooldown > 0) {
            return;
        }
        clientSyncCooldown = CLIENT_SYNC_INTERVAL_TICKS;
        if (energy != lastSyncedEnergy) {
            lastSyncedEnergy = energy;
            sl.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** At most how often (in ticks) we send the energy state to the client. */
    private static final int CLIENT_SYNC_INTERVAL_TICKS = 10;

    private int clientSyncCooldown = CLIENT_SYNC_INTERVAL_TICKS;
    private int lastSyncedEnergy = -1;

    /** How much FE is in the accumulator. */
    public int getEnergy() {
        return energy;
    }

    /** Tick of the last energy change - for the GUI. */
    public long getLastEnergyChangeTick() {
        return lastEnergyChangeTick;
    }

    // ------------------------------------------------------------------
    // Save / load
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Energy", energy);
        ItemStack battery = batterySlot.getItem(0);
        if (!battery.isEmpty()) {
            // We save the WHOLE stack together with its data - an item's energy
            // from another mod lives precisely in that data, so we cannot save
            // "the item alone".
            tag.put("Battery", battery.save(registries, new CompoundTag()));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = Math.max(0, Math.min(ENERGY_CAPACITY, tag.getInt("Energy")));
        batterySlot.setItem(0, tag.contains("Battery")
                ? ItemStack.parse(registries, tag.getCompound("Battery")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY);
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
        return Component.translatable("block.craftingveloce.electric_furnace");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new com.craftingveloce.inventory.VeloceElectricFurnaceMenu(
                id, inv, getBlockPos(), this);
    }
}
