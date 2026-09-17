package com.craftingveloce.inventory;

import com.craftingveloce.block.entity.VeloceExtractorBlockEntity;
import com.craftingveloce.init.VeloceRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class VeloceExtractorMenu extends AbstractContainerMenu {

    private final Container outputContainer;
    private final BlockPos pos;
    private final VeloceExtractorBlockEntity extractorBE;

    public static final int FILTER_SLOT_START = 0;
    public static final int OUTPUT_SLOT_START = 9;
    public static final int PLAYER_INV_START = 18;
    public static final int PLAYER_HOTBAR_START = 45;

    public VeloceExtractorMenu(int containerId, Inventory playerInv, BlockPos pos) {
        this(containerId, playerInv, pos, (playerInv.player.level().getBlockEntity(pos) instanceof VeloceExtractorBlockEntity be) ? be : null);
    }

    public VeloceExtractorMenu(int containerId, Inventory playerInv, BlockPos pos, VeloceExtractorBlockEntity extractorBE) {
        super(VeloceRegistry.VELOCE_EXTRACTOR_MENU.get(), containerId);
        this.pos = pos;
        this.extractorBE = extractorBE;
        this.outputContainer = (extractorBE != null) ? extractorBE.getOutputInventory() : new SimpleContainer(9);

        // 1. Left 3x3: Filter Slots (Fake/Ghost slots 0..8)
        // Position: x=26, y=18
        //
        // ONE shared placeholder container, not nine separate ones.
        // Previously `new SimpleContainer(9)` was created INSIDE THE LOOP, so
        // 9 containers of 9 slots each were produced (81 allocations) for nine
        // ghost slots. The slots themselves are non-interactive (mayPlace/mayPickup
        // return false), and the filter icons are drawn by the screen from
        // clientFilters - so the container serves purely as a "place" for the Slot.
        Container filterPlaceholders = new SimpleContainer(9);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int index = col + row * 3;
                this.addSlot(new Slot(filterPlaceholders, index, 26 + col * 18, 18 + row * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return false;
                    }
                    @Override
                    public boolean mayPickup(Player player) {
                        return false;
                    }
                });
            }
        }

        // 2. Right 3x3: Real Output Slots (9..17)
        // Position: x=134, y=18
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int index = col + row * 3;
                this.addSlot(new Slot(outputContainer, index, 134 + col * 18, 18 + row * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return false; // Only automated or pulled items can enter
                    }
                    @Override
                    public boolean mayPickup(Player player) {
                        return true;
                    }
                });
            }
        }

        // 3. Player Inventory (18..44)
        // Position: x=26, y=84
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9, 26 + col * 18, 84 + row * 18));
            }
        }

        // 4. Player Hotbar (45..53)
        // Position: x=26, y=142
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, 26 + col * 18, 142));
        }
    }

    public BlockPos getPos() {
        return pos;
    }

    public VeloceExtractorBlockEntity getExtractorBE() {
        return extractorBE;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stackInSlot = slot.getItem();
            itemstack = stackInSlot.copy();

            if (index >= OUTPUT_SLOT_START && index < OUTPUT_SLOT_START + 9) {
                // Moving from extractor real outputs to player inventory
                if (!this.moveItemStackTo(stackInSlot, PLAYER_INV_START, slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else if (index >= PLAYER_INV_START) {
                // Clicking in player inventory: do nothing for quickMove (we don't deposit into extractor)
                return ItemStack.EMPTY;
            } else {
                // Filter slots (0..8). We do not move them anywhere - and we MUST
                // return EMPTY, because the quickMoveStack contract says "return what
                // you actually moved". Returning a copy without moving anything
                // would make the client believe the item had shifted.
                return ItemStack.EMPTY;
            }

            if (stackInSlot.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return itemstack;
    }
}
