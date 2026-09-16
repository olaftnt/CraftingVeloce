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

    /** Kruszarka: ruda/sztaba -> pyl (deterministyczne, 1 -> 1). */
    public static RecipeType<?> crushing() {
        return MekanismRecipeTypes.TYPE_CRUSHING.get();
    }

    /** Wzbogacanie: ruda -> 3 sztaby (deterministyczne, 1 -> 1). */
    public static RecipeType<?> enriching() {
        return MekanismRecipeTypes.TYPE_ENRICHING.get();
    }

    /** Laczenie: dwa itemy -> jeden (Combiner). */
    public static RecipeType<?> combining() {
        return MekanismRecipeTypes.TYPE_COMBINING.get();
    }

    /** Pilowanie: item -> deski + LOSOWY trocin (Precision Sawmill). */
    public static RecipeType<?> sawing() {
        return MekanismRecipeTypes.TYPE_SAWING.get();
    }

    public static RecipeType<?> smelting() {
        return MekanismRecipeTypes.TYPE_SMELTING.get();
    }

    public static RecipeType<?> compressing() {
        return MekanismRecipeTypes.TYPE_COMPRESSING.get();
    }

    public static RecipeType<?> metallurgic_infusing() {
        return MekanismRecipeTypes.TYPE_METALLURGIC_INFUSING.get();
    }

    public static RecipeType<?> purifying() {
        return MekanismRecipeTypes.TYPE_PURIFYING.get();
    }

    public static RecipeType<?> injecting() {
        return MekanismRecipeTypes.TYPE_INJECTING.get();
    }

    public static RecipeType<?> crystallizing() {
        return MekanismRecipeTypes.TYPE_CRYSTALLIZING.get();
    }

    public static RecipeType<?> dissolution() {
        return MekanismRecipeTypes.TYPE_DISSOLUTION.get();
    }

    public static RecipeType<?> washing() {
        return MekanismRecipeTypes.TYPE_WASHING.get();
    }

    public static RecipeType<?> separating() {
        return MekanismRecipeTypes.TYPE_SEPARATING.get();
    }

    public static RecipeType<?> reaction() {
        return MekanismRecipeTypes.TYPE_REACTION.get();
    }

    public static RecipeType<?> rotary() {
        return MekanismRecipeTypes.TYPE_ROTARY.get();
    }

    public static RecipeType<?> activating() {
        return MekanismRecipeTypes.TYPE_ACTIVATING.get();
    }

    public static RecipeType<?> centrifuging() {
        return MekanismRecipeTypes.TYPE_CENTRIFUGING.get();
    }

    public static RecipeType<?> nucleosynthesizing() {
        return MekanismRecipeTypes.TYPE_NUCLEOSYNTHESIZING.get();
    }

    public static RecipeType<?> pigment_extracting() {
        return MekanismRecipeTypes.TYPE_PIGMENT_EXTRACTING.get();
    }

    public static RecipeType<?> pigment_mixing() {
        return MekanismRecipeTypes.TYPE_PIGMENT_MIXING.get();
    }

    public static RecipeType<?> painting() {
        return MekanismRecipeTypes.TYPE_PAINTING.get();
    }

    public static RecipeType<?> oxidizing() {
        return MekanismRecipeTypes.TYPE_OXIDIZING.get();
    }

    public static RecipeType<?> chemical_infusing() {
        return MekanismRecipeTypes.TYPE_CHEMICAL_INFUSING.get();
    }


    /** Typy receptur Mekanism. Wolno wolac tylko gdy mod jest obecny. */
    public static Set<RecipeType<?>> types() {
        if (resolved == null) {
            Set<RecipeType<?>> out = new LinkedHashSet<>();
            out.add(crushing());
            out.add(enriching());
            out.add(combining());
            out.add(sawing());
            out.add(smelting());
            out.add(compressing());
            out.add(metallurgic_infusing());
            out.add(purifying());
            out.add(injecting());
            out.add(crystallizing());
            out.add(dissolution());
            out.add(washing());
            out.add(separating());
            out.add(reaction());
            out.add(rotary());
            out.add(activating());
            out.add(centrifuging());
            out.add(nucleosynthesizing());
            out.add(pigment_extracting());
            out.add(pigment_mixing());
            out.add(painting());
            out.add(oxidizing());
            out.add(chemical_infusing());
            resolved = Set.copyOf(out);
        }
        return resolved;
    }
}
