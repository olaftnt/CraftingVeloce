package com.craftingveloce.init;

import com.craftingveloce.block.VelocePipeBlock;
import com.craftingveloce.block.VeloceTomTerminalBlock;
import com.craftingveloce.block.entity.VelocePipeBlockEntity;
import com.craftingveloce.block.entity.VeloceTomTerminalBlockEntity;
import com.craftingveloce.item.VeloceWrenchItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
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

    // 2. Veloce Pipe (Pipez-style smart pipe)
    public static final DeferredBlock<VelocePipeBlock> VELOCE_PIPE = BLOCKS.register(
            "veloce_pipe",
            VelocePipeBlock::new
    );

    public static final DeferredItem<BlockItem> VELOCE_PIPE_ITEM = ITEMS.registerSimpleBlockItem(
            "veloce_pipe",
            VELOCE_PIPE
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VelocePipeBlockEntity>> VELOCE_PIPE_BE =
            BLOCK_ENTITY_TYPES.register("veloce_pipe", () -> createBEType(
                    (pos, state) -> new VelocePipeBlockEntity(pos, state),
                    VELOCE_PIPE.get()
            ));

    public static final DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENU_TYPES =
            DeferredRegister.create(BuiltInRegistries.MENU, MODID);

    // 3. Veloce Extractor (Cube terminal node with 3x3 filter and 3x3 output)
    public static final DeferredBlock<com.craftingveloce.block.VeloceExtractorBlock> VELOCE_EXTRACTOR = BLOCKS.register(
            "veloce_extractor",
            com.craftingveloce.block.VeloceExtractorBlock::new
    );

    public static final DeferredItem<BlockItem> VELOCE_EXTRACTOR_ITEM = ITEMS.registerSimpleBlockItem(
            "veloce_extractor",
            VELOCE_EXTRACTOR
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.craftingveloce.block.entity.VeloceExtractorBlockEntity>> VELOCE_EXTRACTOR_BE =
            BLOCK_ENTITY_TYPES.register("veloce_extractor", () -> createBEType(
                    (pos, state) -> new com.craftingveloce.block.entity.VeloceExtractorBlockEntity(pos, state),
                    VELOCE_EXTRACTOR.get()
            ));

    public static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<com.craftingveloce.inventory.VeloceExtractorMenu>> VELOCE_EXTRACTOR_MENU =
            MENU_TYPES.register("veloce_extractor_menu", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                    (windowId, inv, data) -> new com.craftingveloce.inventory.VeloceExtractorMenu(windowId, inv, data.readBlockPos())
            ));

    // 4. Veloce Wrench
    public static final DeferredItem<VeloceWrenchItem> VELOCE_WRENCH = ITEMS.register(
            "wrench",
            () -> new VeloceWrenchItem(new Item.Properties())
    );


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
        MENU_TYPES.register(modEventBus);
    }
}
