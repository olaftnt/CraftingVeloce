package com.craftingveloce.compat.alchemistry;

import com.craftingveloce.block.entity.VeloceFeModuleBlockEntity;
import com.craftingveloce.crafting.FeModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Block entity types of the Alchemistry module - one per machine.
 *
 * <p>A type is needed per block (that is how the registry works), but the CLASS
 * is a single one ({@code VeloceFeModuleBlockEntity} from the core), which
 * receives its own {@link FeModule} in the constructor. The machine -> type
 * mapping lives in ONE place ({@link #holderFor}).
 */
public final class AlchemistryBlockEntities {

    private AlchemistryBlockEntities() {
    }

    public static final DeferredRegister<BlockEntityType<?>> TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                    com.craftingveloce.CraftingVeloceMod.MODID);

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceFeModuleBlockEntity>> COMPACTOR_MODULE =
            TYPES.register("veloce_alchemistry_compactor_module",
                    () -> BlockEntityType.Builder.of(
                            (pos, state) -> factory(AlchemistryFeModules.COMPACTOR)
                                    .create(pos, state),
                            AlchemistryBlocks.VELOCE_COMPACTOR_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceFeModuleBlockEntity>> COMBINER_MODULE =
            TYPES.register("veloce_alchemistry_combiner_module",
                    () -> BlockEntityType.Builder.of(
                            (pos, state) -> factory(AlchemistryFeModules.COMBINER)
                                    .create(pos, state),
                            AlchemistryBlocks.VELOCE_COMBINER_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceFeModuleBlockEntity>> FISSION_MODULE =
            TYPES.register("veloce_alchemistry_fission_module",
                    () -> BlockEntityType.Builder.of(
                            (pos, state) -> factory(AlchemistryFeModules.FISSION)
                                    .create(pos, state),
                            AlchemistryBlocks.VELOCE_FISSION_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceFeModuleBlockEntity>> FUSION_MODULE =
            TYPES.register("veloce_alchemistry_fusion_module",
                    () -> BlockEntityType.Builder.of(
                            (pos, state) -> factory(AlchemistryFeModules.FUSION)
                                    .create(pos, state),
                            AlchemistryBlocks.VELOCE_FUSION_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> DISSOLVER_MODULE =
            TYPES.register("veloce_alchemistry_dissolver_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(AlchemistryFeModules.DISSOLVER).create(pos, state),
                            AlchemistryBlocks.VELOCE_DISSOLVER_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> LIQUIFIER_MODULE =
            TYPES.register("veloce_alchemistry_liquifier_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(AlchemistryFeModules.LIQUIFIER).create(pos, state),
                            AlchemistryBlocks.VELOCE_LIQUIFIER_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> ATOMIZER_MODULE =
            TYPES.register("veloce_alchemistry_atomizer_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(AlchemistryFeModules.ATOMIZER).create(pos, state),
                            AlchemistryBlocks.VELOCE_ATOMIZER_MODULE.get()).build(null));


    public static void register(IEventBus modEventBus) {
        TYPES.register(modEventBus);
    }

    /**
     * Block entity factory for a given machine.
     *
     * <p>The block receives it in the constructor, so it does not have to know
     * the four holders or reach for them by name.
     */
    public static VeloceFeModuleBlockEntity.Factory factory(FeModule module) {
        return (pos, state) -> new VeloceFeModuleBlockEntity(module, holderFor(module), pos, state);
    }

    /** Block entity type for a machine - for the capability, tests and diagnostics. */
    public static DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>>
            holderFor(FeModule module) {
        if (module == AlchemistryFeModules.COMPACTOR) {
            return COMPACTOR_MODULE;
        }
        if (module == AlchemistryFeModules.COMBINER) {
            return COMBINER_MODULE;
        }
        if (module == AlchemistryFeModules.FISSION) {
            return FISSION_MODULE;
        }
        if (module == AlchemistryFeModules.FUSION) {
            return FUSION_MODULE;
        }
        
        if (module == AlchemistryFeModules.DISSOLVER) {
            return DISSOLVER_MODULE;
        }
        if (module == AlchemistryFeModules.LIQUIFIER) {
            return LIQUIFIER_MODULE;
        }
        if (module == AlchemistryFeModules.ATOMIZER) {
            return ATOMIZER_MODULE;
        }
        throw new IllegalArgumentException("no block entity type for machine " + module.id());
    }
}
