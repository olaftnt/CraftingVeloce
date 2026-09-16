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
 * <p><b>Zakres.</b> Szesc rodzin: milling, cutting, crushing, mechanical
 * crafting, pressing i mixing. Receptury z Basenu i z cieplem sa obslugiwane -
 * planer widzi je tylko wtedy, gdy w sieci jest Basen (press/mixer) albo Blaze
 * Burner (cieplo). Rodziny plynne (spout, fany) poza zakresem: brak warstwy
 * plynow.
 *
 */
public final class CreateRecipeFamily {

    /** Identyfikator rodziny w {@link VeloceRecipeFamilies}. */
    public static final String ID = "create";

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

    /** Mlyn: receptury {@code create:milling}. */
    public static RecipeType<?> milling() {
        return AllRecipeTypes.MILLING.getType();
    }

    /** Piła: receptury {@code create:cutting}. */
    public static RecipeType<?> cutting() {
        return AllRecipeTypes.CUTTING.getType();
    }

    /** Kruszarka: receptury {@code create:crushing} (wyniki losowe). */
    public static RecipeType<?> crushing() {
        return AllRecipeTypes.CRUSHING.getType();
    }

    /** Prasa: receptury {@code create:pressing} (pracuje na Basenie). */
    public static RecipeType<?> pressing() {
        return AllRecipeTypes.PRESSING.getType();
    }

    /** Mixer: receptury {@code create:mixing} (pracuje na Basenie). */
    public static RecipeType<?> mixing() {
        return AllRecipeTypes.MIXING.getType();
    }

    /** Deployer: receptury {@code create:deploying} (nakladanie itemu na item). */
    public static RecipeType<?> deploying() {
        return AllRecipeTypes.DEPLOYING.getType();
    }

    /** Mechanical crafter: receptury {@code create:mechanical_crafting}. */
    public static RecipeType<?> mechanicalCrafting() {
        return AllRecipeTypes.MECHANICAL_CRAFTING.getType();
    }

    /**
     * Typy receptur Create obslugiwane przez modul.
     *
     * <p>Wolno wolac tylko gdy Create jest obecne - metoda dotyka typow obcego
     * moda. Wynik jest liczony raz i zapamietany.
     */
    public static Set<RecipeType<?>> types() {
        if (resolved == null) {
            Set<RecipeType<?>> out = new LinkedHashSet<>();
            out.add(milling());
            out.add(cutting());
            out.add(crushing());
            out.add(mechanicalCrafting());
            out.add(pressing());
            out.add(mixing());
            out.add(deploying());
            resolved = Set.copyOf(out);
        }
        return resolved;
    }
}
