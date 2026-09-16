package com.craftingveloce.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Okno modulu Veloce: energia albo predkosc obrotowa + stan sieci.
 *
 * <p><b>Dwa warianty w jednym oknie.</b> Maszyny z innych modow zasilane sa
 * albo energia (FE - Mekanism, Alchemistry), albo obrotem (Create). Okno
 * rozpoznaje wariant po polach przyslanych z serwera i pokazuje:
 * <ul>
 *   <li><b>FE</b>: wskaznik naladowania (bateryjka), ile pradu zostalo, koszt
 *       operacji i ile operacji jeszcze z tego wyjdzie,</li>
 *   <li><b>obrot</b>: predkosc aktualna, wymagana i maksymalna, pobor SU.</li>
 * </ul>
 * W obu wariantach dochodzi stan podpiętej sieci rur (wezly, magazyny, typy
 * itemow). Wszystkie liczby liczy SERWER (patrz {@code OpenModuleInfoPKT}),
 * wiec okno nic nie zgaduje i nie odpytuje co klatke.
 *
 * <p><b>Panel, nie prostokat na swiecie.</b> Okno rysuje NIEPRZEZROCZYSTA
 * teksture w stylu vanilla (176x166, paleta naszych pozostalych GUI) i ciemny
 * tekst na niej - dokladnie jak okna pieca czy extractora. Pierwsza wersja
 * rysowala polprzezroczysty prostokat na rozmytym swiecie i tekst bez cienia,
 * przez co wygladalo to jak tooltip schowany za blurem (zgloszenie gracza).
 *
 * <p><b>Zamykanie.</b> Esc zamyka (jak kazde okno), ale E NIE - gracz wyraznie
 * o to prosil, bo E otwiera ekwipunek i okno znikalo w chwili, gdy chcial je
 * obejrzec.
 */
public class VeloceModuleInfoScreen extends Screen {

    /** Tekstura panelu - ta sama paleta i uklad co pozostale okna moda. */
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            com.craftingveloce.CraftingVeloceMod.MODID, "textures/gui/module_info.png");

    private static final int PANEL_WIDTH = 176;
    private static final int PANEL_HEIGHT = 166;
    private static final int TEXT_X = 8;
    private static final int TITLE_Y = 6;
    private static final int CONTENT_Y = 22;
    private static final int LINE_HEIGHT = 12;
    private static final int BATTERY_WIDTH = 160;
    private static final int BATTERY_HEIGHT = 10;

    /** Kolor zwyklych linii na jasnym panelu (jak podpisy w oknach vanilla). */
    private static final int TEXT_COLOR = 0x404040;

    private final BlockPos pos;
    private final CompoundTag info;
    private final List<Component> lines = new ArrayList<>();

    /** Nazwa bloku w pasku tytulu - jak "Furnace" w oknie pieca. */
    private Component blockName = Component.empty();

    /** Indeks linii z energia - pod nia rysujemy bateryjke (-1 = brak). */
    private int batteryLine = -1;

    public VeloceModuleInfoScreen(BlockPos pos, CompoundTag info) {
        super(Component.translatable("gui.craftingveloce.module.info.title"));
        this.pos = pos;
        this.info = info;
    }

    /** Otwiera okno z danymi przyslanymi przez serwer. */
    public static void open(BlockPos pos, CompoundTag info) {
        Minecraft.getInstance().setScreen(new VeloceModuleInfoScreen(pos, info));
    }

    @Override
    protected void init() {
        lines.clear();
        // Linie sklada WSPOLNE zrodlo (VeloceModuleInfoLines) - te same teksty
        // pokazuje tooltip Jade, gdy gracz patrzy na maszyne.
        lines.addAll(com.craftingveloce.crafting.VeloceModuleInfoLines.build(info));
        // Naglowek wariantu na energie jest pierwsza linia - pod nia rysujemy
        // pasek baterii.
        batteryLine = com.craftingveloce.crafting.VeloceModuleInfoLines.isEnergy(info) ? 0 : -1;
        blockName = blockNameAt(pos);
    }

    /** Nazwa bloku do paska tytulu; przed otwarciem swiata - tytul okna. */
    private Component blockNameAt(BlockPos at) {
        net.minecraft.world.level.Level level = Minecraft.getInstance().level;
        return level == null ? title : level.getBlockState(at).getBlock().getName();
    }

    /**
     * Tlo okna BEZ rozmycia swiata.
     *
     * <p><b>Zgloszenie gracza: "na naszych nowych GUI jest jakis dziwny blur".</b>
     * Vanilla przy kazdym ekranie rozmywa to, co jest pod nim - razem z paskiem
     * akcji i napisami HUD - wiec teksty wystajace spod panelu wygladaly jak
     * rozmazane plamy ("tooltip za blurem"). Okno informacyjne nie ma pod soba
     * niczego, co mialoby byc rozmyte, wiec zostawiamy samo przygaszenie:
     * swiat i HUD pozostaja ostre, a panel i tak jest nieprzezroczysty.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        renderPanel(graphics, left, top);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * Panel + tytul + tresc - ten sam uklad co okna pozostalych naszych blokow.
     *
     * <p>Kolejnosc jest istotna: najpierw NIEPRZEZROCZYSTA tekstura panelu,
     * a dopiero na niej tekst. Odwrotnie (albo bez tekstury) tekst lezy na
     * rozmytym swiecie i wyglada jak rozmazany tooltip.
     */
    private void renderPanel(GuiGraphics graphics, int left, int top) {
        graphics.blit(TEXTURE, left, top, 0, 0, PANEL_WIDTH, PANEL_HEIGHT);
        graphics.drawString(font, blockName, left + TEXT_X, top + TITLE_Y, TEXT_COLOR, false);
        int y = top + CONTENT_Y;
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(font, lines.get(i), left + TEXT_X, y, TEXT_COLOR, false);
            y += LINE_HEIGHT;
            if (i == batteryLine) {
                drawBattery(graphics, left + TEXT_X, y);
                y += BATTERY_HEIGHT + 6;
            }
        }
    }

    /** Wskaznik naladowania: wgłębiony pasek wypelniony energia. */
    private void drawBattery(GuiGraphics graphics, int x, int y) {
        long energy = Math.max(0L, info.getLong("energy"));
        long capacity = Math.max(1L, info.getLong("energyCapacity"));
        graphics.fill(x - 1, y - 1, x + BATTERY_WIDTH + 1, y + BATTERY_HEIGHT + 1, 0xFF373737);
        graphics.fill(x, y, x + BATTERY_WIDTH, y + BATTERY_HEIGHT, 0xFF8B8B8B);
        int filled = (int) Math.min(BATTERY_WIDTH, BATTERY_WIDTH * energy / capacity);
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + BATTERY_HEIGHT, 0xFF00AA00);
        }
    }

    /** E NIE zamyka okna - tylko Esc (tak chcial gracz przy innych GUI). */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
