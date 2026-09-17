package com.craftingveloce.client.gui;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceElectricFurnaceBlockEntity;
import com.craftingveloce.inventory.VeloceElectricFurnaceMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI of the Velocity Electric Furnace.
 *
 * <p><b>Layout.</b> A horizontal battery (the charge level grows from LEFT to
 * RIGHT), and to its right a slot for an item with energy (a battery, an Energy
 * Cube, a tablet from another mod). Above the battery is the number of smelts
 * the accumulator can still afford - because that is the number the player is
 * really interested in, and not the raw FE.
 *
 * <p><b>Why the label is ABOVE the battery, and not below it.</b> It used to
 * stand under the bar, that is at height y=88 - and the player's inventory
 * starts at y=84. The number overlapped the inventory slots. Now everything
 * this screen draws ends above y=48.
 */
public class VeloceElectricFurnaceScreen
        extends AbstractContainerScreen<VeloceElectricFurnaceMenu> {

    private static final ResourceLocation GUI_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            CraftingVeloceMod.MODID, "textures/gui/electric_furnace.png");

    /**
     * The battery: body + terminal on the right-hand side.
     *
     * <p>The positions are CENTRED in the panel: the whole group (body 56 +
     * terminal 2 + gap 6 + slot 16 = 80 px) has 66 px of margin on each side.
     * They must match the recesses in the texture (gen_furnace_gui.py) -
     * build.py enforces this.
     */
    private static final int BATTERY_X = 66;
    private static final int BATTERY_Y = 32;
    private static final int BATTERY_W = 56;
    private static final int BATTERY_H = 14;

    /** The battery's terminal ("nub") - drawn to the right of the body. */
    private static final int NUB_W = 2;
    private static final int NUB_H = 6;

    /** Accumulator background - dark green, so that empty and full play in one colour. */
    private static final int COLOR_BATTERY_EMPTY = 0xFF8B8B8B;

    /** Charge - the green of "energy". */
    private static final int COLOR_BATTERY_FILL = 0xFF39D353;

    /** A lighter edge at the top of the fill (a readable level). */
    private static final int COLOR_BATTERY_HIGHLIGHT = 0xFF8CF0A5;

    private int energy;
    private int maxEnergy;

    public VeloceElectricFurnaceScreen(VeloceElectricFurnaceMenu menu,
                                       Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        // Dimensions and labels EXACTLY as in the extractor.
        this.imageWidth = 212;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
        this.inventoryLabelX = 26;
        this.titleLabelX = 26;
        this.energy = menu.getEnergy();
        this.maxEnergy = menu.getMaxEnergy();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(GUI_TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);

        int x = this.leftPos + BATTERY_X;
        int y = this.topPos + BATTERY_Y;

        // Accumulator background: dark green over the WHOLE body. Without this,
        // "empty" looked like an ordinary groove instead of a discharged battery.
        graphics.fill(x, y, x + BATTERY_W, y + BATTERY_H, COLOR_BATTERY_EMPTY);

        // Charge FROM LEFT TO RIGHT - that is how a battery looks in every other
        // mod (and that is how it reads fastest).
        int filled = maxEnergy > 0
                ? (int) Math.min(BATTERY_W, ((long) BATTERY_W * energy) / maxEnergy)
                : 0;
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + BATTERY_H, COLOR_BATTERY_FILL);
            graphics.fill(x, y, x + filled, y + 1, COLOR_BATTERY_HIGHLIGHT);
        }
        // The terminal lights up only when there is something in the accumulator -
        // thanks to that "0 FE" and "full battery" differ at first glance.
        if (energy > 0) {
            int nubX = x + BATTERY_W;
            int nubY = y + (BATTERY_H - NUB_H) / 2;
            graphics.fill(nubX, nubY, nubX + NUB_W, nubY + NUB_H, COLOR_BATTERY_FILL);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Tooltip ONLY on the battery. The hint on the slot (what to put into
        // it) was removed at the user's request - the slot is supposed to speak
        // for itself instead of adding text on hover.
        renderBatteryTooltip(graphics, mouseX, mouseY);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    /** On the battery: how much FE there is and how many smelts that will cover. */
    private void renderBatteryTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isHovering(BATTERY_X, BATTERY_Y, BATTERY_W + NUB_W, BATTERY_H, mouseX, mouseY)) {
            return;
        }
        long cycles = energy / VeloceElectricFurnaceBlockEntity.FE_PER_SMELT;
        List<Component> lines = new ArrayList<>();
        // Energy and the cost of one smelt in kFE / MFE - the player does not count zeros on the fly.
        lines.add(Component.translatable("gui.craftingveloce.electric.energy",
                com.craftingveloce.util.VeloceFormat.feCompact(energy),
                com.craftingveloce.util.VeloceFormat.feCompact(maxEnergy)));
        lines.add(Component.translatable("gui.craftingveloce.electric.smelts", cycles)
                .withStyle(net.minecraft.ChatFormatting.GOLD));
        lines.add(Component.translatable("gui.craftingveloce.electric.perSmelt",
                        com.craftingveloce.util.VeloceFormat.feCompact(
                                VeloceElectricFurnaceBlockEntity.FE_PER_SMELT))
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }


    /** Refreshes the bar from the client block entity (without a separate packet). */
    @Override
    public void containerTick() {
        super.containerTick();
        this.energy = this.menu.getEnergy();
        this.maxEnergy = this.menu.getMaxEnergy();
    }
}
