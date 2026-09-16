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
     * <p>Pozycje sa wyCENTROWANE w panelu: cala grupa (korpus 56 + biegun 2 +
     * odstep 6 + slot 16 = 80 px) ma po 66 px marginesu z kazdej strony.
     * Musza sie zgadzac z wglebieniami w teksturze (gen_furnace_gui.py) -
     * pilnuje tego build.py.
     */
    private static final int BATTERY_X = 66;
    private static final int BATTERY_Y = 32;
    private static final int BATTERY_W = 56;
    private static final int BATTERY_H = 14;

    /** Biegun ("kapturek") baterii - rysowany po prawej stronie korpusu. */
    private static final int NUB_W = 2;
    private static final int NUB_H = 6;

    /** Tlo akumulatora - ciemna zielen, zeby pusto i pelno gralo jednym kolorem. */
    private static final int COLOR_BATTERY_EMPTY = 0xFF8B8B8B;

    /** Naladowanie - zielen "energii". */
    private static final int COLOR_BATTERY_FILL = 0xFF39D353;

    /** Jasniejsza krawedz na gorze wypelnienia (czytelny poziom). */
    private static final int COLOR_BATTERY_HIGHLIGHT = 0xFF8CF0A5;

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

        int x = this.leftPos + BATTERY_X;
        int y = this.topPos + BATTERY_Y;

        // Tlo akumulatora: ciemna zielen na CALYM korpusie. Bez tego "pusto"
        // wygladalo jak zwykly rowek, a nie jak rozladowana bateria.
        graphics.fill(x, y, x + BATTERY_W, y + BATTERY_H, COLOR_BATTERY_EMPTY);

        // Naladowanie OD LEWEJ DO PRAWEJ - tak bateria wyglada w kazdym innym
        // modzie (i tak czyta sie to najszybciej).
        int filled = maxEnergy > 0
                ? (int) Math.min(BATTERY_W, ((long) BATTERY_W * energy) / maxEnergy)
                : 0;
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + BATTERY_H, COLOR_BATTERY_FILL);
            graphics.fill(x, y, x + filled, y + 1, COLOR_BATTERY_HIGHLIGHT);
        }
        // Biegun swieci sie tylko wtedy, gdy w akumulatorze cos jest - dzieki
        // temu "0 FE" i "pelna bateria" roznia sie na pierwszy rzut oka.
        if (energy > 0) {
            int nubX = x + BATTERY_W;
            int nubY = y + (BATTERY_H - NUB_H) / 2;
            graphics.fill(nubX, nubY, nubX + NUB_W, nubY + NUB_H, COLOR_BATTERY_FILL);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Tooltip TYLKO na baterii. Podpowiedz na slocie (co w niego wlozyc)
        // zostala usunieta na zyczenie uzytkownika - slot ma mowic sam za
        // siebie, a nie dokladac tekst przy najechaniu.
        renderBatteryTooltip(graphics, mouseX, mouseY);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    /** Na baterii: ile FE i na ile przepalen to wystarczy. */
    private void renderBatteryTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isHovering(BATTERY_X, BATTERY_Y, BATTERY_W + NUB_W, BATTERY_H, mouseX, mouseY)) {
            return;
        }
        long smelts = energy / VeloceElectricFurnaceBlockEntity.FE_PER_SMELT;
        List<Component> lines = new ArrayList<>();
        // Energia i koszt przepalenia w kFE / MFE - gracz nie liczy zer w locie.
        lines.add(Component.translatable("gui.craftingveloce.electric.energy",
                com.craftingveloce.util.VeloceFormat.feCompact(energy),
                com.craftingveloce.util.VeloceFormat.feCompact(maxEnergy)));
        lines.add(Component.translatable("gui.craftingveloce.electric.smelts", smelts)
                .withStyle(net.minecraft.ChatFormatting.GOLD));
        lines.add(Component.translatable("gui.craftingveloce.electric.perSmelt",
                        com.craftingveloce.util.VeloceFormat.feCompact(
                                VeloceElectricFurnaceBlockEntity.FE_PER_SMELT))
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }


    /** Odswieza pasek z block entity klienta (bez osobnego pakietu). */
    @Override
    public void containerTick() {
        super.containerTick();
        this.energy = this.menu.getEnergy();
        this.maxEnergy = this.menu.getMaxEnergy();
    }
}
