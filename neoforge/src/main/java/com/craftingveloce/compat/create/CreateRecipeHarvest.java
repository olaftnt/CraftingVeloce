package com.craftingveloce.compat.create;

import com.craftingveloce.crafting.ProcessingEntry;
import com.simibubi.create.content.kinetics.crafter.MechanicalCraftingRecipe;
import com.simibubi.create.content.processing.recipe.HeatCondition;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedRecipe;
import net.minecraft.core.HolderLookup;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Translation of Create recipes into the common Veloce model.
 *
 * <p><b>Two shapes of Create recipes:</b>
 * <ul>
 *   <li>{@code ProcessingRecipe} (milling, cutting, crushing) - a list of
 *       ingredients and a list of results with PROBABILITY
 *       ({@code ProcessingOutput.getChance()}). Planning will only see the
 *       guaranteed results, while execution rolls the dice - exactly as the
 *       policy of {@link ProcessingEntry} prescribes,</li>
 *   <li>{@code MechanicalCraftingRecipe} - a recipe with a grid (also larger
 *       than 3x3) and a single result.</li>
 * </ul>
 *
 * <p><b>What is deliberately missing in v1:</b>
 * <ul>
 *   <li>recipes with fluids ({@code getFluidIngredients/Results}) - Veloce does
 *       not have a fluid layer yet, and "counting half a recipe" would mean
 *       making a promise we cannot keep,</li>
 *   <li>recipes requiring heat ({@code HeatCondition != NONE}) - our machine
 *       has no blaze burner, so such a recipe would have nothing to pay
 *       with.</li>
 * </ul>
 *
 * <p><b>Memory.</b> We compute the "result -> recipes" index once per recipe
 * manager, just like the vanilla index - otherwise every planner question about
 * an item would scan every recipe in the game (and Create has several hundred
 * of them).
 */
public final class CreateRecipeHarvest {

    private CreateRecipeHarvest() {
    }

    private static final Map<RecipeManager, Map<RecipeType<?>, Map<Item, List<ProcessingEntry>>>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * recipe id -> the recipe types its SEQUENCE steps through.
     *
     * <p>A sequence is not one machine, and our model is "machine owns types", so the
     * steps are required per RECIPE instead: this side map is what
     * {@code CreateModule.requirementsMet} consults, and without it a network with a
     * single Deployer would happily promise a {@code create:track}.
     */
    private static final Map<ResourceLocation, Set<RecipeType<?>>> STEP_TYPES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The recipe types a sequence steps through - empty for every other recipe. */
    public static Set<RecipeType<?>> stepTypesFor(ResourceLocation recipeId) {
        Set<RecipeType<?>> out = STEP_TYPES.get(recipeId);
        return out == null ? Set.of() : out;
    }

    /** The "result -> recipes" index for a given Create recipe type. */
    public static Map<Item, List<ProcessingEntry>> index(ServerLevel level, RecipeType<?> type) {
        RecipeManager manager = level.getRecipeManager();
        Map<RecipeType<?>, Map<Item, List<ProcessingEntry>>> byType =
                CACHE.computeIfAbsent(manager, m -> new HashMap<>());
        return byType.computeIfAbsent(type, t -> build(level, t));
    }

    /** Create recipes producing the given item (for one type). */
    public static List<ProcessingEntry> forItem(ServerLevel level, RecipeType<?> type, Item item) {
        return index(level, type).getOrDefault(item, List.of());
    }

    /** Clears the memory - called on world change, together with the other caches. */
    public static void invalidate() {
        CACHE.clear();
        STEP_TYPES.clear();
    }

    private static Map<Item, List<ProcessingEntry>> build(ServerLevel level, RecipeType<?> type) {
        Map<Item, List<ProcessingEntry>> out = new HashMap<>();
        HolderLookup.Provider registries = level.registryAccess();
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (recipe.getType() != type || recipe.isIncomplete()) {
                continue;
            }
            ProcessingEntry entry = convert(holder.id(), recipe, type, registries);
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

    private static ProcessingEntry convert(ResourceLocation id, Recipe<?> recipe,
                                           RecipeType<?> type,
                                           HolderLookup.Provider registries) {
        if (recipe instanceof SequencedAssemblyRecipe sequenced) {
            return sequenced(id, sequenced, type);
        }
        if (recipe instanceof MechanicalCraftingRecipe mechanical) {
            return mechanical(id, mechanical, type, registries);
        }
        if (recipe instanceof ProcessingRecipe<?, ?> processing) {
            return processing(id, processing, type);
        }
        return null;
    }

    /**
     * A SEQUENCE of machines acting on one transitional item.
     *
     * <p>Everything the model needs comes from the step's own {@link IAssemblyRecipe}:
     * {@code addAssemblyIngredients} for what the step consumes,
     * {@code addAssemblyFluidIngredients} to detect a fluid step, and
     * {@code getRecipe().getType()} for which machine has to be in the network.
     *
     * <p>Two refusals, both deliberate:
     * <ul>
     *   <li>a step needing a FLUID puts the whole sequence out of scope - our network has
     *       no fluid layer, and half a recipe is not a recipe (this is what removes
     *       {@code sturdy_sheet}, whose first step fills 500 mB of lava);</li>
     *   <li>a result POOL of several outcomes is not a promise the auto-crafter can keep.
     *       {@code precision_mechanism} rolls one of eight weighted entries, so it is not
     *       offered; only a single guaranteed output ({@code track}) is.</li>
     * </ul>
     */
    /**
     * Why a sequence produced no entry.
     *
     * <p>Four refusals live in {@link #sequenced}, and a refusal and an absence are
     * indistinguishable from the outside: the filtered `/cv testmodule list` said "offers 0
     * recipe(s)" and three rounds were spent guessing which of the four it was. This names
     * it, which is the same lesson the test rig taught when its one-line state dump ended
     * three rounds of the same guessing about power.
     */
    private static void LOG_REFUSAL(ResourceLocation id, String why) {
        com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                "[create] sequenced assembly {} not offered: {}", id, why);
    }

    private static ProcessingEntry sequenced(ResourceLocation id, SequencedAssemblyRecipe recipe,
                                             RecipeType<?> type) {
        int loops = Math.max(1, recipe.getLoops());
        NonNullList<Ingredient> ingredients = NonNullList.create();
        List<Integer> counts = new ArrayList<>();
        Set<RecipeType<?>> steps = new LinkedHashSet<>();

        // The item the sequence starts from.
        ingredients.add(recipe.getIngredient());
        counts.add(1);

        for (SequencedRecipe<?> step : recipe.getSequence()) {
            // THE STEP'S OWN ProcessingRecipe, not IAssemblyRecipe.
            //
            // The first version of this converter asked every step for IAssemblyRecipe and
            // refused the whole sequence when it was absent - and it is OPTIONAL: pressing
            // does not implement it, so `track` (deploying, deploying, PRESSING) produced
            // nothing at all, silently, while the type showed three recipes in the audit.
            // Every step IS a ProcessingRecipe, which is the same shape create:deploying
            // already converts through, so that is what is read here.
            ProcessingRecipe<?, ?> stepRecipe = step.getRecipe();
            if (stepRecipe == null) {
                LOG_REFUSAL(id, "a step with no recipe");
                return null;
            }
            if (!stepRecipe.getFluidIngredients().isEmpty()
                    || !stepRecipe.getFluidResults().isEmpty()) {
                LOG_REFUSAL(id, "a step needs a fluid");
                return null;
            }
            for (Ingredient stepIngredient : stepRecipe.getIngredients()) {
                if (stepIngredient == null || stepIngredient.isEmpty()) {
                    continue;
                }
                ingredients.add(stepIngredient);
                // The sequence repeats, so the step's items are consumed `loops` times.
                counts.add(loops);
            }
            steps.add(stepRecipe.getType());
        }

        List<ProcessingOutput> pool = recipe.resultPool;
        if (pool.size() != 1) {
            LOG_REFUSAL(id, "a result pool of " + pool.size() + " outcome(s)");
            return null;
        }
        ItemStack result = pool.get(0).getStack();
        if (result.isEmpty()) {
            LOG_REFUSAL(id, "an empty result");
            return null;
        }
        List<ItemStack> results = new ArrayList<>();
        List<Float> chances = new ArrayList<>();
        results.add(result.copy());
        chances.add(Math.max(0.0f, pool.get(0).getChance()));

        STEP_TYPES.put(id, Set.copyOf(steps));
        return new ProcessingEntry(id, results, chances, ingredients, counts, type, 0, 0, false);
    }

    /**
     * A processing recipe (millstone, saw, crusher).
     *
     * <p>Results with a chance go into the model together with that chance;
     * planning only sees the ones with chance 1.0 (see
     * {@link ProcessingEntry#guaranteedResults()}).
     */
    private static ProcessingEntry processing(ResourceLocation id,
                                              ProcessingRecipe<?, ?> recipe,
                                              RecipeType<?> type) {
        // Fluids are out of scope (no fluid layer). Heat is NOT rejected - it
        // travels on with the requiresHeat flag.
        if (!recipe.getFluidIngredients().isEmpty() || !recipe.getFluidResults().isEmpty()) {
            return null;
        }
        // Recipes requiring heat are NOT rejected: they go to the planner with
        // the flag, and the module checks whether there is a Blaze Burner in the
        // network (player: "if it has to be a heated blaze burner, then that
        // counts too, as long as we have one").
        boolean requiresHeat = recipe.getRequiredHeat() != HeatCondition.NONE;
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) {
            return null;
        }
        List<ItemStack> results = new ArrayList<>();
        List<Float> chances = new ArrayList<>();
        for (ProcessingOutput output : recipe.getRollableResults()) {
            ItemStack stack = output.getStack();
            if (!stack.isEmpty()) {
                results.add(stack.copy());
                chances.add(Math.max(0.0f, output.getChance()));
            }
        }
        if (results.isEmpty()) {
            return null;
        }
        List<Integer> counts = new ArrayList<>(ingredients.size());
        for (int i = 0; i < ingredients.size(); i++) {
            counts.add(1);
        }
        return new ProcessingEntry(id, results, chances, ingredients, counts, type,
                0, 0, requiresHeat);
    }

    /**
     * Mechanical crafting - a recipe with a grid (also larger than 3x3).
     *
     * <p>Note: Create marks these recipes as {@code isSpecial()}, but that does
     * not mean "not reproducible automatically" (like vanilla armor dyeing) -
     * that is why the Veloce registry filters by the type's namespace and not
     * by {@code isSpecial()} alone.
     */
    private static ProcessingEntry mechanical(ResourceLocation id,
                                              MechanicalCraftingRecipe recipe,
                                              RecipeType<?> type,
                                              HolderLookup.Provider registries) {
        ItemStack result;
        try {
            result = recipe.getResultItem(registries);
        } catch (Exception ex) {
            return null;
        }
        if (result.isEmpty()) {
            return null;
        }
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) {
            return null;
        }
        List<Integer> counts = new ArrayList<>(ingredients.size());
        for (int i = 0; i < ingredients.size(); i++) {
            counts.add(1);
        }
        // The grid size travels with the recipe: without it the crafter "would
        // be able to" do everything, including recipes larger than the built
        // grid.
        return new ProcessingEntry(id, List.of(result.copy()), List.of(1.0f),
                ingredients, counts, type, recipe.getWidth(), recipe.getHeight());
    }
}
