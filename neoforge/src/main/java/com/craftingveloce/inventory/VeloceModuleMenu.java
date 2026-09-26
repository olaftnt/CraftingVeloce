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
 * Menu of a Veloce machine - EXACTLY like in the furnace.
 *
 * <p>The machine has no slots of its own, so the menu only has the player's
 * inventory (the same coordinates as the furnace and the extractor: x=26,
 * y=84/142). Thanks to that the window is a plain {@code AbstractContainerScreen}
 * with the furnace texture - without its own rendering and without packets.
 *
 * <p>We read the fields for the window from the client's block entity (the
 * furnace does the same: {@code VeloceElectricFurnaceMenu.getEnergy()}). Kinetic
 * speed is synchronized by Create, energy by our block entity.
 */
public class VeloceModuleMenu extends AbstractContainerMenu {

    private static final int PLAYER_X = 26;
    private static final int PLAYER_Y = 84;

    /** Battery slot - aligned with the recess in the furnace texture. */
    private static final int BATTERY_SLOT_X = 130;
    private static final int BATTERY_SLOT_Y = 32;

    private final BlockPos pos;
    private final VeloceModuleDisplay module;

    public VeloceModuleMenu(int id, Inventory playerInv, BlockPos pos) {
        super(com.craftingveloce.init.VeloceRegistry.VELOCE_MODULE_MENU.get(), id);
        this.pos = pos;
        BlockEntity be = playerInv.player.level().getBlockEntity(pos);
        this.module = be instanceof VeloceModuleDisplay display ? display : null;

        // Battery slot - the same coordinates as in the furnace (130, 32). Only
        // energy machines: kinetic ones have nothing to charge.
        if (be instanceof com.craftingveloce.block.entity.VeloceFeModuleBlockEntity fe) {
            this.addSlot(new BatterySlot(fe.getBatterySlot(), 0,
                    BATTERY_SLOT_X, BATTERY_SLOT_Y));
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

    /** Fields for the window (empty when the block entity is not there yet). */
    public CompoundTag display() {
        return module == null ? new CompoundTag() : module.moduleDisplay();
    }

    public BlockPos getPos() {
        return pos;
    }

    /** Shift-click: from the inventory into the battery slot (only energy items). */
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

    private static class BatterySlot extends Slot {
        public BatterySlot(net.minecraft.world.Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return com.craftingveloce.block.entity.VeloceFeModuleBlockEntity.isEnergyItem(stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
