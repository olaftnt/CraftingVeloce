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
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renderuje ZAWARTOSC obudowy Veloce Integrale - dla kazdego naszego klocka.
 *
 * <p><b>Co pokazuje.</b> Model klocka bazowego z tabeli
 * {@link VeloceCaseContents}: w obudowie kontrolera stoi pulpit, w ekstraktorze
 * dozownik, w sensorze obserwator, w stole craftingu stol, w module Create
 * mlynek / pila / kolo mlynskie / crafter.
 *
 * <p><b>Dlaczego model ITEMU, a nie model bloku.</b> Ustalenie z JARa Create:
 * model BLOKU tych maszyn jest okrojony - {@code block/millstone/block} nie ma
 * srodkowego kamienia, {@code block/mechanical_saw/block} nie ma ostrza - bo te
 * czesci rysuje osobny renderer (u Create: Flywheel). Model ITEMU jest ich
 * PELNA, statyczna reprezentacja ({@code block/millstone/item} ma Gear5, a
 * {@code crushing_wheel/item} to model OBJ). Dlatego przedmiot rzucony na
 * ziemie wyglada dobrze, a obudowa z modelem bloku byla "w polowie
 * rozpieprzona". Rysujemy wiec zawsze model itemu - takze dla wanilii, gdzie
 * jest to ten sam model co blok.
 *
 * <p><b>Skala i srodek.</b> Model itemu renderuje sie z wlasna transformacja
 * (FIXED: przesuniecie + zmniejszenie), dlatego wczesniej wychodzil maly i w
 * rogu. Transformacje czytamy z modelu ({@code getTransforms}) i kompensujemy
 * ja nasza poza, wiec zawartosc jest zawsze tej samej wielkosci i dokladnie na
 * srodku obudowy - niezaleznie od moda i jego modelu.
 *
 * <p><b>Bez pracy po stronie serwera.</b> Zawartosc wynika z TYPU bloku, wiec
 * nic nie trzeba zapisywac ani synchronizowac - klient czyta sam stan bloku.
 */
public class VeloceCaseRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {

    /** Rozmiar zawartosci: okno obudowy ma 12 px (0.75 klocka). */
    private static final float CONTENT_SCALE = 0.45F;

    /** Predkosc obrotu calej zawartosci w stopniach na tick (pelny obrot ~10 s). */
    private static final float SPIN_DEGREES_PER_TICK = 0.6F;

    /** Stos przedmiotu dla klocka bazowego (bez alokacji na klatke). */
    private static final Map<Block, ItemStack> CONTENT_STACKS = new ConcurrentHashMap<>();

    /** Transformacja FIXED modelu itemu - potrzebna do kompensacji rozmiaru. */
    private static final Map<Block, ItemTransform> CONTENT_TRANSFORMS = new ConcurrentHashMap<>();

    private static int cachedGeneration = 0;

    /**
     * Kolejnosc pol w kwadracie: od SRODKA na zewnatrz.
     *
     * <p>Gracz: "ma sie rozszerzac od srodka do zewnatrz w kwadracie". Dzieki
     * temu pierwsze oczka sa w srodku obudowy, a kolejne dokladaja sie jako
     * pierscienie wokol nich - zamiast rosnac w jednym rogu. Sortowanie po
     * odleglosci od srodka (a nie chodzenie spirala) jest zawsze skonczone.
     */
    private static final Map<Integer, int[][]> CENTRE_ORDER = new ConcurrentHashMap<>();

    public VeloceCaseRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(T be, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        if (be.getLevel() == null) {
            return;
        }
        Block content = VeloceCaseContents.contentFor(be.getBlockState());
        if (content == null) {
            return;   // rura, terminal albo zwykly blok - nic nie renderujemy
        }
        float time = be.getLevel().getGameTime() + partialTick;

        if (be instanceof VeloceCaseSpin spin) {
            if (spin.caseParts() > 0) {
                renderParts(spin, content, pose, buffers, packedLight, packedOverlay, time);
                return;
            }
            if (spin.caseBuiltFromParts()) {
                // Kruszarka/crafter bez wklikanych elementow: PUSTA obudowa.
                return;
            }
        }
        renderStandard(content, pose, buffers, packedLight, packedOverlay, time);
    }

    /** Zwykla animacja zawartosci: obrot wokol pionowej osi + bujanie. */
    private void renderStandard(Block content, PoseStack pose, MultiBufferSource buffers,
                                int packedLight, int packedOverlay, float time) {
        pose.pushPose();
        beginStandardAnimation(pose, time);
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
     * Elementy maszyny (oczka craftera, kola mlynskie) w ukladzie podanym przez
     * maszyne.
     *
     * <p>Uklad jako CALOSC obraca sie wokol srodka obudowy zwykla animacja, a
     * tylko kola mlynskie krecA sie kazde wokol siebie i z predkoscia napedu
     * (tak sie zazebiaja). Rozmiar elementu wynika z gestosci siatki, wiec
     * 81 oczek jest odpowiednio mniejsze niz jedno.
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
        int[][] order = centreOrder(Math.max(cols, rows));

        pose.pushPose();
        if (!individually) {
            beginStandardAnimation(pose, time);
        } else {
            pose.translate(0.5D, 0.5D, 0.5D);
        }
        for (int i = 0; i < parts; i++) {
            int col = order[i][0];
            int row = order[i][1];
            pose.pushPose();
            pose.translate((col - (cols - 1) / 2.0F) * spacing,
                    -(row - (rows - 1) / 2.0F) * spacing, 0.0D);
            if (individually && speed != 0.0F) {
                float direction = (i % 2 == 0) ? 1.0F : -1.0F;
                pose.mulPose(Axis.ZP.rotationDegrees((time * speed * direction) % 360.0F));
            }
            // Zawartosc rysuje sie w skali CONTENT_SCALE, wiec element siatki
            // skalujemy wzgledem niej (a nie drugi raz od zera).
            float factor = scale / CONTENT_SCALE;
            pose.scale(factor, factor, factor);
            renderContent(content, pose, buffers, packedLight, packedOverlay);
            pose.popPose();
        }
        pose.popPose();
    }

    /**
     * Rysuje klocek bazowy jako PRZEDMIOT - tak, jak wyglada jako encja na
     * ziemi, ale przeskalowany i wysrodkowany w obudowie.
     */
    private void renderContent(Block content, PoseStack pose, MultiBufferSource buffers,
                               int packedLight, int packedOverlay) {
        ItemStack stack = CONTENT_STACKS.computeIfAbsent(content,
                block -> new ItemStack(block.asItem()));
        ItemRenderer renderer = Minecraft.getInstance().getItemRenderer();
        if (stack.isEmpty() || renderer == null) {
            return;
        }
        ItemTransform transform = contentTransform(content, stack);
        pose.pushPose();
        // Kompensacja transformacji przedmiotu: model itemu rysuje sie z wlasnym
        // przesunieciem i zmniejszeniem (FIXED), wiec bez tego zawartosc wychodzi
        // mala i przesunieta. Skalujemy do CONTENT_SCALE i zerujemy przesuniecie.
        float factor = CONTENT_SCALE / Math.max(0.01F, transform.scale.x);
        pose.scale(factor, factor, factor);
        pose.translate(-transform.translation.x, -transform.translation.y,
                -transform.translation.z);
        renderer.renderStatic(stack, ItemDisplayContext.FIXED, packedLight, packedOverlay,
                pose, buffers, Minecraft.getInstance().level, 0);
        pose.popPose();
    }

    /** Transformacja FIXED modelu itemu (liczona raz na generacje modeli). */
    private static ItemTransform contentTransform(Block block, ItemStack stack) {
        ModelManager manager = Minecraft.getInstance().getModelManager();
        int generation = System.identityHashCode(manager);
        if (generation != cachedGeneration) {
            cachedGeneration = generation;
            CONTENT_TRANSFORMS.clear();
        }
        return CONTENT_TRANSFORMS.computeIfAbsent(block, b -> Minecraft.getInstance()
                .getItemRenderer()
                .getModel(stack, Minecraft.getInstance().level, null, 0)
                .getTransforms()
                .getTransform(ItemDisplayContext.FIXED));
    }

    /** Pola kwadratu w kolejnosci od srodka na zewnatrz. */
    private static int[][] centreOrder(int side) {
        return CENTRE_ORDER.computeIfAbsent(side, s -> {
            double centre = (s - 1) / 2.0;
            List<int[]> cells = new ArrayList<>(s * s);
            for (int x = 0; x < s; x++) {
                for (int y = 0; y < s; y++) {
                    cells.add(new int[]{x, y});
                }
            }
            cells.sort(Comparator
                    .comparingDouble((int[] c) -> Math.max(Math.abs(c[0] - centre),
                            Math.abs(c[1] - centre)))
                    .thenComparingDouble(c -> Math.pow(c[0] - centre, 2)
                            + Math.pow(c[1] - centre, 2))
                    .thenComparingInt(c -> c[1])
                    .thenComparingInt(c -> c[0]));
            return cells.toArray(new int[0][]);
        });
    }
}
