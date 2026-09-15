package com.craftingveloce.compat.create;

import com.craftingveloce.crafting.VeloceRecipeFamilies;
import com.simibubi.create.AllRecipeTypes;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Rodzina receptur Create, ktora Veloce umie obslugiwac.
 *
 * <p><b>Po co osobna klasa.</b> Typy receptur Create nie istnieja bez Create,
 * wiec nie moga stac w rdzeniu (patrz {@link VeloceRecipeFamilies}). Ta klasa
 * zyje w {@code compat/create} i wola rejestracje rodziny tylko wtedy, gdy
 * Create jest obecne.
 *
 * <p><b>Dlaczego dopiero w {@code FMLCommonSetupEvent}.</b> DeferredHoldery
 * Create (a wiec i {@code AllRecipeTypes.X.getType()}) sa zwiazane dopiero po
 * zdarzeniach rejestracji. Odczyt w konstruktorze moda rzucilby wyjatkiem
 * "holder not bound" - dlatego rejestrujemy rodzine w zdarzeniu, ktore leci
 * PO rejestracji.
 *
 * <p><b>Zakres v1.</b> Maszyny itemowe (mlyn, piła, prasa, basen, kruszarka)
 * plus mechanical crafting. Pomiary i plyny (spout, fany) oraz sekwencyjny
 * montaz dochodza pozniej - patrz specyfikacja integracji.
 */
public final class CreateRecipeFamily {

    /** Identyfikator rodziny w {@link VeloceRecipeFamilies}. */
    public static final String ID = "create";

    /**
     * Typy Create obslugiwane przez Veloce (v1, itemowe).
     *
     * <p>Kolejnosc jest stala, bo sluzy tez diagnostyce (log przy starcie).
     */
    private static final AllRecipeTypes[] ITEM_TYPES = {
            AllRecipeTypes.CRUSHING,
            AllRecipeTypes.MILLING,
            AllRecipeTypes.CUTTING,
            AllRecipeTypes.PRESSING,
            AllRecipeTypes.MIXING,
            AllRecipeTypes.COMPACTING,
            AllRecipeTypes.BASIN,
            AllRecipeTypes.MECHANICAL_CRAFTING,
    };

    private static Set<RecipeType<?>> resolved;

    private CreateRecipeFamily() {
    }

    /** Rejestruje rodzine receptur Create w rdzeniu (po rejestracji blokow). */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(FMLCommonSetupEvent.class, event -> {
            Set<RecipeType<?>> types = types();
            VeloceRecipeFamilies.registerModFamily(ID, types);
            com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                    "[Veloce][COMPAT] {}: zarejestrowano {} typow receptur",
                    ID, types.size());
        });
    }

    /**
     * Typy receptur Create, rozwiazywane leniwie i raz na proces.
     *
     * <p>Wolno wolac tylko gdy Create jest obecne - metoda dotyka typu obcego
     * moda.
     */
    public static Set<RecipeType<?>> types() {
        if (resolved == null) {
            Set<RecipeType<?>> out = new LinkedHashSet<>();
            for (AllRecipeTypes type : ITEM_TYPES) {
                out.add(type.getType());
            }
            resolved = Set.copyOf(out);
        }
        return resolved;
    }
}
