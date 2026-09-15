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
 * <p><b>Uklad.</b> Pozioma bateria (poziom naladowania rosnie od LEWEJ do
 * PRAWEJ), a po jej prawej stronie slot na itemek z energia (bateria, Energy
 * Cube, tablet z innego moda). Nad bateria jest liczba przepalen, na ktore
 * jeszcze stac akumulator - bo to jest liczba, ktora gracza naprawde
 * interesuje, a nie surowe FE.
 *
 * <p><b>Dlaczego napis jest NAD bateria, a nie pod nia.</b> Wczesniej stal pod
 * paskiem, czyli na wysokosci y=88 - a ekwipunek gracza zaczyna sie na y=84.
 * Liczba nachodzila na sloty ekwipunku. Teraz wszystko, co rysuje ten ekran,
 * konczy sie powyzej y=48.
 */
public class VeloceElectricFurnaceScreen
        extends AbstractContainerScreen<VeloceElectricFurnaceMenu> {

    private static final ResourceLocation GUI_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            CraftingVeloceMod.MODID, "textures/gui/electric_furnace.png");

    /**
     * Bateria: korpus + biegun po prawej stronie.
     *
     * <p>Pozycje musza sie zgadzac z wglebieniem w teksturze
     * (gen_furnace_gui.py) - pilnuje tego build.py.
     */
    private static final int BATTERY_X = 26;
    private static final int BATTERY_Y = 32;
    private static final int BATTERY_W = 56;
    private static final int BATTERY_H = 14;

    /** Biegun ("kapturek") baterii - rysowany po prawej stronie korpusu. */
    private static final int NUB_W = 2;
    private static final int NUB_H = 6;

    /** Napis z liczba przepalen - nad bateria, nie pod nia. */
    private static final int TEXT_X = 26;
    private static final int TEXT_Y = 19;

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

        // Wypelnienie OD LEWEJ DO PRAWEJ - tak naladowana bateria wyglada
        // w kazdym innym modzie (i tak czyta sie to najszybciej).
        int filled = maxEnergy > 0
                ? (int) Math.min(BATTERY_W, ((long) BATTERY_W * energy) / maxEnergy)
                : 0;
        int x = this.leftPos + BATTERY_X;
        int y = this.topPos + BATTERY_Y;
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + BATTERY_H, 0xFF3C46FF);
            // Jasniejsza krawedz na gorze - ta sama czytelnosc co wczesniej.
            graphics.fill(x, y, x + filled, y + 1, 0xFF8C96FF);
        }
        // Biegun swieci sie tylko wtedy, gdy w akumulatorze cos jest - dzieki
        // temu "0 FE" i "pelna bateria" roznia sie na pierwszy rzut oka.
        if (energy > 0) {
            int nubX = x + BATTERY_W;
            int nubY = y + (BATTERY_H - NUB_H) / 2;
            graphics.fill(nubX, nubY, nubX + NUB_W, nubY + NUB_H, 0xFF3C46FF);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Liczba przepalen nad bateria.
        long smelts = energy / VeloceElectricFurnaceBlockEntity.FE_PER_SMELT;
        String text = smelts + "x";
        graphics.drawString(this.font, text, this.leftPos + TEXT_X, this.topPos + TEXT_Y,
                0x404040, false);

        renderBatteryTooltip(graphics, mouseX, mouseY);
        renderBatterySlotHint(graphics, mouseX, mouseY);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    /** Na baterii: ile FE i na ile przepalen to wystarczy. */
    private void renderBatteryTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isHovering(BATTERY_X, BATTERY_Y, BATTERY_W + NUB_W, BATTERY_H, mouseX, mouseY)) {
            return;
        }
        long smelts = energy / VeloceElectricFurnaceBlockEntity.FE_PER_SMELT;
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

    /**
     * Podpowiedz na slocie baterii - TYLKO gdy slot jest pusty.
     *
     * <p>Gdy lezy w nim item, wanilia rysuje jego wlasny tooltip i doklejanie
     * czegokolwiek tutaj tylko by go zaslanialo.
     */
    private void renderBatterySlotHint(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isHovering(VeloceElectricFurnaceMenu.BATTERY_SLOT_X,
                VeloceElectricFurnaceMenu.BATTERY_SLOT_Y, 16, 16, mouseX, mouseY)) {
            return;
        }
        if (this.menu.getSlot(0).hasItem()) {
            return;
        }
        graphics.renderTooltip(this.font,
                Component.translatable("gui.craftingveloce.electric.batterySlot"),
                mouseX, mouseY);
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
