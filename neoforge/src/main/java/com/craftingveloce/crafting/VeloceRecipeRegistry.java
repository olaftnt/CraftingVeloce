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
 * Registry of recipes "craftable without infrastructure".
 *
 * <p>It collects recipes that can be executed while sitting in the Veloce
 * network - that is, those that do NOT require energy, fuel, mana, XP or any
 * external processing block. The classification is based on the recipe type:
 *
 * <ul>
 *   <li>{@code minecraft:crafting} - crafting table (shaped/shapeless/special).
 *       Always craftable: it requires no fuel.</li>
 *   <li>{@code minecraft:stonecutting} - stonecutter. It requires no fuel
 *       (in vanilla), but it requires a block. We treat it as craftable,
 *       because it is a "crafting-like block".</li>
 *   <li>{@code minecraft:smithing} - smithing anvil. No fuel.</li>
 * </ul>
 *
 * <p><b>What we do NOT collect:</b> {@code smelting}, {@code blasting}, {@code smoking},
 * {@code campfire_cooking} (they require fuel), and everything that mods register
 * under their own types with energy/mana (Mekanism, Create, etc.). Recipes from mods
 * are also excluded when their serializer/type comes from a namespace other
 * than {@code minecraft} AND is not known as "infrastructure-free" -
 * that is a safe default policy, so that we do not try to craft something
 * that we cannot reproduce.
 *
 * <p>The classification is cached per-{@link RecipeManager}, because scanning
 * all recipes is expensive and does not change during runtime
 * (apart from datapack reloads, which produce a new RecipeManager).
 */
public final class VeloceRecipeRegistry {

    /**
     * Recipe types without infrastructure.
     *
     * <p>The definition lives in {@link VeloceRecipeFamilies} - the ONE place for
     * the whole mod. Previously this list was here, and its copy was in
     * {@code VeloceRecipeGraph} (and the two could drift apart).
     */
    private static final Set<RecipeType<?>> FREE_TYPES = VeloceRecipeFamilies.FREE;

    /**
     * Recipe types handled by the Velocity Furnace.
     *
     * <p><b>DELIBERATELY A SEPARATE LIST.</b> These recipes require fuel, so they
     * must NOT end up in {@link #FREE_TYPES}. If they were there, the auto-crafter would
     * consider that it can "produce" an iron ingot from ore for free - without a furnace and
     * without fuel - and it would destroy its own assumption that we craft only from what
     * we actually have.
     *
     * <p>The furnace has its own index ({@link #FURNACE_CACHE}) and its own entry point
     * ({@link #getFurnaceRecipesFor}). Thanks to that they can be used ONLY where
     * we consciously verify that a powered furnace stands in the network.
     *
     * <p>The order in {@link #FURNACE_TYPES} does not matter - priority is decided
     * by processing time (blasting and smoking 100 t, smelting 200 t),
     * see {@link #getFurnaceRecipesFor}.
     */
    private static final Set<RecipeType<?>> FURNACE_TYPES = VeloceRecipeFamilies.FURNACE;

    /**
     * Whether the recipe is "special" in the VANILLA sense.
     *
     * <p>Vanilla marks recipes that cannot sensibly be reproduced
     * automatically (armor dyeing, map cloning). Recipes from mods often
     * use the same flag for ordinary recipes (Mekanism: all of them), so
     * rejecting on {@code isSpecial()} alone cut whole mods out of the index.
     */
    public static boolean isVanillaSpecial(net.minecraft.world.item.crafting.Recipe<?> recipe) {
        if (!recipe.isSpecial()) {
            return false;
        }
        ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        return typeId != null && "minecraft".equals(typeId.getNamespace());
    }

    /**
     * Whether this recipe type requires a HEATED furnace.
     *
     * <p>The only place that answers this question. The crafter must
     * know it in order to charge heat when planning and take it when
     * executing - and if it checked the types on its own, the list of furnace
     * types would have to be maintained in two places (and sooner or later
     * it would drift apart just like the network node list drifted apart).
     */
    public static boolean isFurnaceType(RecipeType<?> type) {
        return FURNACE_TYPES.contains(type);
    }

    /**
     * Namespaces of mods whose recipes we want to additionally take into account,
     * even though they use their own type. Empty for now - deliberately conservative.
     * Add only after verifying that the given type requires no infrastructure.
     */
    private static final Set<String> TRUSTED_MOD_NAMESPACES = Set.of();

    /**
     * Recipe index per RecipeManager.
     *
     * <p><b>Weak keys are necessary here.</b> Previously this was a plain
     * IdentityHashMap that held the RecipeManager firmly. Every entry
     * into a world (and every data reload) creates a NEW RecipeManager, so
     * the map grew by a full recipe index every time and nothing ever
     * cleaned it up - a classic memory leak that ends in long
     * GC pauses and lag.
     *
     * <p>A WeakHashMap removes the entry by itself when nothing holds the manager
     * anymore. RecipeManager does not override equals/hashCode, so it behaves
     * identity-wise just as before.
     */
    private static final Map<RecipeManager, Map<Item, List<ProcessingEntry>>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Separate index of furnace recipes (smelting/blasting/smoking). */
    private static final Map<RecipeManager, Map<Item, List<ProcessingEntry>>> FURNACE_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private VeloceRecipeRegistry() {
    }

    /**
     * Returns all recipes producing the given item that are executable
     * without energy/fuel.
     */
    public static List<ProcessingEntry> getRecipesFor(Level level, Item item) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return List.of();
        }
        return getIndex(serverLevel).getOrDefault(item, List.of());
    }

    /**
     * Recipes for an item, taking the FURNACE into account.
     *
     * <p>This is the only place where free recipes may be combined with
     * furnace recipes - and it does so only when the caller CONFIRMED that
     * a powered furnace stands in the network ({@code heatAvailable}).
     *
     * <p><b>Order matters.</b> Free recipes come first, so that
     * smelting is a last resort rather than the default: if an item can
     * be made without fuel, there is no point in burning any.
     *
     * @param heatAvailable whether a powered heat source is present in the network
     */
    public static List<ProcessingEntry> getRecipesFor(Level level, Item item, boolean heatAvailable) {
        List<ProcessingEntry> free = getRecipesFor(level, item);
        if (!heatAvailable) {
            return free;
        }
        List<ProcessingEntry> furnace = getFurnaceRecipesFor(level, item);
        if (furnace.isEmpty()) {
            return free;
        }
        List<ProcessingEntry> out = new ArrayList<>(free.size() + furnace.size());
        out.addAll(free);
        out.addAll(furnace);
        return out;
    }

    /** Whether the item can be crafted in the network at all. */
    public static boolean isCraftable(Level level, Item item) {
        return !getRecipesFor(level, item).isEmpty();
    }

    /** All items craftable in the network - for the filtering GUI. */
    public static Set<Item> getAllCraftableItems(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return Set.of();
        }
        return getIndex(serverLevel).keySet();
    }

    /**
     * Items obtainable in a FURNACE (smelting / blasting / smoking).
     *
     * <p>This is a separate set from {@link #getAllCraftableItems} and is deliberately not
     * mixed with it: a furnace recipe requires a POWERED furnace in the network, while a
     * crafting recipe does not. The controller must therefore be able to say "this item
     * has a furnace recipe, but the furnace is off" - and for that it needs this list
     * regardless of whether a furnace is in the network.
     *
     * <p><b>Note:</b> this method does NOT check whether any furnace exists.
     * That question belongs to {@link VeloceHeatSources}.
     */
    public static Set<Item> getAllFurnaceCraftableItems(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return Set.of();
        }
        return getFurnaceIndex(serverLevel).keySet();
    }

    /** Recipes for an item, ordered so that the first one is the "default". */
    public static List<ProcessingEntry> getOrdered(Level level, Item item, @Nullable ResourceLocation preferred) {        List<ProcessingEntry> all = getRecipesFor(level, item);
        if (all.size() <= 1 || preferred == null) {
            return all;
        }
        List<ProcessingEntry> ordered = new ArrayList<>(all.size());
        for (ProcessingEntry e : all) {
            if (e.id().equals(preferred)) {
                ordered.add(e);
            }
        }
        for (ProcessingEntry e : all) {
            if (!e.id().equals(preferred)) {
                ordered.add(e);
            }
        }
        return ordered;
    }

    /**
     * FURNACE recipes for the given item, in FASTEST-first order.
     *
     * <p>Order: {@code blasting} and {@code smoking} (100 ticks) before
     * {@code smelting} (200 ticks). This fulfils the requirement "if a raw material matches
     * several, take the more efficient/faster one" without any switches.
     *
     * <p>The caller MUST check on its own that a powered furnace is in the network - this
     * method only reads recipes.
     */
    public static List<ProcessingEntry> getFurnaceRecipesFor(Level level, Item item) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return List.of();
        }
        List<ProcessingEntry> all = getFurnaceIndex(serverLevel).getOrDefault(item, List.of());
        if (all.size() <= 1) {
            return all;
        }
        List<ProcessingEntry> sorted = new ArrayList<>(all);
        sorted.sort(java.util.Comparator.comparingLong(VeloceRecipeRegistry::processingTicks));
        return sorted;
    }

    /**
     * Recipe processing time in ticks.
     *
     * <p>Vanilla: blasting and smoking 100 t, smelting 200 t. For unknown
     * types we assume 200 t, so as not to favour anything by accident.
     */
    private static long processingTicks(ProcessingEntry entry) {
        if (entry.type() == RecipeType.BLASTING || entry.type() == RecipeType.SMOKING) {
            return 100L;
        }
        return 200L;
    }

    /** Builds (or retrieves from cache) the Item -> furnace recipes index. */
    private static Map<Item, List<ProcessingEntry>> getFurnaceIndex(ServerLevel level) {
        RecipeManager manager = level.getRecipeManager();
        Map<Item, List<ProcessingEntry>> cached = FURNACE_CACHE.get(manager);
        if (cached != null) {
            return cached;
        }
        long start = System.nanoTime();
        Map<Item, List<ProcessingEntry>> built = buildIndex(manager, level, FURNACE_TYPES);
        FURNACE_CACHE.put(manager, built);

        // SELF-CHECK (so that this bug does not come back silently).
        //
        // An empty index with a non-empty number of furnace recipes in the manager is
        // OUR filtering bug, not "the modpack has no smelting". That is exactly
        // how it was: the gate in addHolder checked the crafting type list,
        // so it rejected EVERY furnace recipe and the log calmly said
        // "0 item(s)" - and the player did not have a single smelting recipe.
        int inManager = 0;
        for (RecipeHolder<?> h : manager.getRecipes()) {
            if (FURNACE_TYPES.contains(h.value().getType())) {
                inManager++;
            }
        }
        if (built.isEmpty() && inManager > 0) {
            VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                    "furnace index is EMPTY, even though the recipe manager has %d furnace recipes"
                            + " - this is our filtering bug, not a lack of smelting."
                            + " Smelting (e.g. charcoal from logs) will NOT be craftable.",
                    inManager);
        } else {
            VeloceLog.Craft.success(VeloceLog.Side.SERVER,
                    "furnace recipe index built: %d item(s) from %d recipe(s), %d ms",
                    built.size(), inManager, (System.nanoTime() - start) / 1_000_000L);
        }
        return built;
    }

    /** Builds (or retrieves from cache) the Item -> recipes index. */
    private static Map<Item, List<ProcessingEntry>> getIndex(ServerLevel level) {
        RecipeManager manager = level.getRecipeManager();
        Map<Item, List<ProcessingEntry>> cached = CACHE.get(manager);
        if (cached != null) {
            return cached;
        }
        // Building the index walks ALL recipes of the modpack together
        // with ingredient resolution - a one-off but real cost on the server
        // thread. We measure it so that it can be pointed at in the log if someone
        // reports "clicking the terminal bogs the server down" again.
        long start = System.nanoTime();
        Map<Item, List<ProcessingEntry>> built = buildIndex(manager, level, FREE_TYPES);
        CACHE.put(manager, built);
        VeloceLog.Craft.success(VeloceLog.Side.SERVER,
                "recipe index built: %d item(s) with a recipe, %d ms",
                built.size(), (System.nanoTime() - start) / 1_000_000L);
        return built;
    }

    private static Map<Item, List<ProcessingEntry>> buildIndex(RecipeManager manager,
                                                             ServerLevel level,
                                                             Set<RecipeType<?>> types) {
        Map<Item, List<ProcessingEntry>> index = new HashMap<>();
        HolderLookup.Provider registries = level.registryAccess();

        // ONE pass over the recipes, not one per type.
        // The previous version called collectType() for each of the 3 types, and each
        // collectType walked the WHOLE recipe list and filtered out the rest by
        // getType(). With a large modpack that was 3x more work than needed -
        // and on the server thread, on first use of the index.
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (types.contains(holder.value().getType())) {
                addHolder(holder, registries, index, false, types);
            }
        }

        // Mods: only if explicitly trusted (none for now).
        if (!TRUSTED_MOD_NAMESPACES.isEmpty()) {
            for (RecipeHolder<?> holder : manager.getRecipes()) {
                ResourceLocation id = holder.id();
                if (!TRUSTED_MOD_NAMESPACES.contains(id.getNamespace())) {
                    continue;
                }
                addHolder(holder, registries, index, /* trusted */ true, types);
            }
        }

        // Turn it into an immutable list and sort by id, so that the order is stable.
        Map<Item, List<ProcessingEntry>> result = new LinkedHashMap<>();
        for (Map.Entry<Item, List<ProcessingEntry>> e : index.entrySet()) {
            List<ProcessingEntry> list = e.getValue();
            list.sort((a, b) -> a.id().toString().compareTo(b.id().toString()));
            result.put(e.getKey(), List.copyOf(list));
        }
        return result;
    }

    /**
     * Adds a recipe to the index if its type is on the given list.
     *
     * <p><b>The BUG this fixes (missing furnace recipes).</b> The gate was hardcoded
     * to {@code FREE_TYPES} (crafting types) instead of using the list that the
     * caller passed in. Effect: when building the FURNACE index, every recipe of
     * smelting/blasting/smoking was rejected by the very gate that was
     * supposed to let through only furnace types - the index came out EMPTY
     * ("furnace recipe index built: 0 item(s)"), so the player did not see a single
     * smelting recipe (e.g. charcoal from logs) and the auto-crafter had nothing
     * to plan.
     *
     * <p>Now the gate is exactly the same set with which the caller filtered
     * recipes - a single source, so it cannot drift apart.
     */
    private static void addHolder(RecipeHolder<?> holder, HolderLookup.Provider registries,
                                  Map<Item, List<ProcessingEntry>> index, boolean trusted,
                                  Set<RecipeType<?>> allowedTypes) {
        var recipe = holder.value();

        if (!trusted && !allowedTypes.contains(recipe.getType())) {
            return;
        }
        // VANILLA "special" recipes (e.g. dye armor, map cloning) have no
        // sensible recipe to reproduce automatically - we skip them.
        //
        // NOTE: it is NOT allowed to reject on `isSpecial()` alone. Recipes from mods
        // (Mekanism does so with ALL of its own) also return true and were
        // therefore silently thrown out of the index - no module from another mod
        // would have anything to compute. So we check the namespace of the recipe type.
        if (isVanillaSpecial(recipe)) {
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
        // There must be at least one non-empty ingredient.
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
        // Vanilla recipe: one output and one unit of each
        // ingredient. Families from other mods build ProcessingEntry themselves
        // (stack counts, multiple outputs, probabilities) - see
        // ProcessingEntry.single() as opposed to this.
        ProcessingEntry entry = ProcessingEntry.single(
                holder.id(),
                result.copy(),
                ingredients,
                recipe.getType()
        );
        index.computeIfAbsent(out, k -> new ArrayList<>()).add(entry);
    }

    /** Clears the caches - called when the world/server changes. */
    public static void invalidate() {
        CACHE.clear();
        FURNACE_CACHE.clear();
        // Module recipes have their own per-module indexes and a memory of
        // "which modules are powered" for the duration of a tick - everything must be
        // invalidated together, otherwise after a world change the planner would see
        // machines from the previous one.
        VeloceModuleRecipes.invalidate();
        VeloceProcessingRegistry.invalidateAll();
    }

    /** Map item -> recipe count (diagnostics). */
    public static Map<Item, Integer> describeIndex(Level level) {
        Map<Item, Integer> out = new ConcurrentHashMap<>();
        for (Map.Entry<Item, List<ProcessingEntry>> e : getIndex((ServerLevel) level).entrySet()) {
            out.put(e.getKey(), e.getValue().size());
        }
        return out;
    }
}
