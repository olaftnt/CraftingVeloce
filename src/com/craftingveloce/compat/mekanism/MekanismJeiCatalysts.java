package com.craftingveloce.compat.mekanism;

import com.craftingveloce.compat.VeloceJeiCatalysts;

/**
 * Kategorie przepisow Mekanism, ktore obsluguja nasze moduly na energie.
 *
 * <p>Wypelnia spis dla JEI ({@link VeloceJeiCatalysts}) - bez dotykania JEI i bez
 * typow Mekanism (same UID-y i nasze klocki).
 *
 * <p>UID kategorii to identyfikatory typow przepisow Mekanism
 * ({@code RecipeTypeRegistryObject.getId()}), czyli {@code mekanism:crushing},
 * {@code mekanism:enriching}, {@code mekanism:combining} i {@code mekanism:sawing}.
 * Dodajemy tylko te cztery - dokladnie te, ktore liczy
 * {@code MekanismRecipeFamily}.
 */
public final class MekanismJeiCatalysts {

    private MekanismJeiCatalysts() {
    }

    /** Dopisuje nasze moduly Mekanism do kategorii JEI z Mekanism. */
    public static void register() {
        VeloceJeiCatalysts.register("mekanism:crushing",
                () -> MekanismBlocks.VELOCE_CRUSHER_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:enriching",
                () -> MekanismBlocks.VELOCE_ENRICHMENT_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:combining",
                () -> MekanismBlocks.VELOCE_COMBINER_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:sawing",
                () -> MekanismBlocks.VELOCE_SAWMILL_MODULE_ITEM.get());
    }
}
