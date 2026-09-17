package com.craftingveloce.compat.create;

import com.craftingveloce.block.VeloceCaseContents;
import com.craftingveloce.block.VeloceIntegraleConversions;
import com.craftingveloce.compat.VeloceMods;
import net.neoforged.bus.api.IEventBus;

/**
 * Gate for the Create integration.
 *
 * <p><b>Isolation.</b> This class is ALWAYS loaded - also without Create - so it
 * may not have any Create type in its fields or signatures. The body of
 * {@link #register(IEventBus)} references classes with Create types, but the JVM
 * resolves such a reference only when it is CALLED, and we call it only when
 * Create is present.
 *
 * <p>{@code try/catch} checks around integration code do NOT work:
 * {@code NoClassDefFoundError} is thrown during class loading and linking,
 * before the try has a chance to act.
 */
public final class CreateCompat {

    private CreateCompat() {
    }

    /** Whether Create is present. Safe also without Create. */
    public static boolean isPresent() {
        return VeloceMods.CREATE.isLoaded();
    }

    /**
     * Registers the integration. May be called EXCLUSIVELY when {@link #isPresent()}.
     */
    public static void register(IEventBus modEventBus) {
        CreateBlocks.register(modEventBus);
        CreateBlockEntities.register(modEventBus);
        CreateRecipeFamily.register(modEventBus);
        CreateModule.register(modEventBus);
        // Module casings: each of our Create modules has an Integrale casing model,
        // and inside it the BASE BLOCK from Create is rendered (millstone, mill,
        // saw, mechanical crafter).
        registerCases();
        registerConversions();
        // Create recipe categories for JEI (just UIDs + our blocks).
        CreateJeiCatalysts.register();
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            registerCaseRenderers(modEventBus);
        }
        // We do NOT register stress: CStress.setImpact throws an exception for
        // blocks outside Create, and our machine computes the constant SU itself
        // (VeloceKineticModuleBlockEntity.calculateStressApplied).
    }

    /** ID of a block from Create (handy for the conversion table). */
    private static net.minecraft.resources.ResourceLocation create(String id) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", id);
    }

    /**
     * Create's base block by ID.
     *
     * <p>NOT through {@code AllBlocks}: that type ({@code BlockEntry} from
     * registrate) is not on the compilation classpath, and a block name in the
     * registry is just as stable as a field in a class.
     */
    private static net.minecraft.world.level.block.Block block(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", id));
    }

    /**
     * Casing table rows: our module -&gt; the base block from Create.
     *
     * <p><b>These use the ITEM model, and that is measured.</b> Create's block models are
     * not a complete picture of the machine: {@code create:block/millstone/block} has 6
     * elements against 12 in {@code create:block/millstone/item}, and
     * {@code create:block/mechanical_saw/block} does not exist at all - the missing parts
     * are drawn by Flywheel. A casing with the block model shows a millstone without its
     * centre stone and a saw without its blade, which is what "half wrecked" meant.
     */
    private static void registerCases() {
        VeloceCaseContents.register(() -> CreateBlocks.VELOCE_MILLSTONE_MODULE.get(),
                () -> block("millstone"), 0.7F, 0.0F, false, true);
        VeloceCaseContents.register(() -> CreateBlocks.VELOCE_SAW_MODULE.get(),
                () -> block("mechanical_saw"), 0.7F, -90.0F, false, true);
        VeloceCaseContents.register(() -> CreateBlocks.VELOCE_CRUSHING_MODULE.get(),
                () -> block("crushing_wheel"), 0.4F, 0.0F, true, true);
        VeloceCaseContents.register(() -> CreateBlocks.VELOCE_MECHANICAL_CRAFTER_MODULE.get(),
                () -> block("mechanical_crafter"), 0.42F, 0.0F, false, true);
        VeloceCaseContents.register(() -> CreateBlocks.VELOCE_PRESS_MODULE.get(),
                () -> block("mechanical_press"), 0.5F, 180.0F, false, true);
        VeloceCaseContents.register(() -> CreateBlocks.VELOCE_MIXER_MODULE.get(),
                () -> block("mechanical_mixer"), 0.5F, 180.0F, false, true);
        VeloceCaseContents.register(() -> CreateBlocks.VELOCE_DEPLOYER_MODULE.get(),
                () -> block("deployer"), 0.5F, -90.0F, false, true);
    }

    /**
     * Conversion rows: an empty Integrale + a block from Create = our module.
     *
     * <p>This is the path the player described: "I take an empty veloce
     * integrale, place it, right-click it with a crushing wheel - one appears in
     * the middle, I click a second time - there is a second one and only now does
     * the machine work". The first click turns the casing into a module (and
     * inserts one part), the second click already hits the module and adds
     * another part.
     */
    private static void registerConversions() {
        VeloceIntegraleConversions.register(create("crushing_wheel"),
                () -> CreateBlocks.VELOCE_CRUSHING_MODULE.get());
        VeloceIntegraleConversions.register(create("mechanical_crafter"),
                () -> CreateBlocks.VELOCE_MECHANICAL_CRAFTER_MODULE.get());
        VeloceIntegraleConversions.register(create("millstone"),
                () -> CreateBlocks.VELOCE_MILLSTONE_MODULE.get());
        VeloceIntegraleConversions.register(create("mechanical_saw"),
                () -> CreateBlocks.VELOCE_SAW_MODULE.get());
        VeloceIntegraleConversions.register(create("mechanical_press"),
                () -> CreateBlocks.VELOCE_PRESS_MODULE.get());
        VeloceIntegraleConversions.register(create("mechanical_mixer"),
                () -> CreateBlocks.VELOCE_MIXER_MODULE.get());
        VeloceIntegraleConversions.register(create("deployer"),
                () -> CreateBlocks.VELOCE_DEPLOYER_MODULE.get());
    }

    /**
     * Renderer of the casing contents for the module block entities - CLIENT only.
     *
     * <p>Called only when Create is present and when we are on the client, so the
     * renderer class (a client class) does not load on the server.
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
                    event.registerBlockEntityRenderer(CreateBlockEntities.DEPLOYER_MODULE.get(),
                            com.craftingveloce.client.render.VeloceCaseRenderer::new);
                });
    }

    /**
     * Module items for the creative tab.
     *
     * <p>The tab is built ALWAYS (also without Create), so this call is
     * conditioned on the mod being present in {@code CraftingVeloceMod}.
     */
    public static void addCreativeItems(net.minecraft.world.item.CreativeModeTab.Output output) {
        CreateBlocks.addCreativeItems(output);
    }
}
