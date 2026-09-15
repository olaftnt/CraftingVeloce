package com.craftingveloce.compat.alchemistry;

import com.craftingveloce.compat.VeloceMods;
import net.neoforged.bus.api.IEventBus;

/**
 * Bramka integracji z Alchemistry.
 *
 * <p><b>Izolacja.</b> Ladowana ZAWSZE, takze bez Alchemistry - dlatego zero
 * typow Alchemistry w polach i sygnaturach. {@link #register(IEventBus)}
 * odwoluje sie do klas z obcymi typami, ale jest wolane tylko po
 * {@link #isPresent()}.
 */
public final class AlchemistryCompat {

    private AlchemistryCompat() {
    }

    /** Czy Alchemistry jest obecne. Bezpieczne takze bez niego. */
    public static boolean isPresent() {
        return VeloceMods.ALCHEMISTRY.isLoaded();
    }

    /** Rejestruje integracje. Wolno wolac WYLACZNIE gdy {@link #isPresent()}. */
    public static void register(IEventBus modEventBus) {
        AlchemistryBlocks.register(modEventBus);
        AlchemistryBlockEntities.register(modEventBus);
        AlchemistryCapabilities.register(modEventBus);
        AlchemistryRecipeFamily.register(modEventBus);
        AlchemistryModule.register(modEventBus);
    }

    /**
     * Pozycje modulu do zakladki kreatywnej.
     *
     * <p>Zakladka buduje sie ZAWSZE (takze bez Alchemistry), wiec to wywolanie
     * jest warunkowane obecnoscia moda w {@code CraftingVeloceMod}.
     */
    public static void addCreativeItems(net.minecraft.world.item.CreativeModeTab.Output output) {
        AlchemistryBlocks.addCreativeItems(output);
    }
}
