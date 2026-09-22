package com.craftingveloce.crafting;


import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reverse recipe index: ingredient item -> items that can be made from it.
 *
 * <p>This is the foundation of incremental recomputation. Without it, answering
 * "what changed after crafting planks" would require scanning all 15k recipes.
 * With the index it is enough to look at a single entry and walk up the chain.
 *
 * <p>The index is built once per {@link net.minecraft.world.item.crafting.RecipeManager}
 * and cached. It is rebuilt after a data reload (a new manager).
 *
 * <p>Memory: for 15k recipes and 3k items this is a few maps - negligible
 * compared with the CPU time saved.
 */
public final class VeloceRecipeGraph {

    /**
     * Cache per RecipeManager.
     *
     * <p>Weak keys - see the comment in {@link VeloceRecipeRegistry}. This map
     * had exactly the same leak: it held the RecipeManager strongly, so every
     * entry into a world left the whole recipe graph behind.
     */
    private static final Map<Object, VeloceRecipeGraph> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** ingredient item -> ids of the recipes that use it. */
    private final Map<Item, Set<ResourceLocation>> usedBy = new HashMap<>();

    /** recipe id -> result (item + count). */
    private final Map<ResourceLocation, ItemStack> results = new HashMap<>();

    /** recipe id -> list of ingredients (each as a set of accepted items). */
    private final Map<ResourceLocation, List<Set<Item>>> ingredients = new HashMap<>();

    /** Recipe types that interest us - the ones without infrastructure. */
    /** The single source of truth about families - see {@link VeloceRecipeFamilies}. */
    private static final java.util.Set<RecipeType<?>> FREE_TYPES = VeloceRecipeFamilies.FREE;

    private VeloceRecipeGraph() {
    }

    /** Returns (building if necessary) the graph for the given world. */
    public static VeloceRecipeGraph get(ServerLevel level) {
        Object key = level.getRecipeManager();
        VeloceRecipeGraph g = CACHE.get(key);
        if (g == null) {
            // As in VeloceRecipeRegistry - a one-off but real cost on the
            // server thread. We log it, so that it is visible rather than guessed.
            long start = System.nanoTime();
            g = build(level);
            CACHE.put(key, g);
            com.craftingveloce.util.VeloceLog.Craft.success(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "recipe graph built: %d recipe(s), %d ingredient item(s), %d ms",
                    g.recipeCount(), g.ingredientItemCount(),
                    (System.nanoTime() - start) / 1_000_000L);
        }
        return g;
    }

    /** Clears the cache - on a world change or a data reload. */
    public static void invalidate() {
        CACHE.clear();
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    private static VeloceRecipeGraph build(ServerLevel level) {
        VeloceRecipeGraph g = new VeloceRecipeGraph();
        var registries = level.registryAccess();
        var manager = level.getRecipeManager();

        // ONE pass over the recipes, not one per type. The previous version
        // called collect() three times, and each collect() walked the WHOLE
        // recipe list and filtered out the rest by getType() - that is 3x more
        // work than needed, on the server thread, on the first stock change.
        g.collect(manager, registries);

        return g;
    }

    private void collect(net.minecraft.world.item.crafting.RecipeManager manager,
                         net.minecraft.core.HolderLookup.Provider registries) {
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            var recipe = holder.value();
            // `isSpecial()` alone is NOT enough: mod recipes are marked that way
            // too (all of Mekanism) and were being cut out of the graph.
            if (!VeloceRecipeFamilies.all().contains(recipe.getType())
                    || VeloceRecipeRegistry.isVanillaSpecial(holder)) {
                continue;
            }
            ItemStack result;
            try {
                result = recipe.getResultItem(registries);
            } catch (Throwable t) {
                continue;
            }
            if (result.isEmpty()) {
                continue;
            }

            ResourceLocation id = holder.id();
            results.put(id, result.copy());

            List<Set<Item>> ing = new ArrayList<>();
            for (Ingredient in : recipe.getIngredients()) {
                ItemStack[] opts = in.getItems();
                if (opts.length == 0) {
                    continue;
                }
                Set<Item> set = new HashSet<>(opts.length * 2);
                for (ItemStack st : opts) {
                    set.add(st.getItem());
                }
                ing.add(set);
                // Reverse index: every accepted item points at this recipe.
                for (Item it : set) {
                    usedBy.computeIfAbsent(it, k -> new HashSet<>()).add(id);
                }
            }
            if (!ing.isEmpty()) {
                ingredients.put(id, ing);
            }
        }
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** Recipes that consume the given item. */
    public Set<ResourceLocation> recipesUsing(Item item) {
        return usedBy.getOrDefault(item, Set.of());
    }

    /** The result of a recipe. */
    public ItemStack resultOf(ResourceLocation id) {
        ItemStack s = results.get(id);
        return s == null ? ItemStack.EMPTY : s;
    }

    /** The ingredients of a recipe (each as a set of accepted items). */
    public List<Set<Item>> ingredientsOf(ResourceLocation id) {
        return ingredients.getOrDefault(id, List.of());
    }

    /**
     * The "upward" chain: all items whose craftability may change when the
     * availability of {@code changed} changes.
     *
     * <p>Key for performance: after crafting planks we do not recompute 3k
     * items, only the planks and what is made from them (sticks, doors, ...)
     * until the chain is exhausted.
     *
     * @param changed     the items whose stock has changed
     * @param maxResults  a fuse, so that a pathological graph does not take the server down
     */
    public Set<Item> affectedBy(Set<Item> changed, int maxResults) {
        Set<Item> affected = new HashSet<>(changed);
        List<Item> frontier = new ArrayList<>(changed);

        while (!frontier.isEmpty() && affected.size() < maxResults) {
            Item current = frontier.remove(frontier.size() - 1);
            for (ResourceLocation recipeId : recipesUsing(current)) {
                ItemStack result = results.get(recipeId);
                if (result == null || result.isEmpty()) {
                    continue;
                }
                Item out = result.getItem();
                if (affected.add(out)) {
                    frontier.add(out);
                }
                if (affected.size() >= maxResults) {
                    break;
                }
            }
        }
        return affected;
    }

    /** The number of recipes in the index (diagnostics). */
    public int recipeCount() {
        return results.size();
    }

    /** The number of items that have any use as an ingredient (diagnostics). */
    public int ingredientItemCount() {
        return usedBy.size();
    }
}
