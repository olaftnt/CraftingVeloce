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
 * Menu maszyny KINETYCZNEJ (Create) - bez slotu baterii.
 *
 * <p>Gracz: "w GUI modulow z Create w tle widoczny jest pasek bateryjki oraz
 * slot na akumulator ... te elementy zasilania elektrycznego nie powinny sie
 * tam znajdowac. Przygotuj osobny ekran dedykowany wylacznie blokom
 * kinetycznym". Dlatego maszyny z Create maja WLASNY typ menu i wlasny ekran:
 * zero slotu, zero wskaznika energii - tylko status pracy.
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

    /** Pola do okna (status pracy). */
    public CompoundTag display() {
        return module == null ? new CompoundTag() : module.moduleDisplay();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;   // brak wlasnych slotow
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }
}
