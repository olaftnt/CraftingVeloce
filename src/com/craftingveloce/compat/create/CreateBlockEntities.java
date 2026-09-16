package com.craftingveloce.compat.create;

import com.craftingveloce.compat.create.block.entity.VeloceKineticModuleBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Typy block entity modulu Create - po jednym na maszyne.
 *
 * <p>Klasa BE jest jedna ({@link VeloceKineticModuleBlockEntity}), a typ jest
 * potrzebny per blok (tak dziala rejestr). Mapowanie maszyna -> typ zyje
 * w JEDNYM miejscu ({@link #holderFor}).
 */
public final class CreateBlockEntities {

    private CreateBlockEntities() {
    }

    public static final DeferredRegister<BlockEntityType<?>> TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                    com.craftingveloce.CraftingVeloceMod.MODID);

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceKineticModuleBlockEntity>> MILLSTONE_MODULE =
            TYPES.register("veloce_create_millstone_module",
                    () -> BlockEntityType.Builder.of(
                            (pos, state) -> create(CreateKineticModules.MILLING, pos, state),
                            CreateBlocks.VELOCE_MILLSTONE_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceKineticModuleBlockEntity>> SAW_MODULE =
            TYPES.register("veloce_create_saw_module",
                    () -> BlockEntityType.Builder.of(
                            (pos, state) -> create(CreateKineticModules.CUTTING, pos, state),
                            CreateBlocks.VELOCE_SAW_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceKineticModuleBlockEntity>> CRUSHING_MODULE =
            TYPES.register("veloce_create_crushing_module",
                    () -> BlockEntityType.Builder.of(
                            (pos, state) -> create(CreateKineticModules.CRUSHING, pos, state),
                            CreateBlocks.VELOCE_CRUSHING_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceKineticModuleBlockEntity>> MECHANICAL_CRAFTER_MODULE =
            TYPES.register("veloce_create_mechanical_crafter_module",
                    () -> BlockEntityType.Builder.of(
                            (pos, state) -> create(
                                    CreateKineticModules.MECHANICAL_CRAFTING, pos, state),
                            CreateBlocks.VELOCE_MECHANICAL_CRAFTER_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceKineticModuleBlockEntity>> PRESS_MODULE =
            TYPES.register("veloce_create_press_module",
                    () -> BlockEntityType.Builder.of(
                            (pos, state) -> create(CreateKineticModules.PRESSING, pos, state),
                            CreateBlocks.VELOCE_PRESS_MODULE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceKineticModuleBlockEntity>> MIXER_MODULE =
            TYPES.register("veloce_create_mixer_module",
                    () -> BlockEntityType.Builder.of(
                            (pos, state) -> create(CreateKineticModules.MIXING, pos, state),
                            CreateBlocks.VELOCE_MIXER_MODULE.get()).build(null));

    public static void register(IEventBus modEventBus) {
        TYPES.register(modEventBus);
    }

    /** Tworzy block entity wlasciwego typu dla danej maszyny. */
    public static VeloceKineticModuleBlockEntity create(KineticModule module,
                                                        net.minecraft.core.BlockPos pos,
                                                        net.minecraft.world.level.block.state.BlockState state) {
        return new VeloceKineticModuleBlockEntity(module, holderFor(module).get(), pos, state);
    }

    /** Typ block entity dla maszyny - do tworzenia BE i diagnostyki. */
    public static DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceKineticModuleBlockEntity>>
            holderFor(KineticModule module) {
        if (module == CreateKineticModules.MILLING) {
            return MILLSTONE_MODULE;
        }
        if (module == CreateKineticModules.CUTTING) {
            return SAW_MODULE;
        }
        if (module == CreateKineticModules.CRUSHING) {
            return CRUSHING_MODULE;
        }
        if (module == CreateKineticModules.MECHANICAL_CRAFTING) {
            return MECHANICAL_CRAFTER_MODULE;
        }
        if (module == CreateKineticModules.PRESSING) {
            return PRESS_MODULE;
        }
        if (module == CreateKineticModules.MIXING) {
            return MIXER_MODULE;
        }
        throw new IllegalArgumentException("brak typu block entity dla maszyny " + module.id());
    }
}
