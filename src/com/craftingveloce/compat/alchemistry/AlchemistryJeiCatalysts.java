package com.craftingveloce.compat.alchemistry;

import com.craftingveloce.compat.VeloceJeiCatalysts;

/**
 * Kategorie przepisow Alchemistry, ktore obsluguja nasze moduly na energie.
 *
 * <p>Wypelnia spis dla JEI ({@link VeloceJeiCatalysts}) - bez dotykania JEI i bez
 * typow Alchemistry (same UID-y i nasze klocki).
 *
 * <p>UID-y pochodza z {@code com.smashingmods.alchemistry.client.jei.RecipeTypes}
 * ({@code RecipeType.create("alchemistry", ...)}): {@code alchemistry:compactor},
 * {@code alchemistry:combiner}, {@code alchemistry:fission},
 * {@code alchemistry:fusion}. Dodajemy tylko te cztery - dokladnie te, ktore
 * liczy {@code AlchemistryRecipeFamily}.
 */
public final class AlchemistryJeiCatalysts {

    private AlchemistryJeiCatalysts() {
    }

    /** Dopisuje nasze moduly Alchemistry do kategorii JEI z Alchemistry. */
    public static void register() {
        VeloceJeiCatalysts.register("alchemistry:compactor",
                () -> AlchemistryBlocks.VELOCE_COMPACTOR_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("alchemistry:combiner",
                () -> AlchemistryBlocks.VELOCE_COMBINER_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("alchemistry:fission",
                () -> AlchemistryBlocks.VELOCE_FISSION_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("alchemistry:fusion",
                () -> AlchemistryBlocks.VELOCE_FUSION_MODULE_ITEM.get());
    }
}
