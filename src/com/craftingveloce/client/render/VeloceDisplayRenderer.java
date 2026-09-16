package com.craftingveloce.client.render;

import com.craftingveloce.block.VeloceCraftingTableBlock;
import com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Renderuje ZAWARTOSC klatki Veloce Integrale - po stronie KLIENTA.
 *
 * <p><b>Co pokazuje.</b> Zaleznie od tego, czym jest blok:
 * <ul>
 *   <li><b>fasada</b> ({@code veloce_crafting_table} ze stanem
 *       {@code facade}) - stol craftingu stojacy w klatce, czyli widac, ze
 *       w tym bloku naprawde mozna craftowac,</li>
 *   <li><b>gablota</b> (klatka z eksponatem) - blok, ktory gracz wlozyl do
 *       srodka.</li>
 * </ul>
 *
 * <p><b>Po co wlasny renderer, a nie encja {@code BlockDisplay}.</b> Encja
 * block display istnieje na serwerze: trzeba ja stworzyc, zapisac, wyslac
 * i utrzymywac - czyli dodatkowa praca serwera i dodatkowe punkty, w ktorych
 * moze sie rozjechac. Tutaj liczy sie tylko WYGLAD: klient czyta przedmiot
 * z block entity (normalnym pakietem aktualizacji) i rysuje jego model
 * w srodku klatki. Serwer nie robi NIC ponad zapis jednego stosu.
 *
 * <p><b>Animacja.</b> Delikatny, ciagly obrot plus lekkie bujanie w lewo-prawo
 * i gora-dol - dokladnie "zywy eksponat w gablocie". Wszystko liczone z czasu
 * gry, wiec klatka animacji nie zalezy od tickow i wyglada plynnie.
 *
 * <p>Renderer wisi na block entity STOLU craftingu (klatka dzieli je ze
 * stolem), a gdy nie ma czego pokazac - wychodzi od razu, wiec zwykly stol
 * nie kosztuje nic.
 */
public class VeloceDisplayRenderer implements BlockEntityRenderer<VeloceCraftingTableBlockEntity> {

    /** Rozmiar eksponatu: miesci sie w oknie klatki (12 px = 0.75 klocka). */
    private static final float DISPLAY_SCALE = 0.45F;

    /** Predkosc obrotu w stopniach na tick (pelny obrot ~10 s). */
    private static final float SPIN_DEGREES_PER_TICK = 0.6F;

    /**
     * Co pokazac w fasadzie: zwykly stol craftingu.
     *
     * <p>Ten sam model, ktory widnieje na ikonie itemu "stol w klatce" - gracz
     * ma zobaczyc w swiecie dokladnie to, co trzyma w rece.
     */
    private static final BlockState FACADE_CONTENT = Blocks.CRAFTING_TABLE.defaultBlockState();

    public VeloceDisplayRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(VeloceCraftingTableBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be.getLevel() == null) {
            return;
        }
        BlockState shown = shownBlock(be);
        if (shown == null) {
            return;   // zwykly stol craftingu - nic nie renderujemy
        }
        float time = be.getLevel().getGameTime() + partialTick;

        pose.pushPose();
        pose.translate(0.5D, 0.5D, 0.5D);
        // Obrot wokol pionowej osi - eksponat "sie kreci".
        pose.mulPose(Axis.YP.rotationDegrees((time * SPIN_DEGREES_PER_TICK) % 360.0F));
        // Bujanie: w lewo-prawo i gora-dol, kazde z inna, wolna czestoscia -
        // dzieki temu ruch nie jest ani kolysaniem w takt, ani statyczny.
        pose.translate(Math.sin(time * 0.05F) * 0.030D,
                Math.sin(time * 0.08F) * 0.040D,
                Math.cos(time * 0.045F) * 0.030D);
        pose.scale(DISPLAY_SCALE, DISPLAY_SCALE, DISPLAY_SCALE);
        pose.translate(-0.5D, -0.5D, -0.5D);
        Minecraft.getInstance().getBlockRenderer()
                .renderSingleBlock(shown, pose, buffers, packedLight, packedOverlay);
        pose.popPose();
    }

    /**
     * Model do pokazania w srodku, albo {@code null} gdy nic nie ma.
     *
     * <p>Fasada pokazuje stol craftingu (to jest jej tresc), a zwykla klatka -
     * eksponat wlozony przez gracza.
     */
    @Nullable
    private static BlockState shownBlock(VeloceCraftingTableBlockEntity be) {
        if (VeloceCraftingTableBlock.isFacade(be.getBlockState())) {
            return FACADE_CONTENT;
        }
        ItemStack display = be.getDisplayItem();
        if (display.isEmpty() || !(display.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        return blockItem.getBlock().defaultBlockState();
    }
}
