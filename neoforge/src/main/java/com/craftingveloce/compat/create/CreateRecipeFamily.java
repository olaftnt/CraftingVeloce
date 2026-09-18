package com.craftingveloce.compat.create;

import com.craftingveloce.crafting.VeloceRecipeFamilies;
import com.simibubi.create.AllRecipeTypes;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The Create recipe family that Veloce can handle.
 *
 * <p><b>Why a separate class.</b> Create recipe types do not exist without
 * Create, so they cannot live in the core (see {@link VeloceRecipeFamilies}).
 * This class lives in {@code compat/create} and calls the family registration
 * only when Create is present.
 *
 * <p><b>Why only in {@code FMLCommonSetupEvent}.</b> Create's DeferredHolders
 * (and therefore {@code AllRecipeTypes.X.getType()}) are bound only after the
 * registration events. Reading them in the mod constructor would throw an
 * "holder not bound" exception - that is why we register the family in an event
 * that fires AFTER registration.
 *
 * <p><b>Scope.</b> Six families: milling, cutting, crushing, mechanical
 * crafting, pressing and mixing. Recipes from the Basin and heat-based ones are
 * handled - the planner sees them only when there is a Basin (press/mixer) or a
 * Blaze Burner (heat) in the network. Fluid families (spout, fans) are out of
 * scope: no fluid layer.
 *
 */
public final class CreateRecipeFamily {

    /** Family identifier in {@link VeloceRecipeFamilies}. */
    public static final String ID = "create";

    private static Set<RecipeType<?>> resolved;

    private CreateRecipeFamily() {
    }

    /** Registers the Create recipe family in the core (after block registration). */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(FMLCommonSetupEvent.class, event -> {
            Set<RecipeType<?>> types = types();
            VeloceRecipeFamilies.registerModFamily(ID, types);
            // The rotation source's tuner lives in the integration, and the core asks for
            // it by block id - see VeloceRotationSources.
            CreateRotationSource.register();
            com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                    "[Veloce][COMPAT] {}: registered {} recipe types",
                    ID, types.size());
        });
    }

    /** Mill: {@code create:milling} recipes. */
    public static RecipeType<?> milling() {
        return AllRecipeTypes.MILLING.getType();
    }

    /** Saw: {@code create:cutting} recipes. */
    public static RecipeType<?> cutting() {
        return AllRecipeTypes.CUTTING.getType();
    }

    /** Crusher: {@code create:crushing} recipes (random outputs). */
    public static RecipeType<?> crushing() {
        return AllRecipeTypes.CRUSHING.getType();
    }

    /** Press: {@code create:pressing} recipes (works on the Basin). */
    public static RecipeType<?> pressing() {
        return AllRecipeTypes.PRESSING.getType();
    }

    /** Mixer: {@code create:mixing} recipes (works on the Basin). */
    public static RecipeType<?> mixing() {
        return AllRecipeTypes.MIXING.getType();
    }

    /**
     * Deployer, on a block in the world: {@code create:item_application} recipes.
     *
     * <p><b>EVERY CASING COMES FROM HERE.</b> {@code create:andesite_casing} is
     * "a stripped log + andesite alloy" as an item_application recipe, and so are the
     * brass, copper, railway and shadow casings. Create's own name for the JEI page is
     * "Manual Item Application", which reads as "by hand", but Create registers the
     * DEPLOYER as the catalyst for it - so a network with a Deployer in it can make
     * casings, and this family was the only thing standing in the way.
     */
    public static RecipeType<?> itemApplication() {
        return AllRecipeTypes.ITEM_APPLICATION.getType();
    }

    /**
     * Press over a BASIN: {@code create:compacting} recipes.
     *
     * <p>Same family as mixing - a basin recipe - and the same machine as pressing, which
     * is why it rides on the Press module and inherits the "a Basin must be in the
     * network" requirement. Without it, compacting, packing and the whole basin press
     * tree were unreachable.
     */
    public static RecipeType<?> compacting() {
        return AllRecipeTypes.COMPACTING.getType();
    }

    /**
     * Deployer holding SANDPAPER: {@code create:sandpaper_polishing} recipes.
     *
     * <p>Create's JEI registers the sand paper ITEM as this category's catalyst, which
     * reads as "no machine does this, a player does". That reading is wrong and I
     * shipped it once: the DEPLOYER runs these recipes too - Create's own bytecode has
     * DeployerBlockEntity, DeployerHandler, BeltDeployerCallbacks and
     * DeployerApplicationRecipe all referencing SandPaper, and
     * SandPaperPolishingRecipe extends StandardProcessingRecipe, so it converts here
     * like any other processing recipe.
     */
    public static RecipeType<?> sandpaperPolishing() {
        return AllRecipeTypes.SANDPAPER_POLISHING.getType();
    }

    /**
     * A SEQUENCE of machines on one transitional item: {@code create:sequenced_assembly}.
     *
     * <p>Not a {@code ProcessingRecipe} - it holds a list of steps, each with its own
     * machine and ingredients, and the result is rolled from a POOL. Two consequences,
     * both handled in {@code CreateRecipeHarvest.sequenced}:
     * <ul>
     *   <li>every step's recipe type has to be powered in the network, not just one, so a
     *       sequence is only offered when the player really has the whole chain;</li>
     *   <li>a recipe whose result is a POOL of several outcomes is NOT offered: the
     *       auto-crafter promises exact amounts, and "one of these, weighted" is not a
     *       promise. Only a single guaranteed output is modelled.</li>
     * </ul>
     */
    public static RecipeType<?> sequencedAssembly() {
        return AllRecipeTypes.SEQUENCED_ASSEMBLY.getType();
    }

    /** Deployer, on an item: {@code create:deploying} recipes (applying one item to another). */
    public static RecipeType<?> deploying() {
        return AllRecipeTypes.DEPLOYING.getType();
    }

    /** Mechanical crafter: {@code create:mechanical_crafting} recipes. */
    public static RecipeType<?> mechanicalCrafting() {
        return AllRecipeTypes.MECHANICAL_CRAFTING.getType();
    }

    /**
     * Create recipe types handled by the module.
     *
     * <p>May only be called when Create is present - the method touches another
     * mod's types. The result is computed once and cached.
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
            out.add(itemApplication());
            out.add(compacting());
            out.add(sandpaperPolishing());
            out.add(sequencedAssembly());
            resolved = Set.copyOf(out);
        }
        return resolved;
    }
}
