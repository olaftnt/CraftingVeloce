package com.craftingveloce.block.entity;

import com.craftingveloce.block.VelocePipeBlock;
import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.storage.VeloceBlockEntity;
import com.craftingveloce.storage.VeloceInventoryLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.state.BlockState;

public class VelocePipeBlockEntity extends VeloceBlockEntity {

    protected final boolean[] disconnectedSides = new boolean[6];
    protected final boolean[] extractingSides = new boolean[6];
    private final VeloceInventoryLookup[] inventoryAccesses = new VeloceInventoryLookup[6];

    public VelocePipeBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.VELOCE_PIPE_BE.get(), pos, state);
        for (int i = 0; i < 6; i++) {
            inventoryAccesses[i] = new VeloceInventoryLookup();
        }
    }

    public boolean isDisconnected(Direction side) {
        return disconnectedSides[side.ordinal()];
    }

    public void setDisconnected(Direction side, boolean disconnected) {
        disconnectedSides[side.ordinal()] = disconnected;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public boolean isExtracting(Direction side) {
        return extractingSides[side.ordinal()];
    }

    public void setExtracting(Direction side, boolean extracting) {
        extractingSides[side.ordinal()] = extracting;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public boolean hasCustomState() {
        for (int i = 0; i < 6; i++) {
            if (disconnectedSides[i] || extractingSides[i]) return true;
        }
        return false;
    }

    /**
     * Period work of a single pipe: make sure the six sides know what inventory
     * they touch. Called by the ticker registered in {@link VelocePipeBlock}.
     */
    public void updateServer() {
        if (level == null || level.isClientSide) return;

        long time = level.getGameTime();
        // The periodic check with SPREADING over the position - every pipe has its
        // own phase, so a wall of pipes does not do its work in a single tick.
        //
        // The BUG that was here: `Math.abs(worldPosition.hashCode()) % 20`.
        // Math.abs(Integer.MIN_VALUE) stays NEGATIVE (-2147483648), so for a pipe
        // with such a hash the phase came out negative and NEVER equaled the
        // non-negative `time % 20`. Such a pipe would not detect a connected
        // inventory - in practice it "does not see" the chest next to it.
        //
        // VeloceTick.everySpread uses Math.floorMod, so the phase is always
        // correct, and it measures the INTERVAL since the last time instead of
        // equality.
        if (com.craftingveloce.util.VeloceTick.everySpread(time, lastPeriodicTick, 20, worldPosition)) {
            lastPeriodicTick = time;
            updateInventories();
        }
    }

    /** Tick of the last periodic work of this pipe. */
    private long lastPeriodicTick = Long.MIN_VALUE;

    /**
     * Re-resolves the inventory on each of the six sides.
     *
     * <p>This is deliberately capability-based
     * ({@link VeloceInventoryLookup}), so a side counts as connected whenever the
     * neighbouring block exposes a NeoForge item handler - a chest, another mod's
     * machine, or Tom's Inventory Connector (which exposes Tom's whole network
     * through exactly that capability).
     */
    private void updateInventories() {
        for (Direction dir : Direction.values()) {
            int idx = dir.ordinal();
            BlockPos targetPos = worldPosition.relative(dir);
            VeloceInventoryLookup access = inventoryAccesses[idx];

            if (!disconnectedSides[idx] && VelocePipeBlock.canConnectToInventory(level, targetPos, dir.getOpposite())) {
                if (!access.exists()) {
                    access.onLoad(level, targetPos, dir.getOpposite(), this::isObjectValid);
                }
            } else {
                if (access.exists()) {
                    access.markInvalid();
                }
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        for (int i = 0; i < 6; i++) {
            inventoryAccesses[i].markInvalid();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        byte dis = 0;
        byte ext = 0;
        for (int i = 0; i < 6; i++) {
            if (disconnectedSides[i]) dis |= (byte) (1 << i);
            if (extractingSides[i]) ext |= (byte) (1 << i);
        }
        tag.putByte("Disconnected", dis);
        tag.putByte("Extracting", ext);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        byte dis = tag.getByte("Disconnected");
        byte ext = tag.getByte("Extracting");
        for (int i = 0; i < 6; i++) {
            disconnectedSides[i] = (dis & (1 << i)) != 0;
            extractingSides[i] = (ext & (1 << i)) != 0;
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, provider);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
