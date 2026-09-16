package com.craftingveloce.block.entity;

import com.craftingveloce.block.VelocePipeBlock;
import com.craftingveloce.init.VeloceRegistry;
import com.tom.storagemod.block.entity.IInventoryConnector;
import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.InventoryCableNetwork;
import com.tom.storagemod.inventory.PlatformInventoryAccess;
import com.tom.storagemod.inventory.PlatformInventoryAccess.BlockInventoryAccess;
import com.tom.storagemod.platform.PlatformBlockEntity;
import com.tom.storagemod.util.TickerUtil.TickableServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VelocePipeBlockEntity extends PlatformBlockEntity implements TickableServer, IInventoryConnector {

    protected final boolean[] disconnectedSides = new boolean[6];
    protected final boolean[] extractingSides = new boolean[6];
    private final BlockInventoryAccess[] inventoryAccesses = new BlockInventoryAccess[6];
    private final Set<IInventoryConnector> linkedConnectors = new HashSet<>();

    public VelocePipeBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.VELOCE_PIPE_BE.get(), pos, state);
        for (int i = 0; i < 6; i++) {
            inventoryAccesses[i] = new BlockInventoryAccess();
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
            InventoryCableNetwork.getNetwork(level).markNodeInvalid(worldPosition);
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
            InventoryCableNetwork.getNetwork(level).markNodeInvalid(worldPosition);
        }
    }

    public boolean hasCustomState() {
        for (int i = 0; i < 6; i++) {
            if (disconnectedSides[i] || extractingSides[i]) return true;
        }
        return false;
    }

    /**
     * Pojemnik energii rury - odpowiednik bufora z Pipez EnergyPipeType.
     *
     * <p>Pobor idzie na stronach oznaczonych jako "extracting", rozdanie na
     * pozostalych polaczonych. Pojemnik NIE jest wystawiany jako capability,
     * wiec nasze rury nadal nie sa przewodem pradu dla innych modow.
     */
    private final net.neoforged.neoforge.energy.EnergyStorage energyBuffer =
            new net.neoforged.neoforge.energy.EnergyStorage(100_000, 100_000, 100_000);

    /** Limit FE na tick - jak getRate(upgrade) w Pipezie. */
    private static final int ENERGY_RATE = 20_000;

    /** Pobor z oznaczonych stron i rozdanie na pozostale (wzorzec z Pipeza). */
    private void tickEnergy() {
        if (level == null || level.isClientSide) {
            return;
        }
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            int i = dir.ordinal();
            if (!extractingSides[i] || disconnectedSides[i]) {
                continue;
            }
            var source = level.getCapability(
                    net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                    worldPosition.relative(dir), dir.getOpposite());
            if (source == null || !source.canExtract()) {
                continue;
            }
            int free = energyBuffer.getMaxEnergyStored() - energyBuffer.getEnergyStored();
            int want = Math.min(free, ENERGY_RATE);
            if (want <= 0) {
                break;
            }
            int taken = source.extractEnergy(want, false);
            if (taken > 0) {
                int accepted = energyBuffer.receiveEnergy(taken, false);
                if (accepted < taken) {
                    source.receiveEnergy(taken - accepted, false);
                }
            }
        }
        if (energyBuffer.getEnergyStored() <= 0) {
            return;
        }
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            int i = dir.ordinal();
            if (extractingSides[i] || disconnectedSides[i]) {
                continue;
            }
            var target = level.getCapability(
                    net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                    worldPosition.relative(dir), dir.getOpposite());
            if (target == null || !target.canReceive()) {
                continue;
            }
            int accepted = target.receiveEnergy(
                    Math.min(energyBuffer.getEnergyStored(), ENERGY_RATE), false);
            if (accepted > 0) {
                energyBuffer.extractEnergy(accepted, false);
            }
        }
    }

    @Override
    public void updateServer() {
        tickEnergy();
        if (level == null || level.isClientSide) return;

        long time = level.getGameTime();
        // Sprawdzanie okresowe z ROZPROSZENIEM po pozycji - kazda rura ma wlasna
        // faze, wiec sciana rur nie robi pracy w jednym ticku.
        //
        // BUG, ktory tu byl: `Math.abs(worldPosition.hashCode()) % 20`.
        // Math.abs(Integer.MIN_VALUE) zostaje UJEMNE (-2147483648), wiec dla
        // rury o takim hashu faza wychodzila ujemna i NIGDY nie zrownala sie
        // z nieujemnym `time % 20`. Taka rura nie wykrywalaby podlaczonego
        // inwentarza ani sieci kabli - w praktyce "nie widzi" skrzyni obok.
        //
        // VeloceTick.everySpread uzywa Math.floorMod, wiec faza jest zawsze
        // poprawna, i mierzy ODSTEP od ostatniego razu zamiast rownosci.
        if (com.craftingveloce.util.VeloceTick.everySpread(time, lastPeriodicTick, 20, worldPosition)) {
            lastPeriodicTick = time;
            updateInventories();
            detectCableNetwork();
        }
    }

    /** Tick ostatniej okresowej pracy tej rury. */
    private long lastPeriodicTick = Long.MIN_VALUE;

    private void updateInventories() {
        for (Direction dir : Direction.values()) {
            int idx = dir.ordinal();
            BlockPos targetPos = worldPosition.relative(dir);
            BlockInventoryAccess access = inventoryAccesses[idx];

            if (!disconnectedSides[idx] && VelocePipeBlock.canConnectToInventory(level, targetPos, dir.getOpposite())) {
                if (!access.exists()) {
                    access.onLoad(level, targetPos, dir.getOpposite(), this);
                }
            } else {
                if (access.exists()) {
                    access.markInvalid();
                }
            }
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
        // Return first active connected inventory or EMPTY
        for (int i = 0; i < 6; i++) {
            if (inventoryAccesses[i].exists()) {
                return inventoryAccesses[i];
            }
        }
        return PlatformInventoryAccess.EMPTY;
    }

    @Override
    public Collection<IInventoryAccess> getConnectedInventories() {
        List<IInventoryAccess> list = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            if (inventoryAccesses[i].exists()) {
                list.add(inventoryAccesses[i]);
            }
        }
        return list;
    }

    @Override
    public Collection<IInventoryConnector> getConnectedConnectors() {
        return linkedConnectors;
    }

    @Override
    public boolean hasConnectedInventories() {
        if (isRemoved()) return false;
        for (int i = 0; i < 6; i++) {
            if (inventoryAccesses[i].exists()) return true;
        }
        return false;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        for (int i = 0; i < 6; i++) {
            inventoryAccesses[i].markInvalid();
        }
        linkedConnectors.clear();
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
