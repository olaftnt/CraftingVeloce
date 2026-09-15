package com.craftingveloce.compat.mekanism;

import com.craftingveloce.crafting.VeloceRecipeFamilies;
import mekanism.api.recipes.MekanismRecipeTypes;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Rodzina receptur Mekanism, ktora Veloce umie obslugiwac.
 *
 * <p>Typy receptur Mekanism nie istnieja bez tego moda, wiec zyja tutaj.
 *
 * <p><b>Dlaczego w {@code FMLCommonSetupEvent}.</b> {@code TYPE_*} to
 * DeferredHoldery zwiazywane dopiero po zdarzeniach rejestracji - odczyt
 * w konstruktorze moda rzucilby wyjatkiem.
 *
 * <p><b>Czego CELOWO tu nie ma.</b> {@code TYPE_SMELTING} Mekanism dokleja
 * do siebie WSZYSTKIE waniliowe receptury smelting, wiec dodanie go do tej
 * rodziny dublowaloby rodzine pieca ({@link VeloceRecipeFamilies#FURNACE}) -
 * ten sam item mialby dwie konkurencyjne sciezki i liczby w GUI liczyloby
 * sie dwa razy. Energized Smelter dostanie wlasna, osobna rodzine razem ze
 * swoim moduem.
 *
 * <p><b>Zakres v1.</b> Maszyny itemowe: crusher, enrichment chamber,
 * combiner, precision sawmill. Chemikalia i plyny dochodza w etapie warstwy
 * chemicznej.
 */
public final class MekanismRecipeFamily {

    /** Identyfikator rodziny w {@link VeloceRecipeFamilies}. */
    public static final String ID = "mekanism";

    private static Set<RecipeType<?>> resolved;

    private MekanismRecipeFamily() {
    }

    /** Rejestruje rodzine receptur Mekanism (po rejestracji blokow). */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(FMLCommonSetupEvent.class, event -> {
            Set<RecipeType<?>> types = types();
            VeloceRecipeFamilies.registerModFamily(ID, types);
            com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                    "[Veloce][COMPAT] {}: zarejestrowano {} typow receptur",
                    ID, types.size());
        });
    }

    /** Typy receptur Mekanism. Wolno wolac tylko gdy mod jest obecny. */
    public static Set<RecipeType<?>> types() {
        if (resolved == null) {
            Set<RecipeType<?>> out = new LinkedHashSet<>();
            out.add(MekanismRecipeTypes.TYPE_CRUSHING.get());
            out.add(MekanismRecipeTypes.TYPE_ENRICHING.get());
            out.add(MekanismRecipeTypes.TYPE_COMBINING.get());
            out.add(MekanismRecipeTypes.TYPE_SAWING.get());
            resolved = Set.copyOf(out);
        }
        return resolved;
    }
}
