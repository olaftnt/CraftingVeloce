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
 * Menu of a KINETIC (Create) machine - without a battery slot.
 *
 * <p>The player: "in the GUI of the Create modules a battery bar and a slot for
 * an accumulator are visible in the background ... these electric power elements
 * should not be there. Prepare a separate screen dedicated solely to kinetic
 * blocks". That is why the Create machines have their OWN menu type and their
 * own screen: zero slots, zero energy indicator - only the working status.
 */
public class VeloceKineticMenu extends AbstractContainerMenu {

    private static final int PLAYER_X = 26;
    private static final int PLAYER_Y = 84;

    private final BlockPos pos;
    private final VeloceModuleDisplay module;

    public VeloceKineticMenu(int id, Inventory playerInv, BlockPos pos) {
        super(com.craftingveloce.init.VeloceRegistry.VELOCE_KINETIC_MENU.get(), id);
        this.pos = pos;
        BlockEntity be = playerInv.player.level().getBlockEntity(pos);
        this.module = be instanceof VeloceModuleDisplay display ? display : null;

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

    /** Fields for the window (working status). */
    public CompoundTag display() {
        return module == null ? new CompoundTag() : module.moduleDisplay();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;   // no slots of our own
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }
}
