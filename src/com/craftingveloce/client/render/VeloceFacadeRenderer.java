package com.craftingveloce.client.render;

import com.craftingveloce.block.VeloceCraftingTableBlock;
import com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.Blocks;

/**
 * Renderuje STOL CRAFTINGU stojacy w klatce Veloce Integrale.
 *
 * <p><b>Kiedy.</b> Tylko dla stolu w stanie {@code facade} (patrz
 * {@link VeloceCraftingTableBlock#isFacade}) - zwykly stol craftingu nie
 * renderuje nic i nie kosztuje ani jednej klatki.
 *
 * <p><b>Po co wlasny renderer, a nie model bloku.</b> Klatka to jeden blok,
 * wiec nie da sie w niej "postawic" drugiego: model ramy pokazuje sama rame
 * i szybe. Renderer dokłada to, co jest w srodku - i tylko to, bo wynik
 * podmiany (jakim blokiem jest klatka) jest widoczny w stanie bloku, bez
 * zadnego synchronizowania przedmiotow.
 *
 * <p><b>Animacja.</b> Delikatny, ciagly obrot plus lekkie bujanie w lewo-prawo
 * i gora-dol - dokladnie "maszyna w gablocie". Wszystko liczone z czasu gry,
 * wiec klatka animacji nie zalezy od tickow i wyglada plynnie.
 */
public class VeloceFacadeRenderer implements BlockEntityRenderer<VeloceCraftingTableBlockEntity> {

    /** Rozmiar stolu w srodku: miesci sie w oknie klatki (12 px = 0.75 klocka). */
    private static final float CONTENT_SCALE = 0.45F;

    /** Predkosc obrotu w stopniach na tick (pelny obrot ~10 s). */
    private static final float SPIN_DEGREES_PER_TICK = 0.6F;

    /**
     * Co stoi w klatce: zwykly stol craftingu.
     *
     * <p>Ten sam model, ktory widnieje na ikonie itemu "stol w klatce" - gracz
     * widzi w swiecie dokladnie to, co trzyma w rece.
     */
    private static final net.minecraft.world.level.block.state.BlockState CONTENT =
            Blocks.CRAFTING_TABLE.defaultBlockState();

    public VeloceFacadeRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(VeloceCraftingTableBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (!VeloceCraftingTableBlock.isFacade(be.getBlockState()) || be.getLevel() == null) {
            return;   // zwykly stol craftingu - nic nie renderujemy
        }
        float time = be.getLevel().getGameTime() + partialTick;

        pose.pushPose();
        pose.translate(0.5D, 0.5D, 0.5D);
        // Obrot wokol pionowej osi - maszyna w obudowie "sie kreci".
        pose.mulPose(Axis.YP.rotationDegrees((time * SPIN_DEGREES_PER_TICK) % 360.0F));
        // Bujanie: w lewo-prawo i gora-dol, kazde z inna, wolna czestoscia -
        // dzieki temu ruch nie jest ani kolysaniem w takt, ani statyczny.
        pose.translate(Math.sin(time * 0.05F) * 0.030D,
                Math.sin(time * 0.08F) * 0.040D,
                Math.cos(time * 0.045F) * 0.030D);
        pose.scale(CONTENT_SCALE, CONTENT_SCALE, CONTENT_SCALE);
        pose.translate(-0.5D, -0.5D, -0.5D);
        Minecraft.getInstance().getBlockRenderer()
                .renderSingleBlock(CONTENT, pose, buffers, packedLight, packedOverlay);
        pose.popPose();
    }
}
