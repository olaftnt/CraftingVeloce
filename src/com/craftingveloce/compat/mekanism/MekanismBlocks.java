package com.craftingveloce.compat.mekanism;

import com.craftingveloce.block.VeloceFeModuleBlock;
import com.craftingveloce.crafting.FeModule;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Bloki modulu Mekanism (rejestracja plain {@code DeferredRegister}, bez
 * Registrate - to biblioteka Create i dolaczylaby obca zaleznosc).
 *
 * <p><b>Cztery maszyny, dwie linie kazda.</b> Wszystkie dziela jeden blok
 * ({@link VeloceFeModuleBlock}) i jeden block entity; rozni je wylacznie
 * {@link FeModule}. Dodanie kolejnej maszyny itemowej to jeden wiersz tutaj
 * plus jeden w {@link FeModule} i jeden w {@link MekanismBlockEntities}.
 *
 * <p>Ta klasa laduje sie tylko przy obecnym Mekanism (patrz
 * {@link MekanismCompat}) i nie zawiera zadnego typu Mekanism - maszyna jest
 * w calosci nasza.
 */
public final class MekanismBlocks {

    private MekanismBlocks() {
    }

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(com.craftingveloce.CraftingVeloceMod.MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(com.craftingveloce.CraftingVeloceMod.MODID);

    /** Kruszarka: receptury {@code mekanism:crushing}. */
    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_CRUSHER_MODULE =
            BLOCKS.register("veloce_mekanism_crusher_module",
                    () -> block(MekanismFeModules.CRUSHER));
    public static final DeferredItem<BlockItem> VELOCE_CRUSHER_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_mekanism_crusher_module", VELOCE_CRUSHER_MODULE);

    /** Wzbogacanie: receptury {@code mekanism:enriching}. */
    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_ENRICHMENT_MODULE =
            BLOCKS.register("veloce_mekanism_enrichment_module",
                    () -> block(MekanismFeModules.ENRICHMENT));
    public static final DeferredItem<BlockItem> VELOCE_ENRICHMENT_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_mekanism_enrichment_module", VELOCE_ENRICHMENT_MODULE);

    /** Laczenie: receptury {@code mekanism:combining} (dwa wejscia). */
    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_COMBINER_MODULE =
            BLOCKS.register("veloce_mekanism_combiner_module",
                    () -> block(MekanismFeModules.COMBINER));
    public static final DeferredItem<BlockItem> VELOCE_COMBINER_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_mekanism_combiner_module", VELOCE_COMBINER_MODULE);

    /** Pilowanie: receptury {@code mekanism:sawing} (wynik losowy). */
    public static final DeferredBlock<VeloceFeModuleBlock> VELOCE_SAWMILL_MODULE =
            BLOCKS.register("veloce_mekanism_sawmill_module",
                    () -> block(MekanismFeModules.SAWMILL));
    public static final DeferredItem<BlockItem> VELOCE_SAWMILL_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_mekanism_sawmill_module", VELOCE_SAWMILL_MODULE);

    /** Jedna linia na maszyne: blok rdzenia + fabryka BE z tego modulu. */
    private static VeloceFeModuleBlock block(FeModule module) {
        return new VeloceFeModuleBlock(module, MekanismBlockEntities.factory(module),
                properties());
    }

    private static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .sound(SoundType.METAL)
                .strength(3.5F)
                .requiresCorrectToolForDrops();
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }

    /**
     * Pozycje do zakladki kreatywnej - wolane TYLKO gdy Mekanism jest obecny.
     *
     * <p>Zakladka kreatywna buduje sie ZAWSZE, takze bez tego moda, wiec
     * sprawdzenie obecnosci musi byc przed siegnieciem do tych blokow - inaczej
     * samo budowanie zakladki zaladowaloby klase z typem obcego moda.
     */
    public static void addCreativeItems(net.minecraft.world.item.CreativeModeTab.Output output) {
        output.accept(VELOCE_CRUSHER_MODULE_ITEM.get());
        output.accept(VELOCE_ENRICHMENT_MODULE_ITEM.get());
        output.accept(VELOCE_COMBINER_MODULE_ITEM.get());
        output.accept(VELOCE_SAWMILL_MODULE_ITEM.get());
    }
}
