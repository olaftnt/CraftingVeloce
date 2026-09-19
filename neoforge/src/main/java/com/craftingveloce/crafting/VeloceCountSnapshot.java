package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.util.VeloceLog;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A FROZEN COPY of everything the counting needs, taken on the server thread.
 *
 * <p><b>Why this exists.</b> Counting "how many of this can I still make" is the most
 * expensive thing this mod does: it plans a recipe tree, and for every item it does that
 * about {@code log2(upperBound)} times (growing attempts, then a bisection). On the server
 * thread that work has to fit inside a budget measured in MILLISECONDS - which is exactly
 * why the number was missing for whole pages of the terminal:
 *
 * <pre>
 *   instant craftable count for 38 item(s) -> 0 result(s) in 25 ms (complete=false)
 * </pre>
 *
 * <p>The fix is not a bigger budget - it is to stop doing the work on the thread that
 * ticks the world. The whole computation is pure arithmetic on numbers: it consumes a
 * stock map and produces a number. The ONLY reason it needs the world at all is to look
 * up recipes ({@code VeloceRecipeRegistry}, {@code VeloceModuleRecipes}) and to ask what
 * is in the network.
 *
 * <p><b>What is captured, and why each part.</b>
 * <ul>
 *   <li>{@link #stock} - the network contents PLUS the asking player's inventory. One read
 *       for the whole batch, exactly as before (a per-item read was the reason a page cost
 *       dozens of network scans).</li>
 *   <li>{@link #countable} - the items that may be crafted at all. The furnace union is
 *       already applied here, so the worker does not have to ask the level whether a
 *       furnace exists.</li>
 *   <li>{@link #preferred} - the player's recipe choices from the crafters.</li>
 *   <li>{@link #preferFurnace} - the per-item "furnace first" flags, copied out of the
 *       network because the network object itself must not be touched off-thread.</li>
 *   <li>{@link #heatOps} - "there is something to burn", or 0. The estimation uses a
 *       constant rather than the live fuel buffer (see {@code estimateHeatOps}), so this is
 *       a boolean dressed as a number.</li>
 *   <li>{@link #recipes} - EVERY recipe the counting can reach, resolved eagerly ON THE
 *       SERVER THREAD into a flat {@code Item -> List}. This is the one place where the
 *       worker would otherwise have to call into the recipe registry, and the registry
 *       reads a {@code RecipeManager} owned by the server. Resolving eagerly costs one walk
 *       of the item list we were asked about (a few dozen entries) and removes the last
 *       reason for the worker to touch the world.</li>
 * </ul>
 *
 * <p><b>Immutability.</b> Every collection is copied and wrapped, so a worker thread cannot
 * be affected by the player moving items around while it counts - the number it produces
 * answers the question "as of the moment the page was asked for", which is exactly what a
 * displayed number means. {@link #recipeFor} never mutates and returns shared immutable
 * lists.
 */
public final class VeloceCountSnapshot {

    private final Map<Item, Long> stock;
    private final Set<Item> countable;
    private final Map<Item, ResourceLocation> preferred;
    private final Set<Item> preferFurnace;
    private final long heatOps;
    private final boolean heatAvailable;

    /**
     * Recipes reachable from the requested items, resolved eagerly.
     *
     * <p>Keyed by the item being PLANNED, not by the item being requested: the planner
     * walks down the tree (a casing needs a stripped log), so the closure has to include
     * every intermediate item, not just the ones on screen.
     */
    private final Map<Item, List<ProcessingEntry>> recipes;

    /**
     * Whether the recipe closure was cut short.
     *
     * <p>The closure is bounded (see {@link #MAX_CLOSURE_ITEMS}) so that a request can
     * never turn into "walk every recipe in the modpack". When it is cut, the worker may
     * see an item with no recipes that really has some - the batch then reports
     * {@code complete=false} instead of claiming a zero, so the GUI keeps the old number
     * rather than showing a wrong one.
     */
    private final boolean closureTruncated;

    /** Upper bound on the number of distinct items pulled into the recipe closure. */
    public static final int MAX_CLOSURE_ITEMS = 4096;

    private VeloceCountSnapshot(Map<Item, Long> stock, Set<Item> countable,
                                Map<Item, ResourceLocation> preferred,
                                Set<Item> preferFurnace, long heatOps, boolean heatAvailable,
                                Map<Item, List<ProcessingEntry>> recipes,
                                boolean closureTruncated) {
        this.stock = stock;
        this.countable = countable;
        this.preferred = preferred;
        this.preferFurnace = preferFurnace;
        this.heatOps = heatOps;
        this.heatAvailable = heatAvailable;
        this.recipes = recipes;
        this.closureTruncated = closureTruncated;
    }

    /**
     * Captures the world state for the given items. <b>Server thread only.</b>
     *
     * @param items    the items to count (the visible terminal page)
     * @param enabled  the auto-craftable set from the crafters
     * @param inventory the asking player's inventory, or null
     */
    public static VeloceCountSnapshot capture(ServerLevel level, VelocePipeNetwork network,
                                              java.util.Collection<Item> items,
                                              Set<Item> enabled,
                                              Map<Item, ResourceLocation> preferred,
                                              @javax.annotation.Nullable
                                              VeloceAutoCrafter.ItemInventory inventory) {
        // --- 1. the stock: network + the player's pockets, ONE read ---
        Map<Item, Long> stock = new HashMap<>(network.getAllItemCounts(level));
        if (inventory != null) {
            for (Item carried : inventory.allItems()) {
                stock.merge(carried, (long) inventory.count(carried), Long::sum);
            }
        }

        // --- 2. heat, as a yes/no for the estimation ---
        boolean heatAnywhere = VeloceHeatSources.hasAnyHeatSource(level, network);
        long heatOps = heatAnywhere ? HEAT_OPS : 0L;

        // --- 3. the countable set, with the furnace union already applied ---
        Set<Item> countable = new HashSet<>(enabled);
        if (heatAnywhere) {
            countable.addAll(VeloceRecipeRegistry.getAllFurnaceCraftableItems(level));
        }

        // --- 4. the per-item "furnace first" flags, copied OUT of the network ---
        Set<Item> preferFurnace = new HashSet<>();
        for (Item it : countable) {
            if (network.prefersFurnace(it)) {
                preferFurnace.add(it);
            }
        }

        // --- 5. the recipe closure, resolved eagerly ---
        Map<Item, List<ProcessingEntry>> recipes = new HashMap<>();
        boolean truncated = false;
        // Breadth-first from the requested items. We do NOT try to be clever about which
        // intermediate items will be needed: the planner decides that, and guessing wrong
        // means an item it wants has "no recipes" and the number silently comes out 0.
        List<Item> frontier = new ArrayList<>(new java.util.LinkedHashSet<>(items));
        Set<Item> seen = new HashSet<>(frontier);
        while (!frontier.isEmpty()) {
            if (seen.size() > MAX_CLOSURE_ITEMS) {
                truncated = true;
                break;
            }
            List<Item> next = new ArrayList<>();
            for (Item item : frontier) {
                List<ProcessingEntry> forItem = resolveRecipes(level, network, item, heatAnywhere);
                recipes.put(item, forItem);
                for (ProcessingEntry entry : forItem) {
                    for (net.minecraft.world.item.crafting.Ingredient ing : entry.ingredients()) {
                        for (var option : ing.getItems()) {
                            if (option.isEmpty()) {
                                continue;
                            }
                            Item optItem = option.getItem();
                            if (seen.add(optItem)) {
                                next.add(optItem);
                            }
                        }
                    }
                }
            }
            frontier = next;
        }

        VeloceCountSnapshot snap = new VeloceCountSnapshot(
                java.util.Collections.unmodifiableMap(stock),
                java.util.Collections.unmodifiableSet(countable),
                java.util.Collections.unmodifiableMap(new HashMap<>(preferred)),
                java.util.Collections.unmodifiableSet(preferFurnace),
                heatOps, heatAnywhere,
                java.util.Collections.unmodifiableMap(recipes),
                truncated);

        // WHAT WAS CAPTURED, in one line.
        //
        // "0 with a value, 45 with ZERO" has two completely different causes - everything
        // really is uncraftable, or `countable` came out EMPTY so every item was answered
        // from the "not in the set" branch without a single recipe being looked at. The two
        // are indistinguishable in the GUI (both draw nothing) and were indistinguishable
        // in the log until this line, which is why a whole round was spent counting a rig
        // that could not produce a number. Logged at NORMAL, not DETAIL: this is the
        // question every "+N is missing" report starts with.
        VeloceLog.Craft.success(VeloceLog.Side.SERVER,
                "count snapshot: %d item(s) requested, %d in the countable set, "
                        + "stock=%d type(s), heat=%s, closure=%d item(s)%s",
                items.size(), countable.size(), stock.size(),
                heatAnywhere ? "yes" : "no", recipes.size(),
                truncated ? " TRUNCATED" : "");

        return snap;
    }

    /**
     * All recipes for one item: vanilla/free PLUS module recipes.
     *
     * <p>This is the exact union the planner used to build inline
     * ({@code VeloceAutoCrafter.allRecipesFor}). It lives here so that it happens once, on
     * the server thread, and the worker only ever reads the result.
     */
    private static List<ProcessingEntry> resolveRecipes(ServerLevel level,
                                                        VelocePipeNetwork network,
                                                        Item item, boolean heatAvailable) {
        List<ProcessingEntry> vanilla =
                VeloceRecipeRegistry.getRecipesFor(level, item, heatAvailable);
        List<ProcessingEntry> modules = VeloceModuleRecipes.forItem(level, network, item);
        if (modules.isEmpty()) {
            return List.copyOf(vanilla);
        }
        List<ProcessingEntry> out = new ArrayList<>(vanilla.size() + modules.size());
        out.addAll(vanilla);
        out.addAll(modules);
        return List.copyOf(out);
    }

    /** The estimation heat constant - "there is something to burn". */
    private static final long HEAT_OPS = 1_000_000L;

    // ------------------------------------------------------------------
    // Reader API - safe from any thread
    // ------------------------------------------------------------------

    public Map<Item, Long> stock() {
        return stock;
    }

    public Set<Item> countable() {
        return countable;
    }

    public Map<Item, ResourceLocation> preferred() {
        return preferred;
    }

    public boolean prefersFurnace(Item item) {
        return preferFurnace.contains(item);
    }

    public long heatOps() {
        return heatOps;
    }

    /** Whether any heat source stands in the network (for the log and {@code countable}). */
    public boolean heatAvailable() {
        return heatAvailable;
    }

    /**
     * Recipes for an item.
     *
     * <p>{@code List.of()} for an item outside the closure. That is indistinguishable from
     * "no recipes" to the planner, which is why {@link #closureTruncated()} exists and is
     * reported as {@code complete=false}.
     */
    public List<ProcessingEntry> recipesFor(Item item) {
        List<ProcessingEntry> out = recipes.get(item);
        return out == null ? List.of() : out;
    }

    /** Whether the recipe closure was cut short - a result computed from it is partial. */
    public boolean closureTruncated() {
        return closureTruncated;
    }

    /** How many items the closure covers - for the progress log. */
    public int closureSize() {
        return recipes.size();
    }
}
