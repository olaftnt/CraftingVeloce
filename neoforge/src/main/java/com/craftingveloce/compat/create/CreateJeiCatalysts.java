package com.craftingveloce.compat.create;

import com.craftingveloce.compat.VeloceJeiCatalysts;

/**
 * Create recipe categories handled by our kinetic modules.
 *
 * <p>Fills the catalog for JEI ({@link VeloceJeiCatalysts}). This class touches
 * neither JEI nor any Create type (only UIDs and our blocks), so it is safe to
 * call without worrying about load order - and the entries are read only later
 * by the JEI plugin.
 *
 * <p><b>A category UID != our recipe type.</b> Every entry is a JEI CATEGORY
 * UID from Create (see {@code CreateJEI.loadCategories}: the category name goes
 * through {@code Create.asResource(name)}), which is why the saw appears as
 * {@code create:sawing}, and not {@code create:cutting}. We add only the
 * families our module REALLY counts - see {@code CreateRecipeFamily}.
 */
public final class CreateJeiCatalysts {

    private CreateJeiCatalysts() {
    }

    /** Appends our Create modules to the JEI categories from Create. */
    public static void register() {
        VeloceJeiCatalysts.register("create:milling",
                () -> CreateBlocks.VELOCE_MILLSTONE_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("create:sawing",
                () -> CreateBlocks.VELOCE_SAW_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("create:crushing",
                () -> CreateBlocks.VELOCE_CRUSHING_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("create:mechanical_crafting",
                () -> CreateBlocks.VELOCE_MECHANICAL_CRAFTER_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("create:pressing",
                () -> CreateBlocks.VELOCE_PRESS_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("create:mixing",
                () -> CreateBlocks.VELOCE_MIXER_MODULE_ITEM.get());
        VeloceJeiCatalysts.register("create:deploying",
                () -> CreateBlocks.VELOCE_DEPLOYER_MODULE_ITEM.get());
        // The Deployer's SECOND page. Create's own JEI registers the Deployer as the
        // catalyst for "Manual Item Application" as well, which is the page every casing
        // recipe lives on - so a catalyst only on "Deploying" would advertise half a
        // machine.
        VeloceJeiCatalysts.register("create:item_application",
                () -> CreateBlocks.VELOCE_DEPLOYER_MODULE_ITEM.get());
    }
}
