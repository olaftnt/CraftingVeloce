package com.craftingveloce.client;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.compat.create.block.entity.VeloceKineticModuleBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Napis przy celowniku, gdy patrzysz na maszyne bez wymaganej predkosci.
 *
 * <p>Gracz: "jak maszyna nie dostaje max rotation speed, to jak gracz sie na nia
 * patrzy, chcemy zeby wyswietlalo: not enough rotation speed, minimum required
 * speed i ten speed". Overlay liczy sie w calosci po stronie klienta - predkosc
 * kinetyczna jest synchronizowana przez Create, wiec serwer nie musi o niczym
 * wiedziec (zero pakietow).
 *
 * <p>Ten sam warunek co po stronie serwera ({@code hasEnoughRotationSpeed}),
 * wiec napis nie kłamie: gdy overlay mowi "za malo", maszyna naprawde nie
 * pracuje - i odwrotnie.
 */
public final class VeloceModuleOverlay {

    private VeloceModuleOverlay() {
    }

    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        if (!(minecraft.hitResult instanceof BlockHitResult hit)
                || minecraft.hitResult.getType() != HitResult.Type.BLOCK) {
            return;
        }
        if (!(minecraft.level.getBlockEntity(hit.getBlockPos())
                instanceof VeloceKineticModuleBlockEntity module) || module.hasEnoughRotationSpeed()) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int centerX = graphics.guiWidth() / 2;
        int centerY = graphics.guiHeight() / 2 + 20;
        graphics.drawCenteredString(minecraft.font,
                Component.translatable("gui.craftingveloce.module.notEnoughSpeed"),
                centerX, centerY, 0xFF5555);
        graphics.drawCenteredString(minecraft.font,
                Component.translatable("gui.craftingveloce.module.requiredSpeed",
                        module.requiredSpeed()),
                centerX, centerY + 10, 0xFFAA55);
    }
}
