package com.craftingveloce.network.pipe;

import com.craftingveloce.rs.RefinedStorageHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.HashMap;
import java.util.Map;

public class ConnectedEndpointInfo {

    public enum Type {
        INVENTORY,
        REFINED_STORAGE
    }

    private final BlockPos pos;
    private final Direction accessSide;
    private final ChunkPos chunkPos;
    private final Type type;
    private final Map<Item, Long> cachedCounts = new HashMap<>();

    public ConnectedEndpointInfo(BlockPos pos, Direction accessSide, Type type) {
        this.pos = pos;
        this.accessSide = accessSide;
        this.chunkPos = new ChunkPos(pos);
        this.type = type;
    }

    public BlockPos getPos() {
        return pos;
    }

    public Direction getAccessSide() {
        return accessSide;
    }

    public ChunkPos getChunkPos() {
        return chunkPos;
    }

    public Type getType() {
        return type;
    }

    public Map<Item, Long> getCachedCounts() {
        return cachedCounts;
    }

    public void refreshIfLoaded(ServerLevel level) {
        if (!level.isLoaded(pos)) {
            return;
        }

        if (type == Type.REFINED_STORAGE) {
            Map<Item, Long> rsCounts = RefinedStorageHelper.getRSItemCounts(level, pos, accessSide);
            cachedCounts.clear();
            cachedCounts.putAll(rsCounts);
            return;
        }

        // Standard INVENTORY
        Map<Item, Long> newCounts = new HashMap<>();
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(level, pos, state, be, accessSide);
            if (handler != null) {
                for (int i = 0; i < handler.getSlots(); i++) {
                    ItemStack stack = handler.getStackInSlot(i);
                    if (!stack.isEmpty()) {
                        newCounts.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
                    }
                }
            } else {
                if (be instanceof Container container) {
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        ItemStack stack = container.getItem(i);
                        if (!stack.isEmpty()) {
                            newCounts.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
                        }
                    }
                }
            }
            cachedCounts.clear();
            cachedCounts.putAll(newCounts);
        } catch (Throwable ignored) {
        }
    }

    public ItemStack extractItem(ServerLevel level, Item item, int maxCount) {
        if (cachedCounts.getOrDefault(item, 0L) <= 0) {
            return ItemStack.EMPTY;
        }

        if (type == Type.REFINED_STORAGE) {
            ItemStack extracted = RefinedStorageHelper.extractItem(level, pos, accessSide, new ItemStack(item), maxCount);
            if (!extracted.isEmpty()) {
                refreshIfLoaded(level);
            }
            return extracted;
        }

        // INVENTORY
        boolean wasLoaded = level.isLoaded(pos);
        if (!wasLoaded) {
            level.setChunkForced(chunkPos.x, chunkPos.z, true);
            level.getChunkSource().getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true);
        }

        ItemStack result = ItemStack.EMPTY;
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(level, pos, state, be, accessSide);
            if (handler != null) {
                int needed = maxCount;
                for (int i = 0; i < handler.getSlots(); i++) {
                    ItemStack inSlot = handler.getStackInSlot(i);
                    if (!inSlot.isEmpty() && inSlot.getItem() == item) {
                        ItemStack extracted = handler.extractItem(i, needed, false);
                        if (!extracted.isEmpty()) {
                            if (result.isEmpty()) {
                                result = extracted.copy();
                            } else {
                                result.grow(extracted.getCount());
                            }
                            needed -= extracted.getCount();
                            if (needed <= 0) break;
                        }
                    }
                }
            } else {
                if (be instanceof Container container) {
                    int needed = maxCount;
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        ItemStack inSlot = container.getItem(i);
                        if (!inSlot.isEmpty() && inSlot.getItem() == item) {
                            int toTake = Math.min(needed, inSlot.getCount());
                            ItemStack taken = container.removeItem(i, toTake);
                            if (!taken.isEmpty()) {
                                if (result.isEmpty()) {
                                    result = taken.copy();
                                } else {
                                    result.grow(taken.getCount());
                                }
                                needed -= taken.getCount();
                                if (needed <= 0) break;
                            }
                        }
                    }
                }
            }

            refreshIfLoaded(level);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (!wasLoaded) {
                level.setChunkForced(chunkPos.x, chunkPos.z, false);
            }
        }

        return result;
    }

    /**
     * Wklada item do tego endpointu (odwrotnosc {@link #extractItem}).
     *
     * <p>Uzywane przez auto-crafter do odkładania wynikow craftowania.
     * Obsluguje te same trzy rodzaje magazynow co ekstrakcja: Refined Storage,
     * NeoForge ItemHandler oraz vanilla Container.
     *
     * @return true, jesli udalo sie wlozyc cala stacke
     */
    public boolean insertItem(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }

        if (type == Type.REFINED_STORAGE) {
            boolean ok = RefinedStorageHelper.insertItem(level, pos, accessSide, stack);
            if (ok) {
                refreshIfLoaded(level);
            }
            return ok;
        }

        boolean wasLoaded = level.isLoaded(pos);
        if (!wasLoaded) {
            level.setChunkForced(chunkPos.x, chunkPos.z, true);
            level.getChunkSource().getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true);
        }

        ItemStack remaining = stack.copy();
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(level, pos, state, be, accessSide);
            if (handler != null) {
                for (int i = 0; i < handler.getSlots() && !remaining.isEmpty(); i++) {
                    remaining = handler.insertItem(i, remaining, false);
                }
            } else if (be instanceof Container container) {
                for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
                    ItemStack inSlot = container.getItem(i);
                    int max = Math.min(container.getMaxStackSize(), remaining.getMaxStackSize());
                    if (inSlot.isEmpty()) {
                        int move = Math.min(max, remaining.getCount());
                        container.setItem(i, remaining.split(move));
                    } else if (ItemStack.isSameItemSameComponents(inSlot, remaining)) {
                        int space = max - inSlot.getCount();
                        if (space > 0) {
                            int move = Math.min(space, remaining.getCount());
                            inSlot.grow(move);
                            remaining.shrink(move);
                            container.setChanged();
                        }
                    }
                }
            }

            refreshIfLoaded(level);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (!wasLoaded) {
                level.setChunkForced(chunkPos.x, chunkPos.z, false);
            }
        }
        return remaining.isEmpty();
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Pos", pos.asLong());
        tag.putInt("Side", accessSide.ordinal());
        tag.putString("Type", type.name());

        ListTag list = new ListTag();
        for (Map.Entry<Item, Long> entry : cachedCounts.entrySet()) {
            if (entry.getValue() <= 0) continue;
            CompoundTag itemTag = new CompoundTag();
            itemTag.putString("id", BuiltInRegistries.ITEM.getKey(entry.getKey()).toString());
            itemTag.putLong("cnt", entry.getValue());
            list.add(itemTag);
        }
        tag.put("Cache", list);
        return tag;
    }

    public static ConnectedEndpointInfo fromNbt(CompoundTag tag) {
        BlockPos pos = BlockPos.of(tag.getLong("Pos"));
        Direction side = Direction.values()[tag.getInt("Side")];
        Type type = Type.valueOf(tag.getString("Type"));

        ConnectedEndpointInfo info = new ConnectedEndpointInfo(pos, side, type);
        ListTag list = tag.getList("Cache", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag itemTag = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(itemTag.getString("id"));
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                Item item = BuiltInRegistries.ITEM.get(id);
                long count = itemTag.getLong("cnt");
                info.cachedCounts.put(item, count);
            }
        }
        return info;
    }
}
