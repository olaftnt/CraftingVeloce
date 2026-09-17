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

    /** Deployer: {@code create:deploying} recipes (applying one item to another). */
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
            resolved = Set.copyOf(out);
        }
        return resolved;
    }
}
