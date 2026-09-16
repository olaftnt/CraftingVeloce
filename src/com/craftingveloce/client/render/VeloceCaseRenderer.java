package com.craftingveloce.client.render;

import com.craftingveloce.block.VeloceCaseContents;
import com.craftingveloce.block.VeloceCaseSpin;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
     * <p><b>Hybryda.</b> Najpierw probujemy modelu BLOKU (tak wygladaja
     * waniliowe klocki, mechanical crafter i wszystko z geometria). Tylko gdy
     * model bloku nie ma zadnych kwadratow - bo maszyne rysuje wlasny renderer
     * block entity (kola mlynskie, maszyny Mekanism) - siegamy po model ITEMU.
     */
    private static final Map<Block, ItemStack> CONTENT_STACKS = new ConcurrentHashMap<>();

    /**
     * Decyzja "model bloku czy model itemu" - liczona raz na blok i raz na
     * generacje modeli.
     *
     * <p>Wiekszosc klockow ma normalny model bloku i wtedy rysujemy BLOK (tak
     * wygladaja waniliowe klocki, mechanical crafter i wszystko, co ma
     * geometrie). Maszyny, ktore rysuje wlasny renderer block entity (kola
     * mlynskie, maszyny Mekanism) nie maja kwadratow w modelu bloku - dla nich
     * jedynym sensownym wygladem jest model ITEMU. Klucz cache zawiera
     * generacje ModelManager, wiec po przeladowaniu paczek decyzja liczy sie
     * od nowa.
     */
    private static final Map<Block, Boolean> BLOCK_MODEL_USABLE = new ConcurrentHashMap<>();

    /**
     * Kolejnosc pol w kwadracie: od SRODKA na zewnatrz (spirala).
     *
     * <p>Gracz: "ma sie rozszerzac od srodka do zewnatrz w kwadracie". Dzieki
     * temu pierwsze oczka sa w srodku obudowy, a kolejne dokladaja sie jako
     * pierscienie wokol nich - zamiast rosnac w jednym rogu.
     */
    private static final Map<Integer, int[][]> SPIRAL_ORDER = new ConcurrentHashMap<>();

    private static int[][] spiralOrder(int side) {
        return SPIRAL_ORDER.computeIfAbsent(side, s -> {
            // Sortowanie po odleglosci od srodka zamiast chodzenia spirala:
            // spacery po siatce potrafily sie zapetlic przy parzystym boku
            // (krok wychodzil poza mape i brakujace pole nigdy sie nie
            // wypelnialo). Sortowanie jest zawsze skonczone i pokrywa
            // wszystkie pola.
            double centre = (s - 1) / 2.0;
            java.util.List<int[]> cells = new java.util.ArrayList<>(s * s);
            for (int x = 0; x < s; x++) {
                for (int y = 0; y < s; y++) {
                    cells.add(new int[]{x, y});
                }
            }
            cells.sort(java.util.Comparator
                    .comparingDouble((int[] c) -> Math.max(Math.abs(c[0] - centre),
                            Math.abs(c[1] - centre)))
                    .thenComparingDouble(c -> Math.pow(c[0] - centre, 2)
                            + Math.pow(c[1] - centre, 2))
                    .thenComparingInt(c -> c[1])
                    .thenComparingInt(c -> c[0]));
            return cells.toArray(new int[0][]);
        });
    }

    /**
     * Decyzja "model bloku czy model itemu" - raz na blok i raz na generacje
     * modeli.
     *
     * <p>Wiekszosc klockow ma normalny model bloku i wtedy rysujemy BLOK (tak
     * wygladaja waniliowe klocki, mechanical crafter i wszystko z geometria).
     * Maszyny, ktore rysuje wlasny renderer block entity (kola mlynskie,
     * maszyny Mekanism), nie maja kwadratow w modelu bloku - dla nich jedynym
     * sensownym wygladem jest model ITEMU. Klucz cache zawiera generacje
     * ModelManager, wiec po przeladowaniu paczek decyzja liczy sie od nowa.
     */
    private static int cachedGeneration = 0;

    /**
     * Renderery block entity klockow bazowych (warstwa 2).
     *
     * <p><b>Po co.</b> Czesc modelu maszyn z innych modow (ostrze piły, srodek
     * mlyna, bijak prasy, kola kruszarki) rysuje WYLACZNIE ich renderer block
     * entity - w modelu bloku i w modelu itemu tych czesci po prostu nie ma.
     * Dlatego item na ziemi wyglada dobrze (tam renderer tez dziala), a w
     * obudowie widac tylko polowe maszyny. Tutaj tworzymy SYNTEtyczny block
     * entity tego klocka (bez wstawiania go do swiata) i wolamy jego renderer.
     */
    private record ContentRenderer(BlockEntity entity,
                                   net.minecraft.client.renderer.blockentity.BlockEntityRenderer<BlockEntity> renderer) {
    }

    private static final Map<Block, Optional<ContentRenderer>> CONTENT_RENDERERS =
            new ConcurrentHashMap<>();

    /** Klocki, ktorych renderer BE rzucil wyjatek - nie probujemy w kolko. */
    private static final Set<Block> BROKEN_CONTENT_RENDERERS = ConcurrentHashMap.newKeySet();

    @SuppressWarnings("unchecked")
    private static Optional<ContentRenderer> contentRenderer(Block block) {
        if (BROKEN_CONTENT_RENDERERS.contains(block)) {
            return Optional.empty();
        }
        return CONTENT_RENDERERS.computeIfAbsent(block, b -> {
            if (!(b instanceof EntityBlock entityBlock)) {
                return Optional.empty();
            }
            try {
                BlockEntity entity = entityBlock.newBlockEntity(BlockPos.ZERO, b.defaultBlockState());
                if (entity == null) {
                    return Optional.empty();
                }
                var level = Minecraft.getInstance().level;
                if (level != null) {
                    entity.setLevel(level);
                }
                var renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher()
                        .getRenderer(entity);
                if (renderer == null) {
                    return Optional.empty();
                }
                return Optional.of(new ContentRenderer(entity,
                        (net.minecraft.client.renderer.blockentity.BlockEntityRenderer<BlockEntity>) renderer));
            } catch (Throwable failure) {
                // Maszyna moze wymagac prawdziwego poziomu/sasiadow - wtedy
                // zostaje model itemu, a bledu nie powtarzamy co klatke.
                BROKEN_CONTENT_RENDERERS.add(b);
                com.craftingveloce.util.VeloceLog.Block.detail(
                        com.craftingveloce.util.VeloceLog.Side.CLIENT,
                        "block entity renderer for %s not usable in case: %s",
                        b, failure.getClass().getSimpleName());
                return Optional.empty();
            }
        });
    }

    private static boolean blockModelUsable(Block block) {
        ModelManager manager = Minecraft.getInstance().getModelManager();
        int generation = System.identityHashCode(manager);
        if (generation != cachedGeneration) {
            cachedGeneration = generation;
            BLOCK_MODEL_USABLE.clear();
        }
        return BLOCK_MODEL_USABLE.computeIfAbsent(block, b -> {
            BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
            var state = b.defaultBlockState();
            BakedModel model = dispatcher.getBlockModel(state);
            RandomSource random = RandomSource.create(42L);
            if (!model.getQuads(state, null, random).isEmpty()) {
                return true;
            }
            for (Direction side : Direction.values()) {
                if (!model.getQuads(state, side, random).isEmpty()) {
                    return true;
                }
            }
            return false;
        });
    }

    public VeloceCaseRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** Rysuje klocek bazowy jako przedmiot (FIXED) - z obrotem i bujaniem. */
    private void renderContent(Block content, float partialTick, PoseStack pose,
                               MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (blockModelUsable(content)) {
            // Normalny model bloku - tak jak bylo: pelny rozmiar i srodek.
            Minecraft.getInstance().getBlockRenderer()
                    .renderSingleBlock(content.defaultBlockState(), pose, buffers,
                            packedLight, packedOverlay);
            return;
        }
        // Warstwa 2: maszyna rysowana przez wlasny renderer block entity
        // (pila, mlyn, prasa, mixer, kruszarka). To jedyny sposob, zeby bylo
        // widac JEJ ANIMOWANE czesci, ktorych nie ma ani w modelu bloku, ani
        // w modelu itemu.
        Optional<ContentRenderer> custom = contentRenderer(content);
        if (custom.isPresent()) {
            ContentRenderer entry = custom.get();
            try {
                entry.renderer().render(entry.entity(), partialTick, pose, buffers,
                        packedLight, packedOverlay);
                return;
            } catch (Throwable failure) {
                BROKEN_CONTENT_RENDERERS.add(content);
                CONTENT_RENDERERS.remove(content);
            }
        }
        // Warstwa 3: model ITEMU, ale z kontekstem NONE,
        // czyli bez transformacji "FIXED". Dzieki temu item jest tej samej
        // wielkosci i dokladnie tam, gdzie model bloku (gracz: "za maly i nie
        // na srodku").
        ItemStack stack = CONTENT_STACKS.computeIfAbsent(content,
                block -> new ItemStack(block.asItem()));
        ItemRenderer renderer = Minecraft.getInstance().getItemRenderer();
        if (stack.isEmpty() || renderer == null) {
            return;
        }
        renderer.renderStatic(stack, ItemDisplayContext.NONE, packedLight, packedOverlay,
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
                renderParts(spin, content, partialTick, pose, buffers, packedLight, packedOverlay, time);
                return;
            }
            if (spin.caseBuiltFromParts()) {
                // Kruszarka/crafter bez wklikanych elementow: PUSTA obudowa.
                return;
            }
            // Maszyna ze stala zawartoscia (mlynek, pila, prasa, mixer,
            // deployer): zwykla animacja, taka sama jak dla waniliowych klockow.
            renderStandard(content, partialTick, pose, buffers, packedLight, packedOverlay, time);
            return;
        }

        renderStandard(content, partialTick, pose, buffers, packedLight, packedOverlay, time);
    }

    /**
     * Zwykla animacja zawartosci: obrot wokol pionowej osi + bujanie.
     *
     * <p>Ta sama dla kazdego klocka - gracz: "maja sie krecic tak jak kazdy
     * inny render, np. crafting czy furnace".
     */
    private void renderStandard(Block content, float partialTick, PoseStack pose,
                                MultiBufferSource buffers, int packedLight, int packedOverlay,
                                float time) {
        pose.pushPose();
        beginStandardAnimation(pose, time);
        pose.scale(CONTENT_SCALE, CONTENT_SCALE, CONTENT_SCALE);
        pose.translate(-0.5D, -0.5D, -0.5D);
        renderContent(content, partialTick, pose, buffers, packedLight, packedOverlay);
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
    private void renderParts(VeloceCaseSpin spin, Block content, float partialTick, PoseStack pose,
                             MultiBufferSource buffers, int packedLight, int packedOverlay,
                             float time) {
        int parts = spin.caseParts();
        int cols = Math.max(1, spin.caseGridColumns());
        int rows = Math.max(1, spin.caseGridRows());
        float spacing = Math.min(0.72F / cols, 0.72F / rows);
        float scale = Math.min(CONTENT_SCALE, spacing * 0.9F);
        boolean individually = spin.casePartsSpinIndividually();
        float speed = spin.caseSpinDegreesPerTick();
        // Wypelnianie od srodka na zewnatrz (kwadrat), a nie rosnaca linia.
        int[][] order = spiralOrder(Math.max(cols, rows));

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
            int col = order[i][0];
            int row = order[i][1];
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
            renderContent(content, partialTick, pose, buffers, packedLight, packedOverlay);
            pose.popPose();
        }
        pose.popPose();
    }

}
