package com.craftingveloce.compat.mekanism;

import com.craftingveloce.compat.VeloceMods;
import net.neoforged.bus.api.IEventBus;

/**
 * Bramka integracji z Mekanism.
 *
 * <p><b>Izolacja.</b> Ladowana ZAWSZE, takze bez Mekanism - zero typow
 * Mekanism w polach i sygnaturach. {@link #register(IEventBus)} siega do klas
 * z obcymi typami, ale tylko po {@link #isPresent()}.
 */
public final class MekanismCompat {

    private MekanismCompat() {
    }

    /** Czy Mekanism jest obecny. Bezpieczne takze bez niego. */
    public static boolean isPresent() {
        return VeloceMods.MEKANISM.isLoaded();
    }

    /**
     * Rejestruje integracje. Wolno wolac WYLACZNIE gdy {@link #isPresent()}.
     */
    public static void register(IEventBus modEventBus) {
        MekanismBlocks.register(modEventBus);
        MekanismBlockEntities.register(modEventBus);
        MekanismCapabilities.register(modEventBus);
        MekanismRecipeFamily.register(modEventBus);
        MekanismModule.register(modEventBus);
    }

    /**
     * Pozycje modulu do zakladki kreatywnej.
     *
     * <p>Zakladka buduje sie ZAWSZE (takze bez Mekanism), wiec to wywolanie
     * jest warunkowane obecnoscia moda w {@code CraftingVeloceMod} - inaczej
     * samo budowanie zakladki zaladowaloby klase z obcym typem.
     */
    public static void addCreativeItems(net.minecraft.world.item.CreativeModeTab.Output output) {
        MekanismBlocks.addCreativeItems(output);
    }
}
