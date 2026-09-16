package com.craftingveloce.inventory;

import com.craftingveloce.block.entity.VeloceModuleDisplay;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Menu maszyny Veloce - DOKLADNIE jak w piecu.
 *
 * <p>Maszyna nie ma wlasnych slotow, wiec menu ma tylko ekwipunek gracza (te
 * same wspolrzedne co piec i ekstraktor: x=26, y=84/142). Dzieki temu okno to
 * zwykly {@code AbstractContainerScreen} z tekstura pieca - bez wlasnego
 * rysowania i bez pakietow.
 *
 * <p>Pola do okna czytamy z block entity u klienta (tak samo robi piec:
 * {@code VeloceElectricFurnaceMenu.getEnergy()}). Predkosc kinetyczna jest
 * synchronizowana przez Create, energia przez nasz block entity.
 */
public class VeloceModuleMenu extends AbstractContainerMenu {

    private static final int PLAYER_X = 26;
    private static final int PLAYER_Y = 84;

    /** Slot baterii - zgodnie z wglebieniem w teksturze pieca. */
    private static final int BATTERY_SLOT_X = 130;
    private static final int BATTERY_SLOT_Y = 32;

    private final BlockPos pos;
    private final VeloceModuleDisplay module;

    public VeloceModuleMenu(int id, Inventory playerInv, BlockPos pos) {
        super(com.craftingveloce.init.VeloceRegistry.VELOCE_MODULE_MENU.get(), id);
        this.pos = pos;
        BlockEntity be = playerInv.player.level().getBlockEntity(pos);
        this.module = be instanceof VeloceModuleDisplay display ? display : null;

        // Slot baterii - te same wspolrzedne co w piecu (130, 32). Tylko
        // maszyny na energie: kinetyczne nie maja czego ladowac.
        if (be instanceof com.craftingveloce.block.entity.VeloceFeModuleBlockEntity fe) {
            this.addSlot(new Slot(fe.getBatterySlot(), 0,
                    BATTERY_SLOT_X, BATTERY_SLOT_Y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return com.craftingveloce.block.entity.VeloceFeModuleBlockEntity
                            .isEnergyItem(stack);
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }
            });
        }

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

    /** Pola do okna (puste, gdy block entity jeszcze nie ma). */
    public CompoundTag display() {
        return module == null ? new CompoundTag() : module.moduleDisplay();
    }

    public BlockPos getPos() {
        return pos;
    }

    /** Shift-klik: z ekwipunku do slotu baterii (tylko itemy z energia). */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem() || module == null) {
            return ItemStack.EMPTY;
        }
        ItemStack inSlot = slot.getItem();
        ItemStack copy = inSlot.copy();
        boolean battery = index == 0 && this.slots.size() > 36;
        if (battery) {
            if (!this.moveItemStackTo(inSlot, 1, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!com.craftingveloce.block.entity.VeloceFeModuleBlockEntity.isEnergyItem(inSlot)) {
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
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }
}
