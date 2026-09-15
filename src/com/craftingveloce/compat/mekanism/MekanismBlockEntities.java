package com.craftingveloce.compat.mekanism;

import com.craftingveloce.compat.mekanism.block.entity.VeloceFeModuleBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Typy block entity modulu Mekanism - po jednym na maszyne.
 *
 * <p>Typ jest potrzebny per blok (tak dziala rejestr), ale KLASA jest jedna:
 * {@link VeloceFeModuleBlockEntity}, ktora dostaje swoj {@link FeModule}
 * w konstruktorze. Dlatego cztery maszyny nie maja czterech kopii logiki
 * energii.
 */
public final class MekanismBlockEntities {

    private MekanismBlockEntities() {
    }

    public static final DeferredRegister<BlockEntityType<?>> TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                    com.craftingveloce.CraftingVeloceMod.MODID);

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceFeModuleBlockEntity>> CRUSHER_MODULE =
            TYPES.register("veloce_crusher_module", () -> BlockEntityType.Builder.of(
                    (pos, state) -> create(FeModule.CRUSHER, pos, state),
                    MekanismBlocks.VELOCE_CRUSHER_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceFeModuleBlockEntity>> ENRICHMENT_MODULE =
            TYPES.register("veloce_enrichment_module", () -> BlockEntityType.Builder.of(
                    (pos, state) -> create(FeModule.ENRICHMENT, pos, state),
                    MekanismBlocks.VELOCE_ENRICHMENT_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceFeModuleBlockEntity>> COMBINER_MODULE =
            TYPES.register("veloce_combiner_module", () -> BlockEntityType.Builder.of(
                    (pos, state) -> create(FeModule.COMBINER, pos, state),
                    MekanismBlocks.VELOCE_COMBINER_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceFeModuleBlockEntity>> SAWMILL_MODULE =
            TYPES.register("veloce_sawmill_module", () -> BlockEntityType.Builder.of(
                    (pos, state) -> create(FeModule.SAWMILL, pos, state),
                    MekanismBlocks.VELOCE_SAWMILL_MODULE.get()).build(null));

    public static void register(IEventBus modEventBus) {
        TYPES.register(modEventBus);
    }

    /**
     * Tworzy block entity wlasciwego typu dla danej maszyny.
     *
     * <p>Mapowanie maszyna -> typ jest w JEDNYM miejscu (tutaj), wiec blok nie
     * musi znac czterech holderow ani siegac do nich po nazwie.
     */
    public static VeloceFeModuleBlockEntity create(FeModule module, BlockPos pos,
                                                  BlockState state) {
        return new VeloceFeModuleBlockEntity(module, holderFor(module), pos, state);
    }

    /** Typ block entity dla maszyny - do capability, testow i diagnostyki. */
    public static DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>>
            holderFor(FeModule module) {
        if (module == FeModule.CRUSHER) {
            return CRUSHER_MODULE;
        }
        if (module == FeModule.ENRICHMENT) {
            return ENRICHMENT_MODULE;
        }
        if (module == FeModule.COMBINER) {
            return COMBINER_MODULE;
        }
        if (module == FeModule.SAWMILL) {
            return SAWMILL_MODULE;
        }
        throw new IllegalArgumentException("brak typu block entity dla maszyny " + module.id());
    }
}
