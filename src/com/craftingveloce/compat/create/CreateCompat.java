package com.craftingveloce.compat.create;

import com.craftingveloce.compat.VeloceMods;
import net.neoforged.bus.api.IEventBus;

/**
 * Bramka integracji z Create.
 *
 * <p><b>Izolacja.</b> Ta klasa jest ladowana ZAWSZE - takze bez Create - wiec
 * nie moze miec w polach ani sygnaturach zadnego typu Create. Cialo
 * {@link #register(IEventBus)} odwoluje sie do klas z typami Create, ale JVM
 * rozwija takie odwolanie dopiero przy WYWOLANIU, a wolamy je tylko wtedy, gdy
 * Create jest obecne.
 *
 * <p>Sprawdzenia {@code try/catch} wokol kodu integracji NIE dzialaja:
 * {@code NoClassDefFoundError} leci przy ladowaniu i linkowaniu klasy, zanim
 * try zdazy zadzialac.
 */
public final class CreateCompat {

    private CreateCompat() {
    }

    /** Czy Create jest obecne. Bezpieczne takze bez Create. */
    public static boolean isPresent() {
        return VeloceMods.CREATE.isLoaded();
    }

    /**
     * Rejestruje integracje. Wolno wolac WYLACZNIE gdy {@link #isPresent()}.
     */
    public static void register(IEventBus modEventBus) {
        CreateBlocks.register(modEventBus);
        CreateBlockEntities.register(modEventBus);
        CreateRecipeFamily.register(modEventBus);
        CreateModule.register(modEventBus);
        // Stresu NIE rejestrujemy: CStress.setImpact rzuca wyjatek dla blokow
        // spoza Create, a nasza maszyna liczy stale SU sama
        // (VeloceKineticModuleBlockEntity.calculateStressApplied).
    }

    /**
     * Pozycje modulu do zakladki kreatywnej.
     *
     * <p>Zakladka buduje sie ZAWSZE (takze bez Create), wiec to wywolanie jest
     * warunkowane obecnoscia moda w {@code CraftingVeloceMod}.
     */
    public static void addCreativeItems(net.minecraft.world.item.CreativeModeTab.Output output) {
        CreateBlocks.addCreativeItems(output);
    }
}
