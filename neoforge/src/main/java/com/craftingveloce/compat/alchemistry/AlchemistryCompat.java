package com.craftingveloce.compat.alchemistry;

import com.craftingveloce.block.VeloceCaseContents;
import com.craftingveloce.compat.VeloceMods;
import net.neoforged.bus.api.IEventBus;
import com.craftingveloce.block.VeloceIntegraleConversions;

/**
 * Integration gate for Alchemistry.
 *
 * <p><b>Isolation.</b> Always loaded, also without Alchemistry - hence zero
 * Alchemistry types in fields and signatures. {@link #register(IEventBus)}
 * refers to classes with foreign types, but it is only called after
 * {@link #isPresent()}.
 */
public final class AlchemistryCompat {

    private AlchemistryCompat() {
    }

    /** Whether Alchemistry is present. Safe without it too. */
    public static boolean isPresent() {
        return VeloceMods.ALCHEMISTRY.isLoaded();
    }

    /** Registers the integration. May be called ONLY when {@link #isPresent()}. */
    public static void register(IEventBus modEventBus) {
        AlchemistryBlocks.register(modEventBus);
        AlchemistryBlockEntities.register(modEventBus);
        AlchemistryCapabilities.register(modEventBus);
        AlchemistryRecipeFamily.register(modEventBus);
        AlchemistryModule.register(modEventBus);
        registerCases();
        // Alchemistry recipe categories for JEI (just the UIDs + our blocks).
        AlchemistryJeiCatalysts.register();
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            registerCaseRenderers(modEventBus);
        }
    }

    /**
     * Module entries for the creative tab.
     *
     * <p>The tab is always built (also without Alchemistry), so this call is
     * conditional on the mod being present in {@code CraftingVeloceMod}.
     */
    public static void addCreativeItems(net.minecraft.world.item.CreativeModeTab.Output output) {
        AlchemistryBlocks.addCreativeItems(output);
    }

    /** Base block from the mod by ID (the registry name, not a class field). */
    /** The id form, for the conversion table - which keys on ids, not on blocks. */
    private static net.minecraft.resources.ResourceLocation alchId(String id) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("alchemistry", id);
    }

    private static net.minecraft.world.level.block.Block block(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("alchemistry", id));
    }

    /** Rows of the casing table: our module -&gt; the base block from the mod. */
    private static void registerCases() {
        VeloceCaseContents.register(() -> AlchemistryBlocks.VELOCE_COMBINER_MODULE.get(), () -> block("combiner"));
        VeloceCaseContents.register(() -> AlchemistryBlocks.VELOCE_COMPACTOR_MODULE.get(), () -> block("compactor"));
        VeloceCaseContents.register(() -> AlchemistryBlocks.VELOCE_FISSION_MODULE.get(), () -> block("fission_chamber_controller"));
        VeloceCaseContents.register(() -> AlchemistryBlocks.VELOCE_FUSION_MODULE.get(), () -> block("fusion_chamber_controller"));

        VeloceCaseContents.register(() -> AlchemistryBlocks.VELOCE_DISSOLVER_MODULE.get(), () -> block("dissolver"));
        VeloceCaseContents.register(() -> AlchemistryBlocks.VELOCE_LIQUIFIER_MODULE.get(), () -> block("liquifier"));
        VeloceCaseContents.register(() -> AlchemistryBlocks.VELOCE_ATOMIZER_MODULE.get(), () -> block("atomizer"));
        // ---- Integrale conversions ----
        //
        // Same rule as Mekanism: the module is no longer crafted, so this is the only
        // way to make one, and a module missing from here is unobtainable.
        VeloceIntegraleConversions.register(alchId("atomizer"), AlchemistryBlocks.VELOCE_ATOMIZER_MODULE::get);
        VeloceIntegraleConversions.register(alchId("combiner"), AlchemistryBlocks.VELOCE_COMBINER_MODULE::get);
        VeloceIntegraleConversions.register(alchId("compactor"), AlchemistryBlocks.VELOCE_COMPACTOR_MODULE::get);
        VeloceIntegraleConversions.register(alchId("dissolver"), AlchemistryBlocks.VELOCE_DISSOLVER_MODULE::get);
        VeloceIntegraleConversions.register(alchId("fission_chamber_controller"), AlchemistryBlocks.VELOCE_FISSION_MODULE::get);
        VeloceIntegraleConversions.register(alchId("fusion_chamber_controller"), AlchemistryBlocks.VELOCE_FUSION_MODULE::get);
        VeloceIntegraleConversions.register(alchId("liquifier"), AlchemistryBlocks.VELOCE_LIQUIFIER_MODULE::get);


    }

    /** Casing contents renderer for the module block entities - CLIENT only. */
    private static void registerCaseRenderers(IEventBus modEventBus) {
        modEventBus.addListener(
                net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers.class,
                event -> {
                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.COMBINER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);

                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.DISSOLVER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.LIQUIFIER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.ATOMIZER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);

                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.COMPACTOR_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);

                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.DISSOLVER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.LIQUIFIER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.ATOMIZER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);

                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.FISSION_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);

                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.DISSOLVER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.LIQUIFIER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.ATOMIZER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);

                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.FUSION_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);

                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.DISSOLVER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.LIQUIFIER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    event.registerBlockEntityRenderer(AlchemistryBlockEntities.ATOMIZER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);

                });
    }

}
