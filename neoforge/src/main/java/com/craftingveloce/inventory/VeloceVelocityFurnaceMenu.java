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
 * Velocity Furnace menu.
 *
 * <p><b>Layout.</b> Six FUEL filter slots (just like in the extractor - these are
 * phantom slots, the icons are drawn by the screen), one slot for the fuel that the furnace
 * actually burns, and the player's inventory.
 *
 * <p>The filters are placeholders, because they do not hold items - they only serve
 * to choose WHICH fuel the furnace should pull from the network. The values are held
 * by the block entity, and the screen displays them.
 */
public class VeloceVelocityFurnaceMenu extends AbstractContainerMenu {

    /** How many filter slots (must match the BE). */
    public static final int FILTER_SLOTS = VeloceVelocityFurnaceBlockEntity.FUEL_FILTERS;

    // Layout (centred in a 212 px panel):
    //
    //     [ filters 3x2 ]   [ flame ]
    //                       [ fuel  ]
    //
    // So to the right of the filters there is a COLUMN of two cells: the flame at the top
    // (drawn by the screen with vanilla sprites), and below it the fuel slot with what
    // the furnace is currently burning. The gap between the filters and the column is one
    // slot step (17 px), so the spacings are even, not "by eye".
    private static final int FILTER_X = 63;
    private static final int FILTER_Y = 18;
    /** The flame+fuel column - exactly one slot step behind the filters. */
    private static final int FUEL_X = 134;
    private static final int FUEL_Y = 36;
    /**
     * Player inventory at x=26 - the SAME as in the extractor.
     *
     * <p>It used to be 8 here (the vanilla default), which made the inventory stick to
     * the left edge of the panel, and the "Inventory" label (drawn from x=26) did not
     * match the slots beneath it. The rest of the mod sets this to 26.
     */
    private static final int PLAYER_X = 26;
    private static final int PLAYER_Y = 84;

    private final VeloceVelocityFurnaceBlockEntity furnace;

    /**
     * Block position. We keep it SEPARATELY, because on the client side the block entity
     * may be temporarily unavailable - and the screen still has to know which
     * furnace it concerns (e.g. to return here from the filter selection).
     */
    private final net.minecraft.core.BlockPos pos;

    /** Constructor used by the menu type - the position comes from the packet. */
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

        // 1. Fuel filters - phantoms, 3 columns x 2 rows.
        //
        // NOTE: the slot MUST stay ACTIVE (we do not override isActive).
        //
        // The BUG that was here: I set isActive() to false "because the screen draws
        // the icons". But AbstractContainerScreen.getSlotUnderMouse() skips
        // inactive slots - so such a slot never reached slotClicked
        // and CLICKING A FILTER DID NOTHING. The screen drew the filters beautifully,
        // but item selection was dead.
        //
        // The extractor does this right: it leaves the slot active and blocks only
        // mayPlace/mayPickup. Then the slot can be hovered and clicked, but nothing
        // can be put into it - and that is the point.
        Container placeholders = new SimpleContainer(FILTER_SLOTS);
        for (int i = 0; i < FILTER_SLOTS; i++) {
            int col = i % 3;
            int row = i / 3;
            this.addSlot(new Slot(placeholders, i,
                    FILTER_X + col * 18, FILTER_Y + row * 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;   // we do not fill filters with items
                }

                @Override
                public boolean mayPickup(Player player) {
                    return false;   // and we do not take items out of them
                }
            });
        }

        // 2. The real fuel slot.
        this.addSlot(new Slot(furnace != null ? furnace.getFuelSlot() : new SimpleContainer(1),
                0, FUEL_X, FUEL_Y));

        // 3. Player inventory.
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

    /** Furnace position - needed by the screen and by the return from filter selection. */
    public net.minecraft.core.BlockPos getPos() {
        return furnace != null ? furnace.getBlockPos() : pos;
    }

    /** Current filter position (for drawing icons and handling clicks). */
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
                // From the furnace to the player.
                if (!this.moveItemStackTo(inSlot, playerStart, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // From the player to the fuel slot - only if it is fuel.
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
