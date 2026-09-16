package com.craftingveloce.compat.create;

import com.craftingveloce.block.VeloceCaseContents;
import com.craftingveloce.compat.VeloceMods;
import net.neoforged.bus.api.IEventBus;

/**
 * Bramka integracji z Create.
 *
 * <p><b>Izolacja.</b> Ta klasa jest ladowana ZAWSZE - takze bez Create - wiec
 * nie moze miec w polach ani sygnaturach zadnego typu Create. Cialo
 * {@link #register(IEventBus)} odwoluje sie do klas z typami Create, ale JVM
 * rozwija takie odwolanie dopiero przy WYWOLANIU, a wolamy je tylko wtedy, gdy
 * Create jest obecne.
 *
 * <p>Sprawdzenia {@code try/catch} wokol kodu integracji NIE dzialaja:
 * {@code NoClassDefFoundError} leci przy ladowaniu i linkowaniu klasy, zanim
 * try zdazy zadzialac.
 */
public final class CreateCompat {

    private CreateCompat() {
    }

    /** Czy Create jest obecne. Bezpieczne takze bez Create. */
    public static boolean isPresent() {
        return VeloceMods.CREATE.isLoaded();
    }

    /**
     * Rejestruje integracje. Wolno wolac WYLACZNIE gdy {@link #isPresent()}.
     */
    public static void register(IEventBus modEventBus) {
        CreateBlocks.register(modEventBus);
        CreateBlockEntities.register(modEventBus);
        CreateRecipeFamily.register(modEventBus);
        CreateModule.register(modEventBus);
        // Obudowy modulow: kazdy nasz modul Create ma model obudowy Integrale,
        // a w srodku renderuje sie BLOK BAZOWY z Create (kolo mlynskie, mlyn,
        // pila, crafter mechaniczny).
        registerCases();
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            registerCaseRenderers(modEventBus);
        }
        // Stresu NIE rejestrujemy: CStress.setImpact rzuca wyjatek dla blokow
        // spoza Create, a nasza maszyna liczy stale SU sama
        // (VeloceKineticModuleBlockEntity.calculateStressApplied).
    }

    /**
     * Blok bazowy z Create po ID.
     *
     * <p>NIE przez {@code AllBlocks}: ten typ ({@code BlockEntry} z registrate)
     * nie jest na classpath kompilacji, a nazwa bloku w rejestrze jest tak samo
     * stabilna jak pole w klasie.
     */
    private static net.minecraft.world.level.block.Block block(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", id));
    }

    /** Wiersze tabeli obudow: nasz modul -&gt; blok bazowy z Create. */
    private static void registerCases() {
        VeloceCaseContents.register(() -> CreateBlocks.VELOCE_MILLSTONE_MODULE.get(),
                () -> block("millstone"));
        VeloceCaseContents.register(() -> CreateBlocks.VELOCE_SAW_MODULE.get(),
                () -> block("mechanical_saw"));
        VeloceCaseContents.register(() -> CreateBlocks.VELOCE_CRUSHING_MODULE.get(),
                () -> block("crushing_wheel"));
        VeloceCaseContents.register(() -> CreateBlocks.VELOCE_MECHANICAL_CRAFTER_MODULE.get(),
                () -> block("mechanical_crafter"));
        VeloceCaseContents.register(() -> CreateBlocks.VELOCE_PRESS_MODULE.get(),
                () -> block("mechanical_press"));
        VeloceCaseContents.register(() -> CreateBlocks.VELOCE_MIXER_MODULE.get(),
                () -> block("mechanical_mixer"));
    }

    /**
     * Renderer zawartosci obudowy dla block entity modulow - TYLKO klient.
     *
     * <p>Wolane wylacznie gdy Create jest obecne i gdy jestesmy na kliencie,
     * wiec klasa renderera (klasy klienta) nie laduje sie na serwerze.
     */
    private static void registerCaseRenderers(IEventBus modEventBus) {
        modEventBus.addListener(
                net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers.class,
                event -> {
                    event.registerBlockEntityRenderer(CreateBlockEntities.MILLSTONE_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(CreateBlockEntities.SAW_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(CreateBlockEntities.CRUSHING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(CreateBlockEntities.MECHANICAL_CRAFTER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(CreateBlockEntities.PRESS_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(CreateBlockEntities.MIXER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                });
    }

    /**
     * Pozycje modulu do zakladki kreatywnej.
     *
     * <p>Zakladka buduje sie ZAWSZE (takze bez Create), wiec to wywolanie jest
     * warunkowane obecnoscia moda w {@code CraftingVeloceMod}.
     */
    public static void addCreativeItems(net.minecraft.world.item.CreativeModeTab.Output output) {
        CreateBlocks.addCreativeItems(output);
    }
}
