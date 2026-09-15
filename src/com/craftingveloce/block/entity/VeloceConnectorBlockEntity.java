package com.craftingveloce.block.entity;

import com.craftingveloce.block.VeloceConnectorBlock;
import com.craftingveloce.init.VeloceRegistry;
import com.tom.storagemod.block.entity.IInventoryConnector;
import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.InventoryCableNetwork;
import com.tom.storagemod.inventory.MultiInventoryAccess;
import com.tom.storagemod.inventory.PlatformInventoryAccess;
import com.tom.storagemod.inventory.PlatformInventoryAccess.BlockInventoryAccess;
import com.tom.storagemod.inventory.PlatformMultiInventoryAccess;
import com.tom.storagemod.platform.PlatformBlockEntity;
import com.tom.storagemod.util.TickerUtil.TickableServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class VeloceConnectorBlockEntity extends PlatformBlockEntity implements TickableServer, IInventoryConnector {
    private final BlockInventoryAccess targetBlockAccess = new BlockInventoryAccess();
    private final MultiInventoryAccess mergedHandler = new PlatformMultiInventoryAccess();
    private final Set<IInventoryConnector> linkedConnectors = new HashSet<>();
    private boolean initialized = false;

    public VeloceConnectorBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.VELOCE_CONNECTOR_BE.get(), pos, state);
    }

    private void ensureInitialized() {
        if (!initialized && level != null && !level.isClientSide) {
            initialized = true;
            BlockState state = getBlockState();
            Direction facing = state.getValue(VeloceConnectorBlock.FACING);
            targetBlockAccess.onLoad(level, worldPosition.relative(facing), facing.getOpposite(), this);
            InventoryCableNetwork.getNetwork(level).markNodeInvalid(worldPosition);
        }
    }

    @Override
    public void updateServer() {
        if (level == null || level.isClientSide) return;

        ensureInitialized();

        long time = level.getGameTime();
        if (time % 20 == Math.abs(worldPosition.hashCode()) % 20) {
            detectCableNetwork();
        }
    }

    private void detectCableNetwork() {
        linkedConnectors.clear();
        Collection<BlockPos> netBlocks = InventoryCableNetwork.getNetwork(level).getNetworkNodes(worldPosition);

        for (BlockPos p : netBlocks) {
            if (!level.isLoaded(p)) continue;
            BlockEntity be = level.getBlockEntity(p);
            if (be == this) continue;
            if (be instanceof IInventoryConnector te) {
                linkedConnectors.add(te);
            }
        }
    }

    @Override
    public IInventoryAccess getMergedHandler() {
        ensureInitialized();
        return targetBlockAccess.exists() ? targetBlockAccess : PlatformInventoryAccess.EMPTY;
    }

    @Override
    public Collection<IInventoryAccess> getConnectedInventories() {
        ensureInitialized();
        return targetBlockAccess.exists() ? Collections.singleton(targetBlockAccess) : Collections.emptyList();
    }

    @Override
    public Collection<IInventoryConnector> getConnectedConnectors() {
        return linkedConnectors;
    }

    @Override
    public boolean hasConnectedInventories() {
        ensureInitialized();
        return !isRemoved() && targetBlockAccess.exists();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        targetBlockAccess.markInvalid();
        mergedHandler.clear();
        linkedConnectors.clear();
    }
}
