package com.craftingveloce.inventory;

import com.craftingveloce.inventory.VeloceCraftingBuffer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Menu magazynu auto-craftera: siatka ze scrollbarem, jak w skrzyni z modow.
 *
 * <p><b>Dlaczego wlasne menu.</b> Poprzednia proba podmieniala sloty w ekranie
 * creative inventory, co konczylo sie itemami na slocie glowy i zepsutym
 * ukladem - walczylismy z vanilla zamiast zrobic wlasny ekran. Tutaj mamy
 * pelna kontrole: wlasna siatka, wlasny scroll, zero niepozadanych slotow.
 *
 * <p>Bufor ma 216 slotow, a na ekranie miesci sie {@link #VISIBLE_ROWS} x 9.
 * Przewijanie realizujemy przez pole danych {@link #DATA_SCROLL}, ktore
 * przesuwa okno widocznosci - sloty pokazuja wtedy inne indeksy bufora.
 *
 * <p>Wyciaganie dziala, wkladanie jest zablokowane: bufor to pamiec podreczna
 * produkcji, nie kolejna skrzynia.
 */
public class VeloceCrafterStorageMenu extends AbstractContainerMenu {

    /** Widoczne wiersze siatki. */
    public static final int VISIBLE_ROWS = 6;

    /** Slotow widocznych naraz. */
    public static final int VISIBLE_SLOTS = VISIBLE_ROWS * 9;

    /** Indeks pola danych z pozycja przewijania. */
    public static final int DATA_SCROLL = 0;

    private final VeloceCraftingBuffer buffer;
    private final ContainerData data;

    /** Kontener posredniczacy: slot i widzi bufor pod przesunietym indeksem. */
    private final Container view;

    /** Pozycja przewijania w wierszach. */
    private int scrollRow = 0;

    public VeloceCrafterStorageMenu(int containerId, Inventory playerInventory,
                                    VeloceCraftingBuffer buffer) {
        super(com.craftingveloce.init.VeloceRegistry.VELOCE_CRAFTER_STORAGE_MENU.get(),
                containerId);
        this.buffer = buffer;

        this.data = new ContainerData() {
            @Override
            public int get(int index) {
                return index == DATA_SCROLL ? scrollRow : 0;
            }

            @Override
            public void set(int index, int value) {
                if (index == DATA_SCROLL) {
                    scrollRow = value;
                }
            }

            @Override
            public int getCount() {
                return 1;
            }
        };
        addDataSlots(data);

        // Kontener-widok: mapuje widoczny slot na odpowiedni indeks bufora.
        this.view = new SimpleContainer(VISIBLE_SLOTS) {
            @Override
            public ItemStack getItem(int slot) {
                return buffer.getItem(viewIndexToBuffer(slot));
            }

            @Override
            public void setItem(int slot, ItemStack stack) {
                buffer.setItem(viewIndexToBuffer(slot), stack);
            }

            @Override
            public ItemStack removeItem(int slot, int amount) {
                return buffer.removeItem(viewIndexToBuffer(slot), amount);
            }

            @Override
            public ItemStack removeItemNoUpdate(int slot) {
                return buffer.removeItemNoUpdate(viewIndexToBuffer(slot));
            }

            @Override
            public void setChanged() {
                buffer.setChanged();
            }

            @Override
            public boolean stillValid(Player player) {
                return true;
            }
        };

        buildSlots(playerInventory);
    }

    /** Zamienia indeks widocznego slotu na indeks w buforze. */
    private int viewIndexToBuffer(int viewSlot) {
        return viewSlot + scrollRow * 9;
    }

    /** Maksymalna pozycja przewijania. */
    public int maxScrollRow() {
        int totalRows = (buffer.getContainerSize() + 8) / 9;
        return Math.max(0, totalRows - VISIBLE_ROWS);
    }

    public int getScrollRow() {
        return scrollRow;
    }

    public void setScrollRow(int row) {
        this.scrollRow = Math.max(0, Math.min(maxScrollRow(), row));
    }

    private void buildSlots(Inventory playerInventory) {
        // Siatka magazynu (tylko wyswietlanie i wyciaganie).
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            for (int col = 0; col < 9; col++) {
                int idx = row * 9 + col;
                addSlot(new Slot(view, idx, 8 + col * 18, 18 + row * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        // Bufor to pamiec produkcji - nie wkladamy recznie.
                        return false;
                    }

                    @Override
                    public boolean mayPickup(Player player) {
                        return true;
                    }
                });
            }
        }

        // Ekwipunek gracza.
        int invY = 18 + VISIBLE_ROWS * 18 + 14;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18,
                        invY + row * 18));
            }
        }
        // Hotbar.
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, invY + 58));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        // Z magazynu craftera do gracza (wyciaganie).
        if (index < VISIBLE_SLOTS) {
            ItemStack inSlot = slot.getItem();
            ItemStack copy = inSlot.copy();
            if (!moveItemStackTo(inSlot, VISIBLE_SLOTS, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
            slot.onQuickCraft(inSlot, copy);
            return copy;
        }
        // Z gracza do magazynu NIE przenosimy.
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
