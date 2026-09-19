package com.craftingveloce.compat.mekanism;

import com.craftingveloce.block.VeloceCaseContents;
import com.craftingveloce.compat.VeloceMods;
import net.neoforged.bus.api.IEventBus;
import com.craftingveloce.block.VeloceIntegraleConversions;

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
    /** The id form, for the conversion table - which keys on ids, not on blocks. */
    private static net.minecraft.resources.ResourceLocation mekId(String id) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mekanism", id);
    }

    private static net.minecraft.world.level.block.Block block(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mekanism", id));
    }

    /** Casing table rows: our module -&gt; base block from the mod. */
    private static void registerCases() {
        // ONLY THE ENABLED MACHINES.
        //
        // The switched-off ones live in MekanismBlocks.DISABLED_BLOCKS, a DeferredRegister
        // that is deliberately never handed to the event bus - so their ResourceKeys stay
        // UNBOUND and `.get()` throws. Registering them here anyway put a null-throwing
        // lookup on the path that every crafting-table click walks:
        //
        //   NullPointerException: Trying to access unbound value:
        //     ResourceKey[minecraft:block / craftingveloce:veloce_mekanism_purification_chamber_module]
        //       at MekanismCompat.lambda$registerCases$12(MekanismCompat.java:73)
        //       at VeloceCaseContents.contentFor(...)
        //       at VeloceCaseDisassemblyRecipe.matches(...)
        //       at RecipeManager.getRecipesFor(...)
        //       at CraftingMenu.redirect$...$polymorph$getRecipe(...)
        //
        // Vanilla asks EVERY recipe whether it matches the grid, so one unbound block broke
        // the vanilla crafting table outright: no output was shown, and crafting failed
        // after a moment. It surfaced in a modpack rather than in dev because Polymorph
        // calls getRecipesFor on every click - without it the same exception fires, only
        // far less often, which is why "it works in the dev client" was true and useless.
        //
        // The Integrale conversions below already followed this rule (see the note there);
        // this table did not, and the asymmetry was the bug.
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_COMBINER_MODULE.get(), () -> block("combiner"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_CRUSHER_MODULE.get(), () -> block("crusher"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_ENRICHMENT_MODULE.get(), () -> block("enrichment_chamber"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_SAWMILL_MODULE.get(), () -> block("precision_sawmill"));

        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_COMPRESSING_MODULE.get(), () -> block("osmium_compressor"));
        VeloceCaseContents.register(() -> MekanismBlocks.VELOCE_METALLURGIC_INFUSING_MODULE.get(), () -> block("metallurgic_infuser"));
        // ---- Integrale conversions ----
        //
        // WHERE THE MACHINE COMES FROM NOW. These modules used to be crafted in a
        // crafting table; that is gone, and the ONLY way to make one is to right-click
        // an Integrale frame with the real machine from the other mod. Without these
        // rows the module would be unobtainable - not crafted, and not convertible
        // either - which is a hole nothing in the build can see.
        //
        // Only the ENABLED modules appear here. See MekanismFeModules.DISABLED: the gas
        // and fluid machines are switched off, so offering a conversion for one would
        // produce a block that cannot run.
        VeloceIntegraleConversions.register(mekId("crusher"), MekanismBlocks.VELOCE_CRUSHER_MODULE::get);
        VeloceIntegraleConversions.register(mekId("enrichment_chamber"), MekanismBlocks.VELOCE_ENRICHMENT_MODULE::get);
        VeloceIntegraleConversions.register(mekId("combiner"), MekanismBlocks.VELOCE_COMBINER_MODULE::get);
        VeloceIntegraleConversions.register(mekId("precision_sawmill"), MekanismBlocks.VELOCE_SAWMILL_MODULE::get);
        VeloceIntegraleConversions.register(mekId("osmium_compressor"), MekanismBlocks.VELOCE_COMPRESSING_MODULE::get);
        VeloceIntegraleConversions.register(mekId("metallurgic_infuser"), MekanismBlocks.VELOCE_METALLURGIC_INFUSING_MODULE::get);

    }

    /**
     * Casing contents renderer for the module block entities - CLIENT ONLY.
     *
     * <p><b>Why this is a loop and not a list.</b> This method used to spell out every
     * machine by name, in four near-identical copies - and every copy included the 16
     * gas and fluid machines that are switched off further up this class. Those machines
     * register their block entity type into a register that is never sent to the bus, so
     * the `.get()` here threw before the title screen:
     *
     * <pre>Trying to access unbound value:
     *   ResourceKey[block_entity_type / craftingveloce:veloce_mekanism_purification_chamber_module]</pre>
     *
     * <p>Walking {@link MekanismFeModules#ALL} means the renderers can only ever cover the
     * machines that are actually on: switching one off removes its renderer in the same
     * edit, with nothing left to remember.
     */
    private static void registerCaseRenderers(IEventBus modEventBus) {
        modEventBus.addListener(
                net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers.class,
                event -> {
                    for (com.craftingveloce.crafting.FeModule module
                            : MekanismFeModules.ALL) {
                        event.registerBlockEntityRenderer(
                                MekanismBlockEntities.holderFor(module).get(),
                                com.craftingveloce.client.render.VeloceCaseRenderer::new);
                    }
                });
    }

}
