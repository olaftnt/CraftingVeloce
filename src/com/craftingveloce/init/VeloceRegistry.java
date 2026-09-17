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

    // 3b. Velocity Furnace (heat source for the auto-crafter)
    public static final DeferredBlock<com.craftingveloce.block.VeloceVelocityFurnaceBlock> VELOCITY_FURNACE =
            BLOCKS.register("velocity_furnace",
                    () -> new com.craftingveloce.block.VeloceVelocityFurnaceBlock(
                            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
        .noOcclusion()
        .isViewBlocking((state, world, pos) -> false)
        .isSuffocating((state, world, pos) -> false)
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

    // 3c. Velocity Electric Furnace (heat source running on Forge Energy)
    public static final DeferredBlock<com.craftingveloce.block.VeloceBrewingStandBlock> BREWING_STAND =
            BLOCKS.register("brewing_stand",
                    () -> new com.craftingveloce.block.VeloceBrewingStandBlock(
                            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
        .noOcclusion()
        .isViewBlocking((state, world, pos) -> false)
        .isSuffocating((state, world, pos) -> false)
                                    .strength(3.5f)
                                    .requiresCorrectToolForDrops()));

    public static final DeferredItem<BlockItem> BREWING_STAND_ITEM = ITEMS.registerSimpleBlockItem(
            "brewing_stand", BREWING_STAND);

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<com.craftingveloce.block.entity.VeloceBrewingStandBlockEntity>> BREWING_STAND_BE =
            BLOCK_ENTITY_TYPES.register("brewing_stand", () -> createBEType(
                    (pos, state) -> new com.craftingveloce.block.entity.VeloceBrewingStandBlockEntity(pos, state),
                    BREWING_STAND.get()));

    public static final DeferredBlock<com.craftingveloce.block.VeloceElectricFurnaceBlock> ELECTRIC_FURNACE =
            BLOCKS.register("electric_furnace",
                    () -> new com.craftingveloce.block.VeloceElectricFurnaceBlock(
                            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
        .noOcclusion()
        .isViewBlocking((state, world, pos) -> false)
        .isSuffocating((state, world, pos) -> false)
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
                    VELOCE_CRAFTING_TABLE.get()));

    // 6. Veloce Controller (network monitoring + item filtering)
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


    // 7. Veloce Threshold Sensor (redstone driven by network state)
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

    // 9. Veloce Integrale (decorative cage: edges only, hollow centre)
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

    public static final DeferredItem<BlockItem> VELOCE_INTEGRALE_ITEM = ITEMS.register(
            "veloce_integrale",
            () -> new com.craftingveloce.item.VeloceIntegraleItem(
                    VELOCE_INTEGRALE.get(), new Item.Properties()));

    // Machine window (right click) - a plain container, like a furnace.
    public static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>,
            net.minecraft.world.inventory.MenuType<com.craftingveloce.inventory.VeloceModuleMenu>> VELOCE_MODULE_MENU =
            MENU_TYPES.register("veloce_module_menu",
                    () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                            (windowId, inv, data) -> new com.craftingveloce.inventory.VeloceModuleMenu(
                                    windowId, inv, data.readBlockPos())));

    // KINETIC (Create) machine window - a separate type, without the battery slot.
    public static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>,
            net.minecraft.world.inventory.MenuType<com.craftingveloce.inventory.VeloceKineticMenu>> VELOCE_KINETIC_MENU =
            MENU_TYPES.register("veloce_kinetic_menu",
                    () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                            (windowId, inv, data) -> new com.craftingveloce.inventory.VeloceKineticMenu(
                                    windowId, inv, data.readBlockPos())));

    public static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>,
            net.minecraft.world.inventory.MenuType<com.craftingveloce.inventory.VeloceBrewingStandMenu>> BREWING_STAND_MENU =
            MENU_TYPES.register("brewing_stand_menu",
                    () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                            (windowId, inv, data) -> new com.craftingveloce.inventory.VeloceBrewingStandMenu(
                                    windowId, inv, data.readBlockPos())));

    @FunctionalInterface
    public interface BlockEntityFactory<T extends BlockEntity> {
        T create(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state);
    }

    private static <T extends BlockEntity> BlockEntityType<T> createBEType(
            BlockEntityFactory<T> factory, Block... blocks) {
        // Standard NeoForge API, instead of reflection.
        //
        // Previously we looked up the BlockEntityType constructor here via
        // reflection and took the FIRST three-argument one, while the order
        // returned by getDeclaredConstructors() is not guaranteed by the
        // specification. On top of that there was a dynamic Proxy. It worked by
        // accident and could break with any Minecraft/NeoForge update - and then
        // the mod would not start at all. Builder.of() is public API and does
        // the same thing.
        return BlockEntityType.Builder.of(factory::create, blocks).build(null);
    }

        // Dummy potions for auto-crafting
    public static final DeferredItem<Item> POTION_WATER = ITEMS.register("potion_water", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_AWKWARD = ITEMS.register("potion_awkward", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_MUNDANE = ITEMS.register("potion_mundane", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_THICK = ITEMS.register("potion_thick", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_NIGHT_VISION = ITEMS.register("potion_night_vision", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_INVISIBILITY = ITEMS.register("potion_invisibility", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_LEAPING = ITEMS.register("potion_leaping", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_FIRE_RESISTANCE = ITEMS.register("potion_fire_resistance", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_SWIFTNESS = ITEMS.register("potion_swiftness", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_SLOWNESS = ITEMS.register("potion_slowness", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_WATER_BREATHING = ITEMS.register("potion_water_breathing", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_HEALING = ITEMS.register("potion_healing", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_HARMING = ITEMS.register("potion_harming", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_POISON = ITEMS.register("potion_poison", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_REGENERATION = ITEMS.register("potion_regeneration", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_STRENGTH = ITEMS.register("potion_strength", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_WEAKNESS = ITEMS.register("potion_weakness", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_SLOW_FALLING = ITEMS.register("potion_slow_falling", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> POTION_TURTLE_MASTER = ITEMS.register("potion_turtle_master", () -> new Item(new Item.Properties()));

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        modEventBus.addListener(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent.class, event -> {
            com.craftingveloce.util.VelocePotionMapper.registerProxy("water", POTION_WATER.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("awkward", POTION_AWKWARD.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("mundane", POTION_MUNDANE.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("thick", POTION_THICK.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("night_vision", POTION_NIGHT_VISION.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("invisibility", POTION_INVISIBILITY.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("leaping", POTION_LEAPING.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("fire_resistance", POTION_FIRE_RESISTANCE.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("swiftness", POTION_SWIFTNESS.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("slowness", POTION_SLOWNESS.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("water_breathing", POTION_WATER_BREATHING.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("healing", POTION_HEALING.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("harming", POTION_HARMING.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("poison", POTION_POISON.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("regeneration", POTION_REGENERATION.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("strength", POTION_STRENGTH.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("weakness", POTION_WEAKNESS.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("slow_falling", POTION_SLOW_FALLING.get());
            com.craftingveloce.util.VelocePotionMapper.registerProxy("turtle_master", POTION_TURTLE_MASTER.get());
        });

        BLOCK_ENTITY_TYPES.register(modEventBus);
        MENU_TYPES.register(modEventBus);
    }
}
