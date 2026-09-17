package com.craftingveloce.inventory;

import com.craftingveloce.block.entity.VeloceThresholdSensorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Veloce Threshold Sensor menu.
 *
 * <p><b>Layout.</b> ONE centred row: item slot, number field, "+",
 * "-" and the mode button. Below it the player inventory (grid as in the
 * extractor: inventory from y=84, hotbar at y=142).
 *
 * <p>All GUI coordinates live HERE - the screen reads them from this class, and
 * the texture generator paints the slot frame under the same numbers. That way
 * there are not three copies that can drift apart (build.py enforces the
 * menu &lt;-&gt; generator pairing).
 *
 * <p>The menu holds neither the threshold nor the mode - they live in the block
 * entity. That way exactly the same value is seen by the server (which drives
 * Redstone from it) and by the screen, with no second source of truth.
 */
public class VeloceThresholdSensorMenu extends AbstractContainerMenu {

    /** Panel - the same width as the texture and the screen (imageWidth). */
    public static final int PANEL_WIDTH = 212;
    public static final int PANEL_HEIGHT = 166;

    // ---------------- main row (centred) ----------------
    //
    //  slot 16 | 4 | field 38 | 4 | "+" 20 | 4 | "-" 20 | 4 | mode 20
    //
    // HORIZONTALLY: row width = 16+4+38+4+20+4+20+4+20 = 130, and
    // (212 - 130) / 2 = 41 - exactly that much margin on EACH side.
    //
    // VERTICALLY: the row must sit in the MIDDLE of the working area, i.e.
    // between the bottom edge of the title (y=15) and the top edge of the
    // player inventory (y=84). 15 + (84 - 15 - 20) / 2 = 39, so 24 px are left
    // above the row and 25 px below it - half a pixel per side, because the
    // height difference is odd. It used to be 26, i.e. the row was 13 px too
    // high (the centre of the panel itself, without the inventory) - a player
    // reported it.
    /** Bottom edge of the title label (titleLabelY = 6 + font height 9). */
    public static final int TITLE_BOTTOM = 15;
    /** Top edge of the row; the 16 px slot is centred in it (2 px of slack). */
    public static final int ROW_Y = 39;
    public static final int ROW_H = 20;
    public static final int GAP = 4;
    public static final int SLOT_SIZE = 16;
    public static final int FIELD_X = 61;
    public static final int FIELD_W = 38;
    public static final int FIELD_H = 20;
    public static final int BTN_W = 20;
    public static final int STEP_PLUS_X = 103;
    public static final int STEP_MINUS_X = 127;
    /** Mode button (torch) - the third button in the row. */
    public static final int MODE_X = 151;

    /** Item slot: the first element of the row. The generator paints a frame here. */
    public static final int FILTER_SLOT_X = 41;
    public static final int FILTER_SLOT_Y = 41;

    private static final int PLAYER_X = 26;
    private static final int PLAYER_Y = 84;

    private final VeloceThresholdSensorBlockEntity sensor;
    private final BlockPos pos;

    public VeloceThresholdSensorMenu(int id, Inventory playerInv, BlockPos pos) {
        this(id, playerInv, pos, playerInv.player.level().getBlockEntity(pos));
    }

    public VeloceThresholdSensorMenu(int id, Inventory playerInv, BlockPos pos, BlockEntity be) {
        this(id, playerInv, pos, be instanceof VeloceThresholdSensorBlockEntity s ? s : null);
    }

    public VeloceThresholdSensorMenu(int id, Inventory playerInv, BlockPos pos,
                                     VeloceThresholdSensorBlockEntity sensor) {
        super(com.craftingveloce.init.VeloceRegistry.THRESHOLD_SENSOR_MENU.get(), id);
        this.sensor = sensor;
        this.pos = pos;

        // Filter slot: a ghost. We block insertion and extraction, but the slot
        // stays ACTIVE - otherwise getSlotUnderMouse() would skip it
        // and clicking the filter would do nothing.
        Container placeholder = new SimpleContainer(1);
        this.addSlot(new Slot(placeholder, 0, FILTER_SLOT_X, FILTER_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public boolean mayPickup(Player player) {
                return false;
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
    }

    public VeloceThresholdSensorBlockEntity getSensor() {
        return sensor;
    }

    public BlockPos getPos() {
        return sensor != null ? sensor.getBlockPos() : pos;
    }

    /** Current filter (from the block entity on the client side). */
    public ItemStack getFilter() {
        return sensor == null ? ItemStack.EMPTY : sensor.getFilter();
    }

    public long getThreshold() {
        return sensor == null ? VeloceThresholdSensorBlockEntity.DEFAULT_THRESHOLD : sensor.getThreshold();
    }

    public VeloceThresholdSensorBlockEntity.Mode getMode() {
        return sensor == null ? VeloceThresholdSensorBlockEntity.Mode.LOW : sensor.getMode();
    }

    public long getLastCount() {
        return sensor == null ? -1L : sensor.getLastCount();
    }

    /**
     * Whether the sensor is currently emitting power.
     *
     * <p>We take this from the BLOCK STATE, not from a recomputed condition. The
     * block state is broadcast by the server and it is the truth; the
     * client-side counter is only a value for display. Recomputing the
     * condition from the counter showed the opposite state while the counter was
     * still unknown.
     */
    public boolean isPowered() {
        return sensor != null && sensor.isPowered();
    }

    /**
     * Shift-click moves nothing.
     *
     * <p>The sensor has no item slots of its own - the only slot is the filter
     * ghost, which cannot be filled anyway. The previous version moved the stack
     * "within the player inventory", which in a machine GUI is surprising:
     * the player expects a transfer to the block, not a reshuffle inside the
     * backpack. Behaviour consistent with the electric furnace.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return sensor == null
                || player.distanceToSqr(sensor.getBlockPos().getX() + 0.5,
                sensor.getBlockPos().getY() + 0.5,
                sensor.getBlockPos().getZ() + 0.5) <= 64.0;
    }
}
