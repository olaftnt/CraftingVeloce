package com.craftingveloce.inventory;

import com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Menu Velocity Furnace.
 *
 * <p><b>Uklad.</b> Szesc slotow FILTRA paliwa (tak jak w ekstraktorze - to
 * sloty-widma, ikony rysuje ekran), jeden slot na paliwo, ktore piec realnie
 * spala, oraz ekwipunek gracza.
 *
 * <p>Filtry sa placeholderami, bo nie trzymaja przedmiotow - sluza tylko do
 * wyboru, JAKIE paliwo piec ma zaciagac z sieci. Wartosci trzyma block entity,
 * a ekran je wyswietla.
 */
public class VeloceVelocityFurnaceMenu extends AbstractContainerMenu {

    /** Ile slotow filtra (musi zgadzac sie z BE). */
    public static final int FILTER_SLOTS = VeloceVelocityFurnaceBlockEntity.FUEL_FILTERS;

    private static final int FILTER_X = 26;
    private static final int FILTER_Y = 18;
    private static final int FUEL_X = 26;
    private static final int FUEL_Y = 60;
    /**
     * Ekwipunek gracza w x=26 - TAK SAMO jak w ekstraktorze.
     *
     * <p>Bylo tu 8 (domyslna wartosc vanilli), przez co ekwipunek kleil sie do
     * lewej krawedzi panelu, a etykieta "Inventory" (rysowana od x=26) nie
     * pasowala do slotow pod nia. Cala reszta moda ustawia to na 26.
     */
    private static final int PLAYER_X = 26;
    private static final int PLAYER_Y = 84;

    private final VeloceVelocityFurnaceBlockEntity furnace;

    /**
     * Pozycja bloku. Trzymamy ja OSOBNO, bo po stronie klienta block entity
     * moze byc chwilowo niedostepne - a ekran i tak musi wiedziec, ktorego
     * pieca dotyczy (np. zeby wrocic tu z wyboru filtra).
     */
    private final net.minecraft.core.BlockPos pos;

    /** Konstruktor uzywany przez typ menu - pozycja przychodzi z pakietu. */
    public VeloceVelocityFurnaceMenu(int id, Inventory playerInv, net.minecraft.core.BlockPos pos) {
        this(id, playerInv, pos, playerInv.player.level().getBlockEntity(pos));
    }

    public VeloceVelocityFurnaceMenu(int id, Inventory playerInv, net.minecraft.core.BlockPos pos,
                                     BlockEntity be) {
        this(id, playerInv, pos, be instanceof VeloceVelocityFurnaceBlockEntity f ? f : null);
    }

    public VeloceVelocityFurnaceMenu(int id, Inventory playerInv,
                                     net.minecraft.core.BlockPos pos,
                                     VeloceVelocityFurnaceBlockEntity furnace) {
        super(com.craftingveloce.init.VeloceRegistry.VELOCITY_FURNACE_MENU.get(), id);
        this.furnace = furnace;
        this.pos = pos;

        // 1. Filtry paliwa - widma, 3 kolumny x 2 rzedy.
        //
        // UWAGA: slot MUSI pozostac AKTYWNY (nie nadpisujemy isActive).
        //
        // BUG, ktory tu byl: ustawialem isActive() na false "bo ikony rysuje
        // ekran". Ale AbstractContainerScreen.getSlotUnderMouse() pomija
        // sloty nieaktywne - wiec taki slot nigdy nie trafial do slotClicked
        // i KLIKNIECIE W FILTR NIE ROBILO NIC. Ekran rysowal filtry pieknie,
        // a wybor itemu byl martwy.
        //
        // Ekstraktor robi to dobrze: zostawia slot aktywnym, a blokuje tylko
        // mayPlace/mayPickup. Wtedy slot da sie najechac i kliknac, ale nic
        // nie da sie do niego przelozyc - i o to chodzi.
        Container placeholders = new SimpleContainer(FILTER_SLOTS);
        for (int i = 0; i < FILTER_SLOTS; i++) {
            int col = i % 3;
            int row = i / 3;
            this.addSlot(new Slot(placeholders, i,
                    FILTER_X + col * 18, FILTER_Y + row * 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;   // filtrow nie wypelniamy przedmiotami
                }

                @Override
                public boolean mayPickup(Player player) {
                    return false;   // i nie zabieramy z nich przedmiotow
                }
            });
        }

        // 2. Realny slot paliwa.
        this.addSlot(new Slot(furnace != null ? furnace.getFuelSlot() : new SimpleContainer(1),
                0, FUEL_X, FUEL_Y));

        // 3. Ekwipunek gracza.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9,
                        PLAYER_X + col * 18, PLAYER_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, PLAYER_X + col * 18, PLAYER_Y + 58));
        }
    }

    public VeloceVelocityFurnaceBlockEntity getFurnace() {
        return furnace;
    }

    /** Pozycja pieca - potrzebna ekranowi i powrotowi z wyboru filtra. */
    public net.minecraft.core.BlockPos getPos() {
        return furnace != null ? furnace.getBlockPos() : pos;
    }

    /** Aktualna pozycja filtra (do rysowania ikon i obslugi klikniec). */
    public ItemStack getFilter(int index) {
        return furnace == null ? ItemStack.EMPTY : furnace.getFuelFilter(index);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack copy = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack inSlot = slot.getItem();
            copy = inSlot.copy();
            int playerStart = FILTER_SLOTS + 1;

            if (index < playerStart) {
                // Z pieca do gracza.
                if (!this.moveItemStackTo(inSlot, playerStart, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // Z gracza do slotu paliwa - tylko jesli to paliwo.
                int fuelIndex = FILTER_SLOTS;
                if (!this.moveItemStackTo(inSlot, fuelIndex, fuelIndex + 1, false)) {
                    return ItemStack.EMPTY;
                }
            }
            if (inSlot.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return furnace == null
                || player.distanceToSqr(furnace.getBlockPos().getX() + 0.5,
                furnace.getBlockPos().getY() + 0.5,
                furnace.getBlockPos().getZ() + 0.5) <= 64.0;
    }
}
