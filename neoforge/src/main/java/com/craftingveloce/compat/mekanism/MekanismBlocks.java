package com.craftingveloce.compat.mekanism;

import com.craftingveloce.block.VeloceFeModuleBlock;
import com.craftingveloce.crafting.FeModule;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Mekanism module blocks (registered with a plain {@code DeferredRegister},
 * without Registrate - that is a Create library and would pull in a foreign
 * dependency).
 *
 * <p><b>Four machines, two lines each.</b> They all share one block
 * ({@link VeloceFeModuleBlock}) and one block entity; the only thing that
 * differs is {@link FeModule}. Adding another item machine is one line here
 * plus one in {@link FeModule} and one in {@link MekanismBlockEntities}.
 *
 * <p>This class loads only when Mekanism is present (see
 * {@link MekanismCompat}) and contains no Mekanism type - the machine is
 * entirely ours.
 */
public final class MekanismBlocks {

    private MekanismBlocks() {
    }

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(com.craftingveloce.CraftingVeloceMod.MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(com.craftingveloce.CraftingVeloceMod.MODID);

    /**
     * Where the SWITCHED-OFF machines register.
     *
     * <p><b>Why a second pair of registers.</b> A {@code DeferredRegister} does nothing
     * until it is handed to the event bus, and these two never are. So the switched-off
     * machines keep their fields, their blocks and their items - nothing is deleted -
     * but the game never learns they exist, which is the point: {@code MekanismFeModules
     * .DISABLED} says they work on gases and fluids, and our network carries items only.
     *
     * <p>The alternative was commenting the declarations out, and a commented-out line
     * rots: nobody knows whether it was disabled on purpose or abandoned. Here the
     * intent is in the code that runs.
     */
    private static final DeferredRegister.Blocks DISABLED_BLOCKS =
            DeferredRegister.createBlocks(com.craftingveloce.CraftingVeloceMod.MODID);
    private static final DeferredRegister.Items DISABLED_ITEMS =
            DeferredRegister.createItems(com.craftingveloce.CraftingVeloceMod.MODID);

    /** Crusher: {@code mekanism:crushing} recipes. */
    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_CRUSHER_MODULE =
            BLOCKS.register("veloce_mekanism_crusher_module",
                    () -> block(MekanismFeModules.CRUSHER));
    public static final DeferredItem<BlockItem> VELOCE_CRUSHER_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_mekanism_crusher_module", VELOCE_CRUSHER_MODULE);

    /** Enrichment: {@code mekanism:enriching} recipes. */
    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_ENRICHMENT_MODULE =
            BLOCKS.register("veloce_mekanism_enrichment_module",
                    () -> block(MekanismFeModules.ENRICHMENT));
    public static final DeferredItem<BlockItem> VELOCE_ENRICHMENT_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_mekanism_enrichment_module", VELOCE_ENRICHMENT_MODULE);

    /** Combining: {@code mekanism:combining} recipes (two inputs). */
    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_COMBINER_MODULE =
            BLOCKS.register("veloce_mekanism_combiner_module",
                    () -> block(MekanismFeModules.COMBINER));
    public static final DeferredItem<BlockItem> VELOCE_COMBINER_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_mekanism_combiner_module", VELOCE_COMBINER_MODULE);

    /** Sawing: {@code mekanism:sawing} recipes (random output). */
    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_SAWMILL_MODULE =
            BLOCKS.register("veloce_mekanism_sawmill_module",
                    () -> block(MekanismFeModules.SAWMILL));
    public static final DeferredItem<BlockItem> VELOCE_SAWMILL_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_mekanism_sawmill_module", VELOCE_SAWMILL_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_COMPRESSING_MODULE =
            BLOCKS.register("veloce_mekanism_osmium_compressor_module",
                    () -> block(MekanismFeModules.COMPRESSING));
    public static final DeferredItem<BlockItem> VELOCE_COMPRESSING_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_mekanism_osmium_compressor_module", VELOCE_COMPRESSING_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_METALLURGIC_INFUSING_MODULE =
            BLOCKS.register("veloce_mekanism_metallurgic_infuser_module",
                    () -> block(MekanismFeModules.METALLURGIC_INFUSING));
    public static final DeferredItem<BlockItem> VELOCE_METALLURGIC_INFUSING_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_mekanism_metallurgic_infuser_module", VELOCE_METALLURGIC_INFUSING_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_PURIFYING_MODULE =
            DISABLED_BLOCKS.register("veloce_mekanism_purification_chamber_module",
                    () -> block(MekanismFeModules.PURIFYING));
    public static final DeferredItem<BlockItem> VELOCE_PURIFYING_MODULE_ITEM =
            DISABLED_ITEMS.registerSimpleBlockItem("veloce_mekanism_purification_chamber_module", VELOCE_PURIFYING_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_INJECTING_MODULE =
            DISABLED_BLOCKS.register("veloce_mekanism_chemical_injection_chamber_module",
                    () -> block(MekanismFeModules.INJECTING));
    public static final DeferredItem<BlockItem> VELOCE_INJECTING_MODULE_ITEM =
            DISABLED_ITEMS.registerSimpleBlockItem("veloce_mekanism_chemical_injection_chamber_module", VELOCE_INJECTING_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_CRYSTALLIZING_MODULE =
            DISABLED_BLOCKS.register("veloce_mekanism_chemical_crystallizer_module",
                    () -> block(MekanismFeModules.CRYSTALLIZING));
    public static final DeferredItem<BlockItem> VELOCE_CRYSTALLIZING_MODULE_ITEM =
            DISABLED_ITEMS.registerSimpleBlockItem("veloce_mekanism_chemical_crystallizer_module", VELOCE_CRYSTALLIZING_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_DISSOLUTION_MODULE =
            DISABLED_BLOCKS.register("veloce_mekanism_chemical_dissolution_chamber_module",
                    () -> block(MekanismFeModules.DISSOLUTION));
    public static final DeferredItem<BlockItem> VELOCE_DISSOLUTION_MODULE_ITEM =
            DISABLED_ITEMS.registerSimpleBlockItem("veloce_mekanism_chemical_dissolution_chamber_module", VELOCE_DISSOLUTION_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_WASHING_MODULE =
            DISABLED_BLOCKS.register("veloce_mekanism_chemical_washer_module",
                    () -> block(MekanismFeModules.WASHING));
    public static final DeferredItem<BlockItem> VELOCE_WASHING_MODULE_ITEM =
            DISABLED_ITEMS.registerSimpleBlockItem("veloce_mekanism_chemical_washer_module", VELOCE_WASHING_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_SEPARATING_MODULE =
            DISABLED_BLOCKS.register("veloce_mekanism_electrolytic_separator_module",
                    () -> block(MekanismFeModules.SEPARATING));
    public static final DeferredItem<BlockItem> VELOCE_SEPARATING_MODULE_ITEM =
            DISABLED_ITEMS.registerSimpleBlockItem("veloce_mekanism_electrolytic_separator_module", VELOCE_SEPARATING_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_REACTION_MODULE =
            DISABLED_BLOCKS.register("veloce_mekanism_pressurized_reaction_chamber_module",
                    () -> block(MekanismFeModules.REACTION));
    public static final DeferredItem<BlockItem> VELOCE_REACTION_MODULE_ITEM =
            DISABLED_ITEMS.registerSimpleBlockItem("veloce_mekanism_pressurized_reaction_chamber_module", VELOCE_REACTION_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_ROTARY_MODULE =
            DISABLED_BLOCKS.register("veloce_mekanism_rotary_condensentrator_module",
                    () -> block(MekanismFeModules.ROTARY));
    public static final DeferredItem<BlockItem> VELOCE_ROTARY_MODULE_ITEM =
            DISABLED_ITEMS.registerSimpleBlockItem("veloce_mekanism_rotary_condensentrator_module", VELOCE_ROTARY_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_ACTIVATING_MODULE =
            DISABLED_BLOCKS.register("veloce_mekanism_solar_neutron_activator_module",
                    () -> block(MekanismFeModules.ACTIVATING));
    public static final DeferredItem<BlockItem> VELOCE_ACTIVATING_MODULE_ITEM =
            DISABLED_ITEMS.registerSimpleBlockItem("veloce_mekanism_solar_neutron_activator_module", VELOCE_ACTIVATING_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_CENTRIFUGING_MODULE =
            DISABLED_BLOCKS.register("veloce_mekanism_isotopic_centrifuge_module",
                    () -> block(MekanismFeModules.CENTRIFUGING));
    public static final DeferredItem<BlockItem> VELOCE_CENTRIFUGING_MODULE_ITEM =
            DISABLED_ITEMS.registerSimpleBlockItem("veloce_mekanism_isotopic_centrifuge_module", VELOCE_CENTRIFUGING_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_NUCLEOSYNTHESIZING_MODULE =
            DISABLED_BLOCKS.register("veloce_mekanism_antiprotonic_nucleosynthesizer_module",
                    () -> block(MekanismFeModules.NUCLEOSYNTHESIZING));
    public static final DeferredItem<BlockItem> VELOCE_NUCLEOSYNTHESIZING_MODULE_ITEM =
            DISABLED_ITEMS.registerSimpleBlockItem("veloce_mekanism_antiprotonic_nucleosynthesizer_module", VELOCE_NUCLEOSYNTHESIZING_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_PIGMENT_EXTRACTING_MODULE =
            DISABLED_BLOCKS.register("veloce_mekanism_pigment_extractor_module",
                    () -> block(MekanismFeModules.PIGMENT_EXTRACTING));
    public static final DeferredItem<BlockItem> VELOCE_PIGMENT_EXTRACTING_MODULE_ITEM =
            DISABLED_ITEMS.registerSimpleBlockItem("veloce_mekanism_pigment_extractor_module", VELOCE_PIGMENT_EXTRACTING_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_PIGMENT_MIXING_MODULE =
            DISABLED_BLOCKS.register("veloce_mekanism_pigment_mixer_module",
                    () -> block(MekanismFeModules.PIGMENT_MIXING));
    public static final DeferredItem<BlockItem> VELOCE_PIGMENT_MIXING_MODULE_ITEM =
            DISABLED_ITEMS.registerSimpleBlockItem("veloce_mekanism_pigment_mixer_module", VELOCE_PIGMENT_MIXING_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_PAINTING_MODULE =
            DISABLED_BLOCKS.register("veloce_mekanism_painting_machine_module",
                    () -> block(MekanismFeModules.PAINTING));
    public static final DeferredItem<BlockItem> VELOCE_PAINTING_MODULE_ITEM =
            DISABLED_ITEMS.registerSimpleBlockItem("veloce_mekanism_painting_machine_module", VELOCE_PAINTING_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_OXIDIZING_MODULE =
            DISABLED_BLOCKS.register("veloce_mekanism_chemical_oxidizer_module",
                    () -> block(MekanismFeModules.OXIDIZING));
    public static final DeferredItem<BlockItem> VELOCE_OXIDIZING_MODULE_ITEM =
            DISABLED_ITEMS.registerSimpleBlockItem("veloce_mekanism_chemical_oxidizer_module", VELOCE_OXIDIZING_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_CHEMICAL_INFUSING_MODULE =
            DISABLED_BLOCKS.register("veloce_mekanism_chemical_infuser_module",
                    () -> block(MekanismFeModules.CHEMICAL_INFUSING));
    public static final DeferredItem<BlockItem> VELOCE_CHEMICAL_INFUSING_MODULE_ITEM =
            DISABLED_ITEMS.registerSimpleBlockItem("veloce_mekanism_chemical_infuser_module", VELOCE_CHEMICAL_INFUSING_MODULE);


    /** One line per machine: the core block + the BE factory from this module. */
    private static VeloceFeModuleBlock block(FeModule module) {
        return new VeloceFeModuleBlock(module, MekanismBlockEntities.factory(module),
                properties());
    }

    private static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .sound(SoundType.METAL)
                .strength(3.5F)
                .requiresCorrectToolForDrops()
        .noOcclusion()
        .isViewBlocking((state, world, pos) -> false)
        .isSuffocating((state, world, pos) -> false);
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }

    /**
     * Creative tab entries - called ONLY when Mekanism is present.
     *
     * <p>The creative tab is built ALWAYS, even without this mod, so the
     * presence check must happen before reaching for these blocks - otherwise
     * merely building the tab would load a class with a foreign mod's type.
     */
    public static void addCreativeItems(net.minecraft.world.item.CreativeModeTab.Output output) {
        output.accept(VELOCE_CRUSHER_MODULE_ITEM.get());
        output.accept(VELOCE_ENRICHMENT_MODULE_ITEM.get());
        output.accept(VELOCE_COMBINER_MODULE_ITEM.get());
        output.accept(VELOCE_SAWMILL_MODULE_ITEM.get());

        output.accept(VELOCE_COMPRESSING_MODULE_ITEM.get());
        output.accept(VELOCE_METALLURGIC_INFUSING_MODULE_ITEM.get());
    }
}
