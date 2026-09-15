package com.craftingveloce.compat.mekanism;

import com.craftingveloce.compat.mekanism.block.entity.VeloceCrusherModuleBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Typy block entity modulu Mekanism. */
public final class MekanismBlockEntities {

    private MekanismBlockEntities() {
    }

    public static final DeferredRegister<BlockEntityType<?>> TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                    com.craftingveloce.CraftingVeloceMod.MODID);

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceCrusherModuleBlockEntity>> CRUSHER_MODULE =
            TYPES.register("veloce_crusher_module", () -> BlockEntityType.Builder.of(
                    VeloceCrusherModuleBlockEntity::new,
                    MekanismBlocks.VELOCE_CRUSHER_MODULE.get()).build(null));

    public static void register(IEventBus modEventBus) {
        TYPES.register(modEventBus);
    }
}
