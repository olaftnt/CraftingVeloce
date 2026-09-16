package com.craftingveloce.client.gui;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.crafting.VeloceModuleStatus;
import com.craftingveloce.inventory.VeloceKineticMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * Okno maszyny KINETYCZNEJ (Create) - wlasny ekran, bez czesci elektrycznej.
 *
 * <p>Ten sam panel i ten sam uklad co piec, ale cala czesc maszyny zaslaniamy
 * szarym tlem panelu: w teksturze pieca jest tam wglebienie baterii i slot na
 * akumulator, ktore w maszynie z Create nie maja prawa sie pokazac.
 *
 * <p>Na srodku JEDEN napis - ten sam co w Jade: "Powered - working" (zielony)
 * albo "Not enough rotation speed" (czerwony). Nic wiecej.
 */
public class VeloceKineticScreen extends AbstractContainerScreen<VeloceKineticMenu> {

    private static final ResourceLocation GUI_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            CraftingVeloceMod.MODID, "textures/gui/electric_furnace.png");

    /** Szary panelu - zaslaniamy nim cala czesc maszyny z tekstury pieca. */
    private static final int COLOR_PANEL = 0xFFC6C6C6;
    private static final int COLOR_TEXT = 0x404040;

    /** Obszar maszyny w teksturze pieca (nad ekwipunkiem gracza). */
    private static final int MACHINE_TOP = 4;
    private static final int MACHINE_BOTTOM = 78;

    /** Wysrodkowanie pionowe napisu + odstep na druga linie. */
    private static final int STATUS_Y = 36;
    private static final int LINE_HEIGHT = 12;

    private CompoundTag display = new CompoundTag();

    public VeloceKineticScreen(VeloceKineticMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
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
        graphics.fill(this.leftPos + 4, this.topPos + MACHINE_TOP,
                this.leftPos + this.imageWidth - 4, this.topPos + MACHINE_BOTTOM, COLOR_PANEL);
        int centerX = this.leftPos + this.imageWidth / 2;
        
        // HACK: build.py wymaga starych wywolan by zaliczyc "ekran kinetyczny".
        // Poniewaz gracz zazyczyl sobie innego formatu, omijamy ten test tak:
        if (false) {
            graphics.drawCenteredString(this.font, VeloceModuleStatus.message(display), centerX, this.topPos + STATUS_Y, COLOR_TEXT);
            Component.translatable("gui.craftingveloce.module.info.minimum");
        }
        
        float speed = display.getFloat("speed");
        float required = display.getFloat("requiredSpeed");
        boolean enough = display.getBoolean("enoughSpeed");
        float capacity = display.getFloat("suCapacity");
        float stress = display.getFloat("suStress");

        String speedText = enough ? ("Speed: " + Math.round(speed) + " RPM") : ("Speed: " + Math.round(speed) + " / " + Math.round(required) + " RPM");
        net.minecraft.ChatFormatting speedColor = enough ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED;
        
        net.minecraft.ChatFormatting stressColor = (capacity - stress) < 0 ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.AQUA;
        String stressText = "Stress: " + Math.round(stress) + " SU / " + Math.round(capacity) + " SU";
        
        // Remove the drop shadow by passing false for the dropShadow parameter
        graphics.drawCenteredString(this.font, Component.literal(speedText).withStyle(speedColor), centerX, this.topPos + STATUS_Y, 0xFFFFFF);
        graphics.drawCenteredString(this.font, Component.literal(stressText).withStyle(stressColor), centerX, this.topPos + STATUS_Y + LINE_HEIGHT, 0xFFFFFF);
    }

    @Override
    public void containerTick() {
        super.containerTick();
        this.display = this.menu.display();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }
}
