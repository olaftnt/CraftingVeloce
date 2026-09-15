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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class VeloceExtractorBlockEntity extends BlockEntity implements MenuProvider {

    private final NonNullList<ItemStack> filterSlots = NonNullList.withSize(9, ItemStack.EMPTY);
    private final SimpleContainer outputInventory = new SimpleContainer(9);

    private int tickCounter = 0;

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

    public ItemStack getFilter(int index) {
        if (index >= 0 && index < 9) {
            return filterSlots.get(index);
        }
        return ItemStack.EMPTY;
    }

    public void setFilter(int index, ItemStack filterItem) {
        if (index >= 0 && index < 9) {
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
            List<ItemStack> filters = new ArrayList<>(filterSlots);
            SyncExtractorFiltersPKT pkt = new SyncExtractorFiltersPKT(worldPosition, filters);
            for (ServerPlayer player : sl.players()) {
                if (player.containerMenu instanceof VeloceExtractorMenu menu && menu.getPos().equals(worldPosition)) {
                    PacketDistributor.sendToPlayer(player, pkt);
                }
            }
        }
    }

    public void syncFiltersToPlayer(ServerPlayer player) {
        if (level != null && !level.isClientSide) {
            PacketDistributor.sendToPlayer(player, new SyncExtractorFiltersPKT(worldPosition, new ArrayList<>(filterSlots)));
        }
    }

    public void serverTick() {
        tickCounter++;
        // Attempt extraction from network every 10 ticks (0.5s)
        if (tickCounter % 10 == 0) {
            pullFilteredItemsFromNetwork();
        }
    }

    private void pullFilteredItemsFromNetwork() {
        if (level == null || level.isClientSide || !(level instanceof ServerLevel sl)) return;

        VelocePipeNetworkManager manager = VelocePipeNetworkManager.get(sl);
        VelocePipeNetwork net = manager.getNetworkForTerminal(sl, worldPosition);
        if (net == null) return;

        for (int i = 0; i < 9; i++) {
            ItemStack filter = filterSlots.get(i);
            if (filter.isEmpty()) continue;

            ItemStack currentOutput = outputInventory.getItem(i);
            int maxStack = filter.getMaxStackSize();

            if (currentOutput.isEmpty()) {
                // Pull up to full stack
                ItemStack pulled = net.extractItem(sl, filter.getItem(), maxStack);
                if (!pulled.isEmpty()) {
                    outputInventory.setItem(i, pulled);
                    setChanged();
                }
            } else if (ItemStack.isSameItemSameComponents(currentOutput, filter)) {
                int needed = maxStack - currentOutput.getCount();
                if (needed > 0) {
                    ItemStack pulled = net.extractItem(sl, filter.getItem(), needed);
                    if (!pulled.isEmpty()) {
                        currentOutput.grow(pulled.getCount());
                        outputInventory.setItem(i, currentOutput);
                        setChanged();
                    }
                }
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag filterList = new ListTag();
        for (int i = 0; i < 9; i++) {
            if (!filterSlots.get(i).isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putByte("Slot", (byte) i);
                filterSlots.get(i).save(registries, itemTag);
                filterList.add(itemTag);
            }
        }
        tag.put("Filters", filterList);

        ListTag outputList = new ListTag();
        for (int i = 0; i < outputInventory.getContainerSize(); i++) {
            ItemStack stack = outputInventory.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putByte("Slot", (byte) i);
                stack.save(registries, itemTag);
                outputList.add(itemTag);
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
                ItemStack.parse(registries, itemTag).ifPresent(s -> filterSlots.set(slot, s));
            }
        }

        outputInventory.clearContent();
        ListTag outputList = tag.getList("Outputs", Tag.TAG_COMPOUND);
        for (int i = 0; i < outputList.size(); i++) {
            CompoundTag itemTag = outputList.getCompound(i);
            int slot = itemTag.getByte("Slot") & 255;
            if (slot < outputInventory.getContainerSize()) {
                ItemStack.parse(registries, itemTag).ifPresent(s -> outputInventory.setItem(slot, s));
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
}
