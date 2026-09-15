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

    /** Rejestruje integracje. Wolno wolac WYLACZNIE gdy {@link #isPresent()}. */
    public static void register(IEventBus modEventBus) {
        MekanismRecipeFamily.register(modEventBus);
    }
}
