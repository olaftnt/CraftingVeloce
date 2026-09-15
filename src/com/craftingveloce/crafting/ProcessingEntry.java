package com.craftingveloce.crafting;

import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.List;

/**
 * JEDEN model receptury przetwarzania - wspolny dla wszystkich modulow.
 *
 * <p><b>Dlaczego nie wystarczal stary {@code CraftingEntry}.</b> Mial JEDEN
 * wynik bez prawdopodobienstwa i zakladal jedna sztuke kazdego skladnika.
 * Zaden modul z innego moda sie w tym nie miesci:
 * <ul>
 *   <li>Alchemistry: {@code IngredientStack} niesie liczbe sztuk, Fission ma
 *       dwa wyniki, Dissolver losuje z grup,</li>
 *   <li>Mekanism: {@code SizedIngredient} ma liczbe sztuk, Sawmill losuje
 *       dodatkowy wynik, Combiner ma dwa NAZWANE wejscia,</li>
 *   <li>Create: crushing zwraca wiele wynikow z prawdopodobienstwem,
 *       mechanical crafting ma siatki wieksze niz 3x3.</li>
 * </ul>
 *
 * <p><b>Polityka prawdopodobienstwa</b> (jedna dla wszystkich modulow):
 * <ol>
 *   <li>PLANOWANIE widzi wylacznie wyniki gwarantowane
 *       ({@link #guaranteedResults()}) - planer nigdy nie obiecuje itemu,
 *       ktorego moze nie byc,</li>
 *   <li>WYKONANIE rzuca koscia za kazdy wynik osobno ({@code resultChances}),
 *       wiec gracz czasem dostanie WIECEJ, nigdy mniej.</li>
 * </ol>
 *
 * @param id               identyfikator receptury (do NBT / wyboru priorytetu)
 * @param results          wyniki (moze byc wiele - Fission ma dwa)
 * @param resultChances    prawdopodobienstwo per wynik (1.0 = gwarantowany)
 * @param ingredients      skladniki; kazdy {@link Ingredient} to alternatywy
 * @param ingredientCounts liczba sztuk per skladnik (ta sama dlugosc co lista)
 * @param type             typ receptury (do gatingu i diagnostyki)
 */
public record ProcessingEntry(
        ResourceLocation id,
        List<ItemStack> results,
        List<Float> resultChances,
        NonNullList<Ingredient> ingredients,
        List<Integer> ingredientCounts,
        RecipeType<?> type
) {

    /**
     * Pilnuje, zeby trzy rownolegle listy nie rozjechaly sie dlugoscia.
     *
     * <p>To nie jest kosmetyka: {@code ingredientCounts} indeksowane pozycja
     * skladnika, a {@code resultChances} pozycja wyniku. Krotsza lista
     * znaczylaby "skladnik bez liczby sztuk" i po cichu inne zuzycie, niz
     * zaplanowano - czyli duplikacje albo gubienie itemow.
     */
    public ProcessingEntry {
        results = List.copyOf(results);
        resultChances = List.copyOf(resultChances);
        ingredientCounts = List.copyOf(ingredientCounts);
        if (results.size() != resultChances.size()) {
            throw new IllegalArgumentException("results=" + results.size()
                    + " ale resultChances=" + resultChances.size() + " (" + id + ")");
        }
        if (ingredients.size() != ingredientCounts.size()) {
            throw new IllegalArgumentException("ingredients=" + ingredients.size()
                    + " ale ingredientCounts=" + ingredientCounts.size() + " (" + id + ")");
        }
    }

    /**
     * Receptura waniliowa (jeden wynik, jedna sztuka kazdego skladnika).
     *
     * <p>Vanilla nie zna liczb sztuk w {@link Ingredient}, wiec ta fabryka
     * NIE zmienia zachowania istniejacych rodzin - doklada tylko nowe pola.
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

    /** Czy receptura jest w pelni deterministyczna (bez prawdopodobienstwa). */
    public boolean isDeterministic() {
        for (float chance : resultChances) {
            if (chance < 1.0f) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tylko wyniki gwarantowane - to one sa podstawa PLANOWANIA.
     *
     * <p>Dzieki temu plan nigdy nie obiecuje itemu, ktorego receptura moze nie
     * dac, a mimo to wykonanie nadal rzuca koscia i czasem daje wiecej.
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
     * Wynik glowny - pierwszy gwarantowany, a gdy takich nie ma, pierwszy
     * z listy (zeby "ile sztuk na operacje" mialo sens takze dla receptur
     * wybitnie losowych).
     */
    public ItemStack primaryResult() {
        for (int i = 0; i < results.size(); i++) {
            if (resultChances.get(i) >= 1.0f) {
                return results.get(i);
            }
        }
        return results.isEmpty() ? ItemStack.EMPTY : results.get(0);
    }

    /** Liczba sztuk skladnika na pozycji - {@code 1}, gdy lista jest krotsza. */
    public int ingredientCount(int index) {
        if (index < 0 || index >= ingredientCounts.size()) {
            return 1;
        }
        return Math.max(1, ingredientCounts.get(index));
    }

    /** Krotki opis do tooltipa: "2x Deska". */
    public String describe() {
        ItemStack main = primaryResult();
        return main.getCount() + "x " + main.getHoverName().getString();
    }

    /**
     * Czy to receptura PIECA - czyli czy wymaga zabrania jednego przepalenia
     * z zasilonego pieca w sieci.
     */
    public boolean isFurnace() {
        return VeloceRecipeRegistry.isFurnaceType(type);
    }

    /** Czy receptura wymaga siatki wiekszej niz {@code size x size}. */
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
