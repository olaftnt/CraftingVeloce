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
 * Odwrotny indeks receptur: item skladnik -> itemy, ktore mozna z niego zrobic.
 *
 * <p>To fundament przyrostowego przeliczania. Bez niego, zeby odpowiedziec
 * "co sie zmienilo po skraftowaniu desek", trzeba by przejrzec wszystkie 15k
 * receptur. Z indeksem wystarczy zajrzec do jednego wpisu i przejsc w gore
 * po chainie.
 *
 * <p>Indeks jest budowany raz na {@link net.minecraft.world.item.crafting.RecipeManager}
 * i cache'owany. Przebudowa nastepuje po przeladowaniu danych (nowy manager).
 *
 * <p>Pamiec: dla 15k receptur i 3k itemow to kilka map - zaniedbywalne
 * w porownaniu z oszczednoscia czasu CPU.
 */
public final class VeloceRecipeGraph {

    /**
     * Cache per RecipeManager.
     *
     * <p>Slabe klucze - patrz komentarz w {@link VeloceRecipeRegistry}. Ta
     * mapa miala dokladnie ten sam wyciek: trzymala RecipeManager na sztywno,
     * wiec kazde wejscie do swiata zostawialo po sobie caly graf receptur.
     */
    private static final Map<Object, VeloceRecipeGraph> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** item skladnik -> id receptur, ktore go uzywaja. */
    private final Map<Item, Set<ResourceLocation>> usedBy = new HashMap<>();

    /** id receptury -> wynik (item + ilosc). */
    private final Map<ResourceLocation, ItemStack> results = new HashMap<>();

    /** id receptury -> lista skladnikow (kazdy jako zbior akceptowanych itemow). */
    private final Map<ResourceLocation, List<Set<Item>>> ingredients = new HashMap<>();

    /** Typy receptur, ktore nas interesuja - te bez infrastruktury. */
    /** Jedno zrodlo prawdy o rodzinach - patrz {@link VeloceRecipeFamilies}. */
    private static final java.util.Set<RecipeType<?>> FREE_TYPES = VeloceRecipeFamilies.FREE;

    private VeloceRecipeGraph() {
    }

    /** Zwraca (budujac w razie potrzeby) graf dla danego swiata. */
    public static VeloceRecipeGraph get(ServerLevel level) {
        Object key = level.getRecipeManager();
        VeloceRecipeGraph g = CACHE.get(key);
        if (g == null) {
            // Jak w VeloceRecipeRegistry - jednorazowy, ale realny koszt na
            // watku serwera. Logujemy, zeby byl widoczny, a nie zgadywany.
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

    /** Czysci cache - przy zmianie swiata lub przeladowaniu danych. */
    public static void invalidate() {
        CACHE.clear();
    }

    // ------------------------------------------------------------------
    // Budowa
    // ------------------------------------------------------------------

    private static VeloceRecipeGraph build(ServerLevel level) {
        VeloceRecipeGraph g = new VeloceRecipeGraph();
        var registries = level.registryAccess();
        var manager = level.getRecipeManager();

        // JEDNO przejscie po recepturach, nie jedno na typ. Poprzednia wersja
        // wolala collect() trzy razy, a kazde collect() przechodzilo CALA liste
        // receptur i odsiewalo reszte po getType() - czyli 3x wiecej pracy niz
        // trzeba, na watku serwera, przy pierwszej zmianie stocku.
        g.collect(manager, registries);

        return g;
    }

    private void collect(net.minecraft.world.item.crafting.RecipeManager manager,
                         net.minecraft.core.HolderLookup.Provider registries) {
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            var recipe = holder.value();
            // `isSpecial()` sam NIE wystarcza: receptury modow tez tak sie
            // oznaczaja (Mekanism wszystkie) i byly wycinane z grafu.
            if (!FREE_TYPES.contains(recipe.getType())
                    || VeloceRecipeRegistry.isVanillaSpecial(recipe)) {
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
                // Odwrotny indeks: kazdy akceptowany item wskazuje na te recepture.
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
    // Zapytania
    // ------------------------------------------------------------------

    /** Receptury, ktore zuzywaja dany item. */
    public Set<ResourceLocation> recipesUsing(Item item) {
        return usedBy.getOrDefault(item, Set.of());
    }

    /** Wynik receptury. */
    public ItemStack resultOf(ResourceLocation id) {
        ItemStack s = results.get(id);
        return s == null ? ItemStack.EMPTY : s;
    }

    /** Skladniki receptury (kazdy jako zbior akceptowanych itemow). */
    public List<Set<Item>> ingredientsOf(ResourceLocation id) {
        return ingredients.getOrDefault(id, List.of());
    }

    /**
     * Chain "w gore": wszystkie itemy, ktorych craftowalnosc moze sie zmienic,
     * gdy zmieni sie dostepnosc {@code changed}.
     *
     * <p>Kluczowe dla wydajnosci: przy skraftowaniu desek nie przeliczamy 3k
     * itemow, tylko deski i to, co z nich powstaje (plotki, drzwi, ...)
     * az do wyczerpania lancucha.
     *
     * @param changed     itemy, ktorych stock sie zmienil
     * @param maxResults  bezpiecznik, zeby patologiczny graf nie zamknal serwera
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

    /** Liczba receptur w indeksie (diagnostyka). */
    public int recipeCount() {
        return results.size();
    }

    /** Liczba itemow majacych jakiekolwiek uzycie jako skladnik (diagnostyka). */
    public int ingredientItemCount() {
        return usedBy.size();
    }
}
