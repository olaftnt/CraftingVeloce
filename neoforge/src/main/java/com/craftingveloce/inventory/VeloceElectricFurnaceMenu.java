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
 * Menu of the Velocity Electric Furnace.
 *
 * <p><b>Layout.</b> The accumulator (a horizontal battery in the screen) and ONE
 * slot for the energy item, to its right - plus the player's inventory.
 *
 * <p>This slot holds a battery / Energy Cube / tablet from another mod, and the
 * furnace draws power from it into its own accumulator. The item is NOT consumed:
 * it stays there until the player takes it out, and it can be taken out at any
 * moment.
 */
public class VeloceElectricFurnaceMenu extends AbstractContainerMenu {

    /** Player inventory at x=26 - just like in the extractor. */
    private static final int PLAYER_X = 26;
    private static final int PLAYER_Y = 84;

    /**
     * Slot for the energy item - on the RIGHT side of the battery.
     *
     * <p>It must match the recess in the texture (gen_furnace_gui.py) and the
     * constant in the screen; build.py enforces this.
     */
    public static final int BATTERY_SLOT_X = 130;
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

        // 1. Battery slot. It accepts ONLY items that can give back energy
        //    (the standard Forge Energy capability on an item) - thanks to that
        //    one cannot accidentally leave ordinary junk here, and the player
        //    immediately sees what the slot is for.
        this.addSlot(new Slot(
                furnace != null ? furnace.getBatterySlot() : new SimpleContainer(1),
                0, BATTERY_SLOT_X, BATTERY_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return VeloceElectricFurnaceBlockEntity.isEnergyItem(stack);
            }

            @Override
            public int getMaxStackSize() {
                return 1;   // batteries are carried one at a time
            }
        });

        // 2. Player inventory.
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

    /** Furnace position - needed by the screen to query the state. */
    public BlockPos getPos() {
        return furnace != null ? furnace.getBlockPos() : pos;
    }

    /** How much power is in the accumulator (0 when the block entity is unavailable). */
    public int getEnergy() {
        return furnace == null ? 0 : furnace.getEnergy();
    }

    public int getMaxEnergy() {
        return furnace == null
                ? VeloceElectricFurnaceBlockEntity.ENERGY_CAPACITY
                : furnace.getMaxEnergyStored();
    }

    /**
     * Shift-click: from the inventory to the battery slot (only energy items)
     * and back.
     *
     * <p>Without this the player would have to drag batteries by hand, and
     * shift-clicking in the furnace would do nothing (that is how it was before,
     * because the furnace had no slot of its own).
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
            // From the furnace to the player.
            if (!this.moveItemStackTo(inSlot, 1, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // From the player to the battery slot - only when it is an energy item.
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
