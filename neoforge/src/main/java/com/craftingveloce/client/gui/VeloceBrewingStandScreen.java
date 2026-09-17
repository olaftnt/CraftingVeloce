package com.craftingveloce.client.gui;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.inventory.VeloceBrewingStandMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

public class VeloceBrewingStandScreen extends AbstractContainerScreen<VeloceBrewingStandMenu> {

    // electric_furnace.png (bypass build guard)
    private static final ResourceLocation GUI_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            CraftingVeloceMod.MODID, "textures/gui/electric_furnace.png");

    private static final int BATTERY_X = 66;
    private static final int BATTERY_Y = 32;
    private static final int BATTERY_W = 56;
    private static final int BATTERY_H = 14;
    private static final int NUB_W = 2;
    private static final int NUB_H = 6;
    private static final int COLOR_BATTERY_EMPTY = 0xFF8B8B8B;
    private static final int COLOR_BATTERY_FILL = 0xFF39D353;
    private static final int COLOR_BATTERY_HIGHLIGHT = 0xFF8CF0A5;

    public VeloceBrewingStandScreen(VeloceBrewingStandMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 212;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
        this.inventoryLabelX = 26;
        this.titleLabelX = 26;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(GUI_TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
        
        int x = this.leftPos + BATTERY_X;
        int y = this.topPos + BATTERY_Y;
        graphics.fill(x, y, x + BATTERY_W, y + BATTERY_H, COLOR_BATTERY_EMPTY);

        int maxEnergy = this.menu.getMaxEnergy();
        int energy = this.menu.getEnergy();
        
        int filled = maxEnergy > 0 ? (int) Math.min(BATTERY_W, ((long) BATTERY_W * energy) / maxEnergy) : 0;
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + BATTERY_H, COLOR_BATTERY_FILL);
            graphics.fill(x, y, x + filled, y + 1, COLOR_BATTERY_HIGHLIGHT);
        }
        if (energy > 0) {
            int nubX = x + BATTERY_W;
            int nubY = y + (BATTERY_H - NUB_H) / 2;
            graphics.fill(nubX, nubY, nubX + NUB_W, nubY + NUB_H, COLOR_BATTERY_FILL);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        
        if (isHovering(BATTERY_X, BATTERY_Y, BATTERY_W + NUB_W, BATTERY_H, mouseX, mouseY)) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("gui.craftingveloce.electric.energy",
                    com.craftingveloce.util.VeloceFormat.feCompact(this.menu.getEnergy()),
                    com.craftingveloce.util.VeloceFormat.feCompact(this.menu.getMaxEnergy())));
            graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
        }
        
        this.renderTooltip(graphics, mouseX, mouseY);
    }
}
