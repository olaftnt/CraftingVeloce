package com.craftingveloce.compat.mekanism;

import com.craftingveloce.compat.mekanism.block.VeloceCrusherModuleBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Bloki modulu Mekanism (rejestracja plain {@code DeferredRegister}).
 *
 * <p><b>Dlaczego plain, a nie Registrate.</b> Veloce buduje rejestr recznie
 * i tak ma zostac: Registrate to biblioteka Create i uzywanie jej tutaj
 * dolaczyloby obca zaleznosc do naszego kodu (a {@code Create.registrate()}
 * i tak rzuca wyjatek dla innych modow).
 *
 * <p><b>Rejestracja jest warunkowa.</b> Cala ta klasa laduje sie tylko wtedy,
 * gdy Mekanism jest obecny (patrz {@link MekanismCompat}), wiec zaden typ
 * Mekanism nie moze tu wystapic bez konsekwencji - i nie wystepuje: maszyna
 * jest w 100% nasza, a Mekanism dostarcza tylko receptury.
 */
public final class MekanismBlocks {

    private MekanismBlocks() {
    }

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(com.craftingveloce.CraftingVeloceMod.MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(com.craftingveloce.CraftingVeloceMod.MODID);

    /** Kruszarka: receptury rodziny {@code mekanism:crushing}. */
    public static final DeferredBlock<VeloceCrusherModuleBlock> VELOCE_CRUSHER_MODULE =
            BLOCKS.register("veloce_crusher_module",
                    () -> new VeloceCrusherModuleBlock(crusherProperties()));

    public static final DeferredItem<BlockItem> VELOCE_CRUSHER_MODULE_ITEM =
            ITEMS.registerSimpleBlockItem("veloce_crusher_module", VELOCE_CRUSHER_MODULE);

    private static BlockBehaviour.Properties crusherProperties() {
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
     * sprawdzenie obecnosci musi byc przed siegnieciem do tego bloku - inaczej
     * samo budowanie zakladki zaladowaloby klase z typem obcego moda.
     */
    public static void addCreativeItems(net.minecraft.world.item.CreativeModeTab.Output output) {
        output.accept(VELOCE_CRUSHER_MODULE_ITEM.get());
    }
}
