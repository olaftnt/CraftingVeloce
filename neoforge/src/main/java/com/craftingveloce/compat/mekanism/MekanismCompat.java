package com.craftingveloce.compat.mekanism;

import com.craftingveloce.block.VeloceCaseContents;
import com.craftingveloce.compat.VeloceMods;
import net.neoforged.bus.api.IEventBus;

/**
 * Gate for the Mekanism integration.
 *
 * <p><b>Isolation.</b> ALWAYS loaded, even without Mekanism - zero Mekanism
 * types in fields and signatures. {@link #register(IEventBus)} reaches into
 * classes with foreign types, but only after {@link #isPresent()}.
 */
public final class MekanismCompat {

    private MekanismCompat() {
    }

    /** Whether Mekanism is present. Safe even without it. */
    public static boolean isPresent() {
        return VeloceMods.MEKANISM.isLoaded();
    }

    /**
     * Registers the integrations. May be called EXCLUSIVELY when {@link #isPresent()}.
     */
    public static void register(IEventBus modEventBus) {
        MekanismBlocks.register(modEventBus);
        MekanismBlockEntities.register(modEventBus);
        MekanismCapabilities.register(modEventBus);
        MekanismRecipeFamily.register(modEventBus);
        MekanismModule.register(modEventBus);
        registerCases();
        // Mekanism recipe categories for JEI (just the UIDs + our blocks).
        MekanismJeiCatalysts.register();
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            registerCaseRenderers(modEventBus);
        }
    }

    /**
     * Module entries for the creative tab.
     *
     * <p>The tab is ALWAYS built (also without Mekanism), so this call is
     * conditioned on the mod being present in {@code CraftingVeloceMod} -
     * otherwise building the tab alone would load a class with a foreign type.
     */
    public static void addCreativeItems(net.minecraft.world.item.CreativeModeTab.Output output) {
        MekanismBlocks.addCreativeItems(output);
    }

    /** Base block from the mod by ID (registry name, not a class field). */
    private static net.minecraft.world.level.block.Block block(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mekanism", id));
    }

    /** Casing table rows: our module -&gt; base block from the mod. */
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

    /** Casing contents renderer for the module block entities - CLIENT ONLY. */
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
