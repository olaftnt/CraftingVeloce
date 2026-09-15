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
 * Bloki modulu Alchemistry.
 *
 * <p>Id blokow maja prefiks moda ({@code veloce_alchemistry_*}), bo nazwy typu
 * "combiner" istnieja w kilku modach naraz - bez prefiksu dwa moduly moglyby
 * wybrac to samo id i jeden blok nadpisalby drugi (pilnuje tego
 * {@code validate_module_block_ids} w build.py).
 *
 * <p>Rejestracja plain {@code DeferredRegister}; klasa laduje sie tylko przy
 * obecnym Alchemistry.
 */
public final class AlchemistryBlocks {

    private AlchemistryBlocks() {
    }

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(com.craftingveloce.CraftingVeloceMod.MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(com.craftingveloce.CraftingVeloceMod.MODID);

    /** Compactor: receptury {@code alchemistry:compactor}. */
    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_COMPACTOR_MODULE =
            BLOCKS.register("veloce_alchemistry_compactor_module",
                    () -> block(AlchemistryFeModules.COMPACTOR));
    public static final DeferredItem<BlockItem> VELOCE_COMPACTOR_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_alchemistry_compactor_module",
                    VELOCE_COMPACTOR_MODULE);

    /** Combiner: receptury {@code alchemistry:combiner} (N skladnikow). */
    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_COMBINER_MODULE =
            BLOCKS.register("veloce_alchemistry_combiner_module",
                    () -> block(AlchemistryFeModules.COMBINER));
    public static final DeferredItem<BlockItem> VELOCE_COMBINER_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_alchemistry_combiner_module",
                    VELOCE_COMBINER_MODULE);

    /** Fission: receptury {@code alchemistry:fission} (1 item -> 2 itemy). */
    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_FISSION_MODULE =
            BLOCKS.register("veloce_alchemistry_fission_module",
                    () -> block(AlchemistryFeModules.FISSION));
    public static final DeferredItem<BlockItem> VELOCE_FISSION_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_alchemistry_fission_module",
                    VELOCE_FISSION_MODULE);

    /** Fusion: receptury {@code alchemistry:fusion} (2 itemy -> 1 item). */
    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_FUSION_MODULE =
            BLOCKS.register("veloce_alchemistry_fusion_module",
                    () -> block(AlchemistryFeModules.FUSION));
    public static final DeferredItem<BlockItem> VELOCE_FUSION_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_alchemistry_fusion_module",
                    VELOCE_FUSION_MODULE);

    /** Jedna linia na maszyne: blok rdzenia + fabryka BE z tego modulu. */
    private static VeloceFeModuleBlock block(FeModule module) {
        return new VeloceFeModuleBlock(module, AlchemistryBlockEntities.factory(module),
                properties());
    }

    private static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .sound(SoundType.METAL)
                .strength(3.5F)
                .requiresCorrectToolForDrops();
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }

    /**
     * Pozycje do zakladki kreatywnej - wolane TYLKO gdy Alchemistry jest
     * obecne (zakladka buduje sie zawsze, takze bez tego moda).
     */
    public static void addCreativeItems(net.minecraft.world.item.CreativeModeTab.Output output) {
        output.accept(VELOCE_COMPACTOR_MODULE_ITEM.get());
        output.accept(VELOCE_COMBINER_MODULE_ITEM.get());
        output.accept(VELOCE_FISSION_MODULE_ITEM.get());
        output.accept(VELOCE_FUSION_MODULE_ITEM.get());
    }
}
