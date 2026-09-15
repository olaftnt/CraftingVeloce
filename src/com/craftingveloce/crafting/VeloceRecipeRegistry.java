package com.craftingveloce.crafting;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rejestr receptur "craftowalnych bez infrastruktury".
 *
 * <p>Zbiera receptury, ktore mozna wykonac siedzac w sieci Veloce - czyli takie,
 * ktore NIE wymagaja energii, paliwa, many, XP ani zadnego zewnetrznego bloku
 * przetwarzajacego. Klasyfikacja opiera sie na typie receptury:
 *
 * <ul>
 *   <li>{@code minecraft:crafting} - crafting table (shaped/shapeless/special).
 *       Zawsze craftowalne: nie wymaga paliwa.</li>
 *   <li>{@code minecraft:stonecutting} - przecinarka. Nie wymaga paliwa
 *       (w vanilla), ale wymaga bloku. Traktujemy jako craftowalne, bo to
 *       "block ala crafting".</li>
 *   <li>{@code minecraft:smithing} - kowadlo smithingowe. Bez paliwa.</li>
 * </ul>
 *
 * <p><b>Czego NIE zbieramy:</b> {@code smelting}, {@code blasting}, {@code smoking},
 * {@code campfire_cooking} (wymagaja paliwa), oraz wszystko co rejestruja mody
 * pod wlasnymi typami z energia/mana (Mekanism, Create itd.). Receptury modow
 * sa wylaczane takze wtedy, gdy ich serializer/typ pochodzi z namespace innego
 * niz {@code minecraft} ORAZ nie jest znany jako "bezinfrastrukturowy" -
 * to bezpieczna domyslna polityka, zeby nie probowac craftowac czegos,
 * czego nie umiemy odtworzyc.
 *
 * <p>Klasyfikacja jest cache'owana per-{@link RecipeManager}, bo skanowanie
 * wszystkich receptur jest kosztowne i nie zmienia sie w trakcie dzialania
 * (poza reloadem datapackow, co daje nowy RecipeManager).
 */
public final class VeloceRecipeRegistry {

    /** Typy receptur, ktore uznajemy za wykonywalne bez energii/paliwa. */
    private static final Set<RecipeType<?>> FREE_TYPES = Set.of(
            RecipeType.CRAFTING,
            RecipeType.STONECUTTING,
            RecipeType.SMITHING
    );

    /**
     * Namespace'y modow, ktorych receptury chcemy dodatkowo brac pod uwage,
     * mimo ze uzywaja wlasnego typu. Na razie puste - swiadomie konserwatywnie.
     * Dodawac tylko po zweryfikowaniu, ze dany typ nie wymaga infrastruktury.
     */
    private static final Set<String> TRUSTED_MOD_NAMESPACES = Set.of();

    /** Cache: RecipeManager -> (wynik Item -> lista receptur). */
    private static final Map<RecipeManager, Map<Item, List<CraftingEntry>>> CACHE =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private VeloceRecipeRegistry() {
    }

    /**
     * Pojedyncza receptura zdolna wyprodukowac dany item.
     *
     * @param id          identyfikator receptury (do NBT / wyboru priorytetu)
     * @param result      wynik (z iloscia)
     * @param ingredients lista skladnikow; kazdy Ingredient to alternatywy
     * @param type        typ receptury (crafting / stonecutting / smithing)
     */
    public record CraftingEntry(
            ResourceLocation id,
            ItemStack result,
            NonNullList<Ingredient> ingredients,
            RecipeType<?> type
    ) {
        /** Krotki opis do tooltipa: "2x Deska". */
        public String describe() {
            return result.getCount() + "x " + result.getHoverName().getString();
        }

        /** Czy receptura wymaga siatki (3x3) czy wystarczy 2x2 / 1x1. */
        public boolean needsGrid(int size) {
            if (!(type == RecipeType.CRAFTING)) {
                return false;
            }
            for (Ingredient ing : ingredients) {
                if (ing.getItems().length == 0) {
                    continue;
                }
                if (countSlots(ing) > size * size) {
                    return true;
                }
            }
            // Dokladniejsza weryfikacja: liczba niepustych Ingredient
            int used = 0;
            for (Ingredient ing : ingredients) {
                if (ing.getItems().length > 0) {
                    used++;
                }
            }
            return used > size * size;
        }

        private static int countSlots(Ingredient ing) {
            return ing.getItems().length > 0 ? 1 : 0;
        }
    }

    /**
     * Zwraca wszystkie receptury wytwarzajace dany item, ktore sa wykonywalne
     * bez energii/paliwa.
     */
    public static List<CraftingEntry> getRecipesFor(Level level, Item item) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return List.of();
        }
        return getIndex(serverLevel).getOrDefault(item, List.of());
    }

    /** Czy item da sie w ogole wycraftowac w sieci. */
    public static boolean isCraftable(Level level, Item item) {
        return !getRecipesFor(level, item).isEmpty();
    }

    /** Wszystkie itemy craftowalne w sieci - dla GUI filtrujacego. */
    public static Set<Item> getAllCraftableItems(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return Set.of();
        }
        return getIndex(serverLevel).keySet();
    }

    /** Receptury dla itemu, posortowane tak, by pierwsza byla "domyslna". */
    public static List<CraftingEntry> getOrdered(Level level, Item item, @Nullable ResourceLocation preferred) {
        List<CraftingEntry> all = getRecipesFor(level, item);
        if (all.size() <= 1 || preferred == null) {
            return all;
        }
        List<CraftingEntry> ordered = new ArrayList<>(all.size());
        for (CraftingEntry e : all) {
            if (e.id().equals(preferred)) {
                ordered.add(e);
            }
        }
        for (CraftingEntry e : all) {
            if (!e.id().equals(preferred)) {
                ordered.add(e);
            }
        }
        return ordered;
    }

    /** Buduje (lub pobiera z cache) indeks Item -> receptury. */
    private static Map<Item, List<CraftingEntry>> getIndex(ServerLevel level) {
        RecipeManager manager = level.getRecipeManager();
        Map<Item, List<CraftingEntry>> cached = CACHE.get(manager);
        if (cached != null) {
            return cached;
        }
        Map<Item, List<CraftingEntry>> built = buildIndex(manager, level);
        CACHE.put(manager, built);
        return built;
    }

    private static Map<Item, List<CraftingEntry>> buildIndex(RecipeManager manager, ServerLevel level) {
        Map<Item, List<CraftingEntry>> index = new HashMap<>();
        HolderLookup.Provider registries = level.registryAccess();

        for (RecipeType<?> type : FREE_TYPES) {
            collectType(manager, type, registries, index);
        }

        // Mody: tylko jesli jawnie zaufane (na razie brak).
        if (!TRUSTED_MOD_NAMESPACES.isEmpty()) {
            for (RecipeHolder<?> holder : manager.getRecipes()) {
                ResourceLocation id = holder.id();
                if (!TRUSTED_MOD_NAMESPACES.contains(id.getNamespace())) {
                    continue;
                }
                addHolder(holder, registries, index, /* trusted */ true);
            }
        }

        // Zamien na liste niezmienna i posortuj po id, zeby kolejnosc byla stabilna.
        Map<Item, List<CraftingEntry>> result = new LinkedHashMap<>();
        for (Map.Entry<Item, List<CraftingEntry>> e : index.entrySet()) {
            List<CraftingEntry> list = e.getValue();
            list.sort((a, b) -> a.id().toString().compareTo(b.id().toString()));
            result.put(e.getKey(), List.copyOf(list));
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void collectType(RecipeManager manager, RecipeType<?> type,
                                    HolderLookup.Provider registries,
                                    Map<Item, List<CraftingEntry>> index) {
        // getRecipes() zwraca wszystkie receptury niezaleznie od typu; filtrujemy
        // po getType(), zeby uniknac problemow z generykami RecipeType<?>.
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (holder.value().getType() != type) {
                continue;
            }
            addHolder(holder, registries, index, false);
        }
    }

    private static void addHolder(RecipeHolder<?> holder, HolderLookup.Provider registries,
                                  Map<Item, List<CraftingEntry>> index, boolean trusted) {
        var recipe = holder.value();

        if (!trusted && !isFreeType(recipe.getType())) {
            return;
        }
        // Receptury "special" (np. dye armor, map cloning) nie maja sensownego
        // przepisu do odtworzenia automatycznie - pomijamy je.
        if (recipe.isSpecial()) {
            return;
        }
        ItemStack result;
        try {
            result = recipe.getResultItem(registries);
        } catch (Exception ex) {
            return;
        }
        if (result.isEmpty()) {
            return;
        }
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) {
            return;
        }
        // Musi byc choc jeden niepusty skladnik.
        boolean any = false;
        for (Ingredient ing : ingredients) {
            if (ing.getItems().length > 0) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }

        Item out = result.getItem();
        CraftingEntry entry = new CraftingEntry(
                holder.id(),
                result.copy(),
                ingredients,
                recipe.getType()
        );
        index.computeIfAbsent(out, k -> new ArrayList<>()).add(entry);
    }

    private static boolean isFreeType(RecipeType<?> type) {
        if (FREE_TYPES.contains(type)) {
            return true;
        }
        ResourceLocation key = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        return key != null && TRUSTED_MOD_NAMESPACES.contains(key.getNamespace());
    }

    /** Czysci cache - wywolywane przy zmianie swiata/serwera. */
    public static void invalidate() {
        CACHE.clear();
    }

    /** Mapa item -> liczba receptur (diagnostyka). */
    public static Map<Item, Integer> describeIndex(Level level) {
        Map<Item, Integer> out = new ConcurrentHashMap<>();
        for (Map.Entry<Item, List<CraftingEntry>> e : getIndex((ServerLevel) level).entrySet()) {
            out.put(e.getKey(), e.getValue().size());
        }
        return out;
    }
}
