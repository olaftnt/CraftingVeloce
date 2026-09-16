package com.craftingveloce.compat.mekanism;

import com.craftingveloce.block.VeloceCaseContents;
import com.craftingveloce.compat.VeloceMods;
import net.neoforged.bus.api.IEventBus;

/**
 * Bramka integracji z Mekanism.
 *
 * <p><b>Izolacja.</b> Ladowana ZAWSZE, takze bez Mekanism - zero typow
 * Mekanism w polach i sygnaturach. {@link #register(IEventBus)} siega do klas
 * z obcymi typami, ale tylko po {@link #isPresent()}.
 */
public final class MekanismCompat {

    private MekanismCompat() {
    }

    /** Czy Mekanism jest obecny. Bezpieczne takze bez niego. */
    public static boolean isPresent() {
        return VeloceMods.MEKANISM.isLoaded();
    }

    /**
     * Rejestruje integracje. Wolno wolac WYLACZNIE gdy {@link #isPresent()}.
     */
    public static void register(IEventBus modEventBus) {
        MekanismBlocks.register(modEventBus);
        MekanismBlockEntities.register(modEventBus);
        MekanismCapabilities.register(modEventBus);
        MekanismRecipeFamily.register(modEventBus);
        MekanismModule.register(modEventBus);
        registerCases();
        // Kategorie przepisow Mekanism dla JEI (same UID-y + nasze klocki).
        MekanismJeiCatalysts.register();
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            registerCaseRenderers(modEventBus);
        }
    }

    /**
     * Pozycje modulu do zakladki kreatywnej.
     *
     * <p>Zakladka buduje sie ZAWSZE (takze bez Mekanism), wiec to wywolanie
     * jest warunkowane obecnoscia moda w {@code CraftingVeloceMod} - inaczej
     * samo budowanie zakladki zaladowaloby klase z obcym typem.
     */
    public static void addCreativeItems(net.minecraft.world.item.CreativeModeTab.Output output) {
        MekanismBlocks.addCreativeItems(output);
    }

    /** Blok bazowy z moda po ID (nazwa w rejestrze, nie pole klasy). */
    private static net.minecraft.world.level.block.Block block(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mekanism", id));
    }

    /** Wiersze tabeli obudow: nasz modul -&gt; blok bazowy z moda. */
    private static void registerCases() {
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_COMBINER_MODULE.get(), () -> block("combiner"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_CRUSHER_MODULE.get(), () -> block("crusher"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_ENRICHMENT_MODULE.get(), () -> block("enrichment_chamber"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_SAWMILL_MODULE.get(), () -> block("precision_sawmill"));

        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_SMELTING_MODULE.get(), () -> block("energized_smelter"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_COMPRESSING_MODULE.get(), () -> block("osmium_compressor"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_METALLURGIC_INFUSING_MODULE.get(), () -> block("metallurgic_infuser"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_PURIFYING_MODULE.get(), () -> block("purification_chamber"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_INJECTING_MODULE.get(), () -> block("chemical_injection_chamber"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_CRYSTALLIZING_MODULE.get(), () -> block("chemical_crystallizer"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_DISSOLUTION_MODULE.get(), () -> block("chemical_dissolution_chamber"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_WASHING_MODULE.get(), () -> block("chemical_washer"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_SEPARATING_MODULE.get(), () -> block("electrolytic_separator"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_REACTION_MODULE.get(), () -> block("pressurized_reaction_chamber"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_ROTARY_MODULE.get(), () -> block("rotary_condensentrator"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_ACTIVATING_MODULE.get(), () -> block("solar_neutron_activator"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_CENTRIFUGING_MODULE.get(), () -> block("isotopic_centrifuge"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_NUCLEOSYNTHESIZING_MODULE.get(), () -> block("antiprotonic_nucleosynthesizer"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_PIGMENT_EXTRACTING_MODULE.get(), () -> block("pigment_extractor"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_PIGMENT_MIXING_MODULE.get(), () -> block("pigment_mixer"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_PAINTING_MODULE.get(), () -> block("painting_machine"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_OXIDIZING_MODULE.get(), () -> block("chemical_oxidizer"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_CHEMICAL_INFUSING_MODULE.get(), () -> block("chemical_infuser"));
    }

    /** Renderer zawartosci obudowy dla block entity modulow - TYLKO klient. */
    private static void registerCaseRenderers(IEventBus modEventBus) {
        modEventBus.addListener(
                net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers.class,
                event -> {
                    event.registerBlockEntityRenderer(MekanismBlockEntities.COMBINER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);

                    event.registerBlockEntityRenderer(MekanismBlockEntities.SMELTING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.COMPRESSING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.METALLURGIC_INFUSING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.PURIFYING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.INJECTING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.CRYSTALLIZING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.DISSOLUTION_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.WASHING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.SEPARATING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.REACTION_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.ROTARY_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.ACTIVATING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.CENTRIFUGING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.NUCLEOSYNTHESIZING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.PIGMENT_EXTRACTING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.PIGMENT_MIXING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.PAINTING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.OXIDIZING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.CHEMICAL_INFUSING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.CRUSHER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);

                    event.registerBlockEntityRenderer(MekanismBlockEntities.SMELTING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.COMPRESSING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.METALLURGIC_INFUSING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.PURIFYING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.INJECTING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.CRYSTALLIZING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.DISSOLUTION_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.WASHING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.SEPARATING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.REACTION_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.ROTARY_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.ACTIVATING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.CENTRIFUGING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.NUCLEOSYNTHESIZING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.PIGMENT_EXTRACTING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.PIGMENT_MIXING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.PAINTING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.OXIDIZING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.CHEMICAL_INFUSING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.ENRICHMENT_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);

                    event.registerBlockEntityRenderer(MekanismBlockEntities.SMELTING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.COMPRESSING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.METALLURGIC_INFUSING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.PURIFYING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.INJECTING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.CRYSTALLIZING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.DISSOLUTION_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.WASHING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.SEPARATING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.REACTION_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.ROTARY_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.ACTIVATING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.CENTRIFUGING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.NUCLEOSYNTHESIZING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.PIGMENT_EXTRACTING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.PIGMENT_MIXING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.PAINTING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.OXIDIZING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.CHEMICAL_INFUSING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.SAWMILL_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);

                    event.registerBlockEntityRenderer(MekanismBlockEntities.SMELTING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.COMPRESSING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.METALLURGIC_INFUSING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.PURIFYING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.INJECTING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.CRYSTALLIZING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.DISSOLUTION_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.WASHING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.SEPARATING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.REACTION_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.ROTARY_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.ACTIVATING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.CENTRIFUGING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.NUCLEOSYNTHESIZING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.PIGMENT_EXTRACTING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.PIGMENT_MIXING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.PAINTING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.OXIDIZING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(MekanismBlockEntities.CHEMICAL_INFUSING_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                });
    }

}
