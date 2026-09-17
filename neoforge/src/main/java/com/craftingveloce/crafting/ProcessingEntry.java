package com.craftingveloce.crafting;

import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.List;

/**
 * ONE processing recipe model - shared by all modules.
 *
 * <p><b>Why the old {@code CraftingEntry} was not enough.</b> It had ONE
 * result with no probability and assumed a single unit of every ingredient.
 * No module from another mod fits that:
 * <ul>
 *   <li>Alchemistry: {@code IngredientStack} carries a unit count, Fission has
 *       two results, Dissolver rolls from groups,</li>
 *   <li>Mekanism: {@code SizedIngredient} has a unit count, Sawmill rolls an
 *       extra result, Combiner has two NAMED inputs,</li>
 *   <li>Create: crushing returns multiple results with probabilities,
 *       mechanical crafting has grids larger than 3x3.</li>
 * </ul>
 *
 * <p><b>Probability policy</b> (one for all modules):
 * <ol>
 *   <li>PLANNING sees only guaranteed results
 *       ({@link #guaranteedResults()}) - the planner never promises an item
 *       that may not appear,</li>
 *   <li>EXECUTION rolls the dice for each result separately
 *       ({@code resultChances}), so the player sometimes gets MORE, never
 *       less.</li>
 * </ol>
 *
 * @param id               recipe identifier (for NBT / priority selection)
 * @param results          results (there may be several - Fission has two)
 * @param resultChances    probability per result (1.0 = guaranteed)
 * @param ingredients      ingredients; each {@link Ingredient} is a set of alternatives
 * @param ingredientCounts unit count per ingredient (same length as the list)
 * @param type             recipe type (for gating and diagnostics)
 * @param gridWidth        recipe grid width (0 = recipe without a grid)
 * @param gridHeight       recipe grid height (0 = recipe without a grid)
 * @param requiresHeat     whether the recipe requires a heated Blaze Burner
 */
public record ProcessingEntry(
        ResourceLocation id,
        List<ItemStack> results,
        List<Float> resultChances,
        NonNullList<Ingredient> ingredients,
        List<Integer> ingredientCounts,
        RecipeType<?> type,
        int gridWidth,
        int gridHeight,
        boolean requiresHeat
) {

    /**
     * A recipe WITHOUT a grid (furnace, item -&gt; item machines, 3x3 crafting).
     *
     * <p>Convenience constructor, so old call sites do not have to pass zeros.
     */
    public ProcessingEntry(ResourceLocation id, List<ItemStack> results,
                           List<Float> resultChances, NonNullList<Ingredient> ingredients,
                           List<Integer> ingredientCounts, RecipeType<?> type) {
        this(id, results, resultChances, ingredients, ingredientCounts, type, 0, 0, false);
    }

    /** A recipe with a grid, but without heat (e.g. Create mechanical crafting). */
    public ProcessingEntry(ResourceLocation id, List<ItemStack> results,
                           List<Float> resultChances, NonNullList<Ingredient> ingredients,
                           List<Integer> ingredientCounts, RecipeType<?> type,
                           int gridWidth, int gridHeight) {
        this(id, results, resultChances, ingredients, ingredientCounts, type,
                gridWidth, gridHeight, false);
    }

    /**
     * Whether the recipe fits in a grid of side {@code side} and {@code parts} cells.
     *
     * <p>This concerns only recipes with a grid (Create mechanical crafting):
     * the player builds a crafter out of separate cells, so a 5x5 recipe
     * requires building 25 cells. A recipe without a grid ({@code gridWidth} =
     * 0) always fits - thanks to that the same filter serves all machine
     * families.
     */
    public boolean fitsGrid(int side, int parts) {
        if (gridWidth <= 0 && gridHeight <= 0) {
            return true;
        }
        // TWO things MUST match: the recipe shape must fit into a square of
        // side "side" AND the number of built cells must cover ALL recipe
        // positions. Without the second condition 8 cells (side 3) let through
        // a 3x3 recipe that needs 9 cells.
        return gridWidth <= side && gridHeight <= side
                && gridWidth * gridHeight <= parts;
    }

    /**
     * Keeps the three parallel lists from drifting apart in length.
     *
     * <p>This is not cosmetic: {@code ingredientCounts} is indexed by
     * ingredient position, and {@code resultChances} by result position. A
     * shorter list would mean "an ingredient with no unit count" and silently
     * different consumption than planned - that is, duplication or loss of
     * items.
     */
    public ProcessingEntry {
        results = List.copyOf(results);
        resultChances = List.copyOf(resultChances);
        ingredientCounts = List.copyOf(ingredientCounts);
        if (results.size() != resultChances.size()) {
            throw new IllegalArgumentException("results=" + results.size()
                    + " but resultChances=" + resultChances.size() + " (" + id + ")");
        }
        if (ingredients.size() != ingredientCounts.size()) {
            throw new IllegalArgumentException("ingredients=" + ingredients.size()
                    + " but ingredientCounts=" + ingredientCounts.size() + " (" + id + ")");
        }

        // EMPTY GRID SLOTS ARE NOT INGREDIENTS - we remove them HERE, for all
        // callers.
        //
        // The BUG this fixes (player report: "the GUI shows 2 crushing
        // wheels, but when crafting it says I have no items"): vanilla
        // represents a free grid cell as {@link Ingredient#EMPTY}, so a 5x5
        // grid had 21 ingredients and 4 empty slots. The planner skipped the
        // empty slots, but EXECUTION did not - it tried to "take" an empty
        // ingredient, failed immediately and returned a message about missing
        // items. The symptom was misleading: the count was calculated
        // correctly, yet the craft never worked.
        //
        // We filter out only {@code Ingredient.EMPTY} (an empty grid cell),
        // and NOT ingredients with no options at all (an empty tag): the
        // latter case is a data error and must stay visible, not silenced.
        if (containsBlankSlot(ingredients)) {
            NonNullList<Ingredient> kept = NonNullList.create();
            List<Integer> keptCounts = new ArrayList<>(ingredientCounts.size());
            for (int i = 0; i < ingredients.size(); i++) {
                if (ingredients.get(i) == Ingredient.EMPTY) {
                    continue;
                }
                kept.add(ingredients.get(i));
                keptCounts.add(ingredientCounts.get(i));
            }
            ingredients = kept;
            ingredientCounts = List.copyOf(keptCounts);
        }
    }

    private static boolean containsBlankSlot(NonNullList<Ingredient> ingredients) {
        for (Ingredient ingredient : ingredients) {
            if (ingredient == Ingredient.EMPTY) {
                return true;
            }
        }
        return false;
    }

    /**
     * A vanilla recipe (one result, one unit of every ingredient).
     *
     * <p>Vanilla does not know unit counts in {@link Ingredient}, so this
     * factory does NOT change the behaviour of existing families - it only
     * adds the new fields.
     */
    public static ProcessingEntry single(ResourceLocation id, ItemStack result,
                                         NonNullList<Ingredient> ingredients,
                                         RecipeType<?> type) {
        List<Integer> counts = new ArrayList<>(ingredients.size());
        for (int i = 0; i < ingredients.size(); i++) {
            counts.add(1);
        }
        return new ProcessingEntry(id, List.of(result), List.of(1.0f), ingredients, counts, type);
    }

    /** Whether the recipe is fully deterministic (no probability). */
    public boolean isDeterministic() {
        for (float chance : resultChances) {
            if (chance < 1.0f) {
                return false;
            }
        }
        return true;
    }

    /**
     * Only guaranteed results - these are the basis of PLANNING.
     *
     * <p>Thanks to that a plan never promises an item the recipe may not give,
     * and yet execution still rolls the dice and sometimes yields more.
     */
    public List<ItemStack> guaranteedResults() {
        List<ItemStack> out = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            if (resultChances.get(i) >= 1.0f) {
                out.add(results.get(i));
            }
        }
        return out;
    }

    /**
     * The primary result - the first guaranteed one, and when there is none,
     * the first on the list (so that "how many units per operation" also makes
     * sense for eminently random recipes).
     */
    public ItemStack primaryResult() {
        for (int i = 0; i < results.size(); i++) {
            if (resultChances.get(i) >= 1.0f) {
                return results.get(i);
            }
        }
        return results.isEmpty() ? ItemStack.EMPTY : results.get(0);
    }

    /** Unit count of the ingredient at the given position - {@code 1} when the list is shorter. */
    public int ingredientCount(int index) {
        if (index < 0 || index >= ingredientCounts.size()) {
            return 1;
        }
        return Math.max(1, ingredientCounts.get(index));
    }

    /** Short tooltip description: "2x Oak Planks". */
    public String describe() {
        ItemStack main = primaryResult();
        return main.getCount() + "x " + main.getHoverName().getString();
    }

    /**
     * Whether this is a FURNACE recipe - that is, whether it requires taking
     * one smelting operation from a powered furnace in the network.
     */
    public boolean isFurnace() {
        return VeloceRecipeRegistry.isFurnaceType(type);
    }

    /** Whether the recipe requires a grid larger than {@code size x size}. */
    public boolean needsGrid(int size) {
        if (type != RecipeType.CRAFTING) {
            return false;
        }
        int used = 0;
        for (Ingredient ing : ingredients) {
            if (ing.getItems().length > 0) {
                used++;
            }
        }
        return used > size * size;
    }
}
