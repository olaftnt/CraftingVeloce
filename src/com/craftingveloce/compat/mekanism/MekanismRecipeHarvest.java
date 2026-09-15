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
 * Tlumaczenie receptur Mekanism na wspolny model Veloce.
 *
 * <p><b>Po co.</b> Rdzen nie zna typow receptur Mekanism - zna tylko
 * {@link ProcessingEntry} (wyniki, szanse, skladniki z liczbami sztuk).
 * Ta klasa czyta receptury Mekanism i buduje z nich ten sam model, wiec planer
 * obsluguje je bez zadnej wiedzy o Mekanism.
 *
 * <p><b>Trzy ksztalty receptur, trzy konwersje:</b>
 * <ul>
 *   <li>{@code ItemStackToItemStackRecipe} (crushing, enriching) - jeden
 *       skladnik z liczba sztuk ({@code SizedIngredient.count()}) i jeden
 *       deterministyczny wynik,</li>
 *   <li>{@code SawmillRecipe} - wynik glowny (gwarantowany) plus wynik
 *       dodatkowy z szansa {@code getSecondaryChance()} - planowanie widzi
 *       tylko glowny, wykonanie rzuca koscia (polityka z {@link ProcessingEntry}),</li>
 *   <li>{@code CombinerRecipe} - DWA nazwane wejscia (main + extra), kazde
 *       z wlasna liczba sztuk.</li>
 * </ul>
 *
 * <p><b>Pamiec.</b> Receptury to dane statyczne dla danego menedzera receptur,
 * wiec indeks liczymy raz na menedzer (jak indeks waniliowy). Bez tego kazde
 * zapytanie planera o item skanowaloby wszystkie receptury gry.
 *
 * <p><b>Czego tu nie ma.</b> {@code mekanism:smelting} celowo poza rodzina -
 * dokleja wszystkie waniliowe receptury smelting, wiec dublowalby rodzine
 * pieca (patrz {@link MekanismRecipeFamily}).
 */
public final class MekanismRecipeHarvest {

    private MekanismRecipeHarvest() {
    }

    private static final Map<RecipeManager, Map<RecipeType<?>, Map<Item, List<ProcessingEntry>>>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Indeks "wynik -> receptury" dla danego typu receptury Mekanism. */
    public static Map<Item, List<ProcessingEntry>> index(ServerLevel level, RecipeType<?> type) {
        RecipeManager manager = level.getRecipeManager();
        Map<RecipeType<?>, Map<Item, List<ProcessingEntry>>> byType =
                CACHE.computeIfAbsent(manager, m -> new HashMap<>());
        return byType.computeIfAbsent(type, t -> build(level, t));
    }

    /** Receptury Mekanism wytwarzajace dany item (dla jednego typu). */
    public static List<ProcessingEntry> forItem(ServerLevel level, RecipeType<?> type, Item item) {
        return index(level, type).getOrDefault(item, List.of());
    }

    /** Czysci pamiec - wolane przy zmianie swiata, razem z innymi cache'ami. */
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
            // Do indeksu trafiaja wylacznie wyniki GWARANTOWANE - planer nie
            // moze obiecac itemu, ktory wypada tylko czasem.
            for (ItemStack result : entry.guaranteedResults()) {
                if (!result.isEmpty()) {
                    out.computeIfAbsent(result.getItem(), k -> new ArrayList<>()).add(entry);
                }
            }
        }
        return out;
    }

    /** Zamienia recepture Mekanism na wspolny model (albo {@code null}). */
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

    /** Jeden skladnik (z liczba sztuk) -> jeden deterministyczny wynik. */
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
     * Pilowanie: wynik glowny gwarantowany + wynik dodatkowy z szansa.
     *
     * <p>Planowanie zobaczy tylko wynik glowny, a wykonanie dorzuci dodatkowy
     * po rzucie koscia - gracz czasem dostanie wiecej, nigdy mniej.
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

    /** Laczenie: DWA nazwane wejscia, kazde z wlasna liczba sztuk. */
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
