package com.craftingveloce.compat.create;

import com.craftingveloce.compat.VeloceJeiCatalysts;

/**
 * Kategorie przepisow Create, ktore obsluguja nasze moduly kinetyczne.
 *
 * <p>Wypelnia spis dla JEI ({@link VeloceJeiCatalysts}). Ta klasa nie dotyka
 * JEI ani zadnego typu Create (same UID-y i nasze klocki), wiec wolno ja wolac
 * bez zastanowienia nad kolejnoscia ladowania - a wpisy czyta dopiero plugin JEI.
 *
 * <p><b>UID kategorii != nasz typ przepisu.</b> Kazdy wpis to UID KATEGORII JEI
 * z Create (patrz {@code CreateJEI.loadCategories}: nazwa kategorii idzie przez
 * {@code Create.asResource(name)}), dlatego piła wystepuje jako
 * {@code create:sawing}, a nie {@code create:cutting}. Dodajemy wylacznie
 * rodziny, ktore nasz modul NAPRAWDE liczy - patrz {@code CreateRecipeFamily}.
 */
public final class CreateJeiCatalysts {

    private CreateJeiCatalysts() {
    }

    /** Dopisuje nasze moduly Create do kategorii JEI z Create. */
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
    }
}
