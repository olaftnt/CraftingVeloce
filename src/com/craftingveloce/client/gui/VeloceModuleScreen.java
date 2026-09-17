package com.craftingveloce.client.gui;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.inventory.VeloceModuleMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * The Veloce machine window - looks EXACTLY like the furnace window.
 *
 * <p>The same texture and the same layout as {@code VeloceElectricFurnaceScreen}:
 * panel, title with the block name, the "Inventory" label and the player's
 * inventory.
 *
 * <ul>
 *   <li><b>energy machine</b>: battery bar (as in the furnace) and the number of
 *       operations the accumulator can still afford,</li>
 *   <li><b>kinetic machine (Create)</b>: WITHOUT a battery - in its place three
 *       lines of text: what speed we are getting, whether that is enough and how
 *       much SU the block uses. Nothing more.</li>
 * </ul>
 */
public class VeloceModuleScreen extends AbstractContainerScreen<VeloceModuleMenu> {

    private static final ResourceLocation GUI_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            CraftingVeloceMod.MODID, "textures/gui/electric_furnace.png");

    /** Battery - the same coordinates as in the furnace. */
    private static final int BATTERY_X = 66;
    private static final int BATTERY_Y = 32;
    private static final int BATTERY_W = 56;
    private static final int BATTERY_H = 14;
    private static final int NUB_W = 2;
    private static final int NUB_H = 6;
    private static final int COLOR_BATTERY_EMPTY = 0xFF8B8B8B;
    private static final int COLOR_BATTERY_FILL = 0xFF39D353;


    private CompoundTag display = new CompoundTag();

    public VeloceModuleScreen(VeloceModuleMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        // Dimensions and labels EXACTLY as in the furnace and the extractor.
        this.imageWidth = 212;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
        this.inventoryLabelX = 26;
        this.titleLabelX = 26;
        this.display = menu.display();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(GUI_TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
        if (display.contains("energy")) {
            drawBattery(graphics);
        }
    }

    /** Energy machine: battery and operation count - as in the furnace. */
    private void drawBattery(GuiGraphics graphics) {
        int x = this.leftPos + BATTERY_X;
        int y = this.topPos + BATTERY_Y;
        long energy = display.getLong("energy");
        long capacity = Math.max(1L, display.getLong("energyCapacity"));
        graphics.fill(x, y, x + BATTERY_W, y + BATTERY_H, COLOR_BATTERY_EMPTY);
        int filled = (int) Math.min(BATTERY_W, BATTERY_W * energy / capacity);
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + BATTERY_H, COLOR_BATTERY_FILL);
        }
        if (energy > 0) {
            int nubY = y + (BATTERY_H - NUB_H) / 2;
            graphics.fill(x + BATTERY_W, nubY, x + BATTERY_W + NUB_W, nubY + NUB_H,
                    COLOR_BATTERY_FILL);
        }
    }

    private void renderBatteryTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!display.contains("energy")) {
            return;
        }
        if (!isHovering(BATTERY_X, BATTERY_Y, BATTERY_W + NUB_W, BATTERY_H, mouseX, mouseY)) {
            return;
        }
        long energy = display.getLong("energy");
        long capacity = Math.max(1L, display.getLong("energyCapacity"));
        long perCycle = Math.max(1L, display.getLong("fePerOperation"));
        long cycles = energy / perCycle;
        java.util.List<Component> lines = new java.util.ArrayList<>();
        lines.add(Component.translatable("gui.craftingveloce.electric.energy",
                com.craftingveloce.util.VeloceFormat.feCompact(energy),
                com.craftingveloce.util.VeloceFormat.feCompact(capacity)));
        lines.add(Component.translatable("gui.craftingveloce.electric.smelts", cycles)
                .withStyle(net.minecraft.ChatFormatting.GOLD));
        lines.add(Component.translatable("gui.craftingveloce.electric.perSmelt",
                        com.craftingveloce.util.VeloceFormat.feCompact(perCycle))
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }
    
    private void drawCreateData(GuiGraphics graphics) {
        if (!display.contains("speed")) {
            return;
        }
        float speed = display.getFloat("speed");
        float required = display.getFloat("requiredSpeed");
        float su = display.getFloat("suDraw");
        boolean enough = display.getBoolean("enoughSpeed");
        
        float capacity = display.getFloat("suCapacity");
        float stress = display.getFloat("suStress");
        
        int x = this.leftPos + BATTERY_X - 10;
        int y = this.topPos + 20;
        
        String speedText = enough ? ("Speed: " + Math.round(speed) + " RPM") : ("Speed: " + Math.round(speed) + " / " + Math.round(required) + " RPM");
        net.minecraft.ChatFormatting speedColor = enough ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED;
        
        graphics.drawString(this.font, Component.literal(speedText).withStyle(speedColor), x, y, 0xFFFFFF, false);
        
        net.minecraft.ChatFormatting stressColor = (capacity - stress) < 0 ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.AQUA;
        String stressText = "Stress: " + Math.round(stress) + " SU / " + Math.round(capacity) + " SU";
        
        graphics.drawString(this.font, Component.literal(stressText).withStyle(stressColor), x, y + 12, 0xFFFFFF, false);
    }

    @Override
    public void containerTick() {
        super.containerTick();
        this.display = this.menu.display();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        drawCreateData(graphics);
        renderBatteryTooltip(graphics, mouseX, mouseY);
        this.renderTooltip(graphics, mouseX, mouseY);
    }
}
