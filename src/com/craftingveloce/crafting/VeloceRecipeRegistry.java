package com.craftingveloce.crafting;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import com.craftingveloce.util.VeloceLog;
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
import java.util.LinkedHashMap;
import java.util.WeakHashMap;
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
     * Typy receptur obslugiwane przez Velocity Furnace.
     *
     * <p><b>CELOWO OSOBNA LISTA.</b> Te receptury wymagaja paliwa, wiec NIE
     * moga trafic do {@link #FREE_TYPES}. Gdyby tam byly, auto-crafter uznalby,
     * ze potrafi "wytworzyc" sztabke zelaza z rudy za darmo - bez pieca i bez
     * paliwa - i zniszczylby wlasne zalozenie, ze craftujemy tylko z tego,
     * co realnie mamy.
     *
     * <p>Piec ma wlasny indeks ({@link #FURNACE_CACHE}) i wlasne wejscie
     * ({@link #getFurnaceRecipesFor}). Dzieki temu mozna je uzyc TYLKO tam,
     * gdzie swiadomie sprawdzimy, ze w sieci stoi zasilony piec.
     *
     * <p>Kolejnosc w {@link #FURNACE_TYPES} nie ma znaczenia - o priorytecie
     * decyduje czas przetwarzania (blasting i smoking 100 t, smelting 200 t),
     * patrz {@link #getFurnaceRecipesFor}.
     */
    private static final Set<RecipeType<?>> FURNACE_TYPES = Set.of(
            RecipeType.SMELTING,
            RecipeType.BLASTING,
            RecipeType.SMOKING
    );

    /**
     * Czy ten typ receptury wymaga ROZGRZANEGO pieca.
     *
     * <p>Jedyne miejsce, ktore odpowiada na to pytanie. Crafter musi to
     * wiedziec, zeby policzyc cieplo przy planowaniu i zabrac je przy
     * wykonaniu - a gdyby sprawdzal typy samodzielnie, lista typow pieca
     * musialaby byc utrzymywana w dwoch miejscach (i predzej czy pozniej
     * rozjechalaby sie tak, jak rozjechala sie lista wezlow sieci).
     */
    public static boolean isFurnaceType(RecipeType<?> type) {
        return FURNACE_TYPES.contains(type);
    }

    /**
     * Namespace'y modow, ktorych receptury chcemy dodatkowo brac pod uwage,
     * mimo ze uzywaja wlasnego typu. Na razie puste - swiadomie konserwatywnie.
     * Dodawac tylko po zweryfikowaniu, ze dany typ nie wymaga infrastruktury.
     */
    private static final Set<String> TRUSTED_MOD_NAMESPACES = Set.of();

    /** Cache: RecipeManager -> (wynik Item -> lista receptur). */
    /**
     * Indeks receptur per RecipeManager.
     *
     * <p><b>Slabe klucze sa tu konieczne.</b> Wczesniej byla to zwykla
     * IdentityHashMap, ktora trzymala RecipeManager na sztywno. Kazde wejscie
     * do swiata (i kazde przeladowanie danych) tworzy NOWY RecipeManager, wiec
     * mapa rosla o pelny indeks receptur za kazdym razem i nic tego nie
     * sprzatalo - klasyczny wyciek pamieci, ktory konczy sie dlugimi
     * pauzami GC i lagami.
     *
     * <p>WeakHashMap sam usuwa wpis, gdy managera nic juz nie trzyma.
     * RecipeManager nie nadpisuje equals/hashCode, wiec zachowuje sie
     * tozsamosciowo jak poprzednio.
     */
    private static final Map<RecipeManager, Map<Item, List<CraftingEntry>>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Osobny indeks receptur pieca (smelting/blasting/smoking). */
    private static final Map<RecipeManager, Map<Item, List<CraftingEntry>>> FURNACE_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

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

        /**
         * Czy to receptura PIECA - czyli czy wymaga zabrania jednego
         * przepalenia z zasilonego pieca w sieci.
         */
        public boolean isFurnace() {
            return isFurnaceType(type);
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

    /**
     * Receptury dla itemu z uwzglednieniem PIECA.
     *
     * <p>To jedyne miejsce, w ktorym wolno polaczyc receptury darmowe z
     * piecowymi - i robi to tylko wtedy, gdy wolajacy POTWIERDZIL, ze w sieci
     * stoi zasilony piec ({@code heatAvailable}).
     *
     * <p><b>Kolejnosc ma znaczenie.</b> Receptury darmowe ida pierwsze, zeby
     * przepalenie bylo ostatnia deska ratunku, a nie domyslem: jesli item da
     * sie zrobic bez paliwa, nie ma po co palic.
     *
     * @param heatAvailable czy w sieci jest zasilone zrodlo ciepla
     */
    public static List<CraftingEntry> getRecipesFor(Level level, Item item, boolean heatAvailable) {
        List<CraftingEntry> free = getRecipesFor(level, item);
        if (!heatAvailable) {
            return free;
        }
        List<CraftingEntry> furnace = getFurnaceRecipesFor(level, item);
        if (furnace.isEmpty()) {
            return free;
        }
        List<CraftingEntry> out = new ArrayList<>(free.size() + furnace.size());
        out.addAll(free);
        out.addAll(furnace);
        return out;
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

    /**
     * Itemy, ktore da sie uzyskac w PIECU (smelting / blasting / smoking).
     *
     * <p>To osobny zbior niz {@link #getAllCraftableItems} i celowo nie jest
     * z nim mieszany: receptura pieca wymaga ZASILONEGO pieca w sieci, a
     * receptura craftingu nie. Kontroler musi wiec umiec powiedziec "ten item
     * ma recepture pieca, ale piec stoi" - a do tego potrzebuje tej listy
     * niezaleznie od tego, czy piec jest w sieci.
     *
     * <p><b>Uwaga:</b> ta metoda NIE sprawdza, czy jakikolwiek piec istnieje.
     * To pytanie nalezy do {@link VeloceHeatSources}.
     */
    public static Set<Item> getAllFurnaceCraftableItems(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return Set.of();
        }
        return getFurnaceIndex(serverLevel).keySet();
    }

    /** Receptury dla itemu, posortowane tak, by pierwsza byla "domyslna". */
    public static List<CraftingEntry> getOrdered(Level level, Item item, @Nullable ResourceLocation preferred) {        List<CraftingEntry> all = getRecipesFor(level, item);
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

    /**
     * Receptury PIECA dla danego itemu, w kolejnosci NAJSZYBSZEJ najpierw.
     *
     * <p>Kolejnosc: {@code blasting} i {@code smoking} (100 tickow) przed
     * {@code smelting} (200 tickow). To realizuje wymog "jesli surowiec pasuje
     * do kilku, bierz wydajniejsza/szybsza" bez zadnych przelacznikow.
     *
     * <p>Wolajacy MUSI sam sprawdzic, ze w sieci jest zasilony piec - ta
     * metoda tylko czyta receptury.
     */
    public static List<CraftingEntry> getFurnaceRecipesFor(Level level, Item item) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return List.of();
        }
        List<CraftingEntry> all = getFurnaceIndex(serverLevel).getOrDefault(item, List.of());
        if (all.size() <= 1) {
            return all;
        }
        List<CraftingEntry> sorted = new ArrayList<>(all);
        sorted.sort(java.util.Comparator.comparingLong(VeloceRecipeRegistry::processingTicks));
        return sorted;
    }

    /**
     * Czas przetwarzania receptury w tickach.
     *
     * <p>Vanilla: blasting i smoking 100 t, smelting 200 t. Dla nieznanych
     * typow przyjmujemy 200 t, zeby nie faworyzowac niczego przypadkiem.
     */
    private static long processingTicks(CraftingEntry entry) {
        if (entry.type() == RecipeType.BLASTING || entry.type() == RecipeType.SMOKING) {
            return 100L;
        }
        return 200L;
    }

    /** Buduje (lub pobiera z cache) indeks Item -> receptury pieca. */
    private static Map<Item, List<CraftingEntry>> getFurnaceIndex(ServerLevel level) {
        RecipeManager manager = level.getRecipeManager();
        Map<Item, List<CraftingEntry>> cached = FURNACE_CACHE.get(manager);
        if (cached != null) {
            return cached;
        }
        long start = System.nanoTime();
        Map<Item, List<CraftingEntry>> built = buildIndex(manager, level, FURNACE_TYPES);
        FURNACE_CACHE.put(manager, built);
        VeloceLog.Craft.success(VeloceLog.Side.SERVER,
                "furnace recipe index built: %d item(s), %d ms",
                built.size(), (System.nanoTime() - start) / 1_000_000L);
        return built;
    }

    /** Buduje (lub pobiera z cache) indeks Item -> receptury. */
    private static Map<Item, List<CraftingEntry>> getIndex(ServerLevel level) {
        RecipeManager manager = level.getRecipeManager();
        Map<Item, List<CraftingEntry>> cached = CACHE.get(manager);
        if (cached != null) {
            return cached;
        }
        // Budowa indeksu to przejscie po WSZYSTKICH recepturach modpacka wraz
        // z rozwiazaniem skladnikow - jednorazowy, ale realny koszt na watku
        // serwera. Mierzymy go, zeby dalo sie go wskazac w logu, gdyby ktos
        // znow zglaszal "klikniecie w terminal zamula serwer".
        long start = System.nanoTime();
        Map<Item, List<CraftingEntry>> built = buildIndex(manager, level, FREE_TYPES);
        CACHE.put(manager, built);
        VeloceLog.Craft.success(VeloceLog.Side.SERVER,
                "recipe index built: %d item(s) with a recipe, %d ms",
                built.size(), (System.nanoTime() - start) / 1_000_000L);
        return built;
    }

    private static Map<Item, List<CraftingEntry>> buildIndex(RecipeManager manager,
                                                             ServerLevel level,
                                                             Set<RecipeType<?>> types) {
        Map<Item, List<CraftingEntry>> index = new HashMap<>();
        HolderLookup.Provider registries = level.registryAccess();

        // JEDNO przejscie po recepturach, nie jedno na typ.
        // Poprzednia wersja wolala collectType() dla kazdego z 3 typow, a kazde
        // collectType przechodzilo CALA liste receptur i odsiewalo reszte po
        // getType(). Przy duzym modpacku to bylo 3x wiecej pracy niz trzeba -
        // i to na watku serwera, przy pierwszym uzyciu indeksu.
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (types.contains(holder.value().getType())) {
                addHolder(holder, registries, index, false);
            }
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
        FURNACE_CACHE.clear();
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
