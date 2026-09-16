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
 * Bloki modulu Create.
 *
 * <p>Id blokow maja prefiks moda ({@code veloce_create_*}) - bez tego dwa
 * moduly moga wybrac te sama nazwe i jeden blok nadpisalby drugi (pilnuje tego
 * {@code validate_module_block_ids}).
 *
 * <p>Rejestracja plain {@code DeferredRegister} - NIE uzywamy
 * {@code Create.registrate()} ani {@code CreateRegistrate}: to narzedzie Create
 * dla wlasnych blokow i dla moda spoza Create potrafi rzucic wyjatkiem.
 */
public final class CreateBlocks {

    private CreateBlocks() {
    }

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(com.craftingveloce.CraftingVeloceMod.MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(com.craftingveloce.CraftingVeloceMod.MODID);

    /** Mlyn: receptury {@code create:milling}. */
    public static final DeferredBlock<VeloceKineticModuleBlock> VELOCE_MILLSTONE_MODULE =
            BLOCKS.register("veloce_create_millstone_module",
                    () -> block(CreateKineticModules.MILLING));
    public static final DeferredItem<BlockItem> VELOCE_MILLSTONE_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_create_millstone_module",
                    VELOCE_MILLSTONE_MODULE);

    /** Piła: receptury {@code create:cutting}. */
    public static final DeferredBlock<VeloceKineticModuleBlock> VELOCE_SAW_MODULE =
            BLOCKS.register("veloce_create_saw_module",
                    () -> block(CreateKineticModules.CUTTING));
    public static final DeferredItem<BlockItem> VELOCE_SAW_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_create_saw_module", VELOCE_SAW_MODULE);

    /** Kruszarka: receptury {@code create:crushing} (wyniki losowe). */
    public static final DeferredBlock<VeloceKineticModuleBlock> VELOCE_CRUSHING_MODULE =
            BLOCKS.register("veloce_create_crushing_module",
                    () -> block(CreateKineticModules.CRUSHING));
    public static final DeferredItem<BlockItem> VELOCE_CRUSHING_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_create_crushing_module",
                    VELOCE_CRUSHING_MODULE);

    /** Mechanical crafter: receptury {@code create:mechanical_crafting}. */
    public static final DeferredBlock<VeloceKineticModuleBlock> VELOCE_MECHANICAL_CRAFTER_MODULE =
            BLOCKS.register("veloce_create_mechanical_crafter_module",
                    () -> block(CreateKineticModules.MECHANICAL_CRAFTING));
    public static final DeferredItem<BlockItem> VELOCE_MECHANICAL_CRAFTER_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_create_mechanical_crafter_module",
                    VELOCE_MECHANICAL_CRAFTER_MODULE);

    /** Prasa: receptury {@code create:pressing} (na Basenie). */
    public static final DeferredBlock<VeloceKineticModuleBlock> VELOCE_PRESS_MODULE =
            BLOCKS.register("veloce_create_press_module",
                    () -> block(CreateKineticModules.PRESSING));
    public static final DeferredItem<BlockItem> VELOCE_PRESS_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_create_press_module", VELOCE_PRESS_MODULE);

    /** Mixer: receptury {@code create:mixing} (na Basenie). */
    public static final DeferredBlock<VeloceKineticModuleBlock> VELOCE_MIXER_MODULE =
            BLOCKS.register("veloce_create_mixer_module",
                    () -> block(CreateKineticModules.MIXING));
    public static final DeferredItem<BlockItem> VELOCE_MIXER_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_create_mixer_module", VELOCE_MIXER_MODULE);

    /** Deployer: receptury {@code create:deploying}. */
    public static final DeferredBlock<VeloceKineticModuleBlock> VELOCE_DEPLOYER_MODULE =
            BLOCKS.register("veloce_create_deployer_module",
                    () -> block(CreateKineticModules.DEPLOYING));
    public static final DeferredItem<BlockItem> VELOCE_DEPLOYER_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_create_deployer_module", VELOCE_DEPLOYER_MODULE);

    /** Jedna linia na maszyne: blok + fabryka BE + typ BE z tego modulu. */
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
     * Pozycje do zakladki kreatywnej - wolane TYLKO gdy Create jest obecne
     * (zakladka buduje sie zawsze, takze bez tego moda).
     */
    public static void addCreativeItems(net.minecraft.world.item.CreativeModeTab.Output output) {
        output.accept(VELOCE_MILLSTONE_MODULE_ITEM.get());
        output.accept(VELOCE_SAW_MODULE_ITEM.get());
        // Maszyny z elementami ida do zakladki WYPELNIONE (gracz nie chce pustych).
        output.accept(VELOCE_CRUSHING_MODULE.get().filledStack());
        // Maszyny z elementami ida do zakladki WYPELNIONE (gracz nie chce pustych).
        output.accept(VELOCE_MECHANICAL_CRAFTER_MODULE.get().filledStack());
        output.accept(VELOCE_PRESS_MODULE_ITEM.get());
        output.accept(VELOCE_MIXER_MODULE_ITEM.get());
        output.accept(VELOCE_DEPLOYER_MODULE_ITEM.get());
    }
}
