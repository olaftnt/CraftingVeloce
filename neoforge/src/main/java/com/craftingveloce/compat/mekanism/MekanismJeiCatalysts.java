package com.craftingveloce.compat.mekanism;

import com.craftingveloce.compat.VeloceJeiCatalysts;

/**
 * Mekanism recipe categories that our energy modules handle.
 *
 * <p>Fills the list for JEI ({@link VeloceJeiCatalysts}) - without touching JEI
 * and without Mekanism types (just UIDs and our blocks).
 *
 * <p>The category UIDs are the identifiers of Mekanism recipe types
 * ({@code RecipeTypeRegistryObject.getId()}), that is {@code mekanism:crushing},
 * {@code mekanism:enriching}, {@code mekanism:combining} and {@code mekanism:sawing}.
 * We add only those four - exactly the ones computed by
 * {@code MekanismRecipeFamily}.
 */
public final class MekanismJeiCatalysts {

    private MekanismJeiCatalysts() {
    }

    /** Appends our Mekanism modules to the JEI categories from Mekanism. */
    public static void register() {
        VeloceJeiCatalysts.register("mekanism:crushing",
                () -> MekanismBlocks.VELOCE_CRUSHER_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:enriching",
                () -> MekanismBlocks.VELOCE_ENRICHMENT_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:combining",
                () -> MekanismBlocks.VELOCE_COMBINER_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:sawing",
                () -> MekanismBlocks.VELOCE_SAWMILL_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:compressing", () -> MekanismBlocks.VELOCE_COMPRESSING_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:metallurgic_infusing", () -> MekanismBlocks.VELOCE_METALLURGIC_INFUSING_MODULE_ITEM.get());
    }
}
