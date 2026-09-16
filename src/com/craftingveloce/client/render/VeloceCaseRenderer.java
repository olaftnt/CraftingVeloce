package com.craftingveloce.client.render;

import com.craftingveloce.block.VeloceCaseContents;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Renderuje ZAWARTOSC obudowy Veloce Integrale - dla kazdego naszego klocka.
 *
 * <p><b>Co pokazuje.</b> Model klocka bazowego z tabeli
 * {@link VeloceCaseContents}: w obudowie kontrolera stoi pulpit, w obudowie
 * ekstraktora dozownik, w obudowie sensora obserwator, w obudowie stolu
 * craftingu stol, w obudowie pieca piec.
 *
 * <p><b>Dlaczego jeden renderer dla wszystkich.</b> Wyglad jest ten sam
 * (obudowa + eksponat), rozni sie tylko zawartosc - a to jest jeden wiersz
 * w tabeli. Klasy renderera nie trzeba pisac dla kazdego bloku, tak samo jak
 * nie pisze sie osobnego modelu dla kazdego stanu.
 *
 * <p><b>Bez pracy po stronie serwera.</b> Zawartosc wynika z TYPU bloku, wiec
 * nic nie trzeba zapisywac ani synchronizowac - klient czyta sam stan bloku.
 *
 * <p><b>Animacja.</b> Delikatny obrot plus bujanie w lewo-prawo i gora-dol,
 * liczone z czasu gry (nie z tickow), wiec ruch jest plynny.
 */
public class VeloceCaseRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {

    /** Rozmiar eksponatu: miesci sie w oknie obudowy (12 px = 0.75 klocka). */
    private static final float CONTENT_SCALE = 0.45F;

    /** Predkosc obrotu w stopniach na tick (pelny obrot ~10 s). */
    private static final float SPIN_DEGREES_PER_TICK = 0.6F;

    public VeloceCaseRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(T be, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        if (be.getLevel() == null) {
            return;
        }
        BlockState state = be.getBlockState();
        Block content = VeloceCaseContents.contentFor(state);
        if (content == null) {
            return;   // rura, terminal albo zwykly blok - nic nie renderujemy
        }
        float time = be.getLevel().getGameTime() + partialTick;

        pose.pushPose();
        pose.translate(0.5D, 0.5D, 0.5D);
        pose.mulPose(Axis.YP.rotationDegrees((time * SPIN_DEGREES_PER_TICK) % 360.0F));
        pose.translate(Math.sin(time * 0.05F) * 0.030D,
                Math.sin(time * 0.08F) * 0.040D,
                Math.cos(time * 0.045F) * 0.030D);
        pose.scale(CONTENT_SCALE, CONTENT_SCALE, CONTENT_SCALE);
        pose.translate(-0.5D, -0.5D, -0.5D);
        Minecraft.getInstance().getBlockRenderer()
                .renderSingleBlock(content.defaultBlockState(), pose, buffers, packedLight, packedOverlay);
        pose.popPose();
    }
}
