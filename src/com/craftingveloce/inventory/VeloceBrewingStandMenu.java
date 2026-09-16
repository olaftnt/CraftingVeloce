package com.craftingveloce.inventory;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Menu brewing standa: 3 butelki, skladnik, blaze powder + ekwipunek gracza (jak piec). */
public class VeloceBrewingStandMenu extends AbstractContainerMenu {

    private static final int PLAYER_X = 26;
    private static final int PLAYER_Y = 84;
    private static final int BOTTLE_X = 56;
    private static final int BOTTLE_Y = 53;
    private static final int INGREDIENT_X = 56;
    private static final int INGREDIENT_Y = 17;
    private static final int FUEL_X = 116;
    private static final int FUEL_Y = 35;

    private final BlockPos pos;

    private final net.minecraft.world.inventory.ContainerData dataAccess;

    public VeloceBrewingStandMenu(int id, Inventory playerInv, BlockPos pos) {
        this(id, playerInv, pos, new net.minecraft.world.inventory.SimpleContainerData(2));
    }

    public VeloceBrewingStandMenu(int id, Inventory playerInv, BlockPos pos, net.minecraft.world.inventory.ContainerData dataAccess) {
        super(com.craftingveloce.init.VeloceRegistry.BREWING_STAND_MENU.get(), id);
        this.pos = pos;
        this.dataAccess = dataAccess;
        BlockEntity be = playerInv.player.level().getBlockEntity(pos);
        net.minecraft.world.Container container = be instanceof net.minecraft.world.Container c
                ? c : new net.minecraft.world.SimpleContainer(5);

        final net.minecraft.world.item.alchemy.PotionBrewing potionBrewing = playerInv.player.level().potionBrewing();

        for (int bottle = 0; bottle < 3; bottle++) {
            this.addSlot(new Slot(container, bottle, BOTTLE_X + bottle * 18, BOTTLE_Y) {
                public boolean mayPlace(ItemStack stack) { 
                    return stack.is(net.minecraft.world.item.Items.POTION) ||
                           stack.is(net.minecraft.world.item.Items.SPLASH_POTION) ||
                           stack.is(net.minecraft.world.item.Items.LINGERING_POTION) ||
                           stack.is(net.minecraft.world.item.Items.GLASS_BOTTLE);
                }
                public int getMaxStackSize() { return 1; }
            });
        }
        this.addSlot(new Slot(container, 3, INGREDIENT_X, INGREDIENT_Y) {
            public boolean mayPlace(ItemStack stack) { return potionBrewing.isIngredient(stack); }
        });
        this.addSlot(new Slot(container, 4, FUEL_X, FUEL_Y) {
            public boolean mayPlace(ItemStack stack) { return stack.is(net.minecraft.world.item.Items.BLAZE_POWDER); }
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

    public int getBrewingTicks() {
        return this.dataAccess.get(0);
    }

    public int getFuel() {
        return this.dataAccess.get(1);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack inSlot = slot.getItem();
        ItemStack copy = inSlot.copy();
        boolean machine = index < 5;
        if (machine) {
            if (!this.moveItemStackTo(inSlot, 5, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveItemStackTo(inSlot, 0, 5, false)) {
            return ItemStack.EMPTY;
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
