package com.craftingveloce.block.entity;

import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity of the Veloce Threshold Sensor.
 *
 * <p><b>What it does.</b> It watches how many pieces of the FILTERED item are
 * physically in the network and compares that with a threshold set by the player.
 * It exposes the result as redstone. In other words: "when there is less than 64
 * iron in the network - turn on the power".
 *
 * <p><b>Two modes.</b> {@link Mode#LOW} gives power when the state drops BELOW the
 * threshold (typically: start the factory, because it is running out).
 * {@link Mode#HIGH} does the opposite - it gives power when the state is at the
 * threshold or above (typically: stop refilling, because there is already enough).
 * One setting covers both directions, so the player does not have to think about
 * "negation" - they simply choose what the condition should be.
 *
 * <p><b>Output.</b> The block keeps the result in its block state ({@code POWERED}),
 * not only inside itself. That is deliberate: Minecraft broadcasts a block state
 * change to the neighbours, so it is precisely that change that starts the machines
 * next to it. Keeping the result solely in the block entity would give no
 * notification at all.
 */
public class VeloceThresholdSensorBlockEntity extends BlockEntity
        implements MenuProvider, VeloceFilterHost {

    /** What the output condition should be. */
    public enum Mode {
        /** Power when the network is BELOW the threshold ("not enough"). */
        LOW("gui.craftingveloce.sensor.mode.low"),
        /** Power when the network is AT the threshold or above ("that is enough"). */
        HIGH("gui.craftingveloce.sensor.mode.high");

        public final String key;

        Mode(String key) {
            this.key = key;
        }

        public Mode next() {
            return this == LOW ? HIGH : LOW;
        }
    }

    /** How many items there should be in the network (the threshold). A new sensor starts at 64. */
    public static final long DEFAULT_THRESHOLD = 64L;

    /**
     * The lower bound of the threshold.
     *
     * <p>Not zero: "fewer than 0 pieces" NEVER happens, so the "below" mode with a
     * threshold of 0 would not turn on the power even once - and it would look like
     * it was set. One is the smallest value that means anything.
     */
    public static final long MIN_THRESHOLD = 1L;

    /** The upper bound of the threshold - protects against absurd values from the text field. */
    public static final long MAX_THRESHOLD = 1_000_000_000L;

    /**
     * How often (in ticks) we check the network. 20 ticks = once a second.
     *
     * <p>Once a second and not more often, for two reasons:
     * <ul>
     *   <li>scanning the network's storages is expensive (a walk over all
     *       endpoints), and the aggregate is cached for exactly 10 ticks -
     *       asking more often would not give fresher data, only more work,</li>
     *   <li>the sensor is meant for starting a factory ("the iron ran out"),
     *       not for clocking - a second of delay is irrelevant, whereas ten
     *       sensors asking every half a second is already a real load.</li>
     * </ul>
     */
    private static final int CHECK_INTERVAL_TICKS = 20;

    private ItemStack filter = ItemStack.EMPTY;
    private long threshold = DEFAULT_THRESHOLD;
    private Mode mode = Mode.LOW;

    /** The last measured number of pieces in the network (-1 = not measured yet). */
    private long lastCount = -1L;

    /**
     * How many pieces were in the network when the state was last sent to the client.
     *
     * <p>Needed so as not to send a block packet every second when nothing changes -
     * with several sensors that is traffic for no reason.
     */
    private long lastSyncedCount = Long.MIN_VALUE;

    private int checkCooldown = CHECK_INTERVAL_TICKS;

    public VeloceThresholdSensorBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.THRESHOLD_SENSOR_BE.get(), pos, state);
    }

    // ------------------------------------------------------------------
    // Filter (through the shared interface - the same item selection as in the extractor)
    // ------------------------------------------------------------------

    @Override
    public int filterCount() {
        return 1;
    }

    @Override
    public ItemStack getFilterAt(int index) {
        return index == 0 ? filter : ItemStack.EMPTY;
    }

    @Override
    public void setFilterAt(int index, ItemStack stack) {
        if (index != 0) {
            return;
        }
        filter = stack == null ? ItemStack.EMPTY : stack.copyWithCount(1);
        setChanged();
        syncToClients();
    }

    public ItemStack getFilter() {
        return filter;
    }

    // ------------------------------------------------------------------
    // Threshold and mode
    // ------------------------------------------------------------------

    public long getThreshold() {
        return threshold;
    }

    /** Sets the threshold, clamping it to a sensible range. */
    public void setThreshold(long value) {
        long clamped = Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, value));
        if (clamped == threshold) {
            return;
        }
        threshold = clamped;
        setChanged();
        syncToClients();
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        if (mode == null || mode == this.mode) {
            return;
        }
        this.mode = mode;
        setChanged();
        syncToClients();
    }

    public long getLastCount() {
        return lastCount;
    }

    /**
     * Whether the sensor is emitting power right now.
     *
     * <p>We read this from the BLOCK STATE, not from our own field. The block state
     * is the single source of truth for the output (because only its change is
     * broadcast to the neighbours), so keeping a second copy in the block entity
     * would mean two places that can drift apart - e.g. after loading the world
     * from NBT.
     */
    public boolean isPowered() {
        BlockState state = getBlockState();
        return state.hasProperty(com.craftingveloce.block.VeloceThresholdSensorBlock.POWERED)
                && state.getValue(com.craftingveloce.block.VeloceThresholdSensorBlock.POWERED);
    }

    /**
     * Whether the condition is met for a given number of pieces.
     *
     * <p>Extracted so that the GUI can show EXACTLY the same thing the tick does -
     * and not its own, "similar" evaluation of the condition.
     */
    public boolean conditionMet(long count) {
        return mode == Mode.LOW ? count < threshold : count >= threshold;
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    public void serverTick() {
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        // We ALWAYS count the timer down, and only measure the network once it
        // reaches zero. A network scan is expensive (a walk over the storages), and
        // the sensor does not have to react every tick - twice a second is still
        // faster than any machine on the other side of the redstone will react.
        if (--checkCooldown > 0) {
            return;
        }
        checkCooldown = CHECK_INTERVAL_TICKS;

        long count = measure(sl);
        lastCount = count;

        boolean shouldPower = !filter.isEmpty() && conditionMet(count);
        if (shouldPower != isPowered()) {
            applyPowerState(sl, shouldPower);
        }
        // The counter is shown in the GUI, so we send it - but ONLY when it really
        // changed. Sending every second "just in case" is traffic for no reason
        // when the stock is stable.
        //
        // WATCH OUT FOR THE UNITS: this code is reached ONLY once every
        // CHECK_INTERVAL_TICKS (there is a return above), so every counter
        // decremented here counts "checks", not ticks.
        // The previous version kept a separate cooldown of 20 here - which gave
        // 20 * 20 = 400 ticks (20 SECONDS) instead of a second, so the counter
        // in the GUI refreshed once every 20 seconds. The comparison with the last
        // sent value is enough on its own: we are here at most once a second.
        if (count != lastSyncedCount) {
            lastSyncedCount = count;
            syncToClients();
        }
    }

    /**
     * The number of pieces of the filtered item in the network.
     *
     * <p>We count the PHYSICAL state of the storages, not "how much can be made" -
     * the sensor is meant to guard the stock, not the production. Craftability
     * changes just from having the recipes, so it is irrelevant here.
     *
     * @return the number of pieces; 0 when there is no filter or no network
     */
    private long measure(ServerLevel sl) {
        if (filter.isEmpty()) {
            return 0L;
        }
        VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                .getNetworkForTerminal(sl, worldPosition);
        if (net == null) {
            return 0L;
        }
        Item wanted = filter.getItem();
        return net.getAllItemCounts(sl).getOrDefault(wanted, 0L);
    }

    /** Sets the block state - it is what broadcasts the change to the neighbours. */
    private void applyPowerState(ServerLevel sl, boolean on) {
        BlockState state = getBlockState();
        if (!state.hasProperty(com.craftingveloce.block.VeloceThresholdSensorBlock.POWERED)) {
            return;
        }
        if (state.getValue(com.craftingveloce.block.VeloceThresholdSensorBlock.POWERED) == on) {
            return;
        }
        sl.setBlockAndUpdate(worldPosition, state.setValue(
                com.craftingveloce.block.VeloceThresholdSensorBlock.POWERED, on));
        setChanged();
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
        if (!filter.isEmpty()) {
            tag.put("Filter", filter.save(registries));
        }
        tag.putLong("Threshold", threshold);
        tag.putString("Mode", mode.name());
        // NOTE: we do NOT save the output (POWERED) here. It lives in the block
        // state, which is saved together with the chunk anyway - a second copy
        // in NBT could drift apart from it after loading the world.
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        filter = tag.contains("Filter")
                ? ItemStack.parse(registries, tag.getCompound("Filter")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        threshold = tag.contains("Threshold")
                ? Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, tag.getLong("Threshold")))
                : DEFAULT_THRESHOLD;
        mode = tag.contains("Mode") && "HIGH".equals(tag.getString("Mode")) ? Mode.HIGH : Mode.LOW;
        // A sync-only field: it is in the block packet, it is not in the world
        // save. Missing = not measured yet.
        lastCount = tag.contains("LastCount") ? tag.getLong("LastCount") : -1L;
    }

    /**
     * The data sent to the client.
     *
     * <p><b>Why separately from {@link #saveAdditional}.</b> {@code lastCount}
     * is a derived value (the result of measuring the network), so it DELIBERATELY
     * does not go into the world save - after loading it is recalculated anyway.
     * But the GUI reads it precisely from the client-side block entity, and that
     * one receives ONLY what this method returns. Without adding it here the
     * counter NEVER reached the client: the GUI showed "unknown" permanently, and
     * the output state label was computed from -1, so it could show the opposite
     * of the truth.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        tag.putLong("LastCount", lastCount);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return net.minecraft.network.chat.Component.translatable(
                "block.craftingveloce.threshold_sensor");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new com.craftingveloce.inventory.VeloceThresholdSensorMenu(
                id, inv, getBlockPos(), this);
    }

    /** Empty container for the menu's needs (the sensor does not hold items). */
    public Container placeholderContainer() {
        return new SimpleContainer(1);
    }
}
