package com.craftingveloce.inventory;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Bufor auto-craftera - pamiec podreczna na nadwyzke produkcji.
 *
 * <p>Gdy craftowanie daje wiecej niz gracz pobral (np. z 1 logu powstaje
 * 4 deski, a gracz chcial 1), nadwyzka zostaje tutaj i jest <b>normalnie
 * dostepna dla calej sieci</b> - mozna ja wyciagnac z terminala, extractorem,
 * hopperem, czymkolwiek.
 *
 * <p>Pojemnosc: 4x wieksza niz podwojna skrzynia (54 sloty) = 216 slotow.
 *
 * <p>Bufor jest tylko do odczytu dla gracza przez GUI - nie ma opcji
 * manualnego wkladania. Automatyzacja (rury, hoppery, extractor) moze
 * z niego korzystac normalnie.
 */
public class VeloceCraftingBuffer implements Container {

    /** 4x podwojna skrzynia: 54 * 4 = 216 slotow. */
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
        // Block entity nadpisuje to, zeby zapisac NBT.
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
    }

    /** Dostep do listy (NBT). */
    public NonNullList<ItemStack> getItems() {
        return items;
    }

    /**
     * Wklada stack do bufora, stackujac z istniejacymi.
     *
     * @return pozostala czesc (EMPTY gdy wszystko sie zmiescilo)
     */
    public ItemStack insert(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        int max = Math.min(64, remaining.getMaxStackSize());

        // Najpierw dostackuj do istniejacych.
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
        // Potem puste sloty.
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

    /** Liczba sztuk danego itemu w buforze. */
    public int count(net.minecraft.world.item.Item item) {
        int n = 0;
        for (ItemStack s : items) {
            if (!s.isEmpty() && s.getItem() == item) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** Laduje zawartosc z listy NBT. */
    public void loadFrom(net.minecraft.nbt.ListTag list, net.minecraft.core.HolderLookup.Provider registries) {
        for (int i = 0; i < SIZE; i++) {
            items.set(i, ItemStack.EMPTY);
        }
        ContainerHelper.loadAllItems(
                wrapList(list), items, registries);
    }

    /** Zapisuje zawartosc do listy NBT. */
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
