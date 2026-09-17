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
        VeloceJeiCatalysts.register("mekanism:smelting", () -> MekanismBlocks.VELOCE_SMELTING_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:compressing", () -> MekanismBlocks.VELOCE_COMPRESSING_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:metallurgic_infusing", () -> MekanismBlocks.VELOCE_METALLURGIC_INFUSING_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:purifying", () -> MekanismBlocks.VELOCE_PURIFYING_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:injecting", () -> MekanismBlocks.VELOCE_INJECTING_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:crystallizing", () -> MekanismBlocks.VELOCE_CRYSTALLIZING_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:dissolution", () -> MekanismBlocks.VELOCE_DISSOLUTION_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:washing", () -> MekanismBlocks.VELOCE_WASHING_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:separating", () -> MekanismBlocks.VELOCE_SEPARATING_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:reaction", () -> MekanismBlocks.VELOCE_REACTION_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:rotary", () -> MekanismBlocks.VELOCE_ROTARY_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:activating", () -> MekanismBlocks.VELOCE_ACTIVATING_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:centrifuging", () -> MekanismBlocks.VELOCE_CENTRIFUGING_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:nucleosynthesizing", () -> MekanismBlocks.VELOCE_NUCLEOSYNTHESIZING_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:pigment_extracting", () -> MekanismBlocks.VELOCE_PIGMENT_EXTRACTING_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:pigment_mixing", () -> MekanismBlocks.VELOCE_PIGMENT_MIXING_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:painting", () -> MekanismBlocks.VELOCE_PAINTING_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:oxidizing", () -> MekanismBlocks.VELOCE_OXIDIZING_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("mekanism:chemical_infusing", () -> MekanismBlocks.VELOCE_CHEMICAL_INFUSING_MODULE_ITEM.get());
    }
}
