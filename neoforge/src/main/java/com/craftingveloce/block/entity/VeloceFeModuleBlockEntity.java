package com.craftingveloce.block.entity;

import com.craftingveloce.crafting.FeModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.Set;

/**
 * The energy accumulator of ONE Mekanism module machine.
 *
 * <p><b>One class for four machines.</b> Mekanism item machines differ only in
 * the recipe type and the label, so there is no reason to write four copies of
 * the same energy logic - they are distinguished by the {@link FeModule} field
 * passed in the constructor.
 *
 * <p><b>The work model.</b> Like the Velocity Electric Furnace: no own ticker
 * and no progress. The auto-crafter asks for {@link #availableOperations()}
 * ("how many operations can you still sustain"), takes operations when the
 * recipe is executed ({@link #consumeOperations(long)}) and inserts the results
 * into the network itself. Thanks to that energy is settled exactly once per
 * completed recipe.
 */
public class VeloceFeModuleBlockEntity extends BlockEntity
        implements VeloceProcessingSource, IEnergyStorage, VeloceModuleInfoSource,
        VeloceModuleDisplay {

    /**
     * The block entity factory - supplied by the module.
     *
     * <p>Each mod has its own block entity types, and the core cannot know their
     * registrations - that is why the block receives the factory in the
     * constructor. Thanks to that one core block serves machines from different
     * mods.
     */
    @FunctionalInterface
    public interface Factory {
        VeloceFeModuleBlockEntity create(BlockPos pos, BlockState state);
    }

    private final FeModule module;
    private final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceFeModuleBlockEntity>> typeHolder;

    private int energy;

    public VeloceFeModuleBlockEntity(
            FeModule module,
            DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> typeHolder,
            BlockPos pos, BlockState state) {
        super(typeHolder.get(), pos, state);
        this.module = module;
        this.typeHolder = typeHolder;
    }

    /** Machine description (recipe type, cost, label) - for diagnostics. */
    public FeModule module() {
        return module;
    }

    /** The block entity type this machine is registered to. */
    public BlockEntityType<?> registeredType() {
        return typeHolder.get();
    }

    // ------------------------------------------------------------------
    // VeloceProcessingSource
    // ------------------------------------------------------------------

    /**
     * Data for the module window: energy, operation cost and the pipe network.
     *
     * <p>Player: "there should be a visible charge indicator (a little battery)
     * showing the current state of the stored power". The window is computed on
     * the server (that is the only place with the real numbers), and the client
     * draws the battery bar from those fields.
     */
    /** Window fields on the CLIENT (energy is synchronised through the block entity). */
    @Override
    public net.minecraft.nbt.CompoundTag moduleDisplay() {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putLong("energy", energy);
        tag.putLong("energyCapacity", module.capacity());
        tag.putLong("fePerOperation", module.fePerOperation());
        tag.putLong("operations", module.fePerOperation() <= 0 ? 0L
                : energy / module.fePerOperation());
        tag.putBoolean("powered", isPowered());
        return tag;
    }

    @Override
    public net.minecraft.nbt.CompoundTag moduleInfo(net.minecraft.server.level.ServerLevel level) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putLong("energy", energy);
        tag.putLong("energyCapacity", module.capacity());
        tag.putLong("fePerOperation", module.fePerOperation());
        tag.putLong("operations", module.fePerOperation() <= 0 ? 0L
                : energy / module.fePerOperation());
        tag.putBoolean("powered", energy >= module.fePerOperation());
        tag.putString("moduleLabel", module.label());
        var pipes = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(level)
                .getNetworkForTerminal(level, worldPosition);
        if (pipes != null) {
            tag.putInt("networkNodes", pipes.getTerminals().size());
            tag.putInt("networkStorages", pipes.getEndpoints().size());
            tag.putInt("networkItems", pipes.getAllItemCounts(level).size());
        }
        return tag;
    }

    @Override
    public String moduleId() {
        return module.id();
    }

    @Override
    public Set<RecipeType<?>> recipeTypes() {
        // We resolve the recipe type ONLY here (not at class load):
        // a DeferredHolder of a foreign mod is bound after the registration events.
        return Set.of(module.recipeType().get());
    }

    @Override
    public long availableOperations() {
        return energy / module.fePerOperation();
    }

    @Override
    public void consumeOperations(long operations) {
        if (operations <= 0) {
            return;
        }
        energy = (int) Math.max(0L, energy - operations * (long) module.fePerOperation());
        setChanged();
    }

    @Override
    public boolean isPowered() {
        return energy >= module.fePerOperation();
    }

    @Override
    public String sourceName() {
        return module.label();
    }

    // ------------------------------------------------------------------
    // IEnergyStorage - insertion from cables, extraction forbidden
    // ------------------------------------------------------------------

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (toReceive <= 0) {
            return 0;
        }
        int accepted = Math.min(toReceive, module.capacity() - energy);
        if (!simulate && accepted > 0) {
            energy += accepted;
            setChanged();
        }
        return accepted;
    }

    /**
     * Extraction is DELIBERATELY blocked.
     *
     * <p>A machine's accumulator is not storage for the network - a cable must
     * not "suck out" the energy the machine has for performing operations. The
     * same rule as in the Velocity Electric Furnace.
     */
    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        return 0;
    }

    @Override
    public int getEnergyStored() {
        return energy;
    }

    @Override
    public int getMaxEnergyStored() {
        return module.capacity();
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
    // Help for the player - how much power is left (the machine has no GUI)
    // ------------------------------------------------------------------

    /** Sends the accumulator state to the player on the action bar. */
    public void sendStatus(ServerPlayer player) {
        player.displayClientMessage(Component.translatable(
                "gui.craftingveloce.module.energy",
                energy, module.capacity(),
                energy / module.fePerOperation(), module.fePerOperation()), true);
    }

    // ------------------------------------------------------------------
    // Save / load
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Battery slot (as in the Velocity Electric Furnace)
    // ------------------------------------------------------------------

    /** An item with energy (battery, energy cube, tablet) - the only thing that goes in here. */
    private final net.minecraft.world.SimpleContainer batterySlot =
            new net.minecraft.world.SimpleContainer(1);

    /** How much FE per tick we pull from the item at most. */
    public static final int MAX_ITEM_DRAIN_PER_TICK = 1_000_000;

    /** The battery slot - for the menu and for the screen. */
    public net.minecraft.world.Container getBatterySlot() {
        return batterySlot;
    }

    /** Whether the item has energy to give (the standard NeoForge capability). */
    public static boolean isEnergyItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        net.neoforged.neoforge.energy.IEnergyStorage st = stack.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);
        return st != null && st.canExtract() && st.getMaxEnergyStored() > 0;
    }

    /** Every tick (server): draw power from the item in the battery slot and from the network. */

    /**
     * The network this machine REALLY belongs to.
     *
     * <p>The BUG from the log: the furnace asked for a network via
     * {@code getNetworkForTerminal} and got SOMEBODY ELSE'S network - in the log
     * its only pipe was adjacent to grass and air, so neither an Energy Cube nor
     * a furnace was there and the draw had nothing to work from. Now we first
     * look for a pipe NEXT TO the machine and ask about that pipe's network; only
     * when there is none do we fall back to the old path.
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

    private int clientSyncCooldown = 10;
    private int lastSyncedEnergy = -1;

    public void serverTick() {
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) {
            return;
        }
        chargeFromItem();
        pullFromNetwork();
        
        if (--clientSyncCooldown > 0) {
            return;
        }
        clientSyncCooldown = 10;
        if (energy != lastSyncedEnergy) {
            lastSyncedEnergy = energy;
            sl.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * Draws power from FOREIGN sources hooked up to the pipe network (Energy
     * Cube, generator). Only our machine draws - the pipe does not conduct power
     * for other mods, and the machines are not a source for each other (the
     * network scan skips our blocks). A full accumulator = zero attempts (see
     * VeloceEnergyPull).
     */
    private void pullFromNetwork() {
        if (level == null || level.isClientSide) {
            return;
        }
        var serverLevel = (net.minecraft.server.level.ServerLevel) level;
        var network = networkFor(serverLevel);
        com.craftingveloce.network.pipe.VeloceEnergyPull.pull(
                serverLevel, network, this, MAX_PULL_PER_TICK);
    }

    /** How much FE per tick we accept from the network at most (next to the source's limit). */
    public static final int MAX_PULL_PER_TICK = Integer.MAX_VALUE;

    /**
     * Takes power from the item and pours it into the accumulator.
     *
     * <p>The order as in the furnace: simulation FIRST, then the pour into the
     * accumulator, and we take from the item only what actually went in -
     * otherwise, with a full accumulator, energy would disappear from the item.
     */
    private void chargeFromItem() {
        ItemStack stack = batterySlot.getItem(0);
        if (stack.isEmpty()) {
            return;
        }
        int space = module.capacity() - energy;
        if (space <= 0) {
            return;
        }
        net.neoforged.neoforge.energy.IEnergyStorage itemEnergy = stack.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);
        if (itemEnergy == null || !itemEnergy.canExtract()) {
            return;
        }
        int available = itemEnergy.extractEnergy(Math.min(space, MAX_ITEM_DRAIN_PER_TICK), true);
        if (available <= 0) {
            return;
        }
        int taken = itemEnergy.extractEnergy(available, false);
        if (taken <= 0) {
            return;
        }
        int accepted = receiveEnergy(taken, false);
        if (accepted < taken) {
            itemEnergy.receiveEnergy(taken - accepted, false);
        }
        if (accepted > 0) {
            batterySlot.setChanged();
            syncEnergy();
        }
    }

    /**
     * Sends the energy to the CLIENT.
     *
     * <p>The BUG this fixes (a player report): the battery bar in the module
     * window kept showing an empty accumulator. The window reads the energy from
     * the block entity locally (like the furnace), but the module did NOT send it
     * to the client - the furnace has done that from the start
     * ({@code sendBlockUpdated} + a data packet).
     */
    private void syncEnergy() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Energy", energy);
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection connection,
                             net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket pkt,
                             HolderLookup.Provider registries) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            energy = Math.max(0, Math.min(module.capacity(), tag.getInt("Energy")));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Energy", energy);
        ItemStack battery = batterySlot.getItem(0);
        if (!battery.isEmpty()) {
            tag.put("Battery", battery.save(registries, new CompoundTag()));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = Math.max(0, Math.min(module.capacity(), tag.getInt("Energy")));
        batterySlot.setItem(0, tag.contains("Battery")
                ? ItemStack.parse(registries, tag.getCompound("Battery")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY);
    }
}
