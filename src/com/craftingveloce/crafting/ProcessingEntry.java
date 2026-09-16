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
 * @param gridWidth        szerokosc siatki receptury (0 = receptura bez siatki)
 * @param gridHeight       wysokosc siatki receptury (0 = receptura bez siatki)
 * @param requiresHeat     czy receptura wymaga podgrzanego Blaze Burnera
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
     * Receptura BEZ siatki (piec, maszyny item -&gt; item, crafting 3x3).
     *
     * <p>Wygodny konstruktor, zeby stare miejsca nie musialy podawac zer.
     */
    public ProcessingEntry(ResourceLocation id, List<ItemStack> results,
                           List<Float> resultChances, NonNullList<Ingredient> ingredients,
                           List<Integer> ingredientCounts, RecipeType<?> type) {
        this(id, results, resultChances, ingredients, ingredientCounts, type, 0, 0, false);
    }

    /** Receptura z siatka, ale bez ciepla (np. mechanical crafting Create). */
    public ProcessingEntry(ResourceLocation id, List<ItemStack> results,
                           List<Float> resultChances, NonNullList<Ingredient> ingredients,
                           List<Integer> ingredientCounts, RecipeType<?> type,
                           int gridWidth, int gridHeight) {
        this(id, results, resultChances, ingredients, ingredientCounts, type,
                gridWidth, gridHeight, false);
    }

    /**
     * Czy receptura zmiesci sie w siatce o boku {@code side} i {@code parts} polach.
     *
     * <p>Dotyczy tylko receptur z siatka (mechanical crafting Create): gracz
     * buduje crafter z osobnych oczek, wiec receptura 5x5 wymaga zbudowania
     * 25 oczek. Receptura bez siatki ({@code gridWidth} = 0) zawsze sie miesci -
     * dzieki temu ten sam filtr obsluguje wszystkie rodziny maszyn.
     */
    public boolean fitsGrid(int side, int parts) {
        if (gridWidth <= 0 && gridHeight <= 0) {
            return true;
        }
        // MUSZA zgadzac sie DWIE rzeczy: ksztalt receptury musi wejsc w kwadrat
        // o boku "side" ORAZ liczba zbudowanych pol musi pokryc WSZYSTKIE
        // pozycje receptury. Bez drugiego warunku 8 oczek (bok 3) przepuszczalo
        // recepture 3x3, ktora potrzebuje 9 pol.
        return gridWidth <= side && gridHeight <= side
                && gridWidth * gridHeight <= parts;
    }

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

        // PUSTE SLOTY SIATKI NIE SA SKLADNIKAMI - usuwamy je TUTAJ, dla
        // wszystkich wolajacych.
        //
        // BUG, ktory to naprawia (zgloszenie gracza: "GUI pokazuje 2 crushing
        // wheele, ale przy craftowaniu mowi, ze nie mam itemkow"): vanilla
        // reprezentuje wolne pole siatki jako {@link Ingredient#EMPTY}, wiec
        // siatka 5x5 miala 21 skladnikow i 4 puste sloty. Planer puste sloty
        // pomijal, a WYKONANIE nie - probowalo "pobrac" pusty skladnik,
        // natychmiast padalo i zwracalo komunikat o braku itemow. Objaw byl
        // mylacy: liczba byla policzona poprawnie, a craft nie dzialal nigdy.
        //
        // Filtrujemy wylacznie {@code Ingredient.EMPTY} (puste pole siatki),
        // a NIE skladniki bez zadnej opcji (pusty tag): ten drugi przypadek
        // jest bledem danych i musi byc widoczny, a nie wyciszony.
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
