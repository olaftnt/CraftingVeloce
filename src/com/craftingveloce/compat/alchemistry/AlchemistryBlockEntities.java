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
 * Typy block entity modulu Alchemistry - po jednym na maszyne.
 *
 * <p>Typ jest potrzebny per blok (tak dziala rejestr), ale KLASA jest jedna
 * ({@code VeloceFeModuleBlockEntity} z rdzenia), ktora dostaje swoj
 * {@link FeModule} w konstruktorze. Mapowanie maszyna -> typ zyje w JEDNYM
 * miejscu ({@link #holderFor}).
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

    public static void register(IEventBus modEventBus) {
        TYPES.register(modEventBus);
    }

    /**
     * Fabryka block entity dla danej maszyny.
     *
     * <p>Blok dostaje ja w konstruktorze, wiec nie musi znac czterech holderow
     * ani siegac do nich po nazwie.
     */
    public static VeloceFeModuleBlockEntity.Factory factory(FeModule module) {
        return (pos, state) -> new VeloceFeModuleBlockEntity(module, holderFor(module), pos, state);
    }

    /** Typ block entity dla maszyny - do capability, testow i diagnostyki. */
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
        throw new IllegalArgumentException("brak typu block entity dla maszyny " + module.id());
    }
}
