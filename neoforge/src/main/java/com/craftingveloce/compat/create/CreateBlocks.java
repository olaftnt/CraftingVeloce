package com.craftingveloce.compat.create;

import com.craftingveloce.compat.create.block.VeloceKineticModuleBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Create module blocks.
 *
 * <p>Block ids carry the mod prefix ({@code veloce_create_*}) - without it two
 * modules could pick the same name and one block would overwrite the other (this is
 * enforced by {@code validate_module_block_ids}).
 *
 * <p>Registration via a plain {@code DeferredRegister} - we do NOT use
 * {@code Create.registrate()} or {@code CreateRegistrate}: that is a Create tool
 * for its own blocks and for a mod outside Create it can throw an exception.
 */
public final class CreateBlocks {

    private CreateBlocks() {
    }

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(com.craftingveloce.CraftingVeloceMod.MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(com.craftingveloce.CraftingVeloceMod.MODID);

    /** Millstone: {@code create:milling} recipes. */
    public static final DeferredBlock<VeloceKineticModuleBlock> VELOCE_MILLSTONE_MODULE =
            BLOCKS.register("veloce_create_millstone_module",
                    () -> block(CreateKineticModules.MILLING));
    public static final DeferredItem<BlockItem> VELOCE_MILLSTONE_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_create_millstone_module",
                    VELOCE_MILLSTONE_MODULE);

    /** Saw: {@code create:cutting} recipes. */
    public static final DeferredBlock<VeloceKineticModuleBlock> VELOCE_SAW_MODULE =
            BLOCKS.register("veloce_create_saw_module",
                    () -> block(CreateKineticModules.CUTTING));
    public static final DeferredItem<BlockItem> VELOCE_SAW_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_create_saw_module", VELOCE_SAW_MODULE);

    /** Crusher: {@code create:crushing} recipes (random outputs). */
    public static final DeferredBlock<VeloceKineticModuleBlock> VELOCE_CRUSHING_MODULE =
            BLOCKS.register("veloce_create_crushing_module",
                    () -> block(CreateKineticModules.CRUSHING));
    public static final DeferredItem<BlockItem> VELOCE_CRUSHING_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_create_crushing_module",
                    VELOCE_CRUSHING_MODULE);

    /** Mechanical crafter: {@code create:mechanical_crafting} recipes. */
    public static final DeferredBlock<VeloceKineticModuleBlock> VELOCE_MECHANICAL_CRAFTER_MODULE =
            BLOCKS.register("veloce_create_mechanical_crafter_module",
                    () -> block(CreateKineticModules.MECHANICAL_CRAFTING));
    public static final DeferredItem<BlockItem> VELOCE_MECHANICAL_CRAFTER_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_create_mechanical_crafter_module",
                    VELOCE_MECHANICAL_CRAFTER_MODULE);

    /** Press: {@code create:pressing} recipes (on a Basin). */
    public static final DeferredBlock<VeloceKineticModuleBlock> VELOCE_PRESS_MODULE =
            BLOCKS.register("veloce_create_press_module",
                    () -> block(CreateKineticModules.PRESSING));
    public static final DeferredItem<BlockItem> VELOCE_PRESS_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_create_press_module", VELOCE_PRESS_MODULE);

    /** Mixer: {@code create:mixing} recipes (on a Basin). */
    public static final DeferredBlock<VeloceKineticModuleBlock> VELOCE_MIXER_MODULE =
            BLOCKS.register("veloce_create_mixer_module",
                    () -> block(CreateKineticModules.MIXING));
    public static final DeferredItem<BlockItem> VELOCE_MIXER_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_create_mixer_module", VELOCE_MIXER_MODULE);

    /** Deployer: {@code create:deploying} recipes. */
    public static final DeferredBlock<VeloceKineticModuleBlock> VELOCE_DEPLOYER_MODULE =
            BLOCKS.register("veloce_create_deployer_module",
                    () -> block(CreateKineticModules.DEPLOYING));
    public static final DeferredItem<BlockItem> VELOCE_DEPLOYER_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_create_deployer_module", VELOCE_DEPLOYER_MODULE);

    /** One line per machine: block + BE factory + BE type from this module. */
    private static VeloceKineticModuleBlock block(KineticModule module) {
        return new VeloceKineticModuleBlock(module,
                (pos, state) -> CreateBlockEntities.create(module, pos, state),
                () -> CreateBlockEntities.holderFor(module).get(),
                properties());
    }

    private static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .sound(SoundType.METAL)
                .strength(3.5F)
                .requiresCorrectToolForDrops()
        .noOcclusion()
        .isViewBlocking((state, world, pos) -> false)
        .isSuffocating((state, world, pos) -> false);
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }

    /**
     * Items for the creative tab - called ONLY when Create is present
     * (the tab is always built, also without that mod).
     */
    public static void addCreativeItems(net.minecraft.world.item.CreativeModeTab.Output output) {
        output.accept(VELOCE_MILLSTONE_MODULE_ITEM.get());
        output.accept(VELOCE_SAW_MODULE_ITEM.get());
        // Machines with components go to the assembled tab (the player does not want empty ones).
        output.accept(VELOCE_CRUSHING_MODULE.get().filledStack());
        // Machines with components go to the assembled tab (the player does not want empty ones).
        output.accept(VELOCE_MECHANICAL_CRAFTER_MODULE.get().filledStack());
        output.accept(VELOCE_PRESS_MODULE_ITEM.get());
        output.accept(VELOCE_MIXER_MODULE_ITEM.get());
        output.accept(VELOCE_DEPLOYER_MODULE_ITEM.get());
    }
}
