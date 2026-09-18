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
 *   <li>accumulator capacity: {@link #energyCapacity()} = 25 000 000 FE</li>
 *   <li>cost of one smelt: {@link #fePerSmelt()} = 200 000 FE</li>
 * </ul>
 * So a full accumulator is enough for 125 smelts, and a stack (64 items)
 * costs 12 800 000 FE.
 */
public class VeloceElectricFurnaceBlockEntity extends BlockEntity
        implements MenuProvider, VeloceHeatSource, IEnergyStorage {

    /**
     * Capacity of the internal accumulator.
     *
     * <p>25 000 000 FE = 125 smelts at {@link #fePerSmelt()}, i.e. a bit less than two
     * stacks of 64. This is the figure agreed with the player and the one the class
     * documentation and the brewing stand already use - the constant had drifted to
     * 200 000 000 (1000 smelts), which made the accumulator effectively bottomless.
     */
    /** Battery size out of the box; the live value comes from {@link #energyCapacity()}. */
    public static final int DEFAULT_ENERGY_CAPACITY = 25_000_000;

    /**
     * Battery size of this furnace, in FE.
     *
     * <p>A METHOD and not the constant, because javac INLINES a {@code static final int} into
     * every use site - a config value could never reach any of them.
     */
    public static int energyCapacity() {
        return com.craftingveloce.config.VeloceBlockConfig.capacity(
                "electric_furnace", DEFAULT_ENERGY_CAPACITY);
    }

    /** Cost of one instant smelt. */
    /** Cost of one smelt out of the box; the live value comes from {@link #fePerSmelt()}. */
    public static final int DEFAULT_FE_PER_SMELT = 200_000;

    /** Cost of ONE smelt, in FE - the configured one when there is one. */
    public static int fePerSmelt() {
        return com.craftingveloce.config.VeloceBlockConfig.fePerOperation(
                "electric_furnace", DEFAULT_FE_PER_SMELT);
    }

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
        // A fresh block entity MUST start empty. This line is the evidence used when
        // chasing the "placing a furnace where a charged one stood resurrects its
        // charge" report: if a newly constructed furnace logs energy=0 here and the
        // GUI still shows a charge, then the value does not come from this object at
        // all (it would come from the client-side copy or from saved NBT), which
        // rules this class out instead of guessing.
        com.craftingveloce.util.VeloceLog.Block.detail(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "[VELOCE-DEBUG] electric furnace instantiated at %s, fresh energy=%s FE, capacity=%s FE",
                pos.toShortString(), energy, energyCapacity());
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
            noteCharge("no item in the battery slot");
            return;
        }
        int space = energyCapacity() - energy;
        if (space <= 0) {
            noteCharge("accumulator full (" + energy + "/" + energyCapacity() + " FE) - item left alone");
            return;   // accumulator full - we leave the item alone
        }
        net.neoforged.neoforge.energy.IEnergyStorage itemEnergy = stack.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);
        if (itemEnergy == null || !itemEnergy.canExtract()) {
            noteCharge("item " + stack.getItem() + " exposes no extractable Forge Energy "
                    + "(capability=" + (itemEnergy == null ? "null" : "present but cannot extract") + ")");
            return;
        }
        int available = itemEnergy.extractEnergy(Math.min(space, MAX_ITEM_DRAIN_PER_TICK), true);
        if (available <= 0) {
            noteCharge("item " + stack.getItem() + " is empty (simulated extract = 0)");
            return;   // the item is empty
        }
        // We take what the item REALLY gave - not what we asked for.
        // Some implementations can give less than they declared in the
        // simulation; in that case pouring "available" into the accumulator
        // would create energy out of nothing.
        int taken = itemEnergy.extractEnergy(available, false);
        if (taken <= 0) {
            noteCharge("item " + stack.getItem() + " advertised " + available
                    + " FE in the simulation but gave 0 on the real extract");
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
            noteCharge("charged " + accepted + " FE from " + stack.getItem()
                    + " -> accumulator now " + energy + " FE");
        }
    }

    /**
     * Logs the charging state, but only when it CHANGES.
     *
     * <p>Called once per tick, so logging unconditionally would flood the log with
     * 20 identical lines a second. A state-change log answers the real question -
     * "why is the accumulator not filling" - with one line per distinct cause.
     */
    private void noteCharge(String note) {
        if (note.equals(lastChargeNote)) {
            return;
        }
        lastChargeNote = note;
        com.craftingveloce.util.VeloceLog.Block.detail(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "[VELOCE-DEBUG] furnace %s battery slot: %s",
                worldPosition.toShortString(), note);
    }

    /** The last charging note we logged - see {@link #noteCharge(String)}. */
    private String lastChargeNote = "";

    // ------------------------------------------------------------------
    // VeloceHeatSource
    // ------------------------------------------------------------------

    @Override
    public long availableOperations() {
        return energy / fePerSmelt();
    }

    @Override
    public void consumeOperations(long operations) {
        if (operations <= 0) {
            return;
        }
        long cost = operations * fePerSmelt();
        int before = energy;
        energy = (int) Math.max(0L, energy - cost);
        if (level != null) {
            lastEnergyChangeTick = level.getGameTime();
        }
        // Per-operation deduction: this is the line that proves a stack of 64 really
        // pays 64 x fePerSmelt() rather than a flat charge. It goes to the mod's
        // gated DETAIL channel (NOT raw slf4j DEBUG, which the log config filters
        // regardless of debugEnabled, so it could never be seen in game).
        com.craftingveloce.util.VeloceLog.Block.detail(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "[VELOCE-DEBUG] smelt deduction at %s: ops=%s x %s FE = %s FE, battery %s -> %s FE",
                worldPosition.toShortString(), operations, fePerSmelt(), cost, before, energy);
        setChanged();
    }

    @Override
    public boolean isPowered() {
        // "Powered" = it can afford at least one smelt. A furnace with a leftover
        // of energy below the cost does not unlock recipes, because it would not
        // be able to perform them anyway.
        return energy >= fePerSmelt();
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
        int space = energyCapacity() - energy;
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
        return energyCapacity();
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

    public void serverTick() {
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) {
            return;
        }
        // The ONLY way this furnace takes power is the energy ITEM in its own battery
        // slot, or a foreign energy cable attached DIRECTLY to the furnace (which the
        // capability below still accepts).
        //
        // It deliberately does NOT draw power through the Veloce network any more.
        // Our cables are a carrier for the network (item logistics), never an energy
        // conduit: zero FE travels on them. This also removes the whole class of
        // "the network empties my Energy Cube" problems, because no code path exists
        // that can pull from a remote source any more.
        chargeFromItem();
        if (--clientSyncCooldown > 0) {
            return;
        }
        clientSyncCooldown = CLIENT_SYNC_INTERVAL_TICKS;
        if (energy != lastSyncedEnergy) {
            com.craftingveloce.util.VeloceLog.Block.detail(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "[VELOCE-DEBUG] furnace %s BE sync: %s -> %s FE (every %s ticks)",
                    worldPosition.toShortString(), lastSyncedEnergy, energy,
                    CLIENT_SYNC_INTERVAL_TICKS);
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
        energy = Math.max(0, Math.min(energyCapacity(), tag.getInt("Energy")));
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
