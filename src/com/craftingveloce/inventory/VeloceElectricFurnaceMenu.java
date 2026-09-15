package com.craftingveloce.inventory;

import com.craftingveloce.block.entity.VeloceElectricFurnaceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Menu Velocity Electric Furnace.
 *
 * <p><b>Uklad.</b> Tylko ekwipunek gracza. Piec elektryczny nie ma ani
 * slotu paliwa, ani filtrow - jego calym "wyposazeniem" jest akumulator,
 * ktory pokazuje ekran jako pasek energii. Zgodnie z ustaleniem piec nie
 * przyjmuje przedmiotow: to bufor pradu dla auto-craftera, a nie miejsce
 * do przechowywania rzeczy.
 */
public class VeloceElectricFurnaceMenu extends AbstractContainerMenu {

    private static final int PLAYER_X = 8;
    private static final int PLAYER_Y = 84;

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

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Piec nie ma wlasnych slotow, wiec shift-klik nie ma gdzie przenosic.
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return furnace == null
                || player.distanceToSqr(furnace.getBlockPos().getX() + 0.5,
                furnace.getBlockPos().getY() + 0.5,
                furnace.getBlockPos().getZ() + 0.5) <= 64.0;
    }
}
