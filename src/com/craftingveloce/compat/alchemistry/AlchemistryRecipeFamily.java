package com.craftingveloce.compat.alchemistry;

import com.craftingveloce.crafting.VeloceRecipeFamilies;
import com.smashingmods.alchemistry.registry.RecipeRegistry;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Rodzina receptur Alchemistry, ktora Veloce umie obslugiwac.
 *
 * <p>Typy receptur Alchemistry nie istnieja bez tego moda, wiec zyja tutaj -
 * w {@code compat/alchemistry} - a nie w rdzeniu.
 *
 * <p><b>Dlaczego w {@code FMLCommonSetupEvent}.</b> DeferredHoldery
 * {@code RecipeRegistry.X_TYPE} sa zwiazane dopiero po zdarzeniach rejestracji;
 * odczyt w konstruktorze moda rzucilby wyjatkiem.
 *
 * <p><b>Zakres v1.</b> Maszyny itemowe (compactor, combiner, fission, fusion).
 * Dissolver jest probabilistyczny i wymaga modelu prawdopodobienstwa, a
 * liquifier/atomizer pracuja na plynnym chemicznym - dochodza w dalszych
 * etapach (warstwa plynow).
 */
public final class AlchemistryRecipeFamily {

    /** Identyfikator rodziny w {@link VeloceRecipeFamilies}. */
    public static final String ID = "alchemistry";

    private static Set<RecipeType<?>> resolved;

    private AlchemistryRecipeFamily() {
    }

    /** Rejestruje rodzine receptur Alchemistry (po rejestracji blokow). */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(FMLCommonSetupEvent.class, event -> {
            Set<RecipeType<?>> types = types();
            VeloceRecipeFamilies.registerModFamily(ID, types);
            com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                    "[Veloce][COMPAT] {}: zarejestrowano {} typow receptur",
                    ID, types.size());
        });
    }

    /** Typy receptur Alchemistry. Wolno wolac tylko gdy mod jest obecny. */
    public static Set<RecipeType<?>> types() {
        if (resolved == null) {
            Set<RecipeType<?>> out = new LinkedHashSet<>();
            out.add(RecipeRegistry.COMPACTOR_TYPE.get());
            out.add(RecipeRegistry.COMBINER_TYPE.get());
            out.add(RecipeRegistry.FISSION_TYPE.get());
            out.add(RecipeRegistry.FUSION_TYPE.get());
            resolved = Set.copyOf(out);
        }
        return resolved;
    }
}
