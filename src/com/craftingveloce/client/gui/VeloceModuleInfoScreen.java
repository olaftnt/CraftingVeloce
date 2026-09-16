package com.craftingveloce.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * Okno modulu Veloce: predkosc obrotowa, pobor SU i stan sieci.
 *
 * <p><b>Co pokazuje.</b> Gracz chcial "aktualna predkosc / maksymalna predkosc,
 * minimalna wymagana, aktualnie ile dostaje SU / ile jest potrzebne, no i
 * informacje o sieci podpiętej". Wszystkie liczby przychodza z serwera
 * (patrz {@code OpenModuleInfoPKT}), wiec okno nic nie liczy i nie kłamie.
 *
 * <p><b>Zamykanie.</b> Esc zamyka (jak kazde okno), ale E NIE - gracz
 * wyraznie o to prosil przy innym GUI, bo E otwiera ekwipunek i okno znikalo
 * w chwili, gdy chcial je obejrzec.
 */
public class VeloceModuleInfoScreen extends Screen {

    private static final int PANEL_WIDTH = 220;
    private static final int LINE_HEIGHT = 12;

    private final BlockPos pos;
    private final CompoundTag info;
    private final List<Component> lines = new ArrayList<>();

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
        float speed = info.getFloat("speed");
        int required = info.getInt("requiredSpeed");
        int max = info.getInt("maxSpeed");
        float stress = info.getFloat("suStress");
        float capacity = info.getFloat("suCapacity");
        float draw = info.getFloat("suDraw");
        float needed = info.getFloat("suNeeded");
        lines.add(header("gui.craftingveloce.module.info.speed", speed, required, max));
        lines.add(text("gui.craftingveloce.module.info.speedRequired", required));
        lines.add(text("gui.craftingveloce.module.info.speedMax", max));
        lines.add(Component.empty());
        lines.add(header("gui.craftingveloce.module.info.stress", draw, capacity, needed));
        lines.add(text("gui.craftingveloce.module.info.stressNeeded", needed));
        lines.add(header("gui.craftingveloce.module.info.network",
                info.getInt("networkNodes"), info.getInt("networkStorages"),
                info.getInt("networkItems")));
        lines.add(text("gui.craftingveloce.module.info.parts", info.getInt("parts")));
        lines.add(info.getBoolean("enoughSpeed")
                ? Component.translatable("gui.craftingveloce.module.info.ok")
                        .withStyle(ChatFormatting.GREEN)
                : Component.translatable("gui.craftingveloce.module.info.notEnough")
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
        int left = (width - PANEL_WIDTH) / 2;
        int top = Math.max(10, height / 2 - (lines.size() * LINE_HEIGHT) / 2);
        graphics.fill(left, top - 6, left + PANEL_WIDTH, top + lines.size() * LINE_HEIGHT + 4,
                0xC0000000);
        int y = top;
        for (Component line : lines) {
            graphics.drawString(font, line, left + 6, y, 0xFFFFFF, false);
            y += LINE_HEIGHT;
        }
        Component name = Minecraft.getInstance().level == null ? Component.empty()
                : Minecraft.getInstance().level.getBlockState(pos).getBlock().getName();
        graphics.drawCenteredString(font, name, width / 2, top - 20, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
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
