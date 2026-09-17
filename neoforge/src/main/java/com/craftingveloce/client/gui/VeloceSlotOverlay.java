package com.craftingveloce.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Slot overlays: how many units are in stock and how many can be crafted.
 *
 * <p><b>Why a separate class.</b> The terminal drew these two numbers
 * correctly, while the controller had its own simplified version - without font
 * scaling and without the same anchoring. The effect was what the player
 * reported: the numbers in the controller stuck out past the icon or were not
 * visible at all.
 *
 * <p>Instead of fixing the second copy, both screens use THIS code. The numbers
 * therefore look identical in the terminal and in the controller, because it is
 * literally the same drawing function - and not two that are supposed to agree.
 *
 * <p>Layout (established earlier and deliberately unchanged):
 * <ul>
 *   <li><b>stock</b> - white, bottom right corner of the slot,</li>
 *   <li><b>craftable</b> - orange, top left corner, with a "+" when it fits.</li>
 * </ul>
 */
public final class VeloceSlotOverlay {

    private VeloceSlotOverlay() {
    }

    /** Font scale in the overlay. The vanilla size would not fit in the slot. */
    private static final float SCALE = 0.6f;

    /** How many characters fit in the overlay on a slot. */
    private static final int MAX_OVERLAY_CHARS = 5;

    /** The stock colour - white, like the stack count in vanilla. */
    private static final int COLOR_STOCK = 0xFFFFFF;

    /** The "craftable" colour - orange, to tell it apart from stock. */
    private static final int COLOR_CRAFTABLE = 0xFFA500;

    /**
     * Abbreviated number: 1K, 1.2K, 1M, 1.2M, 1B.
     *
     * <p><b>The rule lives in ONE place</b>
     * ({@link com.craftingveloce.util.VeloceFormat#compact(long)}) - here only
     * the name used by the GUI remains. Previously this method had its own copy
     * of the formatting next to {@code VeloceFormat}, and two copies of the same
     * rule always end up drifting apart eventually.
     */
    public static String formatCount(long number) {
        return com.craftingveloce.util.VeloceFormat.compact(number);
    }

    /** Stock: white, bottom right corner of the slot. Zero is not drawn. */
    public static void drawStock(GuiGraphics graphics, Font font, long count, int x, int y) {
        if (count <= 0) {
            return;
        }
        String text = formatCount(count);
        pushOverlay(graphics);
        graphics.pose().scale(SCALE, SCALE, SCALE);
        graphics.pose().translate(0, 0, 450);
        float inverse = 1.0f / SCALE;
        int textX = (int) (((float) x + 16.0f - font.width(text) * SCALE) * inverse);
        int textY = (int) (((float) y + 16.0f - 7.0f * SCALE) * inverse);
        graphics.drawString(font, text, textX, textY, COLOR_STOCK, true);
        popOverlay(graphics);
    }

    /**
     * The number of units that can be CRAFTED (e.g. "+12"), top left corner of
     * the slot.
     *
     * <p>The plus only when it fits within 5 characters TOGETHER with the
     * number. The slot has room for 5 characters: "+1234" fits, "+12.3K" does
     * not - so we show just "12.3K". Thanks to that the number never goes past
     * the icon, and the plus still distinguishes "can be crafted" from plain
     * stock, as long as there is room.
     */
    public static void drawCraftable(GuiGraphics graphics, Font font, long craftable, int x, int y) {
        if (craftable <= 0) {
            return;
        }
        String number = formatCount(craftable);
        String text = (number.length() + 1 <= MAX_OVERLAY_CHARS) ? "+" + number : number;

        pushOverlay(graphics);
        graphics.pose().scale(SCALE, SCALE, SCALE);
        graphics.pose().translate(0, 0, 450);
        float inverse = 1.0f / SCALE;
        int textX = (int) (((float) x + 1.0f) * inverse);
        int textY = (int) (((float) y + 1.0f) * inverse);
        graphics.drawString(font, text, textX, textY, COLOR_CRAFTABLE, true);
        popOverlay(graphics);
    }

    private static void pushOverlay(GuiGraphics graphics) {
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();
        graphics.pose().pushPose();
    }

    private static void popOverlay(GuiGraphics graphics) {
        graphics.pose().popPose();
        RenderSystem.enableDepthTest();
    }
}
