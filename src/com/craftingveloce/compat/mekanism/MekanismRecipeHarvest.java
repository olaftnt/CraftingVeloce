package com.craftingveloce.compat.mekanism;

import com.craftingveloce.crafting.ProcessingEntry;
import mekanism.api.recipes.CombinerRecipe;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.SawmillRecipe;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.common.crafting.SizedIngredient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Translating Mekanism recipes into the common Veloce model.
 *
 * <p><b>Why.</b> The core does not know Mekanism recipe types - it only knows
 * {@link ProcessingEntry} (results, chances, ingredients with unit counts).
 * This class reads Mekanism recipes and builds that same model from them, so
 * the planner handles them without any knowledge of Mekanism.
 *
 * <p><b>Three recipe shapes, three conversions:</b>
 * <ul>
 *   <li>{@code ItemStackToItemStackRecipe} (crushing, enriching) - one
 *       ingredient with a unit count ({@code SizedIngredient.count()}) and one
 *       deterministic result,</li>
 *   <li>{@code SawmillRecipe} - the main result (guaranteed) plus a secondary
 *       result with probability {@code getSecondaryChance()} - planning sees
 *       only the main one, execution rolls the dice (policy from
 *       {@link ProcessingEntry}),</li>
 *   <li>{@code CombinerRecipe} - TWO named inputs (main + extra), each with its
 *       own unit count.</li>
 * </ul>
 *
 * <p><b>Memory.</b> Recipes are static data for a given recipe manager, so we
 * compute the index once per manager (like the vanilla index). Without that,
 * every planner query for an item would scan all recipes in the game.
 *
 * <p><b>What is not here.</b> {@code mekanism:smelting} is deliberately left
 * outside the family - it appends all vanilla smelting recipes to itself, so it
 * would duplicate the furnace family (see {@link MekanismRecipeFamily}).
 */
public final class MekanismRecipeHarvest {

    private MekanismRecipeHarvest() {
    }

    private static final Map<RecipeManager, Map<RecipeType<?>, Map<Item, List<ProcessingEntry>>>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Index "result -> recipes" for a given Mekanism recipe type. */
    public static Map<Item, List<ProcessingEntry>> index(ServerLevel level, RecipeType<?> type) {
        RecipeManager manager = level.getRecipeManager();
        Map<RecipeType<?>, Map<Item, List<ProcessingEntry>>> byType =
                CACHE.computeIfAbsent(manager, m -> new HashMap<>());
        return byType.computeIfAbsent(type, t -> build(level, t));
    }

    /** Mekanism recipes producing the given item (for one type). */
    public static List<ProcessingEntry> forItem(ServerLevel level, RecipeType<?> type, Item item) {
        return index(level, type).getOrDefault(item, List.of());
    }

    /** Clears the memory - called on world change, together with the other caches. */
    public static void invalidate() {
        CACHE.clear();
    }

    private static Map<Item, List<ProcessingEntry>> build(ServerLevel level, RecipeType<?> type) {
        Map<Item, List<ProcessingEntry>> out = new HashMap<>();
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (recipe.getType() != type || recipe.isIncomplete()) {
                continue;
            }
            ProcessingEntry entry = convert(holder.id(), recipe, type);
            if (entry == null) {
                continue;
            }
            // Only GUARANTEED results make it into the index - the planner must
            // not promise an item that only drops sometimes.
            for (ItemStack result : entry.guaranteedResults()) {
                if (!result.isEmpty()) {
                    out.computeIfAbsent(result.getItem(), k -> new ArrayList<>()).add(entry);
                }
            }
        }
        return out;
    }

    /** Converts a Mekanism recipe into the common model (or {@code null}). */
    private static ProcessingEntry convert(ResourceLocation id, Recipe<?> recipe,
                                           RecipeType<?> type) {
        if (recipe instanceof SawmillRecipe sawmill) {
            return sawmill(id, sawmill, type);
        }
        if (recipe instanceof CombinerRecipe combiner) {
            return combiner(id, combiner, type);
        }
        if (recipe instanceof ItemStackToItemStackRecipe single) {
            return singleOutput(id, single, type);
        }
        return null;
    }

    /** One ingredient (with a unit count) -> one deterministic result. */
    private static ProcessingEntry singleOutput(ResourceLocation id,
                                                ItemStackToItemStackRecipe recipe,
                                                RecipeType<?> type) {
        List<ItemStack> definitions = recipe.getOutputDefinition();
        if (definitions.isEmpty()) {
            return null;
        }
        SizedIngredient input = recipe.getInput().ingredient();
        NonNullList<Ingredient> ingredients = NonNullList.withSize(1, Ingredient.EMPTY);
        ingredients.set(0, input.ingredient());
        return new ProcessingEntry(id,
                List.of(definitions.get(0).copy()),
                List.of(1.0f),
                ingredients,
                List.of(Math.max(1, input.count())),
                type);
    }

    /**
     * Sawing: the main result is guaranteed + a secondary result with a chance.
     *
     * <p>Planning will see only the main result, and execution adds the
     * secondary one after a dice roll - the player sometimes gets more, never
     * less.
     */
    private static ProcessingEntry sawmill(ResourceLocation id, SawmillRecipe recipe,
                                           RecipeType<?> type) {
        List<ItemStack> results = new ArrayList<>();
        List<Float> chances = new ArrayList<>();
        for (ItemStack main : recipe.getMainOutputDefinition()) {
            if (!main.isEmpty()) {
                results.add(main.copy());
                chances.add(1.0f);
            }
        }
        if (results.isEmpty()) {
            return null;
        }
        float secondaryChance = (float) recipe.getSecondaryChance();
        if (secondaryChance > 0.0f && secondaryChance <= 1.0f) {
            for (ItemStack secondary : recipe.getSecondaryOutputDefinition()) {
                if (!secondary.isEmpty()) {
                    results.add(secondary.copy());
                    chances.add(secondaryChance);
                }
            }
        }
        SizedIngredient input = recipe.getInput().ingredient();
        NonNullList<Ingredient> ingredients = NonNullList.withSize(1, Ingredient.EMPTY);
        ingredients.set(0, input.ingredient());
        return new ProcessingEntry(id, results, chances, ingredients,
                List.of(Math.max(1, input.count())), type);
    }

    /** Combining: TWO named inputs, each with its own unit count. */
    private static ProcessingEntry combiner(ResourceLocation id, CombinerRecipe recipe,
                                            RecipeType<?> type) {
        List<ItemStack> definitions = recipe.getOutputDefinition();
        if (definitions.isEmpty()) {
            return null;
        }
        SizedIngredient main = recipe.getMainInput().ingredient();
        SizedIngredient extra = recipe.getExtraInput().ingredient();
        NonNullList<Ingredient> ingredients = NonNullList.withSize(2, Ingredient.EMPTY);
        ingredients.set(0, main.ingredient());
        ingredients.set(1, extra.ingredient());
        return new ProcessingEntry(id,
                List.of(definitions.get(0).copy()),
                List.of(1.0f),
                ingredients,
                List.of(Math.max(1, main.count()), Math.max(1, extra.count())),
                type);
    }
}
