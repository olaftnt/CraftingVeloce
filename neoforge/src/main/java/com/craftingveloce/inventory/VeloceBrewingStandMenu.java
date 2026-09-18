package com.craftingveloce.inventory;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The brewing stand menu: 5 machine slots + the player inventory.
 *
 * <p><b>Two bugs lived here, both reported as "the brewing stand does not work".</b>
 * <ol>
 *   <li>The three bottle slots and the ingredient slot were wrapped in
 *       {@code if (false) { ... }} under a comment saying they existed only to
 *       satisfy the build guard. The guard indeed looked for the
 *       {@code new Slot(container, ...)} text, so it passed while the GUI had no
 *       bottle slots at all - dead code satisfying a text check. They are real
 *       slots now.</li>
 *   <li>The menu was opened through the 3-argument constructor, which attached a
 *       throwaway {@link SimpleContainerData}. Nothing ever wrote into it, so the
 *       accumulator read as 0 and the battery gauge never moved. The constructor
 *       now attaches the BLOCK ENTITY's own {@link ContainerData}, which the
 *       server keeps up to date and which the client updates from the sync
 *       packets.</li>
 * </ol>
 */
public class VeloceBrewingStandMenu extends AbstractContainerMenu {

    private static final int PLAYER_X = 26;
    private static final int PLAYER_Y = 84;
    private static final int BATTERY_SLOT_X = 130;
    private static final int BATTERY_SLOT_Y = 32;

    /** Slots 0-2 are the bottles, 3 the ingredient, 4 the fuel/battery slot. */
    private static final int MACHINE_SLOTS = 5;

    /** The three bottle slots, in a row above the accumulator gauge. */
    private static final int BOTTLE_X = 26;
    private static final int BOTTLE_Y = 18;
    private static final int BOTTLE_STEP = 18;

    /** The ingredient slot, next to the bottles. */
    private static final int INGREDIENT_X = 100;
    private static final int INGREDIENT_Y = 18;

    private final BlockPos pos;
    private final ContainerData dataAccess;

    public VeloceBrewingStandMenu(int id, Inventory playerInv, BlockPos pos) {
        this(id, playerInv, pos, dataOf(playerInv, pos));
    }

    /**
     * Resolves the LIVE container data for a position.
     *
     * <p>Used by the network path (the client re-creating the menu from a packet)
     * and by the block when it opens the menu. On the client the block entity is
     * present as well, so this works on both sides - the client's data object is
     * then written by the sync packets, which is exactly how vanilla does it.
     */
    private static ContainerData dataOf(Inventory playerInv, BlockPos pos) {
        BlockEntity be = playerInv.player.level().getBlockEntity(pos);
        if (be instanceof com.craftingveloce.block.entity.VeloceBrewingStandBlockEntity stand) {
            return stand.getDataAccess();
        }
        // No block entity (e.g. a stale position): keep three zeroed slots so the
        // screen can still index them instead of crashing.
        return new SimpleContainerData(3);
    }

    public VeloceBrewingStandMenu(int id, Inventory playerInv, BlockPos pos, ContainerData dataAccess) {
        super(com.craftingveloce.init.VeloceRegistry.BREWING_STAND_MENU.get(), id);
        this.pos = pos;
        this.dataAccess = dataAccess;
        BlockEntity be = playerInv.player.level().getBlockEntity(pos);
        net.minecraft.world.Container container = be instanceof net.minecraft.world.Container c
                ? c : new net.minecraft.world.SimpleContainer(MACHINE_SLOTS);

        // REAL machine slots - three bottles and one ingredient.
        for (int bottle = 0; bottle < 3; bottle++) {
            this.addSlot(new Slot(container, bottle,
                    BOTTLE_X + bottle * BOTTLE_STEP, BOTTLE_Y));
        }
        this.addSlot(new Slot(container, 3, INGREDIENT_X, INGREDIENT_Y));

        // Slot 4 is the fuel slot in vanilla; here it also accepts an energy item
        // (battery, Energy Cube), which the block entity discharges into the
        // accumulator. Items without Forge Energy are still allowed so blaze powder
        // keeps working.
        this.addSlot(new Slot(container, 4, BATTERY_SLOT_X, BATTERY_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return super.mayPlace(stack);
            }
        });

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9,
                        PLAYER_X + col * 18, PLAYER_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, PLAYER_X + col * 18, PLAYER_Y + 58));
        }

        this.addDataSlots(dataAccess);
    }

    public int getBrewingTicks() { return this.dataAccess.get(0); }

    /** The accumulator, reassembled from the two 16-bit halves sent by the server. */
    public int getEnergy() {
        return (this.dataAccess.get(1) & 0xFFFF) | ((this.dataAccess.get(2) & 0xFFFF) << 16);
    }

    public int getMaxEnergy() {
        return com.craftingveloce.block.entity.VeloceBrewingStandBlockEntity.energyCapacity();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack inSlot = slot.getItem();
        ItemStack copy = inSlot.copy();
        // Shift-clicking a machine slot moves the stack out to the player inventory;
        // shift-clicking the player inventory moves it into the machine slots.
        if (index < MACHINE_SLOTS) {
            if (!this.moveItemStackTo(inSlot, MACHINE_SLOTS, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveItemStackTo(inSlot, 0, MACHINE_SLOTS, false)) {
            return ItemStack.EMPTY;
        }
        if (inSlot.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }
}
