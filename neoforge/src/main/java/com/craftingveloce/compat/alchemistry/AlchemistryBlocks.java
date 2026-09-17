package com.craftingveloce.compat.alchemistry;

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
 * Alchemistry module blocks.
 *
 * <p>Block ids carry the mod prefix ({@code veloce_alchemistry_*}), because
 * names like "combiner" exist in several mods at once - without the prefix two
 * modules could pick the same id and one block would overwrite the other
 * ({@code validate_module_block_ids} in build.py guards this).
 *
 * <p>Registration is a plain {@code DeferredRegister}; the class loads only when
 * Alchemistry is present.
 */
public final class AlchemistryBlocks {

    private AlchemistryBlocks() {
    }

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(com.craftingveloce.CraftingVeloceMod.MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(com.craftingveloce.CraftingVeloceMod.MODID);

    /** Compactor: {@code alchemistry:compactor} recipes. */
    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_COMPACTOR_MODULE =
            BLOCKS.register("veloce_alchemistry_compactor_module",
                    () -> block(AlchemistryFeModules.COMPACTOR));
    public static final DeferredItem<BlockItem> VELOCE_COMPACTOR_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_alchemistry_compactor_module",
                    VELOCE_COMPACTOR_MODULE);

    /** Combiner: {@code alchemistry:combiner} recipes (N ingredients). */
    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_COMBINER_MODULE =
            BLOCKS.register("veloce_alchemistry_combiner_module",
                    () -> block(AlchemistryFeModules.COMBINER));
    public static final DeferredItem<BlockItem> VELOCE_COMBINER_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_alchemistry_combiner_module",
                    VELOCE_COMBINER_MODULE);

    /** Fission: {@code alchemistry:fission} recipes (1 item -> 2 items). */
    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_FISSION_MODULE =
            BLOCKS.register("veloce_alchemistry_fission_module",
                    () -> block(AlchemistryFeModules.FISSION));
    public static final DeferredItem<BlockItem> VELOCE_FISSION_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_alchemistry_fission_module",
                    VELOCE_FISSION_MODULE);

    /** Fusion: {@code alchemistry:fusion} recipes (2 items -> 1 item). */
    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_FUSION_MODULE =
            BLOCKS.register("veloce_alchemistry_fusion_module",
                    () -> block(AlchemistryFeModules.FUSION));
    public static final DeferredItem<BlockItem> VELOCE_FUSION_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_alchemistry_fusion_module",
                    VELOCE_FUSION_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_DISSOLVER_MODULE =
            BLOCKS.register("veloce_alchemistry_dissolver_module",
                    () -> block(AlchemistryFeModules.DISSOLVER));
    public static final DeferredItem<BlockItem> VELOCE_DISSOLVER_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_alchemistry_dissolver_module",
                    VELOCE_DISSOLVER_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_LIQUIFIER_MODULE =
            BLOCKS.register("veloce_alchemistry_liquifier_module",
                    () -> block(AlchemistryFeModules.LIQUIFIER));
    public static final DeferredItem<BlockItem> VELOCE_LIQUIFIER_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_alchemistry_liquifier_module",
                    VELOCE_LIQUIFIER_MODULE);

    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_ATOMIZER_MODULE =
            BLOCKS.register("veloce_alchemistry_atomizer_module",
                    () -> block(AlchemistryFeModules.ATOMIZER));
    public static final DeferredItem<BlockItem> VELOCE_ATOMIZER_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_alchemistry_atomizer_module",
                    VELOCE_ATOMIZER_MODULE);


    /** One line per machine: the core block + the BE factory from this module. */
    private static VeloceFeModuleBlock block(FeModule module) {
        return new VeloceFeModuleBlock(module, AlchemistryBlockEntities.factory(module),
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
     * Entries for the creative tab - called ONLY when Alchemistry is present
     * (the tab always builds, also without that mod).
     */
    public static void addCreativeItems(net.minecraft.world.item.CreativeModeTab.Output output) {
        output.accept(VELOCE_COMPACTOR_MODULE_ITEM.get());
        output.accept(VELOCE_COMBINER_MODULE_ITEM.get());
        output.accept(VELOCE_FISSION_MODULE_ITEM.get());
        output.accept(VELOCE_FUSION_MODULE_ITEM.get());
        output.accept(VELOCE_DISSOLVER_MODULE_ITEM.get());
        output.accept(VELOCE_LIQUIFIER_MODULE_ITEM.get());
        output.accept(VELOCE_ATOMIZER_MODULE_ITEM.get());
    }
}
