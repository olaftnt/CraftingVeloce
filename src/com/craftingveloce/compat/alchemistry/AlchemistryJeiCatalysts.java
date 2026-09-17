package com.craftingveloce.compat.alchemistry;

import com.craftingveloce.compat.VeloceJeiCatalysts;

/**
 * Alchemistry recipe categories handled by our energy modules.
 *
 * <p>Fills the registry for JEI ({@link VeloceJeiCatalysts}) - without touching
 * JEI and without Alchemistry types (only UIDs and our blocks).
 *
 * <p>The UIDs come from {@code com.smashingmods.alchemistry.client.jei.RecipeTypes}
 * ({@code RecipeType.create("alchemistry", ...)}): {@code alchemistry:compactor},
 * {@code alchemistry:combiner}, {@code alchemistry:fission},
 * {@code alchemistry:fusion}. We add only these four - exactly the ones that
 * {@code AlchemistryRecipeFamily} counts.
 */
public final class AlchemistryJeiCatalysts {

    private AlchemistryJeiCatalysts() {
    }

    /** Adds our Alchemistry modules to the Alchemistry JEI categories. */
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
