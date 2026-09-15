package com.craftingveloce.init;

import com.craftingveloce.block.VeloceTomTerminalBlock;
import com.craftingveloce.block.entity.VeloceTomTerminalBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.lang.reflect.Constructor;
import java.util.Set;

public class VeloceRegistry {
    public static final String MODID = "craftingveloce";

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, MODID);

    public static final DeferredBlock<VeloceTomTerminalBlock> VELOCE_TOM_TERMINAL = BLOCKS.register(
            "veloce_tom_terminal",
            VeloceTomTerminalBlock::new
    );

    public static final DeferredItem<BlockItem> VELOCE_TOM_TERMINAL_ITEM = ITEMS.registerSimpleBlockItem(
            "veloce_tom_terminal",
            VELOCE_TOM_TERMINAL
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceTomTerminalBlockEntity>> VELOCE_TOM_TERMINAL_BE =
            BLOCK_ENTITY_TYPES.register("veloce_tom_terminal", () -> createBEType());

    @SuppressWarnings("unchecked")
    private static BlockEntityType<VeloceTomTerminalBlockEntity> createBEType() {
        try {
            // Use reflection on the 3-arg constructor
            Constructor<?>[] constructors = BlockEntityType.class.getDeclaredConstructors();
            for (Constructor<?> c : constructors) {
                if (c.getParameterCount() == 3) {
                    c.setAccessible(true);
                    Object supplier = java.lang.reflect.Proxy.newProxyInstance(
                            BlockEntityType.class.getClassLoader(),
                            new Class<?>[]{Class.forName("net.minecraft.world.level.block.entity.BlockEntityType$BlockEntitySupplier")},
                            (proxy, method, args) -> new VeloceTomTerminalBlockEntity(
                                    (net.minecraft.core.BlockPos) args[0],
                                    (net.minecraft.world.level.block.state.BlockState) args[1]
                            )
                    );
                    return (BlockEntityType<VeloceTomTerminalBlockEntity>) c.newInstance(
                            supplier,
                            Set.of(VELOCE_TOM_TERMINAL.get()),
                            null
                    );
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create VeloceTomTerminalBlockEntity type", e);
        }
        throw new IllegalStateException("Could not find BlockEntityType constructor");
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
    }
}
