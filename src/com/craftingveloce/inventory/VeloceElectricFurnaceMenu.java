package com.craftingveloce.inventory;

import com.craftingveloce.block.entity.VeloceElectricFurnaceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Menu Velocity Electric Furnace.
 *
 * <p><b>Uklad.</b> Akumulator (pozioma bateria w ekranie) i JEDEN slot na
 * itemek z energia, po jego prawej stronie - plus ekwipunek gracza.
 *
 * <p>W tym slocie lezy bateria / Energy Cube / tablet z innego moda, a piec
 * pobiera z niej prad do swojego akumulatora. Item NIE jest zuzywany: lezy
 * tak dlugo, az gracz go wyjmie, i mozna go wyjac w kazdej chwili.
 */
public class VeloceElectricFurnaceMenu extends AbstractContainerMenu {

    /** Ekwipunek gracza w x=26 - tak samo jak w ekstraktorze. */
    private static final int PLAYER_X = 26;
    private static final int PLAYER_Y = 84;

    /**
     * Slot na itemek z energia - po PRAWEJ stronie baterii.
     *
     * <p>Musi sie zgadzac z wglebieniem w teksturze (gen_furnace_gui.py) i ze
     * stala w ekranie; pilnuje tego build.py.
     */
    public static final int BATTERY_SLOT_X = 92;
    public static final int BATTERY_SLOT_Y = 32;

    private final VeloceElectricFurnaceBlockEntity furnace;
    private final BlockPos pos;

    public VeloceElectricFurnaceMenu(int id, Inventory playerInv, BlockPos pos) {
        this(id, playerInv, pos, playerInv.player.level().getBlockEntity(pos));
    }

    public VeloceElectricFurnaceMenu(int id, Inventory playerInv, BlockPos pos, BlockEntity be) {
        this(id, playerInv, pos, be instanceof VeloceElectricFurnaceBlockEntity f ? f : null);
    }

    public VeloceElectricFurnaceMenu(int id, Inventory playerInv, BlockPos pos,
                                     VeloceElectricFurnaceBlockEntity furnace) {
        super(com.craftingveloce.init.VeloceRegistry.ELECTRIC_FURNACE_MENU.get(), id);
        this.furnace = furnace;
        this.pos = pos;

        // 1. Slot baterii. Przyjmuje TYLKO itemy, z ktorych da sie oddac
        //    energie (standardowa zdolnosc Forge Energy na itemie) - dzieki
        //    temu nie da sie tu przypadkiem zostawic zwyklego smiecia, a gracz
        //    od razu widzi, po co ten slot jest.
        this.addSlot(new Slot(
                furnace != null ? furnace.getBatterySlot() : new SimpleContainer(1),
                0, BATTERY_SLOT_X, BATTERY_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return VeloceElectricFurnaceBlockEntity.isEnergyItem(stack);
            }

            @Override
            public int getMaxStackSize() {
                return 1;   // baterie nosi sie po jednej
            }
        });

        // 2. Ekwipunek gracza.
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

    public VeloceElectricFurnaceBlockEntity getFurnace() {
        return furnace;
    }

    /** Pozycja pieca - potrzebna ekranowi do zapytan o stan. */
    public BlockPos getPos() {
        return furnace != null ? furnace.getBlockPos() : pos;
    }

    /** Ile pradu jest w akumulatorze (0 gdy block entity niedostepne). */
    public int getEnergy() {
        return furnace == null ? 0 : furnace.getEnergy();
    }

    public int getMaxEnergy() {
        return furnace == null
                ? VeloceElectricFurnaceBlockEntity.ENERGY_CAPACITY
                : furnace.getMaxEnergyStored();
    }

    /**
     * Shift-klik: z ekwipunku do slotu baterii (tylko itemy z energia)
     * i z powrotem.
     *
     * <p>Bez tego gracz musialby przeciagac baterie recznie, a shift-klik
     * w piecu robilby nic (tak bylo wczesniej, bo piec nie mial zadnego
     * wlasnego slotu).
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack inSlot = slot.getItem();
        ItemStack copy = inSlot.copy();
        boolean isBatterySlot = index == 0;

        if (isBatterySlot) {
            // Z pieca do gracza.
            if (!this.moveItemStackTo(inSlot, 1, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // Z gracza do slotu baterii - tylko gdy to item z energia.
            if (!VeloceElectricFurnaceBlockEntity.isEnergyItem(inSlot)) {
                return ItemStack.EMPTY;
            }
            if (!this.moveItemStackTo(inSlot, 0, 1, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (inSlot.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
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
