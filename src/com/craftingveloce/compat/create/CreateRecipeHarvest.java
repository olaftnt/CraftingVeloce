package com.craftingveloce.compat.create;

import com.craftingveloce.crafting.ProcessingEntry;
import com.simibubi.create.content.kinetics.crafter.MechanicalCraftingRecipe;
import com.simibubi.create.content.processing.recipe.HeatCondition;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Tlumaczenie receptur Create na wspolny model Veloce.
 *
 * <p><b>Dwa ksztalty receptur Create:</b>
 * <ul>
 *   <li>{@code ProcessingRecipe} (milling, cutting, crushing) - lista
 *       skladnikow i lista wynikow z PRAWDOPODOBIENSTWEM
 *       ({@code ProcessingOutput.getChance()}). Planowanie zobaczy tylko
 *       wyniki gwarantowane, a wykonanie rzuci koscia - dokladnie zgodnie
 *       z polityka {@link ProcessingEntry},</li>
 *   <li>{@code MechanicalCraftingRecipe} - receptura z siatka (takze wieksza
 *       niz 3x3) i jednym wynikiem.</li>
 * </ul>
 *
 * <p><b>Czego celowo nie ma w v1:</b>
 * <ul>
 *   <li>receptur z plynami ({@code getFluidIngredients/Results}) - Veloce nie
 *       ma jeszcze warstwy plynow, a "policzenie polowy receptury" znaczyloby
 *       obietnice bez pokrycia,</li>
 *   <li>receptur wymagajacych ciepla ({@code HeatCondition != NONE}) - nasza
 *       maszyna nie ma blaze burnera, wiec taka receptura nie mialaby czym
 *       zaplacic.</li>
 * </ul>
 *
 * <p><b>Pamiec.</b> Indeks "wynik -> receptury" liczymy raz na menedzer
 * receptur, tak jak indeks waniliowy - inaczej kazde pytanie planera o item
 * skanowaloby wszystkie receptury gry (a Create ma ich kilkaset).
 */
public final class CreateRecipeHarvest {

    private CreateRecipeHarvest() {
    }

    private static final Map<RecipeManager, Map<RecipeType<?>, Map<Item, List<ProcessingEntry>>>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Indeks "wynik -> receptury" dla danego typu receptury Create. */
    public static Map<Item, List<ProcessingEntry>> index(ServerLevel level, RecipeType<?> type) {
        RecipeManager manager = level.getRecipeManager();
        Map<RecipeType<?>, Map<Item, List<ProcessingEntry>>> byType =
                CACHE.computeIfAbsent(manager, m -> new HashMap<>());
        return byType.computeIfAbsent(type, t -> build(level, t));
    }

    /** Receptury Create wytwarzajace dany item (dla jednego typu). */
    public static List<ProcessingEntry> forItem(ServerLevel level, RecipeType<?> type, Item item) {
        return index(level, type).getOrDefault(item, List.of());
    }

    /** Czysci pamiec - wolane przy zmianie swiata, razem z innymi cache'ami. */
    public static void invalidate() {
        CACHE.clear();
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
            // Do indeksu trafiaja tylko wyniki GWARANTOWANE - planer nie moze
            // obiecac itemu, ktory wypada tylko czasem.
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
        if (recipe instanceof MechanicalCraftingRecipe mechanical) {
            return mechanical(id, mechanical, type, registries);
        }
        if (recipe instanceof ProcessingRecipe<?, ?> processing) {
            return processing(id, processing, type);
        }
        return null;
    }

    /**
     * Receptura przetwarzania (mlynek, piła, kruszarka).
     *
     * <p>Wyniki z szansa ida do modelu razem z szansa; planowanie widzi tylko
     * te z szansa 1.0 (patrz {@link ProcessingEntry#guaranteedResults()}).
     */
    private static ProcessingEntry processing(ResourceLocation id,
                                              ProcessingRecipe<?, ?> recipe,
                                              RecipeType<?> type) {
        // Plyny i cieplo - poza zakresem v1 (patrz komentarz klasy).
        if (!recipe.getFluidIngredients().isEmpty() || !recipe.getFluidResults().isEmpty()) {
            return null;
        }
        // Receptury wymagajace ciepla NIE sa odrzucane: ida do planera z flaga,
        // a modul sprawdza, czy w sieci jest Blaze Burner (gracz: "jak musi byc
        // heated blaze burner, to tez mamy zaliczone, jesli tylko go mamy").
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
     * Mechanical crafting - receptura z siatka (takze wieksza niz 3x3).
     *
     * <p>Uwaga: Create oznacza te receptury jako {@code isSpecial()}, ale to
     * nie znaczy "nie do odtworzenia automatycznie" (jak waniliowe barwienie
     * zbroi) - dlatego rejestr Veloce filtruje po namespace typu, a nie po
     * samym {@code isSpecial()}.
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
        // Rozmiar siatki jedzie z receptury: bez tego crafter "umialby"
        // wszystko, takze receptury wieksze niz zbudowana siatka.
        return new ProcessingEntry(id, List.of(result.copy()), List.of(1.0f),
                ingredients, counts, type, recipe.getWidth(), recipe.getHeight());
    }
}
