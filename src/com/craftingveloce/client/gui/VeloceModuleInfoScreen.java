package com.craftingveloce.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

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
 * <p><b>Zamykanie.</b> Esc zamyka (jak kazde okno), ale E NIE - gracz wyraznie
 * o to prosil, bo E otwiera ekwipunek i okno znikalo w chwili, gdy chcial je
 * obejrzec.
 */
public class VeloceModuleInfoScreen extends Screen {

    private static final int PANEL_WIDTH = 240;
    private static final int LINE_HEIGHT = 12;
    private static final int BATTERY_WIDTH = 120;
    private static final int BATTERY_HEIGHT = 10;

    private final BlockPos pos;
    private final CompoundTag info;
    private final List<Component> lines = new ArrayList<>();

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
                        .withStyle(ChatFormatting.GREEN)
                : Component.translatable(hasEnergy()
                        ? "gui.craftingveloce.module.info.noPower"
                        : "gui.craftingveloce.module.info.notEnough")
                        .withStyle(ChatFormatting.RED));
    }

    private Component header(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.YELLOW);
    }

    private Component text(String key, Object... args) {
        return Component.translatable(key, args);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int extra = batteryLine >= 0 ? BATTERY_HEIGHT + 6 : 0;
        int contentHeight = lines.size() * LINE_HEIGHT + extra;
        int left = (width - PANEL_WIDTH) / 2;
        int top = Math.max(24, height / 2 - contentHeight / 2);
        graphics.fill(left, top - 6, left + PANEL_WIDTH, top + contentHeight + 4, 0xC0000000);

        Component name = Minecraft.getInstance().level == null ? Component.empty()
                : Minecraft.getInstance().level.getBlockState(pos).getBlock().getName();
        graphics.drawCenteredString(font, name, width / 2, top - 20, 0xFFFFFF);

        int y = top;
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(font, lines.get(i), left + 6, y, 0xFFFFFF, false);
            y += LINE_HEIGHT;
            if (i == batteryLine) {
                drawBattery(graphics, left + 6, y);
                y += BATTERY_HEIGHT + 6;
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Wskaznik naladowania: pasek wypelniony proporcjonalnie do energii. */
    private void drawBattery(GuiGraphics graphics, int x, int y) {
        long energy = Math.max(0L, info.getLong("energy"));
        long capacity = Math.max(1L, info.getLong("energyCapacity"));
        graphics.fill(x, y, x + BATTERY_WIDTH, y + BATTERY_HEIGHT, 0xFF101010);
        int filled = (int) Math.min(BATTERY_WIDTH, BATTERY_WIDTH * energy / capacity);
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + BATTERY_HEIGHT, 0xFF2ECC40);
        }
        graphics.fill(x, y, x + BATTERY_WIDTH, y + 1, 0xFF555555);
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
