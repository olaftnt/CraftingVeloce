package com.craftingveloce.init;

import com.craftingveloce.block.VeloceCableBlock;
import com.craftingveloce.block.VeloceConnectorBlock;
import com.craftingveloce.block.VeloceTomTerminalBlock;
import com.craftingveloce.block.entity.VeloceConnectorBlockEntity;
import com.craftingveloce.block.entity.VeloceTomTerminalBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
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

    // 1. Veloce Terminal
    public static final DeferredBlock<VeloceTomTerminalBlock> VELOCE_TOM_TERMINAL = BLOCKS.register(
            "veloce_tom_terminal",
            VeloceTomTerminalBlock::new
    );

    public static final DeferredItem<BlockItem> VELOCE_TOM_TERMINAL_ITEM = ITEMS.registerSimpleBlockItem(
            "veloce_tom_terminal",
            VELOCE_TOM_TERMINAL
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceTomTerminalBlockEntity>> VELOCE_TOM_TERMINAL_BE =
            BLOCK_ENTITY_TYPES.register("veloce_tom_terminal", () -> createBEType(
                    (pos, state) -> new VeloceTomTerminalBlockEntity(pos, state),
                    VELOCE_TOM_TERMINAL.get()
            ));

    // 2. Veloce Cable
    public static final DeferredBlock<VeloceCableBlock> VELOCE_CABLE = BLOCKS.register(
            "veloce_cable",
            VeloceCableBlock::new
    );

    public static final DeferredItem<BlockItem> VELOCE_CABLE_ITEM = ITEMS.registerSimpleBlockItem(
            "veloce_cable",
            VELOCE_CABLE
    );

    // 3. Veloce Inventory Connector
    public static final DeferredBlock<VeloceConnectorBlock> VELOCE_CONNECTOR = BLOCKS.register(
            "veloce_connector",
            VeloceConnectorBlock::new
    );

    public static final DeferredItem<BlockItem> VELOCE_CONNECTOR_ITEM = ITEMS.registerSimpleBlockItem(
            "veloce_connector",
            VELOCE_CONNECTOR
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceConnectorBlockEntity>> VELOCE_CONNECTOR_BE =
            BLOCK_ENTITY_TYPES.register("veloce_connector", () -> createBEType(
                    (pos, state) -> new VeloceConnectorBlockEntity(pos, state),
                    VELOCE_CONNECTOR.get()
            ));

    @FunctionalInterface
    public interface BlockEntityFactory<T extends BlockEntity> {
        T create(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state);
    }

    @SuppressWarnings("unchecked")
    private static <T extends BlockEntity> BlockEntityType<T> createBEType(BlockEntityFactory<T> factory, Block block) {
        try {
            Constructor<?>[] constructors = BlockEntityType.class.getDeclaredConstructors();
            for (Constructor<?> c : constructors) {
                if (c.getParameterCount() == 3) {
                    c.setAccessible(true);
                    Object supplier = java.lang.reflect.Proxy.newProxyInstance(
                            BlockEntityType.class.getClassLoader(),
                            new Class<?>[]{Class.forName("net.minecraft.world.level.block.entity.BlockEntityType$BlockEntitySupplier")},
                            (proxy, method, args) -> factory.create(
                                    (net.minecraft.core.BlockPos) args[0],
                                    (net.minecraft.world.level.block.state.BlockState) args[1]
                            )
                    );
                    return (BlockEntityType<T>) c.newInstance(
                            supplier,
                            Set.of(block),
                            null
                    );
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create BlockEntityType", e);
        }
        throw new IllegalStateException("Could not find BlockEntityType constructor");
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
    }
}
