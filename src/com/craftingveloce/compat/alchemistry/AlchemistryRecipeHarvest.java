package com.craftingveloce.compat.alchemistry;

import com.craftingveloce.crafting.ProcessingEntry;
import com.smashingmods.alchemistry.common.recipe.combiner.CombinerRecipe;
import com.smashingmods.alchemistry.common.recipe.compactor.CompactorRecipe;
import com.smashingmods.alchemistry.common.recipe.fission.FissionRecipe;
import com.smashingmods.alchemistry.common.recipe.fusion.FusionRecipe;
import com.smashingmods.alchemistry.registry.RecipeRegistry;
import com.smashingmods.alchemylib.api.item.IngredientStack;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Tlumaczenie receptur Alchemistry na wspolny model Veloce.
 *
 * <p><b>Po co.</b> Rdzen zna tylko {@link ProcessingEntry}, a Alchemistry ma
 * wlasne modele: skladniki to {@link IngredientStack} (ingredient + liczba
 * sztuk), fission ma DWA wyniki, combiner ma liste skladnikow. Ta klasa czyta
 * receptury przez publiczne API Alchemistry ({@link RecipeRegistry}) i buduje
 * z nich wspolny model, wiec planer nie wie nic o Alchemistry.
 *
 * <p><b>Cztery ksztalty, cztery konwersje:</b>
 * <ul>
 *   <li>compactor: {@code IngredientStack} -> 1 wynik,</li>
 *   <li>combiner: lista {@code IngredientStack} -> 1 wynik,</li>
 *   <li>fission: 1 item -> 2 wyniki (deterministyczne),</li>
 *   <li>fusion: 2 itemy -> 1 wynik.</li>
 * </ul>
 *
 * <p><b>Czego tu nie ma.</b> Dissolver (probabilistyczny {@code ProbabilitySet})
 * oraz liquifier/atomizer (plyny) - pozniejsze etapy. Planowanie widzi wylacznie
 * wyniki gwarantowane, wiec nawet przypadkowe grupy dissolvera nie moglyby
 * obiecywac itemu, ktory moze nie wypasc.
 *
 * <p><b>Pamiec.</b> Alchemistry sam cache'uje swoje listy receptur, ale my
 * budujemy z nich indeks "wynik -> receptury". Liczymy go raz na menedzer
 * receptur, tak jak indeks waniliowy.
 */
public final class AlchemistryRecipeHarvest {

    private AlchemistryRecipeHarvest() {
    }

    private static final Map<RecipeManager, Map<RecipeType<?>, Map<Item, List<ProcessingEntry>>>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Indeks "wynik -> receptury" dla danego typu receptury Alchemistry. */
    public static Map<Item, List<ProcessingEntry>> index(ServerLevel level, RecipeType<?> type) {
        RecipeManager manager = level.getRecipeManager();
        Map<RecipeType<?>, Map<Item, List<ProcessingEntry>>> byType =
                CACHE.computeIfAbsent(manager, m -> new HashMap<>());
        return byType.computeIfAbsent(type, t -> build(level, t));
    }

    /** Receptury Alchemistry wytwarzajace dany item (dla jednego typu). */
    public static List<ProcessingEntry> forItem(ServerLevel level, RecipeType<?> type, Item item) {
        return index(level, type).getOrDefault(item, List.of());
    }

    /** Czysci pamiec - wolane przy zmianie swiata, razem z innymi cache'ami. */
    public static void invalidate() {
        CACHE.clear();
    }

    private static Map<Item, List<ProcessingEntry>> build(ServerLevel level, RecipeType<?> type) {
        Map<Item, List<ProcessingEntry>> out = new HashMap<>();
        if (type == AlchemistryRecipeFamily.compactor()) {
            for (CompactorRecipe recipe : RecipeRegistry.getCompactorRecipes(level)) {
                add(out, compactor(recipe));
            }
        } else if (type == AlchemistryRecipeFamily.combiner()) {
            for (CombinerRecipe recipe : RecipeRegistry.getCombinerRecipes(level)) {
                add(out, combiner(recipe));
            }
        } else if (type == AlchemistryRecipeFamily.fission()) {
            for (FissionRecipe recipe : RecipeRegistry.getFissionRecipes(level)) {
                add(out, fission(recipe));
            }
        } else if (type == AlchemistryRecipeFamily.fusion()) {
            for (FusionRecipe recipe : RecipeRegistry.getFusionRecipes(level)) {
                add(out, fusion(recipe));
            }
        }
        return out;
    }

    private static void add(Map<Item, List<ProcessingEntry>> out, ProcessingEntry entry) {
        if (entry == null) {
            return;
        }
        for (ItemStack result : entry.guaranteedResults()) {
            if (!result.isEmpty()) {
                out.computeIfAbsent(result.getItem(), k -> new ArrayList<>()).add(entry);
            }
        }
    }

    /** Compactor: jeden skladnik z liczba sztuk -> jeden wynik. */
    private static ProcessingEntry compactor(CompactorRecipe recipe) {
        return fromIngredientStack(recipe.getId(), recipe.getInput(),
                List.of(recipe.getOutput()), recipe.getType());
    }

    /** Combiner: lista skladnikow (kazdy z liczba sztuk) -> jeden wynik. */
    private static ProcessingEntry combiner(CombinerRecipe recipe) {
        List<IngredientStack> inputs = recipe.getInput();
        if (inputs == null || inputs.isEmpty()) {
            return null;
        }
        NonNullList<Ingredient> ingredients = NonNullList.create();
        List<Integer> counts = new ArrayList<>(inputs.size());
        for (IngredientStack input : inputs) {
            if (input == null || input.isEmpty()) {
                continue;
            }
            ingredients.add(input.getIngredient());
            counts.add(Math.max(1, input.getCount()));
        }
        if (ingredients.isEmpty()) {
            return null;
        }
        return new ProcessingEntry(recipe.getId(), List.of(recipe.getOutput().copy()),
                List.of(1.0f), ingredients, counts, recipe.getType());
    }

    /** Fission: jeden item -> DWA wyniki (oba gwarantowane). */
    private static ProcessingEntry fission(FissionRecipe recipe) {
        ItemStack input = recipe.getInput();
        if (input.isEmpty()) {
            return null;
        }
        List<ItemStack> results = new ArrayList<>(2);
        List<Float> chances = new ArrayList<>(2);
        for (ItemStack result : List.of(recipe.getOutput1(), recipe.getOutput2())) {
            if (!result.isEmpty()) {
                results.add(result.copy());
                chances.add(1.0f);
            }
        }
        if (results.isEmpty()) {
            return null;
        }
        NonNullList<Ingredient> ingredients = NonNullList.withSize(1, Ingredient.EMPTY);
        ingredients.set(0, Ingredient.of(input));
        return new ProcessingEntry(recipe.getId(), results, chances, ingredients,
                List.of(Math.max(1, input.getCount())), recipe.getType());
    }

    /** Fusion: DWA itemy -> jeden wynik (kolejnosc dowolna). */
    private static ProcessingEntry fusion(FusionRecipe recipe) {
        ItemStack first = recipe.getInput1();
        ItemStack second = recipe.getInput2();
        if (first.isEmpty() || second.isEmpty()) {
            return null;
        }
        NonNullList<Ingredient> ingredients = NonNullList.withSize(2, Ingredient.EMPTY);
        ingredients.set(0, Ingredient.of(first));
        ingredients.set(1, Ingredient.of(second));
        return new ProcessingEntry(recipe.getId(), List.of(recipe.getOutput().copy()),
                List.of(1.0f), ingredients,
                List.of(Math.max(1, first.getCount()), Math.max(1, second.getCount())),
                recipe.getType());
    }

    /** Wspolna konwersja dla receptur z jednym {@link IngredientStack}. */
    private static ProcessingEntry fromIngredientStack(ResourceLocation id, IngredientStack input,
                                                       List<ItemStack> results,
                                                       RecipeType<?> type) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        List<ItemStack> copies = new ArrayList<>(results.size());
        List<Float> chances = new ArrayList<>(results.size());
        for (ItemStack result : results) {
            if (!result.isEmpty()) {
                copies.add(result.copy());
                chances.add(1.0f);
            }
        }
        if (copies.isEmpty()) {
            return null;
        }
        NonNullList<Ingredient> ingredients = NonNullList.withSize(1, Ingredient.EMPTY);
        ingredients.set(0, input.getIngredient());
        return new ProcessingEntry(id, copies, chances, ingredients,
                List.of(Math.max(1, input.getCount())), type);
    }
}
