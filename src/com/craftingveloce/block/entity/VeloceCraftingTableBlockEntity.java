package com.craftingveloce.block.entity;

import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.network.OpenCraftingTableScreenPKT;
import com.craftingveloce.network.SyncCraftingTableStatePKT;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.Set;

public class VeloceCraftingTableBlockEntity extends BlockEntity {

    private Set<Item> enabledItems = new HashSet<>();

    public VeloceCraftingTableBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.VELOCE_CRAFTING_TABLE_BE.get(), pos, state);
    }

    public Set<Item> getEnabledItems() {
        return enabledItems;
    }

    public void toggleItem(Item item) {
        if (enabledItems.contains(item)) {
            enabledItems.remove(item);
        } else {
            enabledItems.add(item);
        }
        setChanged();
    }

    public void syncToPlayer(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new OpenCraftingTableScreenPKT(this.getBlockPos(), new HashSet<>(enabledItems)));
    }

    public void syncToWatchers(ServerLevel level) {
        SyncCraftingTableStatePKT pkt = new SyncCraftingTableStatePKT(this.getBlockPos(), new HashSet<>(enabledItems));
        for (ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, pkt);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (Item item : enabledItems) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(item);
            if (rl != null) {
                list.add(StringTag.valueOf(rl.toString()));
            }
        }
        tag.put("EnabledItems", list);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        enabledItems = new HashSet<>();
        ListTag list = tag.getList("EnabledItems", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            String s = list.getString(i);
            ResourceLocation rl = ResourceLocation.tryParse(s);
            if (rl != null) {
                Item item = BuiltInRegistries.ITEM.get(rl);
                if (item != null) {
                    enabledItems.add(item);
                }
            }
        }
    }
}
