package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.util.VelocePotionMapper;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.common.brewing.IBrewingRecipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Brewing as a network processing module.
 *
 * <p><b>The recipes come from the game, not from a list here.</b> This module used
 * to carry twenty-one hand-written mixes, which meant it knew only the potions
 * somebody had typed out: every vanilla mix nobody wrote down was invisible, and a
 * brewing recipe added by another mod could never be planned at all - even though
 * the brewing stand itself would happily brew it, because the stand asks the game.
 *
 * <p>The module now asks the same source the stand does, {@code level.potionBrewing()},
 * and translates whatever it finds into the proxy domain:
 *
 * <ol>
 *   <li><b>Vanilla mixes are PROBED, not enumerated.</b> {@code PotionBrewing} keeps
 *       its potion-mix list private with no getter, so there is no way to read "all
 *       brewing recipes" directly. What it does expose is the question
 *       {@link PotionBrewing#hasMix} - so every potion state is tried against every
 *       accepted ingredient. That is a brute-force search rather than a clean read,
 *       and it is the only complete one available.</li>
 *   <li><b>Mod recipes come through the same probe.</b> {@code hasMix} consults the
 *       custom {@link IBrewingRecipe}s as well, and {@link PotionBrewing#getRecipes}
 *       additionally tells us which extra inputs those recipes accept - inputs that
 *       may not be potions at all - so they are probed too.</li>
 * </ol>
 *
 * <p><b>Proxy rule (why every result and every input here is a PROXY item).</b>
 * Vanilla brewing has no {@code RecipeType}, so the network cannot reason about
 * potions by their {@code PotionContents} component. We therefore describe every
 * potion STATE as its own ordinary item - a proxy resolved through
 * {@link VelocePotionMapper}. The network then plans over plain item ids, and
 * {@code toRealPotion} turns the proxy back into a real potion when it is extracted
 * into a player inventory or a container. Keeping inputs and outputs in the SAME
 * domain (all proxies) is what makes the plan and the execution agree.
 *
 * <p><b>A potion with no proxy is skipped, not approximated.</b> Another mod can add
 * a potion this build has no proxy item for. Emitting such a mix anyway would make
 * it resolve to the generic {@code minecraft:potion} item - and since every
 * proxy-less potion resolves there, all of them would collapse into one stock entry
 * and the terminal would hand out the wrong potion. Those mixes are left out and
 * counted in the log so the gap is visible instead of quietly wrong.
 */
public class VeloceBrewingModule implements VeloceProcessingModule {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("craftingveloce-brewing");

    /**
     * Containers a brewing stand works with.
     *
     * <p>Splash and lingering are reached BY brewing, so leaving them out would hide
     * a whole branch of the vanilla tree rather than merely an extra potion.
     */
    private static final List<Item> CONTAINERS =
            List.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION);

    private final List<ProcessingEntry> recipes = new ArrayList<>();

    /**
     * Whether {@link #recipes} has been built for the current server state.
     *
     * <p><b>Volatile, and written LAST.</b> The discovery is now also built from the counting
     * worker - by the recipe-index warm-up (see {@code VeloceRecipeWarmup}) - while the server
     * thread can ask for a brewing recipe in the same moment. Without the volatile flag the
     * worker's "I am building it" was visible to the server thread BEFORE the list was filled,
     * and a caller could read a half-built {@code recipes} (or an empty one) and conclude that a
     * potion has no recipe. Writing the flag after the list is published gives every later
     * reader a happens-before edge onto the finished list.
     */
    private volatile boolean discovered;

    public VeloceBrewingModule() {
    }

    @Override
    public void warmRecipeIndex(ServerLevel level) {
        // The brewing discovery is the most expensive single build of all of them: it probes
        // every potion input against every ingredient (the log line "probed 246 input(s) x 28
        // ingredient(s)"), and nothing about it needs the server thread.
        ensureRecipes(level);
    }

    @Override
    public void invalidate() {
        synchronized (this) {
            discovered = false;
            recipes.clear();
        }
    }

    /**
     * Builds the brewing recipes once, safely under two threads.
     *
     * <p>The fast path is the flag alone (this is called per item, so a lock on every call would
     * be a real cost), and only the first call goes into the synchronized block. Keeping the
     * build inside it means the server thread waits for the worker's build instead of reading a
     * partially filled list.
     */
    private void ensureRecipes(ServerLevel level) {
        if (discovered) {
            return;
        }
        synchronized (this) {
            if (discovered) {
                return;   // another thread built it while we waited
            }
            recipes.clear();
            addBottleFilling();
            discoverMixes(level);
            LOG.info("brewing discovery: {} recipe(s) in total, built from the mix tree "
                    + "the game reports", recipes.size());
            discovered = true;   // LAST - see the field
        }
    }

    /**
     * Filling a glass bottle is not a brewing mix - it is not in {@code PotionBrewing}
     * at all - so unlike everything below it stays written down here.
     */
    private void addBottleFilling() {
        recipes.add(ProcessingEntry.single(
                ResourceLocation.fromNamespaceAndPath("craftingveloce", "brewing_water"),
                proxyStack("water"),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.GLASS_BOTTLE)),
                VeloceRecipes.getBrewing()
        ));
    }

    /** A recipe result in the PROXY domain - never a real potion. */
    private ItemStack proxyStack(String proxyKey) {
        return new ItemStack(VelocePotionMapper.getProxy(proxyKey));
    }

    /** One discovered mix, still in the domain the game speaks. */
    private record Mix(ItemStack input, ItemStack ingredient, ItemStack result) {
    }

    private void discoverMixes(ServerLevel level) {
        PotionBrewing brewing = level.potionBrewing();
        if (brewing == null) {
            LOG.warn("[VELOCE-DEBUG] no PotionBrewing on this level - no mixes discovered");
            return;
        }

        List<ItemStack> ingredients = candidateIngredients(brewing);
        List<ItemStack> inputs = candidateInputs(brewing);

        // Probe, then name. Two passes are needed because the id of a mix depends on
        // how many routes reach the same result - see naming below.
        Map<String, Mix> mixes = new LinkedHashMap<>();
        Map<String, Integer> routesPerResult = new LinkedHashMap<>();
        for (ItemStack input : inputs) {
            for (ItemStack ingredient : ingredients) {
                if (!brewing.hasMix(input, ingredient)) continue;
                // mix() takes (ingredient, input) while hasMix() above takes
                // (input, ingredient) - the order really is inverted between the two
                // vanilla methods. Swapped, mix() reads potion contents off the
                // INGREDIENT, finds none, and hands back the ingredient: every
                // discovered "result" would be a redstone or a stone.
                ItemStack result = brewing.mix(ingredient, input);
                if (result.isEmpty()) continue;
                String key = stateKey(input) + " + " + itemKey(ingredient) + " -> " + stateKey(result);
                if (mixes.putIfAbsent(key, new Mix(input, ingredient, result)) == null) {
                    routesPerResult.merge(stateKey(result), 1, Integer::sum);
                }
            }
        }

        java.util.Set<String> usedIds = new java.util.HashSet<>();
        java.util.Map<String, Integer> notOffered = new java.util.TreeMap<>();
        for (Mix mix : mixes.values()) {
            String reason = emit(mix, routesPerResult.getOrDefault(stateKey(mix.result()), 0), usedIds);
            if (reason != null) {
                notOffered.merge(reason, 1, Integer::sum);
            }
        }
        if (!notOffered.isEmpty()) {
            // Grouped by reason. "15 skipped" reads like a rounding error, while "12 with
            // no proxy for X" is a to-do list. An earlier version counted EVERY failure as
            // a missing proxy, which was wrong twice over: the proxies were all there, and
            // the real cause was duplicate recipe ids.
            LOG.info("brewing discovery: {} mix(es) not offered: {}",
                    notOffered.values().stream().mapToInt(Integer::intValue).sum(), notOffered);
        }
        // INFO, once per data load, and deliberately not a gated DEBUG line: this is the
        // only place that answers "how much of the game's brewing tree did the module
        // actually pick up". A discovery that quietly finds nothing looks exactly like a
        // brewing stand that refuses to work, and that is the failure this class was
        // rewritten to remove.
        LOG.info("brewing discovery: probed {} input(s) x {} ingredient(s), found {} mix(es), offered {}",
                inputs.size(), ingredients.size(), mixes.size(), mixes.size() - notOffered.size());
    }

    /**
     * Every item the brewing system will accept as an ingredient.
     *
     * <p>{@code isIngredient} already covers both halves - the potion ingredients
     * (nether wart, sugar, ...) and the container ingredients (gunpowder, dragon's
     * breath) - and it answers for a mod's own recipes as well, so this is the whole
     * candidate set rather than the vanilla one.
     */
    private static List<ItemStack> candidateIngredients(PotionBrewing brewing) {
        List<ItemStack> ingredients = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack stack = new ItemStack(item);
            if (brewing.isIngredient(stack)) {
                ingredients.add(stack);
            }
        }
        return ingredients;
    }

    /**
     * Every stack worth asking {@code hasMix} about.
     *
     * <p>All three containers for every registered potion - which is also how the
     * extended and level-II variants are found, since vanilla registers
     * {@code long_swiftness} and {@code strong_swiftness} as potions of their own -
     * plus any input a mod's own brewing recipe accepts, which need not be a potion.
     */
    private static List<ItemStack> candidateInputs(PotionBrewing brewing) {
        List<ItemStack> inputs = new ArrayList<>();
        BuiltInRegistries.POTION.holders().forEach(potion -> {
            for (Item container : CONTAINERS) {
                inputs.add(PotionContents.createItemStack(container, potion));
            }
        });
        for (IBrewingRecipe custom : brewing.getRecipes()) {
            for (Item item : BuiltInRegistries.ITEM) {
                ItemStack stack = new ItemStack(item);
                if (custom.isInput(stack)) {
                    inputs.add(stack);
                }
            }
        }
        return inputs;
    }

    /**
     * Turns one discovered mix into a recipe, or says why it cannot be one.
     *
     * @return null when the recipe was added, otherwise the reason it was not
     */
    private String emit(Mix mix, int routesToThisResult, java.util.Set<String> usedIds) {
        // hasMix() says a mix EXISTS; it does not promise the bottle changes. A pair
        // that leaves the potion alone is not a recipe, and emitting it produced a
        // second recipe called "brewing_water" - the same id as filling a glass bottle
        // - which the recipe index then deduplicated away, silently losing a recipe.
        if (stateKey(mix.result()).equals(stateKey(mix.input()))) {
            return null;
        }

        Item inputProxy = VelocePotionMapper.proxyOrNull(mix.input());
        Item resultProxy = VelocePotionMapper.proxyOrNull(mix.result());
        if (inputProxy == null || resultProxy == null) {
            String side = inputProxy == null ? stateKey(mix.input()) : stateKey(mix.result());
            LOG.debug("[VELOCE-DEBUG] brewing mix skipped - no proxy for {}: {} + {} -> {}",
                    inputProxy == null ? "input " + side : "result " + side,
                    stateKey(mix.input()), itemKey(mix.ingredient()), stateKey(mix.result()));
            return "no proxy for " + side;
        }

        // A result is named after itself when only one route reaches it, and after the
        // route when several do. Vanilla reaches Mundane from nine different
        // ingredients, so "brewing_mundane" alone could not identify a recipe - and an
        // id that silently depends on which of the nine was probed first would make a
        // failed test unreproducible, which is the whole point of naming recipes.
        String resultKey = stateKey(mix.result());
        String id = routesToThisResult == 1
                ? "brewing_" + resultKey
                : "brewing_" + resultKey + "_from_" + ingredientKey(mix.ingredient());
        if (!usedIds.add(id)) {
            // The qualifier has to be the dimension that actually DIFFERS. Fermented
            // spider eye turns both Swiftness and Leaping into Slowness, and Poison into
            // Harming - same ingredient, different input - so qualifying a multi-route
            // result by its ingredient alone is not unique and twelve real recipes were
            // being dropped. Mundane is the mirror case: many ingredients, all poured
            // into water. The recipe index deduplicates by id, so a lost id is a lost
            // recipe, silently.
            id = "brewing_" + resultKey + "_from_" + ingredientKey(mix.ingredient())
                    + "_via_" + inputKey(mix.input());
            if (!usedIds.add(id)) {
                LOG.warn("[VELOCE-DEBUG] brewing mix dropped - id {} is already taken", id);
                return "duplicate id " + id;
            }
        }

        recipes.add(ProcessingEntry.single(
                ResourceLocation.fromNamespaceAndPath("craftingveloce", id),
                new ItemStack(resultProxy),
                NonNullList.of(Ingredient.EMPTY,
                        Ingredient.of(inputProxy),
                        Ingredient.of(mix.ingredient())),
                VeloceRecipes.getBrewing()
        ));
        return null;
    }

    /** Proxy key of a stack, falling back to the item id for things that are not potions. */
    private static String stateKey(ItemStack stack) {
        String key = VelocePotionMapper.proxyKey(stack);
        return key != null ? key : itemKey(stack);
    }

    private static String itemKey(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? stack.getItem().toString() : id.toString();
    }

    /**
     * The INPUT side as an id fragment - its potion STATE, not its item.
     *
     * <p>Not {@link #ingredientKey}: every drinkable potion is the same item
     * ({@code minecraft:potion}), so keying the input that way collapses Poison and
     * strong Poison - two different starting points for Harming - into one name and
     * loses a real recipe.
     */
    private static String inputKey(ItemStack stack) {
        return stateKey(stack).replace(':', '_');
    }

    /** Path-safe suffix for a recipe id - recipe ids cannot contain a colon. */
    private static String ingredientKey(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return "unknown";
        return "minecraft".equals(id.getNamespace())
                ? id.getPath()
                : id.getNamespace() + "_" + id.getPath();
    }

    @Override
    public String id() { return "brewing"; }

    @Override
    public Set<RecipeType<?>> recipeTypes() { return Set.of(VeloceRecipes.getBrewing()); }

    @Override
    public Set<Item> producible(ServerLevel level, VelocePipeNetwork network) {
        ensureRecipes(level);
        Set<Item> out = recipes.stream().map(r -> r.primaryResult().getItem()).collect(Collectors.toSet());
        LOG.debug("[VELOCE-DEBUG] brewing producible: {} result(s) from {} recipe(s) -> {}",
                out.size(), recipes.size(), out);
        return out;
    }

    @Override
    public boolean available(ServerLevel level, VelocePipeNetwork network) {
        if (network == null) return false;
        for (net.minecraft.core.BlockPos pos : network.getTerminals()) {
            if (level.isLoaded(pos) && level.getBlockState(pos).getBlock()
                    == com.craftingveloce.init.VeloceRegistry.BREWING_STAND.get()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean powered(ServerLevel level, VelocePipeNetwork network) {
        return available(level, network);
    }

    @Override
    public List<ProcessingEntry> recipesFor(ServerLevel level, VelocePipeNetwork network, Item item) {
        if (!powered(level, network)) {
            LOG.debug("[VELOCE-DEBUG] brewing match for {}: no powered brewing stand in the network", item);
            return List.of();
        }
        ensureRecipes(level);
        List<ProcessingEntry> matched = recipes.stream().filter(r -> r.primaryResult().getItem() == item).toList();
        // The count is the useful part: "no recipe" and "the recipe exists but names a
        // different item" look identical to the player, while they need opposite fixes.
        LOG.debug("[VELOCE-DEBUG] brewing match for {}: {} recipe(s) ({} known, proxy={})",
                item, matched.size(), recipes.size(), VelocePotionMapper.isProxy(item));
        return matched;
    }

    @Override
    public List<ProcessingEntry> recipesAnywhere(ServerLevel level, Item item) {
        ensureRecipes(level);
        return recipes.stream().filter(r -> r.primaryResult().getItem() == item).toList();
    }
}
