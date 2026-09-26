package com.craftingveloce.block.entity;

import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.inventory.VeloceImporterMenu;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class VeloceImporterBlockEntity extends BlockEntity implements MenuProvider, WorldlyContainer {
    private final NonNullList<ItemStack> items = NonNullList.withSize(9, ItemStack.EMPTY);
    private int tickCount = 0;

    public VeloceImporterBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.VELOCE_IMPORTER_BE.get(), pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, VeloceImporterBlockEntity be) {
        if (level.isClientSide || !(level instanceof ServerLevel sl)) return;
        
        be.tickCount++;
        if (be.tickCount % 5 != 0) return; // Process every 5 ticks

        VelocePipeNetworkManager manager = VelocePipeNetworkManager.get(sl);
        VelocePipeNetwork net = manager.getNetworkForTerminal(sl, pos);
        if (net == null) return;

        boolean changed = false;
        for (int i = 0; i < be.items.size(); i++) {
            ItemStack stack = be.items.get(i);
            if (!stack.isEmpty()) {
                ItemStack remainder = net.insertIntoStorage(sl, stack);
                if (remainder.getCount() != stack.getCount()) {
                    be.items.set(i, remainder);
                    changed = true;
                }
            }
        }
        if (changed) {
            be.setChanged();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, this.items, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.items.clear();
        ContainerHelper.loadAllItems(tag, this.items, registries);
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8};
    }

    @Override
    public boolean canPlaceItemThroughFace(int index, ItemStack itemStack, @Nullable Direction direction) {
        return true;
    }

    @Override
    public boolean canTakeItemThroughFace(int index, ItemStack stack, Direction direction) {
        return false; // Importer only takes items in, external pipes shouldn't pull out
    }

    @Override
    public int getContainerSize() {
        return items.size();
    }

    @Override
    public boolean isEmpty() {
        return items.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty()) setChanged();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        if (this.level == null || this.level.getBlockEntity(this.worldPosition) != this) return false;
        return player.distanceToSqr(this.worldPosition.getX() + 0.5D, this.worldPosition.getY() + 0.5D, this.worldPosition.getZ() + 0.5D) <= 64.0D;
    }

    @Override
    public void clearContent() {
        items.clear();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.craftingveloce.veloce_importer");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new VeloceImporterMenu(containerId, playerInventory, this);
    }
}
