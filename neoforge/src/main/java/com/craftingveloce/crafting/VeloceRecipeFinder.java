package com.craftingveloce.crafting;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "How do I make this item at all" - recipes from ALL sources.
 *
 * <p><b>How this differs from {@link VeloceModuleRecipes}.</b> That one answers
 * the PLANNER's question: "what can I make NOW from what I have in the network" -
 * so it only lets through modules with a machine and power. This is a HUMAN
 * question (and that of tools, e.g. the {@code /cv getitems} command): "how is
 * this made" - so the recipe must be visible also when the player does not have
 * any machine yet.
 *
 * <p><b>The order matters for the messages:</b> first recipes without
 * infrastructure (crafting table, stonecutter, smithing), then the module
 * families (Create/Alchemistry/Mekanism), and finally the furnace - because for
 * an item with a crafting recipe, the information "you can also use a furnace"
 * is only an addition.
 *
 * <p><b>No duplicates.</b> The same recipe can be seen from two sides (e.g. the
 * index and the module), so we key by the recipe identifier.
 */
public final class VeloceRecipeFinder {

    private VeloceRecipeFinder() {
    }

    /** All recipes producing a given item, in source order. */
    public static List<ProcessingEntry> all(ServerLevel level, Item item) {
        Map<ResourceLocation, ProcessingEntry> unique = new LinkedHashMap<>();
        add(unique, VeloceRecipeRegistry.getRecipesFor(level, item));
        for (VeloceProcessingModule module : VeloceProcessingRegistry.all()) {
            add(unique, module.recipesAnywhere(level, item));
        }
        add(unique, VeloceRecipeRegistry.getFurnaceRecipesFor(level, item));
        return new ArrayList<>(unique.values());
    }

    /**
     * Does the recipe come from a module family (Create/Alchemistry/Mekanism)?
     *
     * <p>For the messages: the player must know that this recipe requires a
     * module machine, not an ordinary crafting table.
     */
    public static boolean isModuleRecipe(ProcessingEntry recipe) {
        Set<RecipeType<?>> furnace = VeloceRecipeFamilies.FURNACE;
        return !furnace.contains(recipe.type())
                && !VeloceRecipeFamilies.isFree(recipe.type())
                && VeloceRecipeFamilies.isKnown(recipe.type());
    }

    /** A readable recipe type name, e.g. {@code "create:mechanical_crafting"}. */
    public static String typeName(RecipeType<?> type) {
        ResourceLocation id = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        return id == null ? String.valueOf(type) : id.toString();
    }

    private static void add(Map<ResourceLocation, ProcessingEntry> unique,
                            List<ProcessingEntry> recipes) {
        if (recipes == null) {
            return;
        }
        for (ProcessingEntry recipe : recipes) {
            unique.putIfAbsent(recipe.id(), recipe);
        }
    }
}
