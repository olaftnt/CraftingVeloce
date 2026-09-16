package com.craftingveloce.compat.mekanism;

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
            TYPES.register("veloce_mekanism_crusher_module", () -> BlockEntityType.Builder.of(
                    (pos, state) -> factory(MekanismFeModules.CRUSHER).create(pos, state),
                    MekanismBlocks.VELOCE_CRUSHER_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceFeModuleBlockEntity>> ENRICHMENT_MODULE =
            TYPES.register("veloce_mekanism_enrichment_module", () -> BlockEntityType.Builder.of(
                    (pos, state) -> factory(MekanismFeModules.ENRICHMENT).create(pos, state),
                    MekanismBlocks.VELOCE_ENRICHMENT_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceFeModuleBlockEntity>> COMBINER_MODULE =
            TYPES.register("veloce_mekanism_combiner_module", () -> BlockEntityType.Builder.of(
                    (pos, state) -> factory(MekanismFeModules.COMBINER).create(pos, state),
                    MekanismBlocks.VELOCE_COMBINER_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceFeModuleBlockEntity>> SAWMILL_MODULE =
            TYPES.register("veloce_mekanism_sawmill_module", () -> BlockEntityType.Builder.of(
                    (pos, state) -> factory(MekanismFeModules.SAWMILL).create(pos, state),
                    MekanismBlocks.VELOCE_SAWMILL_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> SMELTING_MODULE =
            TYPES.register("veloce_mekanism_energized_smelter_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.SMELTING).create(pos, state),
                            MekanismBlocks.VELOCE_SMELTING_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> COMPRESSING_MODULE =
            TYPES.register("veloce_mekanism_osmium_compressor_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.COMPRESSING).create(pos, state),
                            MekanismBlocks.VELOCE_COMPRESSING_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> METALLURGIC_INFUSING_MODULE =
            TYPES.register("veloce_mekanism_metallurgic_infuser_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.METALLURGIC_INFUSING).create(pos, state),
                            MekanismBlocks.VELOCE_METALLURGIC_INFUSING_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> PURIFYING_MODULE =
            TYPES.register("veloce_mekanism_purification_chamber_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.PURIFYING).create(pos, state),
                            MekanismBlocks.VELOCE_PURIFYING_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> INJECTING_MODULE =
            TYPES.register("veloce_mekanism_chemical_injection_chamber_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.INJECTING).create(pos, state),
                            MekanismBlocks.VELOCE_INJECTING_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> CRYSTALLIZING_MODULE =
            TYPES.register("veloce_mekanism_chemical_crystallizer_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.CRYSTALLIZING).create(pos, state),
                            MekanismBlocks.VELOCE_CRYSTALLIZING_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> DISSOLUTION_MODULE =
            TYPES.register("veloce_mekanism_chemical_dissolution_chamber_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.DISSOLUTION).create(pos, state),
                            MekanismBlocks.VELOCE_DISSOLUTION_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> WASHING_MODULE =
            TYPES.register("veloce_mekanism_chemical_washer_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.WASHING).create(pos, state),
                            MekanismBlocks.VELOCE_WASHING_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> SEPARATING_MODULE =
            TYPES.register("veloce_mekanism_electrolytic_separator_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.SEPARATING).create(pos, state),
                            MekanismBlocks.VELOCE_SEPARATING_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> REACTION_MODULE =
            TYPES.register("veloce_mekanism_pressurized_reaction_chamber_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.REACTION).create(pos, state),
                            MekanismBlocks.VELOCE_REACTION_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> ROTARY_MODULE =
            TYPES.register("veloce_mekanism_rotary_condensentrator_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.ROTARY).create(pos, state),
                            MekanismBlocks.VELOCE_ROTARY_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> ACTIVATING_MODULE =
            TYPES.register("veloce_mekanism_solar_neutron_activator_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.ACTIVATING).create(pos, state),
                            MekanismBlocks.VELOCE_ACTIVATING_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> CENTRIFUGING_MODULE =
            TYPES.register("veloce_mekanism_isotopic_centrifuge_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.CENTRIFUGING).create(pos, state),
                            MekanismBlocks.VELOCE_CENTRIFUGING_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> NUCLEOSYNTHESIZING_MODULE =
            TYPES.register("veloce_mekanism_antiprotonic_nucleosynthesizer_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.NUCLEOSYNTHESIZING).create(pos, state),
                            MekanismBlocks.VELOCE_NUCLEOSYNTHESIZING_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> PIGMENT_EXTRACTING_MODULE =
            TYPES.register("veloce_mekanism_pigment_extractor_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.PIGMENT_EXTRACTING).create(pos, state),
                            MekanismBlocks.VELOCE_PIGMENT_EXTRACTING_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> PIGMENT_MIXING_MODULE =
            TYPES.register("veloce_mekanism_pigment_mixer_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.PIGMENT_MIXING).create(pos, state),
                            MekanismBlocks.VELOCE_PIGMENT_MIXING_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> PAINTING_MODULE =
            TYPES.register("veloce_mekanism_painting_machine_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.PAINTING).create(pos, state),
                            MekanismBlocks.VELOCE_PAINTING_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> OXIDIZING_MODULE =
            TYPES.register("veloce_mekanism_chemical_oxidizer_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.OXIDIZING).create(pos, state),
                            MekanismBlocks.VELOCE_OXIDIZING_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> CHEMICAL_INFUSING_MODULE =
            TYPES.register("veloce_mekanism_chemical_infuser_module",
                    () -> BlockEntityType.Builder.of((pos, state) -> factory(MekanismFeModules.CHEMICAL_INFUSING).create(pos, state),
                            MekanismBlocks.VELOCE_CHEMICAL_INFUSING_MODULE.get()).build(null));


    public static void register(IEventBus modEventBus) {
        TYPES.register(modEventBus);
    }

    /**
     * Fabryka block entity dla danej maszyny.
     *
     * <p>Blok dostaje ja w konstruktorze, wiec nie musi znac czterech holderow
     * ani siegac do nich po nazwie - a mapowanie maszyna -> typ zyje w JEDNYM
     * miejscu ({@link #holderFor}).
     */
    public static VeloceFeModuleBlockEntity.Factory factory(FeModule module) {
        return (pos, state) -> new VeloceFeModuleBlockEntity(module, holderFor(module), pos, state);
    }

    /** Typ block entity dla maszyny - do capability, testow i diagnostyki. */
    public static DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>>
            holderFor(FeModule module) {
        if (module == MekanismFeModules.CRUSHER) {
            return CRUSHER_MODULE;
        }
        if (module == MekanismFeModules.ENRICHMENT) {
            return ENRICHMENT_MODULE;
        }
        if (module == MekanismFeModules.COMBINER) {
            return COMBINER_MODULE;
        }
        if (module == MekanismFeModules.SAWMILL) {
            return SAWMILL_MODULE;
        }
        
        if (module == MekanismFeModules.SMELTING) {
            return SMELTING_MODULE;
        }
        if (module == MekanismFeModules.COMPRESSING) {
            return COMPRESSING_MODULE;
        }
        if (module == MekanismFeModules.METALLURGIC_INFUSING) {
            return METALLURGIC_INFUSING_MODULE;
        }
        if (module == MekanismFeModules.PURIFYING) {
            return PURIFYING_MODULE;
        }
        if (module == MekanismFeModules.INJECTING) {
            return INJECTING_MODULE;
        }
        if (module == MekanismFeModules.CRYSTALLIZING) {
            return CRYSTALLIZING_MODULE;
        }
        if (module == MekanismFeModules.DISSOLUTION) {
            return DISSOLUTION_MODULE;
        }
        if (module == MekanismFeModules.WASHING) {
            return WASHING_MODULE;
        }
        if (module == MekanismFeModules.SEPARATING) {
            return SEPARATING_MODULE;
        }
        if (module == MekanismFeModules.REACTION) {
            return REACTION_MODULE;
        }
        if (module == MekanismFeModules.ROTARY) {
            return ROTARY_MODULE;
        }
        if (module == MekanismFeModules.ACTIVATING) {
            return ACTIVATING_MODULE;
        }
        if (module == MekanismFeModules.CENTRIFUGING) {
            return CENTRIFUGING_MODULE;
        }
        if (module == MekanismFeModules.NUCLEOSYNTHESIZING) {
            return NUCLEOSYNTHESIZING_MODULE;
        }
        if (module == MekanismFeModules.PIGMENT_EXTRACTING) {
            return PIGMENT_EXTRACTING_MODULE;
        }
        if (module == MekanismFeModules.PIGMENT_MIXING) {
            return PIGMENT_MIXING_MODULE;
        }
        if (module == MekanismFeModules.PAINTING) {
            return PAINTING_MODULE;
        }
        if (module == MekanismFeModules.OXIDIZING) {
            return OXIDIZING_MODULE;
        }
        if (module == MekanismFeModules.CHEMICAL_INFUSING) {
            return CHEMICAL_INFUSING_MODULE;
        }
        throw new IllegalArgumentException("brak typu block entity dla maszyny " + module.id());
    }
}
