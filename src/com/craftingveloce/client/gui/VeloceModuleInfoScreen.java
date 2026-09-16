package com.craftingveloce.client.gui;

import net.minecraft.ChatFormatting;
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

    private boolean hasEnergy() {
        return info.contains("energy");
    }

    @Override
    protected void init() {
        lines.clear();
        batteryLine = -1;
        if (hasEnergy()) {
            long energy = info.getLong("energy");
            long capacity = info.getLong("energyCapacity");
            lines.add(header("gui.craftingveloce.module.info.energy", energy, capacity));
            batteryLine = lines.size() - 1;
            lines.add(text("gui.craftingveloce.module.info.operations",
                    info.getLong("operations"), info.getLong("fePerOperation")));
        } else {
            float speed = info.getFloat("speed");
            int required = info.getInt("requiredSpeed");
            lines.add(header("gui.craftingveloce.module.info.speed", speed, required, required));
            lines.add(text("gui.craftingveloce.module.info.speedRequired", required));
            lines.add(text("gui.craftingveloce.module.info.stress",
                    info.getFloat("suDraw"), info.getFloat("suCapacity"), info.getFloat("suNeeded")));
            lines.add(text("gui.craftingveloce.module.info.parts", info.getInt("parts")));
        }
        lines.add(Component.empty());
        lines.add(header("gui.craftingveloce.module.info.network",
                info.getInt("networkNodes"), info.getInt("networkStorages"),
                info.getInt("networkItems")));
        boolean working = hasEnergy() ? info.getBoolean("powered") : info.getBoolean("enoughSpeed");
        lines.add(working
                ? Component.translatable("gui.craftingveloce.module.info.ok")
                        .withStyle(ChatFormatting.DARK_GREEN)
                : Component.translatable(hasEnergy()
                        ? "gui.craftingveloce.module.info.noPower"
                        : "gui.craftingveloce.module.info.notEnough")
                        .withStyle(ChatFormatting.DARK_RED));
        blockName = blockNameAt(pos);
    }

    /**
     * Naglowek sekcji - pogrubiony, bez koloru (na jasnym panelu kolory z
     * {@code ChatFormatting} typu YELLOW sa nieczytelne).
     */
    private Component header(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.BOLD);
    }

    private Component text(String key, Object... args) {
        return Component.translatable(key, args);
    }

    /** Nazwa bloku do paska tytulu; przed otwarciem swiata - tytul okna. */
    private Component blockNameAt(BlockPos at) {
        net.minecraft.world.level.Level level = Minecraft.getInstance().level;
        return level == null ? title : level.getBlockState(at).getBlock().getName();
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
