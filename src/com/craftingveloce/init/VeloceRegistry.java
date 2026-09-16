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

    // 3b. Velocity Furnace (zrodlo ciepla dla auto-craftera)
    public static final DeferredBlock<com.craftingveloce.block.VeloceVelocityFurnaceBlock> VELOCITY_FURNACE =
            BLOCKS.register("velocity_furnace",
                    () -> new com.craftingveloce.block.VeloceVelocityFurnaceBlock(
                            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
                                    .strength(3.5f)
                                    .requiresCorrectToolForDrops()));

    public static final DeferredItem<BlockItem> VELOCITY_FURNACE_ITEM = ITEMS.registerSimpleBlockItem(
            "velocity_furnace", VELOCITY_FURNACE);

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity>> VELOCITY_FURNACE_BE =
            BLOCK_ENTITY_TYPES.register("velocity_furnace", () -> createBEType(
                    (pos, state) -> new com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity(pos, state),
                    VELOCITY_FURNACE.get()));

    public static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>,
            net.minecraft.world.inventory.MenuType<com.craftingveloce.inventory.VeloceVelocityFurnaceMenu>> VELOCITY_FURNACE_MENU =
            MENU_TYPES.register("velocity_furnace_menu",
                    () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                            (windowId, inv, data) -> new com.craftingveloce.inventory.VeloceVelocityFurnaceMenu(
                                    windowId, inv, data.readBlockPos())));

    // 3c. Velocity Electric Furnace (zrodlo ciepla na Forge Energy)
    public static final DeferredBlock<com.craftingveloce.block.VeloceElectricFurnaceBlock> ELECTRIC_FURNACE =
            BLOCKS.register("electric_furnace",
                    () -> new com.craftingveloce.block.VeloceElectricFurnaceBlock(
                            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
                                    .strength(3.5f)
                                    .requiresCorrectToolForDrops()));

    public static final DeferredItem<BlockItem> ELECTRIC_FURNACE_ITEM = ITEMS.registerSimpleBlockItem(
            "electric_furnace", ELECTRIC_FURNACE);

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<com.craftingveloce.block.entity.VeloceElectricFurnaceBlockEntity>> ELECTRIC_FURNACE_BE =
            BLOCK_ENTITY_TYPES.register("electric_furnace", () -> createBEType(
                    (pos, state) -> new com.craftingveloce.block.entity.VeloceElectricFurnaceBlockEntity(pos, state),
                    ELECTRIC_FURNACE.get()));

    public static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>,
            net.minecraft.world.inventory.MenuType<com.craftingveloce.inventory.VeloceElectricFurnaceMenu>> ELECTRIC_FURNACE_MENU =
            MENU_TYPES.register("electric_furnace_menu",
                    () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                            (windowId, inv, data) -> new com.craftingveloce.inventory.VeloceElectricFurnaceMenu(
                                    windowId, inv, data.readBlockPos())));

    // 4. Veloce Wrench
    public static final DeferredItem<VeloceWrenchItem> VELOCE_WRENCH = ITEMS.register(
            "wrench",
            () -> new VeloceWrenchItem(new Item.Properties())
    );

    // 5. Veloce Crafting Table (auto-crafter node)
    public static final DeferredBlock<com.craftingveloce.block.VeloceCraftingTableBlock> VELOCE_CRAFTING_TABLE = BLOCKS.register(
            "veloce_crafting_table",
            com.craftingveloce.block.VeloceCraftingTableBlock::new
    );

    public static final DeferredItem<BlockItem> VELOCE_CRAFTING_TABLE_ITEM = ITEMS.registerSimpleBlockItem(
            "veloce_crafting_table",
            VELOCE_CRAFTING_TABLE
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity>> VELOCE_CRAFTING_TABLE_BE =
            BLOCK_ENTITY_TYPES.register("veloce_crafting_table", () -> createBEType(
                    (pos, state) -> new com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity(pos, state),
                    VELOCE_CRAFTING_TABLE.get()
            ));

    // 6. Veloce Controller (monitoring sieci + filtrowanie itemow)
    public static final DeferredBlock<com.craftingveloce.block.VeloceControllerBlock> VELOCE_CONTROLLER = BLOCKS.register(
            "veloce_controller",
            com.craftingveloce.block.VeloceControllerBlock::new
    );

    public static final DeferredItem<BlockItem> VELOCE_CONTROLLER_ITEM = ITEMS.registerSimpleBlockItem(
            "veloce_controller",
            VELOCE_CONTROLLER
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.craftingveloce.block.entity.VeloceControllerBlockEntity>> VELOCE_CONTROLLER_BE =
            BLOCK_ENTITY_TYPES.register("veloce_controller", () -> createBEType(
                    (pos, state) -> new com.craftingveloce.block.entity.VeloceControllerBlockEntity(pos, state),
                    VELOCE_CONTROLLER.get()
            ));


    // 7. Veloce Threshold Sensor (redstone zalezny od stanu sieci)
    public static final DeferredBlock<com.craftingveloce.block.VeloceThresholdSensorBlock> THRESHOLD_SENSOR =
            BLOCKS.register("threshold_sensor",
                    com.craftingveloce.block.VeloceThresholdSensorBlock::new);

    public static final DeferredItem<BlockItem> THRESHOLD_SENSOR_ITEM = ITEMS.registerSimpleBlockItem(
            "threshold_sensor",
            THRESHOLD_SENSOR);

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<com.craftingveloce.block.entity.VeloceThresholdSensorBlockEntity>> THRESHOLD_SENSOR_BE =
            BLOCK_ENTITY_TYPES.register("threshold_sensor", () -> createBEType(
                    (pos, state) -> new com.craftingveloce.block.entity.VeloceThresholdSensorBlockEntity(pos, state),
                    THRESHOLD_SENSOR.get()));

    public static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>,
            net.minecraft.world.inventory.MenuType<com.craftingveloce.inventory.VeloceThresholdSensorMenu>> THRESHOLD_SENSOR_MENU =
            MENU_TYPES.register("threshold_sensor_menu",
                    () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                            (windowId, inv, data) -> new com.craftingveloce.inventory.VeloceThresholdSensorMenu(
                                    windowId, inv, data.readBlockPos())));

    // 9. Veloce Integrale (ozdobna klatka: tylko krawedzie, pusty srodek)
    public static final DeferredBlock<com.craftingveloce.block.VeloceIntegraleBlock> VELOCE_INTEGRALE =
            BLOCKS.register("veloce_integrale",
                    () -> new com.craftingveloce.block.VeloceIntegraleBlock(
                            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
                                    .mapColor(net.minecraft.world.level.material.MapColor.METAL)
                                    .sound(net.minecraft.world.level.block.SoundType.METAL)
                                    .strength(2.0F)
                                    .noOcclusion()
                                    .isViewBlocking((state, world, pos) -> false)
                                    .isSuffocating((state, world, pos) -> false)
                                    .requiresCorrectToolForDrops()));

    public static final DeferredItem<BlockItem> VELOCE_INTEGRALE_ITEM = ITEMS.registerSimpleBlockItem(
            "veloce_integrale",
            VELOCE_INTEGRALE);

    @FunctionalInterface
    public interface BlockEntityFactory<T extends BlockEntity> {
        T create(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state);
    }

    private static <T extends BlockEntity> BlockEntityType<T> createBEType(BlockEntityFactory<T> factory, Block block) {
        // Standardowe API NeoForge, zamiast refleksji.
        //
        // Wczesniej szukalismy tu konstruktora BlockEntityType przez refleksje
        // i bralismy PIERWSZY trójargumentowy, a kolejnosc zwracana przez
        // getDeclaredConstructors() nie jest gwarantowana przez specyfikacje.
        // Do tego dochodzil dynamiczny Proxy. To dzialalo przypadkiem i moglo
        // peknac przy kazdej aktualizacji Minecrafta/NeoForge - a wtedy mod
        // nie wstaje wcale. Builder.of() jest publicznym API i robi to samo.
        return BlockEntityType.Builder.of(factory::create, block).build(null);
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        MENU_TYPES.register(modEventBus);
    }
}
