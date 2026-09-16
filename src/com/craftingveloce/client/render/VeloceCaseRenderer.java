package com.craftingveloce.client.render;

import com.craftingveloce.block.VeloceCaseContents;
import com.craftingveloce.block.VeloceCaseSpin;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * Model ITEMU zamiast modelu bloku.
     *
     * <p><b>Dlaczego.</b> Maszyny z innych modow (mlynek, pila, kruszarka,
     * maszyny Mekanism) NIE sa rysowane zwyklym modelem bloku - maja wlasne
     * renderery block entity (partial/OBJ). {@code renderSingleBlock} rysowal
     * wiec PUSTKE i gracz widzial obudowe bez niczego w srodku. Ich modele
     * itemow sa poprawne i reprezentatywne, wiec renderujemy je tak, jak
     * renderuje sie przedmiot (z tym samym obrotem i bujaniem).
     */
    private static final Map<Block, ItemStack> CONTENT_STACKS = new ConcurrentHashMap<>();

    public VeloceCaseRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** Rysuje klocek bazowy jako przedmiot (FIXED) - z obrotem i bujaniem. */
    private void renderContent(Block content, PoseStack pose, MultiBufferSource buffers,
                               int packedLight, int packedOverlay) {
        ItemStack stack = CONTENT_STACKS.computeIfAbsent(content,
                block -> new ItemStack(block.asItem()));
        ItemRenderer renderer = Minecraft.getInstance().getItemRenderer();
        if (stack.isEmpty() || renderer == null) {
            return;
        }
        renderer.renderStatic(stack, ItemDisplayContext.FIXED, packedLight, packedOverlay,
                pose, buffers, Minecraft.getInstance().level, 0);
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

        // Maszyna z wlasnymi elementami (kola mlynskie) rysuje je OBOK SIEBIE
        // i kreci z predkoscia, ktora podaje sama maszyna (Create).
        if (be instanceof VeloceCaseSpin spin) {
            if (spin.caseParts() > 0) {
                renderParts(spin, content, pose, buffers, packedLight, packedOverlay, time);
                return;
            }
            if (spin.caseBuiltFromParts()) {
                // Kruszarka/crafter bez wklikanych elementow: PUSTA obudowa.
                return;
            }
            // Maszyna ze stala zawartoscia (mlynek, pila, prasa, mixer,
            // deployer): zwykla animacja, taka sama jak dla waniliowych klockow.
            renderStandard(content, pose, buffers, packedLight, packedOverlay, time);
            return;
        }

        renderStandard(content, pose, buffers, packedLight, packedOverlay, time);
    }

    /**
     * Zwykla animacja zawartosci: obrot wokol pionowej osi + bujanie.
     *
     * <p>Ta sama dla kazdego klocka - gracz: "maja sie krecic tak jak kazdy
     * inny render, np. crafting czy furnace".
     */
    private void renderStandard(Block content, PoseStack pose, MultiBufferSource buffers,
                                int packedLight, int packedOverlay, float time) {
        pose.pushPose();
        beginStandardAnimation(pose, time);
        pose.scale(CONTENT_SCALE, CONTENT_SCALE, CONTENT_SCALE);
        pose.translate(-0.5D, -0.5D, -0.5D);
        renderContent(content, pose, buffers, packedLight, packedOverlay);
        pose.popPose();
    }

    /** Przejscie do srodka obudowy + obrot + bujanie (wspolne dla calych ukladow). */
    private static void beginStandardAnimation(PoseStack pose, float time) {
        pose.translate(0.5D, 0.5D, 0.5D);
        pose.mulPose(Axis.YP.rotationDegrees((time * SPIN_DEGREES_PER_TICK) % 360.0F));
        pose.translate(Math.sin(time * 0.05F) * 0.030D,
                Math.sin(time * 0.08F) * 0.040D,
                Math.cos(time * 0.045F) * 0.030D);
    }

    /**
     * Elementy maszyny obok siebie (np. dwa kola mlynskie).
     *
     * <p>Kazde kreci sie wokol wlasnej osi, a sasiednie w PRZECIWNA strone -
     * dokladnie tak wygladaja zazebione kola w Create. Predkosc bierze sie
     * z maszyny (RPM z sieci kinetycznej), wiec szybszy naped = szybszy obrot,
     * a zatrzymany naped = kola stoja (nadal widoczne).
     */
    private void renderParts(VeloceCaseSpin spin, Block content, PoseStack pose,
                             MultiBufferSource buffers, int packedLight, int packedOverlay,
                             float time) {
        int parts = spin.caseParts();
        int cols = Math.max(1, spin.caseGridColumns());
        int rows = Math.max(1, spin.caseGridRows());
        float spacing = Math.min(0.72F / cols, 0.72F / rows);
        float scale = Math.min(CONTENT_SCALE, spacing * 0.9F);
        boolean individually = spin.casePartsSpinIndividually();
        float speed = spin.caseSpinDegreesPerTick();

        pose.pushPose();
        if (!individually) {
            // Caly uklad jako JEDEN obiekt: obraca sie wokol srodka obudowy,
            // zwykla animacja - bez zwiazku z predkoscia napedu (oczka
            // craftera maja wygladac jak kazda inna zawartosc obudowy).
            beginStandardAnimation(pose, time);
        } else {
            pose.translate(0.5D, 0.5D, 0.5D);
        }
        for (int i = 0; i < parts; i++) {
            int col = i / rows;
            int row = i % rows;
            pose.pushPose();
            pose.translate((col - (cols - 1) / 2.0F) * spacing,
                    -(row - (rows - 1) / 2.0F) * spacing, 0.0D);
            if (individually && speed != 0.0F) {
                // Kola mlynskie: kazde wokol siebie i w przeciwne strony, z
                // predkoscia, ktora podaje maszyna (tak sie zazebiaja).
                float direction = (i % 2 == 0) ? 1.0F : -1.0F;
                pose.mulPose(Axis.ZP.rotationDegrees((time * speed * direction) % 360.0F));
            }
            pose.scale(scale, scale, scale);
            pose.translate(-0.5D, -0.5D, -0.5D);
            renderContent(content, pose, buffers, packedLight, packedOverlay);
            pose.popPose();
        }
        pose.popPose();
    }

}
