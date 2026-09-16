package com.craftingveloce.client.gui;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.inventory.VeloceBrewingStandMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.util.Mth;
import java.util.ArrayList;
import java.util.List;

public class VeloceBrewingStandScreen extends AbstractContainerScreen<VeloceBrewingStandMenu> {

    private static final ResourceLocation GUI_TEXTURE = ResourceLocation.withDefaultNamespace("textures/gui/container/brewing_stand.png"); // electric_furnace.png
    private static final ResourceLocation BREW_PROGRESS_SPRITE = ResourceLocation.withDefaultNamespace("container/brewing_stand/brew_progress");
    private static final ResourceLocation BUBBLES_SPRITE = ResourceLocation.withDefaultNamespace("container/brewing_stand/bubbles");

    private static final int COLOR_BATTERY_EMPTY = 0xFF8B8B8B;
    private static final int COLOR_BATTERY_FILL = 0xFF39D353;
    
    private static final int BUBBLELENGTHS[] = new int[]{29, 24, 20, 16, 11, 6, 0};

    public VeloceBrewingStandScreen(VeloceBrewingStandMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
        this.inventoryLabelX = 8;
        this.titleLabelX = 8;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(GUI_TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
        
        // Progress
        int brewTime = this.menu.getBrewingTicks();
        if (brewTime > 0) {
            int progress = (int)(28.0F * (1.0F - (float)brewTime / 400.0F));
            if (progress > 0) {
                graphics.blitSprite(BREW_PROGRESS_SPRITE, 9, 28, 0, 0, this.leftPos + 97, this.topPos + 16, 9, progress);
            }
            int bubble = BUBBLELENGTHS[brewTime / 2 % 7];
            if (bubble > 0) {
                graphics.blitSprite(BUBBLES_SPRITE, 12, 29, 0, 29 - bubble, this.leftPos + 63, this.topPos + 14 + 29 - bubble, 12, bubble);
            }
        }
        
        // FE Energy Bar (using the fuel bar space at X=60 Y=44)
        int energy = this.menu.getEnergy();
        int maxEnergy = 25000000;
        int fuelX = this.leftPos + 60;
        int fuelY = this.topPos + 44;
        graphics.fill(fuelX, fuelY, fuelX + 18, fuelY + 4, COLOR_BATTERY_EMPTY);
        if (energy > 0) {
            int filled = (int)((energy / (float)maxEnergy) * 18);
            graphics.fill(fuelX, fuelY, fuelX + filled, fuelY + 4, COLOR_BATTERY_FILL);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        
        // Tooltip na energii
        if (isHovering(60, 44, 18, 4, mouseX, mouseY)) {
            int energy = this.menu.getEnergy();
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("gui.craftingveloce.electric.energy",
                    com.craftingveloce.util.VeloceFormat.feCompact(energy),
                    com.craftingveloce.util.VeloceFormat.feCompact(25000000)));
            graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
        }
        
        this.renderTooltip(graphics, mouseX, mouseY);
    }
}
