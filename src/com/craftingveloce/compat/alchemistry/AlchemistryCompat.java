package com.craftingveloce.compat.alchemistry;

import com.craftingveloce.block.VeloceCaseContents;
import com.craftingveloce.compat.VeloceMods;
import net.neoforged.bus.api.IEventBus;

/**
 * Bramka integracji z Alchemistry.
 *
 * <p><b>Izolacja.</b> Ladowana ZAWSZE, takze bez Alchemistry - dlatego zero
 * typow Alchemistry w polach i sygnaturach. {@link #register(IEventBus)}
 * odwoluje sie do klas z obcymi typami, ale jest wolane tylko po
 * {@link #isPresent()}.
 */
public final class AlchemistryCompat {

    private AlchemistryCompat() {
    }

    /** Czy Alchemistry jest obecne. Bezpieczne takze bez niego. */
    public static boolean isPresent() {
        return VeloceMods.ALCHEMISTRY.isLoaded();
    }

    /** Rejestruje integracje. Wolno wolac WYLACZNIE gdy {@link #isPresent()}. */
    public static void register(IEventBus modEventBus) {
        AlchemistryBlocks.register(modEventBus);
        AlchemistryBlockEntities.register(modEventBus);
        AlchemistryCapabilities.register(modEventBus);
        AlchemistryRecipeFamily.register(modEventBus);
        AlchemistryModule.register(modEventBus);
        registerCases();
        // Kategorie przepisow Alchemistry dla JEI (same UID-y + nasze klocki).
        AlchemistryJeiCatalysts.register();
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            registerCaseRenderers(modEventBus);
        }
    }

    /**
     * Pozycje modulu do zakladki kreatywnej.
     *
     * <p>Zakladka buduje sie ZAWSZE (takze bez Alchemistry), wiec to wywolanie
     * jest warunkowane obecnoscia moda w {@code CraftingVeloceMod}.
     */
    public static void addCreativeItems(net.minecraft.world.item.CreativeModeTab.Output output) {
        AlchemistryBlocks.addCreativeItems(output);
    }

    /** Blok bazowy z moda po ID (nazwa w rejestrze, nie pole klasy). */
    private static net.minecraft.world.level.block.Block block(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("alchemistry", id));
    }

    /** Wiersze tabeli obudow: nasz modul -&gt; blok bazowy z moda. */
    private static void registerCases() {
        VeloceCaseContents.register(() -> AlchemistryBlocks.VELOCE_COMBINER_MODULE.get(), () -> block("combiner"));
        VeloceCaseContents.register(() -> AlchemistryBlocks.VELOCE_COMPACTOR_MODULE.get(), () -> block("compactor"));
        VeloceCaseContents.register(() -> AlchemistryBlocks.VELOCE_FISSION_MODULE.get(), () -> block("fission_chamber_controller"));
        VeloceCaseContents.register(() -> AlchemistryBlocks.VELOCE_FUSION_MODULE.get(), () -> block("fusion_chamber_controller"));
    }

    /** Renderer zawartosci obudowy dla block entity modulow - TYLKO klient. */
    private static void registerCaseRenderers(IEventBus modEventBus) {
        modEventBus.addListener(
                net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers.class,
                event -> {
                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.COMBINER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.COMPACTOR_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.FISSION_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.FUSION_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                });
    }

}
