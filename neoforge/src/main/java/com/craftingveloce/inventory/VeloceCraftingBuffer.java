package com.craftingveloce.inventory;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Buffer of the auto-crafter - a cache for surplus production.
 *
 * <p>When crafting yields more than the player took (e.g. 1 log produces
 * 4 planks, but the player wanted 1), the surplus stays here and is
 * <b>normally available to the whole network</b> - it can be pulled out from
 * the terminal, with an extractor, a hopper, anything.
 *
 * <p>Capacity: 4x a double chest (54 slots) = 216 slots.
 *
 * <p>The buffer is read-only for the player through the GUI - there is no
 * option for manual insertion. Automation (pipes, hoppers, the extractor) can
 * use it normally.
 */
public class VeloceCraftingBuffer implements Container {

    /** 4x a double chest: 54 * 4 = 216 slots. */
    public static final int SIZE = 216;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : items) {
            if (!s.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < SIZE ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot < 0 || slot >= SIZE || amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack existing = items.get(slot);
        if (existing.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = existing.split(amount);
        if (existing.isEmpty()) {
            items.set(slot, ItemStack.EMPTY);
        }
        setChanged();
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot < 0 || slot >= SIZE) {
            return ItemStack.EMPTY;
        }
        ItemStack s = items.get(slot);
        items.set(slot, ItemStack.EMPTY);
        return s;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        items.set(slot, stack);
        setChanged();
    }

    @Override
    public void setChanged() {
        // The block entity overrides this in order to save NBT.
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        // NOT items.clear().
        //
        // NonNullList.clear() clears the UNDERLYING list, so its size drops
        // from SIZE (216) to zero - while getContainerSize() still returns SIZE.
        // Every subsequent items.get(i) (that is getItem, removeItem, insert,
        // the endpoint scan) would throw IndexOutOfBoundsException.
        //
        // Correctly: zero out the contents while keeping the size.
        for (int i = 0; i < SIZE; i++) {
            items.set(i, ItemStack.EMPTY);
        }
        setChanged();
    }

    /** Access to the list (NBT). */
    public NonNullList<ItemStack> getItems() {
        return items;
    }

    /**
     * Inserts a stack into the buffer, stacking with the existing ones.
     *
     * @return the remainder (EMPTY when everything fit)
     */
    public ItemStack insert(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        int max = Math.min(64, remaining.getMaxStackSize());

        // First stack onto the existing ones.
        for (int i = 0; i < SIZE && !remaining.isEmpty(); i++) {
            ItemStack slot = items.get(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, remaining)) {
                int space = max - slot.getCount();
                if (space > 0) {
                    int move = Math.min(space, remaining.getCount());
                    slot.grow(move);
                    remaining.shrink(move);
                }
            }
        }
        // Then the empty slots.
        for (int i = 0; i < SIZE && !remaining.isEmpty(); i++) {
            if (items.get(i).isEmpty()) {
                int move = Math.min(max, remaining.getCount());
                items.set(i, remaining.split(move));
            }
        }
        if (remaining.getCount() != stack.getCount()) {
            setChanged();
        }
        return remaining;
    }

    /** Number of units of the given item in the buffer. */
    public int count(net.minecraft.world.item.Item item) {
        int n = 0;
        for (ItemStack s : items) {
            if (!s.isEmpty() && s.getItem() == item) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** Loads the contents from an NBT list. */
    public void loadFrom(net.minecraft.nbt.ListTag list, net.minecraft.core.HolderLookup.Provider registries) {
        for (int i = 0; i < SIZE; i++) {
            items.set(i, ItemStack.EMPTY);
        }
        ContainerHelper.loadAllItems(
                wrapList(list), items, registries);
    }

    /** Saves the contents to an NBT list. */
    public net.minecraft.nbt.ListTag saveTo(net.minecraft.core.HolderLookup.Provider registries) {
        return ContainerHelper.saveAllItems(new net.minecraft.nbt.CompoundTag(), items, registries)
                .getList("Items", net.minecraft.nbt.Tag.TAG_COMPOUND);
    }

    private static net.minecraft.nbt.CompoundTag wrapList(net.minecraft.nbt.ListTag list) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.put("Items", list);
        return tag;
    }
}
