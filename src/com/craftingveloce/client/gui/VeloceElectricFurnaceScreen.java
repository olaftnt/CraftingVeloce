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
 * GUI Velocity Electric Furnace.
 *
 * <p><b>Co tu jest.</b> Pasek akumulatora (FE) i nic wiec - ten piec nie ma
 * slotow na przedmioty. To bufor pradu dla auto-craftera: crafter przychodzi
 * po cieplo, a piec oddaje je w porcjach {@code FE_PER_SMELT}.
 *
 * <p>Pasek pokazuje energie, a POD nim liczymy, ile to jeszcze przepalen -
 * bo to jest liczba, ktora gracza naprawde interesuje ("na ile smeltow mnie
 * jeszcze stac"), a nie surowe FE.
 */
public class VeloceElectricFurnaceScreen
        extends AbstractContainerScreen<VeloceElectricFurnaceMenu> {

    private static final ResourceLocation GUI_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            CraftingVeloceMod.MODID, "textures/gui/electric_furnace.png");

    /** Pole paska energii w teksturze GUI. */
    // Pozycja musi sie zgadzac z wglebieniem w teksturze (gen_furnace_gui.py).
    private static final int BAR_X = 150;
    private static final int BAR_Y = 30;
    private static final int BAR_W = 18;
    private static final int BAR_H = 54;

    private int energy;
    private int maxEnergy;

    public VeloceElectricFurnaceScreen(VeloceElectricFurnaceMenu menu,
                                       Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        // Wymiary i etykiety DOKLADNIE jak w ekstraktorze.
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

        // Wypelnienie OD DOLU - tak jak poziom paliwa, bo to jest zapas.
        int filled = maxEnergy > 0
                ? (int) Math.min(BAR_H, ((long) BAR_H * energy) / maxEnergy)
                : 0;
        if (filled > 0) {
            int x = this.leftPos + BAR_X;
            int y = this.topPos + BAR_Y + (BAR_H - filled);
            graphics.fill(x, y, x + BAR_W, y + filled, 0xFF3C46FF);
            // Jasniejszy pasek na gorze slupka - czytelny poziom.
            graphics.fill(x, y, x + BAR_W, y + 1, 0xFF8C96FF);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Liczba przepalen pod paskiem - to ona odpowiada na pytanie gracza.
        long smelts = energy / VeloceElectricFurnaceBlockEntity.FE_PER_SMELT;
        String text = smelts + "x";
        graphics.drawString(this.font, text,
                this.leftPos + BAR_X + BAR_W / 2 - this.font.width(text) / 2,
                this.topPos + BAR_Y + BAR_H + 4, 0xFFFFFF, true);

        if (isHovering(BAR_X, BAR_Y, BAR_W, BAR_H, mouseX, mouseY)) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("gui.craftingveloce.electric.energy",
                    formatFe(energy), formatFe(maxEnergy)));
            lines.add(Component.translatable("gui.craftingveloce.electric.smelts", smelts)
                    .withStyle(net.minecraft.ChatFormatting.GOLD));
            lines.add(Component.translatable("gui.craftingveloce.electric.perSmelt",
                            formatFe(VeloceElectricFurnaceBlockEntity.FE_PER_SMELT))
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
        }
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    /** "1 234 567" - czytelnie, bez naukowego zapisu. */
    private static String formatFe(long fe) {
        return String.format(java.util.Locale.ROOT, "%,d", fe).replace(',', ' ');
    }

    /** Odswieza pasek z block entity klienta (bez osobnego pakietu). */
    @Override
    public void containerTick() {
        super.containerTick();
        this.energy = this.menu.getEnergy();
        this.maxEnergy = this.menu.getMaxEnergy();
    }
}
