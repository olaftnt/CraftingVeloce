package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Veloce auto-crafting engine.
 *
 * <p><b>Overriding principle:</b> we craft ONLY what the player has consciously
 * enabled (auto-crafting ON for the given item). The recursion descends only
 * into items with auto-crafting enabled. An ingredient without auto-crafting
 * enabled must simply be in stock - otherwise the whole operation fails and
 * <b>nothing</b> is crafted.
 *
 * <p>Thanks to this there are no "items out of nowhere" (e.g. sticks when
 * stick crafting is disabled).
 *
 * <p>The algorithm has two phases:
 * <ol>
 *   <li><b>Planning</b> - a pure simulation on numbers, without touching items.</li>
 *   <li><b>Execution</b> - only once the plan checks out, does it physically take
 *       the ingredients and insert the results.</li>
 * </ol>
 * Thanks to the phase split there is no situation where some ingredients have
 * already been consumed and the crafting still failed.
 */
public final class VeloceAutoCrafter {

    /** Recipe-resolver diagnostics (see {@code [VELOCE-DEBUG]} lines in the planner). */
    private static final org.slf4j.Logger RESOLVER_LOG =
            org.slf4j.LoggerFactory.getLogger("craftingveloce-resolver");

    /**
     * Recursion depth for REAL crafting.
     *
     * <p>12 is already an absurdly long chain for a player, and every level
     * multiplies the number of branches. At 24 the recipe tree grew exponentially
     * and a single task could freeze the server thread for tens of seconds.
     * Deeper chains lose on the time budget anyway.
     */
    private static final int CRAFT_MAX_DEPTH = 12;

    /**
     * Time budget for planning a REAL craft (50 ms).
     *
     * <p>It used to be 0 here, i.e. no time limit - the only safety valve was an
     * operation counter, and that is far too late to save a tick.
     */
    private static final long CRAFT_PLAN_BUDGET_NS = 50_000_000L;

    /** Limit of planning steps for real crafting. */
    private static final int CRAFT_MAX_STEPS = 8192;

    /**
     * Emergency operation limit in a single estimation.
     *
     * <p><b>This is now the ONLY limit on the counting path, and it was the wall.</b> The
     * time budgets are gone (see {@link #countFromSnapshot}, which runs on
     * {@link VeloceCountWorker} with no deadline), so this counter - meant to be a
     * never-reached backstop - became the binding constraint. Measured on the terminal page
     * the report is about:
     *
     * <pre>
     *   count 0/35: minecraft:andesite -> UNKNOWN (op limit) (362 ms, ...)
     *   counts AFTER merge: 0 with a value, 4 with ZERO, 31 without any entry (complete=false)
     * </pre>
     *
     * One item burned 200 000 operations and 362 ms, came back UNKNOWN, and because the
     * CLIENT puts items without a value at the FRONT of the request, that same item led the
     * next request too - so it starved the whole page, every time. The item was ordinary
     * (andesite: 2 recipes); what is expensive is the BISECTION, which re-runs the whole plan
     * about log2(upperBound) times, and the upper bound on that page was in the hundreds.
     *
     * <p><b>Why it is raised rather than removed.</b> The counter's purpose is to stop a
     * pathological recipe graph from spinning forever, and that purpose is real. Off the
     * server thread a page may now take hundreds of milliseconds without harming the tick
     * (measured: 362 ms for this very item), so the ceiling is set high enough that a
     * genuinely countable item finishes and low enough that a cycle cannot run away. The
     * abort is still reported - {@code UNKNOWN} plus {@code complete=false} - so the client
     * keeps the previous number instead of being shown a false zero.
     *
     * <p>The previous value (2000) was the main constraint and was <b>drastically too
     * small</b>: with 5033 recipes the counter ran out halfway through the computation and
     * returned 0, so an oak fence made from 2 logs showed up as impossible.
     */
    private static final int MAX_ESTIMATE_OPS = 300_000;

    /**
     * How many ingredient options the planner may RECURSE INTO per ingredient, per run.
     *
     * <p>Deliberately small: the options are sorted first (stock first, then the ones
     * craftable in this network), so the workable ones are tried first and the cap only ever
     * cuts off the long tail of "could this maybe be made another way". Without it the tail
     * is what a page dies on - see the note at the use site, where one ordinary item reached
     * 300 000 operations because Alchemistry dusts are all countable and every option was
     * explored.
     */
    private static final int MAX_INGREDIENT_ATTEMPTS = 4;

    /**
     * The "could not compute" result (the time budget ran out).
     *
     * <p>The distinction matters: 0 means "it definitely cannot be made", while
     * {@code UNKNOWN_COUNT} means "I do not know, try again later". Without it an
     * aborted planning run cleared the counter in the GUI.
     */
    public static final long UNKNOWN_COUNT = -1L;

    /**
     * Budget for recomputing the visible terminal page.
     *
     * <p>This runs on the server thread, so it must leave headroom for the rest of
     * the tick.
     *
     * <p><b>Why 25 ms and not 8.</b> At 8 ms the budget broke halfway through a page
     * (in the log: "instant craftable count for 45 item(s) -> 17 result(s)
     * in 8 ms (complete=false)"). And with an incomplete response the client
     * DELIBERATELY keeps the old numbers for the unfinished items - so the user saw
     * a stale "how many can be made" and that is exactly the reported bug. 25 ms
     * happens only on opening/scrolling a page, not every tick, so it is safe.
     */
    public static final long DEFAULT_ESTIMATE_BUDGET_NS = 25_000_000L;

    private VeloceAutoCrafter() {
    }

    /**
     * The result of an operation.
     *
     * <p>{@code reason} is the language key of the message, and {@code detail} is
     * its complement (e.g. the name of the missing item). Previously the reason was
     * only a key that NOBODY displayed - the player saw a generic "no such item in
     * the network", and one had to dig through the log to find out what was going on.
     */
    public record CraftResult(boolean success, int produced, String reason, String detail,
                              String hint) {
        static CraftResult ok(int produced) {
            return new CraftResult(true, produced, "", "", "");
        }

        static CraftResult fail(String reason) {
            return new CraftResult(false, 0, reason, "", "");
        }

        static CraftResult fail(String reason, String detail) {
            return new CraftResult(false, 0, reason, detail == null ? "" : detail, "");
        }

        /**
         * A failure that also says WHERE ELSE the item could be made.
         *
         * <p>{@code hint} is a comma-separated list of machine names (description ids),
         * the same convention as {@code detail}. It is shown as a separate line under
         * the reason - an OPTION, not a cause: the whole point of the branch that
         * builds it is that blaming a machine the player does not need was wrong.
         */
        static CraftResult fail(String reason, String detail, String hint) {
            return new CraftResult(false, 0, reason, detail == null ? "" : detail,
                    hint == null ? "" : hint);
        }
    }

    /**
     * Operation context: what the player enabled, which recipes are preferred,
     * and what is available.
     */
    public static final class Context {
        final ServerLevel level;
        final VelocePipeNetwork network;
        /** Items with auto-crafting enabled (only these may be crafted). */
        final Set<Item> enabledItems;
        /** Preferred recipes (item -> recipe id). */
        final Map<Item, ResourceLocation> preferred;
        /**
         * Player inventory. <b>Always {@code null} in the current code</b> - see
         * the {@link ItemInventory} documentation. The branches that check it are
         * in place, but unreachable.
         */
        @Nullable
        final ItemInventory inventory;

        /**
         * Crafter buffers (scratch storage). Surplus production goes here and is
         * normally available to the network.
         */
        @Nullable
        final List<com.craftingveloce.inventory.VeloceCraftingBuffer> buffers;

        /**
         * Where to drop what the network did not accept.
         *
         * <p>Without this, with a full network the crafted items (and the
         * ingredients returned on a failed execution) simply vanished.
         */
        @Nullable
        final net.minecraft.core.BlockPos dropPos;

        public Context(ServerLevel level, VelocePipeNetwork network,
                       Set<Item> enabledItems, Map<Item, ResourceLocation> preferred,
                       @Nullable ItemInventory inventory) {
            this(level, network, enabledItems, preferred, inventory, null, null);
        }

        public Context(ServerLevel level, VelocePipeNetwork network,
                       Set<Item> enabledItems, Map<Item, ResourceLocation> preferred,
                       @Nullable ItemInventory inventory,
                       @Nullable List<com.craftingveloce.inventory.VeloceCraftingBuffer> buffers) {
            this(level, network, enabledItems, preferred, inventory, buffers, null);
        }

        public Context(ServerLevel level, VelocePipeNetwork network,
                       Set<Item> enabledItems, Map<Item, ResourceLocation> preferred,
                       @Nullable ItemInventory inventory,
                       @Nullable List<com.craftingveloce.inventory.VeloceCraftingBuffer> buffers,
                       @Nullable net.minecraft.core.BlockPos dropPos) {
            this.level = level;
            this.network = network;
            this.enabledItems = enabledItems;
            this.preferred = preferred;
            this.inventory = inventory;
            this.buffers = buffers;
            this.dropPos = dropPos;
        }

        boolean isEnabled(Item item) {
            return enabledItems.contains(item);
        }

        /**
         * How much smelting the network can bend RIGHT NOW - the heat budget for
         * the planner.
         *
         * <p>Computed once and remembered, because the question is asked for EVERY
         * item considered by the planner, and the answer requires walking the
         * network's nodes and reading their block entities. Without the memo that
         * would be hundreds of such walks per single click.
         *
         * <p>Zero means "no furnace" - and that is the ONLY source of this
         * information. The planner does not check block types on its own.
         */
        long heatOps() {
            if (heatOpsCache < 0) {
                heatOpsCache = VeloceHeatSources.totalOperations(level, network);
                VeloceLog.Craft.failure(VeloceLog.Side.SERVER, "HEAT OPS COMPUTED: " + heatOpsCache);
            }
            return heatOpsCache;
        }

        /** Recipes for the item, with the furnace only when there is something to burn. */
        List<ProcessingEntry> recipesFor(Item item) {
            return allRecipesFor(level, network, item, heatOps() > 0);
        }

        /**
         * Module machines for a given recipe type - fetched ONCE per execution.
         *
         * <p>The same lesson as with heat sources: fetching the machine list for
         * EVERY single unit would mean a full walk of the network's nodes with
         * sorting hundreds of times in one tick. Within a single order the set of
         * machines does not change.
         */
        java.util.List<com.craftingveloce.block.entity.VeloceProcessingSource> moduleSourcesFor(
                RecipeType<?> type) {
            if (moduleSourcesCache == null) {
                moduleSourcesCache = new HashMap<>();
            }
            return moduleSourcesCache.computeIfAbsent(type,
                    t -> VeloceProcessingSources.forType(level, network, t));
        }

        /**
         * What could not be taken during execution - for the player-facing message.
         *
         * <p>Set in {@code runOnce}; read once, when building the result.
         */
        @Nullable
        String lastMissingIngredient;

        private long heatOpsCache = -1L;
        private Map<RecipeType<?>, java.util.List<com.craftingveloce.block.entity.VeloceProcessingSource>>
                moduleSourcesCache;
    }

    /**
     * Vanilla recipes PLUS module recipes from other mods.
     *
     * <p>Every module that stands in the network and is powered contributes its
     * recipes for this item - otherwise the automation would never use a crusher
     * or a compactor, even though they stand connected to the pipes.
     */
    private static List<ProcessingEntry> allRecipesFor(ServerLevel level, VelocePipeNetwork network,
                                                       Item item, boolean heatAvailable) {
        List<ProcessingEntry> vanilla = VeloceRecipeRegistry.getRecipesFor(level, item, heatAvailable);
        List<ProcessingEntry> modules = VeloceModuleRecipes.forItem(level, network, item);
        if (modules.isEmpty()) {
            return vanilla;
        }
        List<ProcessingEntry> out = new ArrayList<>(vanilla.size() + modules.size());
        out.addAll(vanilla);
        out.addAll(modules);
        return out;
    }

    /**
     * Falls back to the amount the network ALREADY holds when the request cannot be
     * satisfied in full.
     *
     * <p><b>The bug this fixes</b> (player: "shift-click does not work on the items
     * from the furnace recipe; it works when I take one, but not a stack"). A plain
     * click asks for ONE unit and is satisfied straight from stock. A shift-click asks
     * for a whole stack, which the network usually does not have, so the planner is
     * asked to craft the remainder. When that could not be done, the whole request was
     * reported as failed and the terminal handed over NOTHING - the player lost even
     * the units that were already sitting in the network. That is why the symptom
     * looked count-dependent: one works, a stack does not.
     *
     * <p>Now a failed craft falls back to what is available, so a shift-click yields as
     * much as the network can actually provide. The original reason is still logged, so
     * the diagnostics do not regress into "it silently gave less".
     *
     * @param available units already present (inventory + network) at request time
     */
    private static CraftResult partialOrFail(long available, int count, CraftResult failure) {
        if (available <= 0) {
            return failure;
        }
        int give = (int) Math.min(count, available);
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "cannot satisfy the full request (%d) - handing over the %d already available "
                        + "instead of nothing (reason kept: %s)",
                count, give, failure.reason());
        return CraftResult.ok(give);
    }

    /**
     * Ensures that the network will contain at least {@code count} units of
     * {@code item}. Crafts the missing amount if the item has auto-crafting
     * enabled.
     */
    public static CraftResult ensureAvailable(ServerLevel level, VelocePipeNetwork network,
                                              Item item, int count, Context ctx) {
        return ensureAvailable(level, network, item, count, ctx, CRAFT_PLAN_BUDGET_NS);
    }

    /**
     * As above, but with an explicit planning budget.
     *
     * <p>Called from the background (extractor), where we cannot afford a full
     * player-task budget - otherwise one device eats the tick.
     */
    public static CraftResult ensureAvailable(ServerLevel level, VelocePipeNetwork network,
                                              Item item, int count, Context ctx,
                                              long planBudgetNanos) {
        if (count <= 0) {
            return CraftResult.fail("craftingveloce.craft.error.amount");
        }

        VeloceLog.Craft.attempt(VeloceLog.Side.SERVER,
                "ensure %sx %s (enabled=%s)", count, item, ctx.isEnabled(item));

        // 1. THE NETWORK FIRST.
        //
        // This was the other way round, with the player's inventory checked first and
        // short-circuiting the whole method. The owner asked for the opposite: an item
        // standing in a chest next to the terminal must be used before the one in the
        // player's pocket, and the inventory is only there to cover what the network could
        // not supply. Getting this backwards emptied a player's pockets while a chest full
        // of the same item sat in the same network.
        //
        // ONE forced scan - in a moment we make a decision about taking items, so we cannot
        // work on stale state. The same snapshot is then passed to planning.
        Map<Item, Long> netStock = network.getAllItemCounts(level, true);
        long inNetwork = netStock.getOrDefault(item, 0L);
        if (inNetwork >= count) {
            return CraftResult.ok(count);
        }

        // 2. The player's inventory is an INGREDIENT source, never a deliverable.
        //
        // THE BUG, and it was mine: `available` used to be `inNetwork + inInventory`, so an
        // item the player was merely CARRYING made this method answer "you already have it"
        // and return ok without crafting anything. The terminal then went to hand the item
        // over, found nothing in the NETWORK - which is the only place it can deliver from -
        // and returned an empty result with an empty reason. The player saw a click that did
        // nothing and no explanation, which is exactly the report: "I have a log in my
        // inventory and a log in the chest and it will not make the doors" (the trace showed
        // `in inventory=3` oak doors already carried, so the request short-circuited every
        // time).
        //
        // The inventory still counts as a source: `snapshotStock` merges it into the
        // planning stock and `takeOne` draws on it last of all. What it must not do is
        // satisfy the "nothing left to do" test, because being in the player's pocket is not
        // being in the network.
        int inInventory = ctx.inventory == null ? 0 : ctx.inventory.count(item);
        long available = inNetwork;
        long onStockBefore = inNetwork;
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "%s: inventory=%d, network=%d, requested=%d",
                item, inInventory, inNetwork, count);
        if (available >= count) {
            return CraftResult.ok(count);
        }

        // 3. Something is missing - we have to craft. Only allowed when enabled.
        int missing = (int) Math.min(Integer.MAX_VALUE, count - available);
        if (!ctx.isEnabled(item)) {
            // A CONCRETE reason, not just "disabled": no crafter, item
            // disabled in the crafter, no furnace, module machine without power...
            VeloceCraftingRegistry.DisabledReason reason =
                    VeloceCraftingRegistry.whyNotCraftable(level, network, item);
            VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                    "%s is not craftable in this network: %s %s",
                    item, reason.reasonKey(), reason.detail());
            VeloceCraftTrace.log("item is not craftable: %s %s",
                    reason.reasonKey(), reason.detail());
            // Even when the item cannot be CRAFTED, anything already in the network must
            // still reach the player - refusing to hand over existing stock because we
            // cannot make MORE of it is exactly the "shift-click gives nothing" bug.
            return partialOrFail(available, count,
                    CraftResult.fail(reason.reasonKey(), reason.detail()));
        }

        // TRACE (only for player actions - see VeloceCraftTrace.begin):
        // we dump EVERYTHING that decides the outcome before we compute anything.
        if (VeloceCraftTrace.active()) {
            // The terminal already dumps the environment and recipes (before the
            // "is it even allowed" gate), so here we only add the numbers.
            VeloceCraftTrace.log("task: %sx %s (%s), in network=%d, in inventory=%d, missing=%d",
                    count, VeloceCraftTrace.name(item), VeloceCraftTrace.id(item),
                    inNetwork, inInventory, missing);
            // The enabled set is printed because it is the switch the planner consults
            // before it will craft ANYTHING, and a report of "it will not use my log" is
            // unanswerable without knowing whether planks are in here.
            VeloceCraftTrace.log("auto-crafting enabled for: %s", ctx.enabledItems);
            VeloceCraftTrace.dumpStock(netStock, item,
                    VeloceRecipeFinder.all(level, item));
        }

        // Phase 1: planning (simulation on numbers).
        // We set a hard time budget - planning a recipe tree must not freeze the
        // server thread, even when the player asks for something absurdly complex.
        // On exceeding it we say "too complex", not "missing ingredients" - those
        // are two different situations.
        startEstimate(planBudgetNanos);
        Map<Item, Long> stock = snapshotStock(ctx, netStock, item);
        Plan plan = new Plan(ctx.heatOps());

        // How much we MANAGED to plan (may be less than missing).
        long planned = missing;

        if (!plan(level, ctx.network, ctx.enabledItems, ctx.preferred, item, missing, stock, plan, new HashSet<>(), 0)) {
            if (estimateAborted()) {
                VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                        "planning for %s x%d exceeded the %d ms budget - recipe tree too complex",
                        item, missing, planBudgetNanos / 1_000_000L);
                return partialOrFail(available, count,
                        CraftResult.fail("craftingveloce.craft.error.tooComplex"));
            }

            // THERE IS NOT ENOUGH FOR THE FULL AMOUNT - try to make LESS.
            //
            // The BUG that used to be here: planning went for exactly `missing`
            // units, and if that failed, the whole operation was lost. An extractor
            // that wanted a full stack (e.g. 64) got NOTHING, even when the network
            // had enough material for 12 units. Now we step down until we find a
            // feasible amount.
            planned = planAsMuchAsPossible(level, ctx, item, missing, stock, plan, planBudgetNanos);
            if (planned <= 0) {
                // A distinction that matters to the player: "I did not have time to
                // compute" is not the same as "you have nothing to make it from".
                // Previously both ended with a missing-ingredients message.
                if (estimateAborted()) {
                    VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                            "planning for %s x%d ran out of the %d ms budget",
                            item, missing, planBudgetNanos / 1_000_000L);
                    return partialOrFail(available, count,
                            CraftResult.fail("craftingveloce.craft.error.tooComplex"));
                }
                logPlanFailure(level, ctx, item, missing, stock);
                // WE SAY WHAT IS MISSING - otherwise the player only gets "no such
                // item in the network" and does not know whether the material, the
                // machine or the recipe is missing.
                return partialOrFail(available, count,
                        diagnosePlanFailure(level, ctx, item, stock, missing));
            }
        }

        // Phase 2: execution of exactly what was planned.
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "plan for %s x%d: %d recipe run(s) to execute",
                item, planned, plan.runs.size());
        VeloceCraftTrace.log("starting plan execution: %s x%d (steps=%d)",
                VeloceCraftTrace.id(item), planned, plan.runs.size());
        if (!execute(level, ctx, plan)) {
            VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                    "execution failed for %s - ingredients vanished mid-craft", item);
            String ingredient = ctx.lastMissingIngredient;
            if (ingredient == null || ingredient.isEmpty()) {
                // Execution failed with NOTHING missing, so the plan was fine and the
                // MACHINE could not pay. That is the "the machine has no power" case, and
                // it used to arrive as a bare "could not take ingredients" - the reason the
                // player sees is the one thing that has to name the machine.
                String machine = unpoweredMachine(level, ctx, item);
                if (!machine.isEmpty()) {
                    return partialOrFail(available, count,
                            CraftResult.fail("craftingveloce.craft.error.moduleUnpowered", machine));
                }
            }
            return partialOrFail(available, count,
                    CraftResult.fail("craftingveloce.craft.error.extract",
                            ingredient == null ? "" : ingredient));
        }
        // We return the amount that ACTUALLY came into being - it may be smaller
        // than requested, when there was not enough material (see
        // planAsMuchAsPossible). Previously the log and the result lied with the
        // full amount, even though less was executed.
        // How much actually arrived in the network. this is the number the caller
        // can safely extract - not the requested amount.
        long actuallyCrafted = Math.max(0L, planned);
        Map<Item, Long> after = network.getAllItemCounts(level, true);
        long gained = after.getOrDefault(item, 0L) - onStockBefore;
        if (gained > 0) {
            actuallyCrafted = gained;
        }
        VeloceLog.Craft.success(VeloceLog.Side.SERVER,
                "crafted %s x%d successfully (requested %d)", item, actuallyCrafted, count);
        return CraftResult.ok((int) Math.min(Integer.MAX_VALUE, actuallyCrafted));
    }

    /**
     * Operation counter of the current estimation.
     *
     * <p>ThreadLocal, because estimation may be called from different threads
     * (although normally only from the server thread), and we do not want to change
     * the signatures of all recursive methods.
     */
    private static final ThreadLocal<int[]> ESTIMATE_OPS =
            ThreadLocal.withInitial(() -> new int[]{0});

    /**
     * Deadline of the current estimation (System.nanoTime).
     *
     * <p>0 = no time budget (emergency operation limit only).
     */
    private static final ThreadLocal<long[]> ESTIMATE_DEADLINE =
            ThreadLocal.withInitial(() -> new long[]{0L});

    /**
     * Whether the current estimation was aborted for lack of budget.
     *
     * <p>Without this distinction an aborted planning run looked identical to
     * "this cannot be made" - the item got 0 and its counter disappeared from the
     * GUI, even though in reality we simply ran out of time to compute it.
     */
    private static final ThreadLocal<boolean[]> ESTIMATE_ABORTED =
            ThreadLocal.withInitial(() -> new boolean[]{false});

    /** Sets the time budget for the current estimation. */
    private static void startEstimate(long budgetNanos) {
        ESTIMATE_OPS.get()[0] = 0;
        ESTIMATE_ABORTED.get()[0] = false;
        ESTIMATE_DEADLINE.get()[0] = budgetNanos > 0
                ? System.nanoTime() + budgetNanos
                : 0L;
    }

    /** Whether the current estimation was aborted (result unknown). */
    private static boolean estimateAborted() {
        return ESTIMATE_ABORTED.get()[0];
    }

    /**
     * Whether the estimation should abort.
     *
     * <p><b>The history of this bug.</b> The time check was sampled every 64 calls
     * ({@code (ops & 63) == 0}). That sounds like a sensible optimization, but it
     * is wrong: if the whole planning of one item makes FEWER than 64 calls to
     * {@code plan}, the check happens only once - on the first call, when the time
     * has not yet run out - and never again. The time budget was then completely
     * dead, and the only remaining constraint was the emergency operation counter.
     * That is exactly what was visible in the log:
     *
     * <pre>
     *   TICK OVERRUN: 4520 ms total = scan 0 ms + chunks 0 ms + items 4520 ms
     * </pre>
     *
     * <p>That is why we now check the time on EVERY entry. {@code nanoTime} costs
     * a few dozen nanoseconds and protects against freezing the server.
     */
    private static boolean estimateBudgetExceeded() {
        int ops = ESTIMATE_OPS.get()[0]++;
        long deadline = ESTIMATE_DEADLINE.get()[0];
        if (deadline != 0L && System.nanoTime() > deadline) {
            ESTIMATE_ABORTED.get()[0] = true;
            return true;
        }
        if (ops > MAX_ESTIMATE_OPS) {
            ESTIMATE_ABORTED.get()[0] = true;
            return true;
        }
        return false;
    }

    /**
     * How many units of the given item can be <b>additionally produced</b> by
     * auto-crafting beyond what is already in the network.
     *
     * <p>This is the number for the yellow "+N" indicator in the terminal, so it
     * must be the <b>surplus</b>, not the total. Previously it returned the total
     * available units, which is why after crafting 4 planks (1 taken, 3 in the
     * buffer) it showed "+3" despite no log line at all - the counter was eating
     * its own stock.
     *
     * @return how many more units can be produced (0 when base ingredients are missing)
     */
    public static long countCraftableNow(ServerLevel level, VelocePipeNetwork network,
                                         Item item, Set<Item> enabledItems,
                                         Map<Item, ResourceLocation> preferred) {
        return countCraftableNow(level, network, item, enabledItems, preferred,
                DEFAULT_ESTIMATE_BUDGET_NS);
    }

    /**
     * As above, but with an explicit time budget.
     *
     * @param budgetNanos maximum time in nanoseconds; 0 = no time limit
     *                    (emergency operation limit only)
     */
    public static long countCraftableNow(ServerLevel level, VelocePipeNetwork network,
                                         Item item, Set<Item> enabledItems,
                                         Map<Item, ResourceLocation> preferred,
                                         long budgetNanos) {
        if (!enabledItems.contains(item)) {
            return 0L;
        }
        Map<Item, Long> stock = new HashMap<>(network.getAllItemCounts(level));
        return countCraftableFromStock(level, network, item, stock, enabledItems, preferred,
                budgetNanos, VeloceHeatSources.totalOperations(level, network));
    }

    /**
     * How many units can be <b>additionally produced from raw materials</b> -
     * without counting what is already finished.
     *
     * <p><b>The BUG this fixes.</b> The previous version computed it like this:
     * <pre>
     *   total = maxCraftable(stock)      // = fromStock + lo
     *   craftable = total - onStock      // = lo
     * </pre>
     * and it seemed that the stock was being subtracted. But it <b>is not</b>: the
     * function {@code plan} (used in the bisection inside {@code maxCraftable})
     * FIRST CONSUMES what is already in stock, and only crafts the rest. So
     * {@code lo} = the maximum "how many one can HAVE", not "how many one can
     * ADDITIONALLY PRODUCE" - that is, it includes the stock. Subtracting the stock
     * achieved nothing, because that same stock was already included in {@code lo}.
     *
     * <p><b>The symptom.</b> The terminal showed a counter that changed when a
     * finished item was moved around:
     * <pre>
     *   356  (0 in stock)
     *   355  after crafting 1 log: +3 to the buffer, -1 log  (-4 from raw +3 stock)
     *   354  after taking 1 unit out of the buffer            (-1 stock)
     *   353  after taking another one                         (-1 stock)
     * </pre>
     * Finished items in the buffer therefore reduced the "how many more can I make"
     * number, even though those two things were supposed to be kept separate.
     *
     * <p><b>The solution.</b> We compute with this item's stock ZEROED OUT.
     * The plan then has nothing to consume at the start, so the bisection finds
     * exactly how much can be produced from raw materials. The result does not
     * depend on how many finished units lie in the buffer, a chest or anywhere else.
     *
     * @return how many can be produced from raw materials (UNKNOWN_COUNT when out of budget)
     */
    private static long craftableFromRaw(ServerLevel level, VelocePipeNetwork network,
                                         Item item, Map<Item, Long> stock,
                                         Set<Item> enabled,
                                         Map<Item, ResourceLocation> preferred,
                                         long heatOps) {
        Map<Item, Long> rawStock = new HashMap<>(stock);
        rawStock.put(item, 0L);
        // With zero stock, maxCraftable returns just "lo" (because fromStock = 0),
        // i.e. exactly the amount makeable from raw materials.
        return maxCraftable(level, network, item, rawStock, enabled, preferred, heatOps);
    }

    public static long countCraftableFromStock(
            ServerLevel level, VelocePipeNetwork network, Item item, Map<Item, Long> stock,
            Set<Item> enabledItems, Map<Item, ResourceLocation> preferred,
            long budgetNanos, long heatOps) {
        if (!enabledItems.contains(item)) {
            return 0L;
        }
        startEstimate(budgetNanos);
        long craftable = craftableFromRaw(level, network, item, stock, enabledItems, preferred, heatOps);
        if (craftable == UNKNOWN_COUNT) {
            return UNKNOWN_COUNT;
        }
        return Math.max(0L, craftable);
    }

    /**
     * The result of a batch computation.
     *
     * @param counts   how many can be additionally produced (only values > 0)
     * @param complete whether ALL requested items were computed
     * @param heatAvailable whether ANY heat source (furnace) stands in the network.
     *        For the log: without this one cannot distinguish "there is no furnace"
     *        from "the furnace is just refuelling" - and those are two completely
     *        different states.
     */
    public record BatchResult(Map<Item, Long> counts, boolean complete,
                              boolean heatAvailable) {
        /** Variant without heat information - for early exits (no network etc.). */
        public BatchResult(Map<Item, Long> counts, boolean complete) {
            this(counts, complete, false);
        }
    }

/**
     * Computes many items at once, sharing a single time budget.
     *
     * <p>Used for the INSTANT recomputation of the visible terminal page.
     * Instead of paying for a stock read per item, we do it once. Thanks to that
     * 45 items are computed in a few milliseconds.
     *
     * @return map item -> how many can be additionally produced (only values > 0)
     */
    public static Map<Item, Long> countCraftableBatch(
            ServerLevel level, VelocePipeNetwork network,
            java.util.Collection<Item> items, Set<Item> enabledItems,
            Map<Item, ResourceLocation> preferred, long budgetNanos) {
        return countCraftableBatchResult(level, network, items, enabledItems,
                preferred, budgetNanos).counts();
    }

    /**
     * Like {@link #countCraftableBatch}, but it also says whether the batch was
     * computed in full.
     *
     * <p>This matters for the GUI: when the budget ran out and some items were
     * skipped, the client must NOT treat a missing entry as "cannot be made" -
     * otherwise the numbers disappeared and never came back.
     */
    public static BatchResult countCraftableBatchResult(
            ServerLevel level, VelocePipeNetwork network,
            java.util.Collection<Item> items, Set<Item> enabledItems,
            Map<Item, ResourceLocation> preferred, long budgetNanos) {
        return countCraftableBatchResult(level, network, items, enabledItems, preferred,
                budgetNanos, null);
    }

    /**
     * As above, but also counting what the PLAYER is carrying.
     *
     * <p><b>Why the estimate needs the player at all.</b> The whole point of these numbers is
     * the question "how many of this can I have", and until now the answer only ever counted
     * the network. A player holding an oak log saw no number for oak planks, and a player with
     * a log in hand and a chest of them beside the terminal saw the same number as with the
     * chest alone - because the inventory was not part of the arithmetic.
     *
     * <p>The estimate is allowed to count the whole inventory, including copies of the
     * requested item: for a "how many can I have" display, what the player is already holding
     * is part of the answer. (CRAFTING deliberately does not do this - see snapshotStock -
     * because the terminal has to produce the item into the network before it can hand it
     * over. Two different questions, two different answers.)
     */
    public static BatchResult countCraftableBatchResult(
            ServerLevel level, VelocePipeNetwork network,
            java.util.Collection<Item> items, Set<Item> enabledItems,
            Map<Item, ResourceLocation> preferred, long budgetNanos,
            @Nullable ItemInventory inventory) {
        Map<Item, Long> out = new HashMap<>();
        if (items == null || items.isEmpty()) {
            return new BatchResult(out, true, false);
        }

        // A single stock read for the whole batch.
        Map<Item, Long> stockSnapshot = network.getAllItemCounts(level);
        if (inventory != null) {
            for (Item carried : inventory.allItems()) {
                stockSnapshot.merge(carried, (long) inventory.count(carried), Long::sum);
            }
        }

        // Heat for the NUMBERS: "is there a furnace in the network", not "how much
        // is in its buffer RIGHT NOW".
        //
        // The BUG this fixes (player report: "the numbers do not load"): we took
        // totalOperations(), i.e. the current fuel buffer. A fuel furnace burns fuel
        // and REFILLS it from the network, so the buffer cyclically drops to zero -
        // in that window the furnace recipes were disabled, the number for glass came
        // out 0 and stayed that way in the GUI (zero is not drawn), and after
        // clearing the text in the search box a new task hit a moment with fuel and
        // the number appeared. The number should answer the question "how many can I
        // have from what is in the network", not "how long will the fuel in this
        // second last" (see estimateHeatOps).
        boolean heatAnywhere = VeloceHeatSources.hasAnyHeatSource(level, network);
        long heatOps = heatAnywhere ? ESTIMATE_HEAT_OPS : 0L;

        // Since we compute "how many I CAN have", with a furnace placed the items
        // with a furnace recipe are countable even in a window without fuel -
        // otherwise they dropped out of `enabled` and got zero (see the comment above).
        Set<Item> countable = enabledItems;
        if (heatAnywhere && !VeloceRecipeRegistry.getAllFurnaceCraftableItems(level).isEmpty()) {
            Set<Item> merged = new HashSet<>(enabledItems);
            merged.addAll(VeloceRecipeRegistry.getAllFurnaceCraftableItems(level));
            countable = merged;
        }
        long deadline = budgetNanos > 0 ? System.nanoTime() + budgetNanos : 0L;
        boolean complete = true;

        // ORDER: exactly as it came from the client.
        //
        // There used to be a start offset ("rotation") so that the tail of the list
        // would also get its turn at being computed. It turned out to be worse than
        // the problem: the batch started at a random place, the budget ran out, and
        // the BEGINNING of the list (e.g. glass in the search box) had no numbers
        // while the bottom did - that is exactly what the player reported. The client
        // now puts items without a value at the front of the task itself (see
        // VeloceCraftableCounts), so the server should simply compute in the given
        // order.
        List<Item> queue = items instanceof List<Item> list ? list : new ArrayList<>(items);
        int size = queue.size();
        int aborted = 0;

        for (int i = 0; i < size; i++) {
            Item item = queue.get(i);
            if (!countable.contains(item)) {
                // The "cannot be made" entry is CORRECT and must reach the result -
                // otherwise the GUI would keep the old, inflated number.
                out.put(item, 0L);
                continue;
            }
            // Break when the budget is exceeded - the rest comes in the next task.
            if (deadline != 0L && System.nanoTime() > deadline) {
                complete = false;
                break;
            }
            Map<Item, Long> stock = new HashMap<>(stockSnapshot);
            // Budget for THIS item = an equal share of the remaining time, not the
            // whole window.
            //
            // The BUG this fixes: every item got the WHOLE remaining window, so one
            // overly complex (or expensive) item ate the budget of the entire batch
            // and the remaining items got no numbers - in the player's log you could
            // see "45 item(s) -> 0 result(s) (complete=false)". An equal share
            // guarantees that every item gets its chance, and those that do not fit
            // come back in the next task - at the front of the list, because the
            // client puts items without a value first (VeloceCraftableCounts).
            long slice = 0L;
            if (deadline != 0L) {
                long left = Math.max(0L, deadline - System.nanoTime());
                slice = Math.max(MIN_ITEM_BUDGET_NS, left / Math.max(1, size - i));
            }
            startEstimate(slice);
            long total = craftableFromRaw(level, network, item, stock, countable, preferred, heatOps);
            // (the index `i` is used only for the equal budget split above)
            if (total == UNKNOWN_COUNT) {
                // THIS item did not fit in the budget - we skip IT, but do NOT
                // abort the whole batch.
                //
                // The BUG this fixes: `break` ended the batch after the first
                // difficult item. In the player's log this was visible as
                // "instant craftable count for 45 item(s) -> 0 result(s)
                // (complete=false)" - ONE overly complex item (or a momentary lack
                // of time) took the numbers away from ALL the remaining ones, and
                // the player saw this as "the controller cannot compute the furnace".
                //
                // The next item gets a fresh budget (startEstimate below), and once
                // the TIME runs out, the loop breaks on the deadline check at the
                // top - so there is no risk of an infinite loop.
                complete = false;
                aborted++;
                continue;
            }
            // We store ZEROS as well. Previously the entry appeared only for
            // surplus > 0, so "nothing more can be made" was indistinguishable from
            // "this item was not computed" and the client kept the old, inflated
            // number. Zero is a concrete, correct answer.
            out.put(item, Math.max(0L, total));
        }
        if (aborted > 0) {
            // How many items did not fit in their share - that is the number that
            // explains "the GUI does not show numbers for some items".
            VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                    "craftable count: %d of %d item(s) aborted the estimation "
                            + "(item too heavy for its share of time)", aborted, size);
        }
        return new BatchResult(out, complete, heatAnywhere);
    }

    /**
     * Finds the LARGEST amount of the item that can be planned from this stock.
     *
     * <p>Used when it was not possible to plan the full requested amount. Bisection
     * on the amount: instead of returning "cannot be done" and leaving the player
     * with nothing, we deliver as much as we are really able to make (e.g. 12 of 64).
     *
     * @return the amount stored into {@code plan}, or 0 when nothing can be made
     */
    private static long planAsMuchAsPossible(ServerLevel level, Context ctx, Item item,
                                             long wanted, Map<Item, Long> stock, Plan plan,
                                             long planBudgetNanos) {
        // The SAME algorithm as when computing numbers (findMaxPlannable): first
        // CHEAP attempts (1, 2, 4, ...), then bisection. Previously this method
        // started from `wanted / 2`, so the first attempt was the most expensive -
        // with a recipe of 21 ingredients (Create mechanical crafting) and an order
        // of 64 units the plan was never built within the budget and the player got
        // "no items", even though the GUI showed a feasible number.
        Plan best = new Plan(ctx.heatOps());
        long planned = findMaxPlannable(level, ctx.network, item, Math.max(1, wanted), stock,
                ctx.enabledItems, ctx.preferred, ctx.heatOps(), planBudgetNanos, best);
        if (planned <= 0) {
            return 0;
        }
        plan.runs.clear();
        plan.runs.addAll(best.runs);
        VeloceCraftTrace.log("plan: requested %d, feasible %d, steps in plan %d, heat=%d",
                wanted, planned, plan.runs.size(), plan.heatRemaining);
        // [VELOCE-DEBUG] mirror of the trace line: the trace channel can be off, and
        // "the resolver found nothing / found fewer than asked" is the single most
        // useful fact when a recipe silently does not craft.
        RESOLVER_LOG.debug("[VELOCE-DEBUG] recipe resolver: item={} requested={} feasible={} "
                        + "steps={} heatRemaining={} proxy={}",
                item, wanted, planned, plan.runs.size(), plan.heatRemaining,
                com.craftingveloce.util.VelocePotionMapper.isProxy(item));
        for (var run : plan.runs) {
            VeloceCraftTrace.log("  plan step: %s x%d", run.recipe().id(), run.times());
            RESOLVER_LOG.debug("[VELOCE-DEBUG] recipe resolver step: {} x{} (type={}, furnace={})",
                    run.recipe().id(), run.times(), run.recipe().type(), run.recipe().isFurnace());
        }
        if (planned < wanted) {
            VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                    "%s: requested %d, feasible %d (plan within the %d ms budget)",
                    item, wanted, planned, planBudgetNanos / 1_000_000L);
        }
        return planned;
    }

    /**
     * Logs why planning failed.
     *
     * <p>It goes through {@link VeloceLog}, not straight to the LOGGER.
     *
     * <p><b>There used to be a source of log spam here.</b> The previous version
     * wrote through {@code CraftingVeloceMod.LOGGER.info(...)} with its own "[Veloce]"
     * prefix, so it bypassed both the debug level and the category switches from the
     * config - it fired ALWAYS, on every failed attempt. And since every click on an
     * item that cannot be made ends in a failed plan, the log clogged up during
     * normal clicking around the GUI.
     *
     * <p>In addition, the first ingredient of every recipe is nothing more than an
     * EXAMPLE (Ingredient.getItems() returns all accepted stacks), so "have=0" for
     * it does not mean that exactly that item is missing.
     */
    private static void logPlanFailure(ServerLevel level, Context ctx, Item item,
                                       int missing, Map<Item, Long> stock) {
        if (!VeloceLog.Craft.isDetailEnabled(VeloceLog.Side.SERVER)) {
            return;   // cheap check - we do not build strings for nothing
        }
        VeloceLog.Craft.why(VeloceLog.Side.SERVER,
                "craft %s x%d failed: no base ingredients (enabled=%d, %d item type(s) in stock)",
                item, missing, ctx.enabledItems.size(), stock.size());
        var recipes = ctx.recipesFor(item);
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "  %s has %d recipe(s)", item, recipes.size());
        for (var r : recipes) {
            StringBuilder sb = new StringBuilder();
            for (var ing : r.ingredients()) {
                var opts = ing.getItems();
                if (opts.length == 0) {
                    continue;
                }
                // We give the number of ACCEPTED options, not one example - a single
                // "have=0" was misleading, because a different option was missing.
                int have = 0;
                for (var opt : opts) {
                    if (stock.getOrDefault(opt.getItem(), 0L) > 0) {
                        have++;
                    }
                }
                sb.append(have).append('/').append(opts.length).append(" option(s) available; ");
            }
            VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                    "  %s <- %s", r.id(), sb.toString());
        }
    }

    // ------------------------------------------------------------------
    // Planning phase - pure arithmetic on numbers
    // ------------------------------------------------------------------

    /** Plan: how many times to run which recipe, in execution order. */
    static final class Plan {
        final List<PlannedRun> runs = new ArrayList<>();


        /**
         * How many smelting operations may still be planned.
         *
         * <p>A furnace recipe consumes one smelting operation per EVERY unit, so the
         * plan cannot promise more items than the furnace can bend. Zero also means
         * "no furnace" - and that is why it extinguishes furnace recipes during
         * planning when the budget runs out.
         */
        long heatRemaining;

        Plan(long heatRemaining) {
            this.heatRemaining = heatRemaining;
        }

        void add(ProcessingEntry recipe, long times) {
            runs.add(new PlannedRun(recipe, times));
        }

        /**
         * Rolls the plan back to the mark - <b>together with the heat</b>.
         *
         * <p>This is not cosmetic. Planning tries successive recipes and undoes failed
         * attempts, and every failed attempt may already have consumed heat. Without
         * giving it back, the budget would melt away on failures alone, and after a
         * few attempts the planner would conclude that the furnace is empty - even
         * though not a single smelting operation was performed.
         */
        void rollbackTo(int mark) {
            while (runs.size() > mark) {
                PlannedRun removed = runs.remove(runs.size() - 1);
                if (removed.recipe().isFurnace()) {
                    heatRemaining += removed.times();
                }
            }
        }
    }

    record PlannedRun(ProcessingEntry recipe, long times) {
    }

    /**
     * Plans obtaining {@code amount} units of {@code item}.
     * Modifies {@code stock} (a simulation of consumption). Does not touch the world.
     */
    private static boolean plan(ServerLevel level, VelocePipeNetwork network,
                                Set<Item> enabled,
                                Map<Item, ResourceLocation> preferred,
                                Item item, long amount,
                                Map<Item, Long> stock, Plan plan, Set<Item> visiting, int depth) {
        if (amount <= 0) {
            return true;
        }
        // We check the time budget ALWAYS, right on entry.
        //
        // The BUG that hung here before: this condition was pulled inside the
        // "depth/step exceeded" block. So it only executed when the limit had
        // already been exceeded anyway - and then it returned false regardless.
        // The effect: plan() had NO time throttling at all and the recipe tree grew
        // exponentially on the server thread.
        if (estimateBudgetExceeded()) {
            return false;
        }
        if (depth > CRAFT_MAX_DEPTH || plan.runs.size() > CRAFT_MAX_STEPS) {
            return false;
        }
        if (!visiting.add(item)) {
            return false;   // recipe cycle
        }
        try {
            // First consume what is already in stock.
            long have = stock.getOrDefault(item, 0L);
            long fromStock = Math.min(have, amount);
            stock.put(item, have - fromStock);
            long remaining = amount - fromStock;
            if (remaining <= 0) {
                return true;
            }

            // Recursion only for the item the player ASKED for - not for its ingredients.
            //
            // The gate said "may this item be auto-crafted", and applying it at every depth
            // meant a request for oak doors REFUSED to make the oak planks for them. So an
            // oak log sitting right there - in a chest, or in the player's own inventory -
            // was ignored, and the request failed with "not supplied". The player asked for
            // doors; the planks are a step towards them, not a request of their own, and
            // nobody who clicks a door expects to have to go and enable planks first.
            //
            // depth == 0 is the requested item, and its switch has already been honoured by
            // the caller (ensureAvailable returns early when the item is not enabled), so
            // this now only refuses what was never asked for.
            if (!enabled.contains(item) && depth == 0) {
                return false;
            }

            // Furnace recipes only as long as there is something to pay with. When
            // the heat budget runs out, the furnace stops being an option - just as
            // it would stop being one if ingredients were missing.
            List<ProcessingEntry> recipes =
                    orderRecipes(level, network, item, preferred, plan.heatRemaining > 0,
                            network.prefersFurnace(item));
            if (recipes.isEmpty()) {
                // NOTHING can make this item. Deeper recording used to name it here, and that was
                // WRONG in a way the player saw immediately: a recipe whose ingredient can be
                // converted back and forth (a clock needs gold INGOTS, ingots are made from
                // nuggets, nuggets from ingots) walked every branch of that cycle and added one
                // entry per branch - "missing 72x Gold Ingot" for a recipe that needs four. The
                // name of the missing material is recorded ONE level up, at the ingredient slot
                // that could not be supplied, with the amount that slot really wants.
                return false;
            }

            // Try successive recipes - the first feasible one wins.
            for (ProcessingEntry recipe : recipes) {
                Map<Item, Long> snapshot = new HashMap<>(stock);
                int planMark = plan.runs.size();
                if (planRecipe(level, network, enabled, preferred, recipe, remaining, stock, plan, visiting, depth)) {
                    return true;
                }
                // The recipe did not work out - undo the simulation.
                stock.clear();
                stock.putAll(snapshot);
                plan.rollbackTo(planMark);
            }
            return false;
        } finally {
            visiting.remove(item);
        }
    }

    /**
     * Plans running a single recipe enough times to obtain {@code amount}.
     * For every ingredient it picks ONE option and ensures its full quantity.
     */
    private static boolean planRecipe(ServerLevel level, VelocePipeNetwork network,
                                      Set<Item> enabled,
                                      Map<Item, ResourceLocation> preferred,
                                      ProcessingEntry recipe, long amount,
                                      Map<Item, Long> stock, Plan plan,
                                      Set<Item> visiting, int depth) {
        // How many units ONE run yields: the primary result times its count.
        //
        // We compute from the PRIMARY result (the first guaranteed one), not from
        // the sum of all of them - planning only sees guaranteed results, so that it
        // never promises an item that may not drop (see ProcessingEntry).
        ItemStack primary = recipe.primaryResult();
        long perCraft = Math.max(1, primary.getCount());
        long times = (amount + perCraft - 1) / perCraft;
        if (times <= 0 || times > CRAFT_MAX_STEPS) {
            return false;
        }

        // A FURNACE RECIPE PAYS WITH HEAT - one smelting operation per unit.
        //
        // We check BEFORE planning the ingredients, because when there is no heat
        // there is no point in planning them. If the rest of the plan fails, the
        // caller will undo the plan through rollbackTo, which gives the heat back.

            if (recipe.isFurnace()) {
                if (times > plan.heatRemaining) {
                    return false;
                }
                // NOTE: the heat is NOT decremented here any more. It used to be, and
                // that was the leak: a recipe whose ingredients could not be planned
                // returned false WITHOUT ever reaching plan.add(), so rollbackTo could
                // never give that heat back. With ~10 furnace recipes for one item, every
                // failed unit attempt melted ~10 heat operations, and the planner
                // concluded "no heat" long before the furnace was actually empty.
                //
                // The decrement now happens together with plan.add() below, so a run and
                // its heat cost are atomic - rollbackTo restores both.
            }


        // First plan the ingredients (recursion), then record ourselves.
        //
        // ITEM COUNTS: an ingredient may require more than one unit per run
        // (Alchemistry IngredientStack, Mekanism SizedIngredient). Previously the
        // planner assumed `need = times`, so such a recipe would consume less in the
        // plan than it really needs - the numbers in the GUI would be inflated, and
        // execution would lose items.
        List<Ingredient> ingredientList = recipe.ingredients();
        for (int ingIndex = 0; ingIndex < ingredientList.size(); ingIndex++) {
            Ingredient ing = ingredientList.get(ingIndex);
            long perIngredient = recipe.ingredientCount(ingIndex);
            if (!hasOptions(ing)) {
                continue;   // an empty grid slot or an ingredient with no option at all
            }
            List<ItemStack> options = new ArrayList<>(nonEmpty(ing));
            Map<Item, Long> snapshot = new HashMap<>(stock);
            int planMark = plan.runs.size();

            boolean supplied = false;
            long need = times * perIngredient;
            // Pass 1: take directly from stock if any option is already present in full.
            for (ItemStack opt : options) {
                Item optItem = opt.getItem();
                long avail = stock.getOrDefault(optItem, 0L);
                if (avail >= need) {
                    stock.put(optItem, avail - need);
                    supplied = true;
                    break;
                }
            }

            // Pass 2: if not directly in stock, prioritize options that have partial stock
            // or are enabled in the crafter/processing modules, and try to plan them.
            if (!supplied) {
                options.sort((a, b) -> {
                    long sa = stock.getOrDefault(a.getItem(), 0L);
                    long sb = stock.getOrDefault(b.getItem(), 0L);
                    if (sa != sb) return Long.compare(sb, sa);
                    boolean ea = enabled.contains(a.getItem());
                    boolean eb = enabled.contains(b.getItem());
                    return Boolean.compare(eb, ea);
                });

                int tried = 0;
                for (ItemStack opt : options) {
                    Item optItem = opt.getItem();
                    long avail = stock.getOrDefault(optItem, 0L);
                    if (avail == 0 && !enabled.contains(optItem) && tried++ >= 2) {
                        break;
                    }
                    long lacking = need - avail;
                    Map<Item, Long> snap2 = new HashMap<>(stock);
                    int mark2 = plan.runs.size();
                    stock.put(optItem, 0L);
                    if (plan(level, network, enabled, preferred, optItem, lacking, stock, plan, visiting, depth + 1)) {
                        long produced = stock.getOrDefault(optItem, 0L);
                        stock.put(optItem, Math.max(0L, produced - need));
                        supplied = true;
                        break;
                    }
                    stock.clear();
                    stock.putAll(snap2);
                    plan.rollbackTo(mark2);
                }
            }

            if (!supplied) {
                // STOP HERE - THE RECIPE CANNOT RUN.
                //
                // An earlier attempt kept walking the remaining ingredients so the player could
                // be told about all of them at once, and it broke the mod: every skipped
                // ingredient was another full recursive planning attempt on a tree that is
                // already known to fail, and the operation counter (300 000) was exhausted
                // before the first attempt finished. The symptom was "recipe tree too complex"
                // for a FENCE - six planks - while the same fence a second earlier had failed
                // with a perfectly good reason.
                //
                // The list of missing items comes from a separate, cheap walk that plans
                // nothing (see `findMissing`), so stopping here loses no information.
                //
                // The line below is the OTHER half of that: if the message ever says "nothing
                // is missing" while the plan failed, this names the slot the PLANNER could not
                // supply, so the two answers can be compared instead of argued about.
                VeloceCraftTrace.log("plan failed: recipe %s x%d cannot supply slot %d = %s x%d "
                                + "(stock of the first option: %d, auto-crafting allowed: %s)",
                        recipe.id(), times, ingIndex, describeOptions(ing), need,
                        options.isEmpty() ? 0L
                                : stock.getOrDefault(options.get(0).getItem(), 0L),
                        !options.isEmpty() && enabled.contains(options.get(0).getItem()));
                stock.clear();
                stock.putAll(snapshot);
                plan.rollbackTo(planMark);
                return false;
            }

        }

        // A furnace recipe pays with ONE smelting operation per unit. The heat is
        // charged HERE, atomically with the run, so a failed ingredient plan above
        // (which returns before this line) does not leak heat; rollbackTo restores it
        // together with the run.
        if (recipe.isFurnace()) {
            plan.heatRemaining -= times;
        }
        plan.add(recipe, times);

        // CRUCIAL: credit the produced items to the simulated stock.
        //
        // Without this, planning did not see its own intermediate results.
        // Example: a fence needs 4 planks and 2 sticks. The planks do get planned
        // from logs (4 units), but stock[planks] still stood at 0, so the later
        // plank slots in the recipe could not find them and the plan fell over with
        // "no base ingredients" - even though the logs were in the network.
        // That was the source of the error "I cannot make a fence but I have logs in
        // the chest".
        //
        // We credit ALL guaranteed results (Fission has two), not just the primary
        // one - otherwise a multi-output recipe would create less in the plan than
        // it gives in reality.
        for (ItemStack guaranteed : recipe.guaranteedResults()) {
            if (!guaranteed.isEmpty()) {
                stock.merge(guaranteed.getItem(), times * guaranteed.getCount(), Long::sum);
            }
        }
        return true;
    }

    /**
     * How many units of the given item can really be obtained from this stock.
     *
     * <p><b>Why not a formula.</b> The previous version computed the limit for every
     * ingredient separately, taking the FULL base stock for each. For a fence
     * (4 planks + 2 sticks) it computed the planks from 7 logs and the sticks from
     * those same 7 logs - even though sticks are made FROM planks, so both drew from
     * a single source. The result was inflated and did not match reality.
     *
     * <p>Now we check feasibility with REAL planning (which correctly consumes
     * ingredients and credits intermediate results) and look for the largest
     * possible number by bisection. The number is real, because it comes from the
     * very same code that then actually crafts.
     *
     * <p>Cost: log2(N) planning runs per item. Planning is cheap, because it operates
     * on numbers, without touching the world.
     *
     * @return the total number of available units (stock + what can be additionally produced)
     */
    private static long maxCraftable(ServerLevel level, VelocePipeNetwork network,
                                     Item item, Map<Item, Long> stock,
                                     Set<Item> enabled,
                                     Map<Item, ResourceLocation> preferred,
                                     long realHeatOps) {
        // The ESTIMATION computes "how many I can HAVE", not "how much the furnace
        // can bend in this second" - see estimateHeatOps(). The EXECUTION plan still
        // gets the real budget (Context.heatOps() -> consumeFrom).
        long heatOps = estimateHeatOps(realHeatOps);
        long fromStock = stock.getOrDefault(item, 0L);
        if (!enabled.contains(item)) {
            return fromStock;
        }
        List<ProcessingEntry> recipes = allRecipesFor(level, network, item, heatOps > 0);
        if (recipes.isEmpty()) {
            return fromStock;
        }

        // FAST PATH for items produced EXCLUSIVELY in a furnace from raw materials
        // that themselves have no recipe (e.g. glass from sand).
        //
        // Why: the answer is then plain arithmetic on the stock, rather than a
        // bisection with full planning. Thanks to that such items (and there are
        // several dozen of them - see "furnace=74" in the log) do not eat the batch
        // budget and do not disappear from the GUI when the budget runs out.
        long furnaceOnly = countFurnaceOnly(level, item, stock, heatOps, recipes);
        if (furnaceOnly >= 0) {
            return fromStock + furnaceOnly;
        }

        // Upper bound of the bisection.
        //
        // The BUG that used to be here: we took the sum of ALL units in the network
        // and claimed that "every craft consumes at least one item, so more cannot be
        // made". That is not true for recipes yielding multiple units: 1 log ->
        // 4 planks -> 16 sticks. With 64 logs the bound came out as 64, so the
        // bisection NEVER checked more - and the terminal showed "64 fences" where
        // several hundred can really be made.
        //
        // Now we compute the REAL upper bound: for every raw material we multiply
        // its amount by how many units of the given item can be obtained from it in
        // one chain of recipes. This is still only a bisection constraint (a safe
        // overestimate), and the planner finds the true value anyway.
        long hi = Math.min(estimateUpperBound(level, network, item, stock, recipes, heatOps > 0),
                MAX_ESTIMATE_RESULT);
        if (hi <= 0) {
            return fromStock;
        }

        // How much can REALLY be made from this stock - with the shared algorithm
        // (growing, CHEAP attempts, then bisection). Without it the computation began
        // at half the upper bound, so the first attempt was the most expensive and
        // the budget ran out before anything was computed.
        long lo = findMaxPlannable(level, network, item, hi, stock, enabled, preferred,
                heatOps, DEFAULT_ESTIMATE_BUDGET_NS, null);
        if (lo <= 0 && estimateAborted()) {
            // THE ONE LINE THAT EXPLAINS "this icon has no +N" FOR AN EXPENSIVE ITEM.
            //
            // This used to be a bare `System.out` probe for items whose id happened to
            // contain "casing" or "mechanism", and the bisection itself printed an
            // unbounded `PLAN 16 FAILED` warning per attempt with no item name at all -
            // so the log said "PLAN 16 FAILED: NOT SUPPLIED (ingIndex=0)" several hundred
            // times and never once said WHICH item could not be computed. This line names
            // the item, says how many recipes it has and how far the bisection got before
            // the budget ran out: exactly the numbers the "+N is missing" report needs.
            VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                    "craftable count for %s: ran out of the estimation budget after "
                            + "checking up to %d unit(s) (recipes=%d, hi=%d) - the GUI shows "
                            + "no number for this item",
                    item, lo, recipes.size(), hi);
            // The budget ran out and not even one unit worked out - the result is
            // unreliable. We return UNKNOWN so that the GUI keeps the previous number
            // instead of showing zero.
            return UNKNOWN_COUNT;
        }
        return fromStock + lo;
    }

    /**
     * The largest amount of the item the planner can produce - ONE algorithm for
     * both computing numbers and executing.
     *
     * <p><b>Growing attempts, not descending from the top.</b> The previous version
     * started from half the order, so the FIRST attempt was the most expensive:
     * a recipe with 21 ingredients (Create mechanical crafting) times 32 units means
     * hundreds of planning operations and the budget ran out before anything was
     * computed. The effect seen by the player: the GUI showed a number (from an
     * earlier, cheap computation), while the crafting attempt ended with a
     * missing-items message, because the plan was not built within the budget.
     *
     * <p>Now attempts grow from 1 (1, 2, 4, ...), so even a small budget yields a
     * CORRECT plan for as many units as we managed to check - and not zero. Then the
     * bisection closes in on the result between the last success and the first
     * failure.
     *
     * @param resultPlan when not {@code null}, it is filled with the best plan
     * @return the largest feasible amount (0 when not even one unit succeeded)
     */
    private static long findMaxPlannable(ServerLevel level, VelocePipeNetwork network, Item item,
                                         long upperBound, Map<Item, Long> stock,
                                         Set<Item> enabled,
                                         Map<Item, ResourceLocation> preferred,
                                         long heatOps, long budgetNanos,
                                         @Nullable Plan resultPlan) {
        if (upperBound <= 0) {
            return 0;
        }
        long found = 0;
        long failed = 0;
        Plan best = null;

        // Step 1: growing attempts (1, 2, 4, ...) - cheap ones first.
        long amount = 1;
        while (amount <= upperBound && !estimateAborted()) {
            startEstimate(budgetNanos);
            Map<Item, Long> copy = new HashMap<>(stock);
            Plan candidate = new Plan(heatOps);
            long startNanos = System.nanoTime();
            boolean ok = plan(level, network, enabled, preferred, item, amount, copy, candidate,
                    new HashSet<>(), 0);
            VeloceCraftTrace.log("plan: attempt %d units -> %s (runs=%d, %d ms, heat=%d, abort=%s)",
                    amount, ok ? "OK" : "NO", candidate.runs.size(),
                    (System.nanoTime() - startNanos) / 1_000_000L,
                    candidate.heatRemaining, estimateAborted());
            if (ok) {
                found = amount;
                best = candidate;
            } else {
                failed = amount;
                if (estimateAborted()) {
                    break;   // further attempts would only eat the budget
                }
            }
            if (amount == upperBound) {
                break;
            }
            amount = Math.min(upperBound, amount * 2);
        }

        // Step 2: bisection between the last success and the first failure.
        long hi = failed > 0 ? failed - 1 : upperBound;
        while (found < hi && !estimateAborted()) {
            long mid = found + (hi - found + 1) / 2;
            startEstimate(budgetNanos);
            Map<Item, Long> copy = new HashMap<>(stock);
            Plan candidate = new Plan(heatOps);
            if (plan(level, network, enabled, preferred, item, mid, copy, candidate,
                    new HashSet<>(), 0)) {
                found = mid;
                best = candidate;
            } else {
                hi = mid - 1;
                if (estimateAborted()) {
                    break;
                }
            }
        }

        if (resultPlan != null && best != null) {
            resultPlan.runs.clear();
            resultPlan.runs.addAll(best.runs);
        }
        return found;
    }

    /**
     * How many units of the item can be made in a furnace - without the planner.
     *
     * <p>It applies ONLY when the item is produced exclusively in furnaces and from
     * raw materials that themselves have no recipe at all. Then the only limit is the
     * raw material stock, so the result is
     * {@code min(stock_of_raw / how_many_needed) * how_many_come_out} - exactly what
     * the planner would compute, only without bisection.
     *
     * <p>If the item also has a crafting recipe, or any of its raw materials can be
     * additionally produced (has its own recipe), the fast path backs out: the result
     * then depends on the whole chain and the planner has to compute it.
     *
     * @return the computed number of units or {@code -1} = "use the planner"
     */
    private static long countFurnaceOnly(ServerLevel level, Item item,
                                         Map<Item, Long> stock, long heatOps,
                                         List<ProcessingEntry> recipes) {
        if (recipes.isEmpty()) {
            return 0L;
        }
        long best = 0;
        for (ProcessingEntry recipe : recipes) {
            if (!recipe.isFurnace()) {
                return -1L;   // there is also crafting - that is a job for the planner
            }
            long runs = Long.MAX_VALUE;
            List<Ingredient> ingredientList = recipe.ingredients();
            for (int ingIndex = 0; ingIndex < ingredientList.size(); ingIndex++) {
                Ingredient ingredient = ingredientList.get(ingIndex);
                // An ingredient may require several units (Alchemistry, Mekanism) -
                // then the limit is the stock divided by that number, not by 1.
                long perIngredient = recipe.ingredientCount(ingIndex);
                long bestOption = 0;
                for (ItemStack option : ingredient.getItems()) {
                    Item raw = option.getItem();
                    if (!VeloceRecipeRegistry.getRecipesFor(level, raw, true).isEmpty()) {
                        // The raw material is itself craftable - the stock alone is
                        // not the real limit, the planner will compute this better.
                        return -1L;
                    }
                    long needed = Math.max(1, option.getCount()) * perIngredient;
                    bestOption = Math.max(bestOption, stock.getOrDefault(raw, 0L) / needed);
                }
                runs = Math.min(runs, bestOption);
            }
            if (runs == Long.MAX_VALUE) {
                return -1L;   // a recipe with no sensible ingredients
            }
            // Without heat the furnace will do nothing - but we already know that from heatOps.
            if (heatOps <= 0) {
                return 0L;
            }
            best = Math.max(best, Math.min(MAX_ESTIMATE_RESULT,
                    runs * Math.max(1, recipe.primaryResult().getCount())));
        }
        return best;
    }

    /** Limit of the estimation result - protects against an absurd bisection. */
    private static final long MAX_ESTIMATE_RESULT = 100_000L;

    /**
     * Heat for the ESTIMATION - that is, for the number the player sees.
     *
     * <p><b>The BUG this fixes (player report).</b> The estimation got the same heat
     * budget as the execution plan, so the number next to an item with a furnace
     * recipe was clipped to HOW MUCH THE FURNACE HAS IN ITS BUFFER RIGHT NOW. With a
     * furnace holding 25 smelting operations in the buffer, the terminal showed
     * 25 glass regardless of whether the chest held 32 or 64 sand ("I take out half
     * and it still shows 25"). The number should answer the question "how many can I
     * have from what is in the network", not "how much the furnace can bend in this
     * second".
     *
     * <p>The distinction is the same as with {@code isPowered()}: zero heat = furnace
     * recipes disabled (nothing to burn), any amount = recipes work, and the number
     * follows the raw material. A fuel furnace pulls fuel from the network by itself,
     * so its buffer is not the limit of what can be made - the raw material (and the
     * fuel in the network) is. An electric furnace has a resource that must be
     * replenished, but it still answers a different question: the number says "how
     * many I CAN have from what lies in the network", not "how long one charge will
     * last".
     *
     * <p>The value is larger than {@link #MAX_ESTIMATE_RESULT}, so it will never clip
     * the result; and it is tiny, so that nobody is tempted to compute "to infinity"
     * and risk an overflow.
     */
    /**
     * The share a single item gets in the SYNCHRONOUS batch (2 ms) - a diagnostic path now.
     *
     * <p><b>This is no longer what the terminal uses.</b> The "+N" numbers are computed by
     * {@link VeloceCountWorker} from a frozen {@link VeloceCountSnapshot} with NO time
     * budget - see {@link #countFromSnapshot}. What remains here is the synchronous batch,
     * kept for tests and for callers that cannot wait for a worker.
     *
     * <p><b>Why the budget exists on this path at all.</b> It runs on the server thread, so
     * it must leave headroom for the tick. That constraint is exactly why the numbers were
     * missing from the GUI, and exactly why the real path moved off-thread.
     *
     * <p><b>History, kept because it explains the numbers.</b> This was first 0.2 ms, then
     * 2 ms: at 0.2 ms the heavy items ABORTED the estimation on every task and never got a
     * number. An EVEN share is a compromise - an expensive item and a cheap one get the same
     * slice - and it is the compromise the worker removes rather than tunes. Raising the
     * floor for "expensive shapes" was tried and reverted: the measured failure
     * ({@code minecraft:andesite}, 2 recipes, hi=383, 0 units checked) is not an expensive
     * recipe, it is an expensive BISECTION, so an ingredient-option heuristic never fired.
     */
    private static final long MIN_ITEM_BUDGET_NS = 2_000_000L;

    /**
     * The WHOLE batch, computed from a frozen snapshot - <b>safe off the server thread</b>.
     *
     * <p>This is the entry point the worker uses (see {@code VeloceCountWorker}). It is
     * deliberately a faithful re-implementation of {@link #countCraftableBatchResult} with
     * two differences:
     *
     * <ol>
     *   <li>it never touches the level, the network or the recipe manager - everything it
     *       needs was captured by {@link VeloceCountSnapshot#capture} on the server
     *       thread;</li>
     *   <li>it has NO time budget. The whole reason for moving this off-thread is that the
     *       work is worth doing properly: a number that took 300 ms to compute on a worker
     *       is still a number the player sees, while the same work on the server thread
     *       had to be abandoned after 2 ms per item - which is exactly why the GUI showed
     *       nothing.</li>
     * </ol>
     *
     * <p>The only limit left is {@link #MAX_ESTIMATE_OPS}, an emergency valve against a
     * pathological recipe graph - it is not a time budget and does not fire in practice.
     *
     * @return the counts, plus whether every requested item was really computed
     */
    public static BatchResult countFromSnapshot(VeloceCountSnapshot snapshot,
                                                java.util.Collection<Item> items,
                                                Progress progress) {
        Map<Item, Long> out = new HashMap<>();
        if (items == null || items.isEmpty()) {
            return new BatchResult(out, true, snapshot.heatAvailable());
        }

        boolean complete = !snapshot.closureTruncated();
        long started = System.nanoTime();
        int aborted = 0;
        int done = 0;

        List<Item> queue = items instanceof List<Item> list ? list : new ArrayList<>(items);
        int size = queue.size();

        for (int i = 0; i < size; i++) {
            Item item = queue.get(i);
            // "Cannot be made" is a real answer and must reach the client - otherwise the
            // GUI would keep an old, inflated number.
            if (!snapshot.countable().contains(item)) {
                out.put(item, 0L);
                done++;
                progress.tick(done, size, item, 0L, 0L);
                continue;
            }

            // NO TIME SLICE. One item may take as long as it needs; the emergency
            // operation counter inside the estimation is the only backstop.
            startEstimate(0L);
            long itemStart = System.nanoTime();
            long total = craftableFromRawSnapshot(snapshot, item);
            long itemNanos = System.nanoTime() - itemStart;

            if (total == UNKNOWN_COUNT) {
                // The emergency op counter tripped - this item really is pathological.
                // We skip IT (not the whole batch) and report the batch as incomplete so
                // the client keeps the previous number instead of showing a false zero.
                complete = false;
                aborted++;
                progress.tick(done, size, item, -1L, itemNanos);
                continue;
            }
            out.put(item, Math.max(0L, total));
            done++;
            progress.tick(done, size, item, Math.max(0L, total), itemNanos);
        }

        if (aborted > 0) {
            VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                    "snapshot count: %d of %d item(s) aborted the estimation "
                            + "(item too heavy even without a time budget)", aborted, size);
        }
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "snapshot count: %d item(s) in %d ms (complete=%s, closure=%d item(s)%s)",
                size, (System.nanoTime() - started) / 1_000_000L, complete,
                snapshot.closureSize(),
                snapshot.closureTruncated() ? ", TRUNCATED" : "");
        return new BatchResult(out, complete, snapshot.heatAvailable());
    }

    /** Progress callback - lets the worker log "still working, N of M". */
    public interface Progress {
        void tick(int done, int total, Item item, long value, long nanos);

        Progress SILENT = (done, total, item, value, nanos) -> {
        };
    }

    /**
     * The snapshot twin of {@link #maxCraftable} - same algorithm, no world access.
     *
     * <p>Kept as a separate method rather than a flag on {@code maxCraftable} because the
     * two have genuinely different inputs: the world version resolves recipes through
     * {@code level}/{@code network} on every call, the snapshot version reads a map. Making
     * one method branch on which is present would put a nullable-world check on the hot
     * path of the planner.
     */
    private static long craftableFromRawSnapshot(VeloceCountSnapshot snapshot, Item item) {
        // THE ITEM'S OWN STOCK STAYS IN THE PLAN - it is a legitimate SOURCE.
        //
        // This used to zero it (`rawStock.put(item, 0L)`), so the number answered "how many
        // can I ADDITIONALLY produce". That fixed a real complaint - the counter moved when
        // finished units were shuffled around a buffer - but it broke an equally real one: an
        // item the player CARRIES is a source for the things made FROM it. With 64 andesite in
        // the backpack and `polished_andesite` on screen, zeroing removed those 64 from the
        // plan, so the polished andesite the player could obviously make from their own pocket
        // was not counted - and every item further down that chain with it.
        //
        // THE COUNT IS RETURNED ON ITS OWN, WITH NO `fromStock` ADDED, and that is the subtle
        // half. `maxCraftableSnapshot` answers `fromStock + lo`, on the assumption that the
        // plan starts from a ZEROED stock and therefore only ever produces NEW units. Here the
        // plan starts from the REAL stock, so it FIRST CONSUMES what is already held and then
        // produces the rest - `lo` is already the total "how many I can have", stock included.
        // Adding `fromStock` on top of that would count the same units twice: 5 finished in a
        // chest plus 10 makeable would report 20 instead of 15.
        //
        // The distinction the old code reached for is between the item as an OUTPUT and the
        // item as an INPUT, and one map cannot express both - but the arithmetic can, because
        // the two roles differ only in whether the stock is consumed before or after counting.
        Map<Item, Long> stock = new HashMap<>(snapshot.stock());
        return maxCraftableWithStockIncluded(snapshot, item, stock);
    }

    /**
     * How many units of {@code item} the player can HAVE, counting the stock already held.
     *
     * <p>The snapshot twin of "the plan consumes the stock first and then produces": the
     * returned value is the total, so the caller must NOT add {@code fromStock} again. See
     * {@link #craftableFromRawSnapshot} for why the two totals differ.
     */
    private static long maxCraftableWithStockIncluded(VeloceCountSnapshot snapshot, Item item,
                                                      Map<Item, Long> stock) {
        long heatOps = snapshot.heatOps();
        long fromStock = stock.getOrDefault(item, 0L);
        if (!snapshot.countable().contains(item)) {
            return fromStock;
        }
        List<ProcessingEntry> recipes = snapshot.recipesFor(item);
        if (recipes.isEmpty()) {
            return fromStock;
        }

        // The furnace fast path counts what the RAW MATERIAL allows, so it is asked with the
        // same "produce only" view as the planner below - it must not credit the units already
        // held as if the furnace had made them.
        Map<Item, Long> produceOnly = new HashMap<>(stock);
        produceOnly.put(item, 0L);

        long furnaceOnly = countFurnaceOnlySnapshot(snapshot, item, produceOnly, heatOps, recipes);
        if (furnaceOnly >= 0) {
            return furnaceOnly;
        }

        // THE PLAN MUST NOT COUNT THE STOCK AS SOMETHING IT PRODUCED.
        //
        // `findMaxPlannableSnapshot` answers "can I have this many", and `plan` answers it by
        // CONSUMING the stock it already finds and only crafting the remainder - so a request
        // for 10 doors with 10 doors in the player's pockets succeeds WITHOUT CRAFTING
        // ANYTHING, and the number comes back as 10. The player sees "+10 doors" next to an
        // item they cannot make at all, which is exactly the report: the counter is reading
        // the inventory and presenting it as craftable output.
        //
        // The fix is not to hide the stock - it is a legitimate SOURCE for the things made
        // FROM it (see craftableFromRawSnapshot). It is to ask the question that separates the
        // two roles: how many can be produced on top of what is already held. Zeroing a COPY
        // of the stock for the duration of the measurement does that, while the real stock is
        // still there for every ingredient lookup inside the plan.
        //
        // Concretely for "10 doors in the backpack, none craftable": the plan starts from an
        // empty door pile, cannot produce any, and the answer is 0. For "64 andesite in the
        // backpack, polished andesite on screen": andesite is NOT the item being measured, so
        // it keeps its 64, and the plan makes polished andesite from it - the carried stack is
        // still a source.
        // The upper bound is what the RAW MATERIALS allow; the stock already held is not part
        // of it, because it is not produced.
        long hi = Math.min(estimateUpperBoundSnapshot(snapshot, item, produceOnly, recipes),
                MAX_ESTIMATE_RESULT);
        if (hi <= 0) {
            return 0;
        }

        long lo = findMaxPlannableSnapshot(snapshot, item, hi, produceOnly);
        if (lo <= 0 && estimateAborted()) {
            return UNKNOWN_COUNT;
        }
        // What the plan can ADDITIONALLY make; the stock held is reported separately by the
        // caller as the green number, not as craftable output.
        return lo;
    }

    private static long maxCraftableSnapshot(VeloceCountSnapshot snapshot, Item item,
                                             Map<Item, Long> stock) {
        long heatOps = snapshot.heatOps();
        long fromStock = stock.getOrDefault(item, 0L);
        if (!snapshot.countable().contains(item)) {
            return fromStock;
        }
        List<ProcessingEntry> recipes = snapshot.recipesFor(item);
        if (recipes.isEmpty()) {
            return fromStock;
        }

        // The furnace fast path counts what the RAW MATERIAL allows, so it is asked with the
        // same "produce only" view as the planner below - it must not credit the units already
        // held as if the furnace had made them.
        Map<Item, Long> produceOnly = new HashMap<>(stock);
        produceOnly.put(item, 0L);

        long furnaceOnly = countFurnaceOnlySnapshot(snapshot, item, produceOnly, heatOps, recipes);
        if (furnaceOnly >= 0) {
            return furnaceOnly;
        }

        long hi = Math.min(estimateUpperBoundSnapshot(snapshot, item, stock, recipes),
                MAX_ESTIMATE_RESULT);
        if (hi <= 0) {
            return fromStock;
        }

        long lo = findMaxPlannableSnapshot(snapshot, item, hi, stock);
        if (lo <= 0 && estimateAborted()) {
            return UNKNOWN_COUNT;
        }
        return fromStock + lo;
    }

    /** Snapshot twin of {@link #findMaxPlannable} - identical growth + bisection. */
    private static long findMaxPlannableSnapshot(VeloceCountSnapshot snapshot, Item item,
                                                 long upperBound, Map<Item, Long> stock) {
        if (upperBound <= 0) {
            return 0;
        }
        long found = 0;
        long failed = 0;

        // ONE BUDGET FOR THE WHOLE ITEM, NOT ONE PER ATTEMPT.
        //
        // `startEstimate(0L)` RESETS the operation counter, and the original loop called it
        // before every single bisection attempt. The 20-million ceiling therefore applied to
        // each attempt separately and the TOTAL was unbounded - which is exactly the hang
        // this caused:
        //
        //   count entry: minecraft:andesite (recipes=2)     <- and never an exit line
        //
        // A single page request held the worker thread forever, no progress was ever
        // printed, and because the packet handler skips repeats for a terminal while a count
        // is in flight, that terminal then showed "=none" for every icon permanently. The
        // item itself is ordinary; its two recipes come from Alchemistry (compactor and
        // dissolver), whose ingredients are element dusts that have their own recipes, so the
        // recursive plan branches combinatorially. The `visiting` set only stops a true
        // cycle, not that branching.
        //
        // The time budget used to hide this: the old code always had a deadline, so the
        // explosion was cut off. Removing the time limits - the whole point of the worker -
        // exposed it. We now set the budget ONCE for the item and let the loops share it,
        // which is what "the emergency counter is the only backstop" was always meant to
        // mean.
        startEstimate(0L);

        long amount = 1;
        while (amount <= upperBound && !estimateAborted()) {
            Map<Item, Long> copy = new HashMap<>(stock);
            Plan candidate = new Plan(snapshot.heatOps());
            boolean ok = planSnapshot(snapshot, item, amount, copy, candidate,
                    new HashSet<>(), 0);
            if (ok) {
                found = amount;
            } else {
                failed = amount;
                if (estimateAborted()) {
                    break;
                }
                // STOP AT THE FIRST FAILURE - everything above it is impossible too.
                //
                // Planning MORE units is strictly harder than planning fewer: the same
                // recipes, the same stock, a larger amount. So once an amount cannot be
                // planned, no larger amount can be either, and climbing further is pure
                // waste. Measured on the very page this bug is about:
                //
                //   plan-attempt create:cut_andesite x64  -> OK, plan() calls=1
                //   plan-attempt create:cut_andesite x128 -> NO, plan() calls=7333
                //   plan-attempt create:cut_andesite x256 -> NO, plan() calls=14391
                //   plan-attempt create:cut_andesite x512 -> NO, plan() calls=14391
                //   plan-attempt create:cut_andesite x717 -> NO, plan() calls=14391
                //
                // The three failed climbs above x128 cost ~43 000 plan calls for an answer
                // already known, and the same again inside the bisection - which is where the
                // 300 000-operation budget went, at ~1 item per second. The attempts that
                // come straight out of stock cost ONE call, which is why the explosion
                // appeared only once the amount exceeded what was in the network.
                break;
            }
            if (amount == upperBound) {
                break;
            }
            amount = Math.min(upperBound, amount * 2);
        }

        long hi = failed > 0 ? failed - 1 : upperBound;
        while (found < hi && !estimateAborted()) {
            long mid = found + (hi - found + 1) / 2;
            Map<Item, Long> copy = new HashMap<>(stock);
            Plan candidate = new Plan(snapshot.heatOps());
            if (planSnapshot(snapshot, item, mid, copy, candidate, new HashSet<>(), 0)) {
                found = mid;
            } else {
                hi = mid - 1;
                if (estimateAborted()) {
                    break;
                }
            }
        }
        return found;
    }

    /** Snapshot twin of {@link #plan}. */
    private static boolean planSnapshot(VeloceCountSnapshot snapshot, Item item, long amount,
                                        Map<Item, Long> stock, Plan plan,
                                        Set<Item> visiting, int depth) {
        if (amount <= 0) {
            return true;
        }
        if (estimateBudgetExceeded()) {
            return false;
        }
        if (depth > CRAFT_MAX_DEPTH || plan.runs.size() > CRAFT_MAX_STEPS) {
            return false;
        }
        if (!visiting.add(item)) {
            return false;   // recipe cycle
        }
        try {
            long have = stock.getOrDefault(item, 0L);
            long fromStock = Math.min(have, amount);
            stock.put(item, have - fromStock);
            long remaining = amount - fromStock;
            if (remaining <= 0) {
                return true;
            }
            if (!snapshot.countable().contains(item) && depth == 0) {
                return false;
            }

            List<ProcessingEntry> recipes = orderRecipesSnapshot(snapshot, item, plan);
            if (recipes.isEmpty()) {
                return false;
            }
            for (ProcessingEntry recipe : recipes) {
                Map<Item, Long> snapshotStock = new HashMap<>(stock);
                int planMark = plan.runs.size();
                if (planRecipeSnapshot(snapshot, recipe, remaining, stock, plan,
                        visiting, depth)) {
                    return true;
                }
                stock.clear();
                stock.putAll(snapshotStock);
                plan.rollbackTo(planMark);
            }
            return false;
        } finally {
            visiting.remove(item);
        }
    }

    /** Snapshot twin of {@link #planRecipe}. */
    private static boolean planRecipeSnapshot(VeloceCountSnapshot snapshot,
                                              ProcessingEntry recipe, long amount,
                                              Map<Item, Long> stock, Plan plan,
                                              Set<Item> visiting, int depth) {
        ItemStack primary = recipe.primaryResult();
        long perCraft = Math.max(1, primary.getCount());
        long times = (amount + perCraft - 1) / perCraft;
        if (times <= 0 || times > CRAFT_MAX_STEPS) {
            return false;
        }
        if (recipe.isFurnace() && times > plan.heatRemaining) {
            return false;
        }

        List<Ingredient> ingredientList = recipe.ingredients();
        for (int ingIndex = 0; ingIndex < ingredientList.size(); ingIndex++) {
            Ingredient ing = ingredientList.get(ingIndex);
            long perIngredient = recipe.ingredientCount(ingIndex);
            if (!hasOptions(ing)) {
                continue;
            }
            List<ItemStack> options = new ArrayList<>(nonEmpty(ing));
            Map<Item, Long> stockBefore = new HashMap<>(stock);
            int planMark = plan.runs.size();
            boolean supplied = false;
            long need = times * perIngredient;

            for (ItemStack opt : options) {
                Item optItem = opt.getItem();
                long avail = stock.getOrDefault(optItem, 0L);
                if (avail >= need) {
                    stock.put(optItem, avail - need);
                    supplied = true;
                    break;
                }
            }

            if (!supplied) {
                options.sort((a, b) -> {
                    long sa = stock.getOrDefault(a.getItem(), 0L);
                    long sb = stock.getOrDefault(b.getItem(), 0L);
                    if (sa != sb) {
                        return Long.compare(sb, sa);
                    }
                    boolean ea = snapshot.countable().contains(a.getItem());
                    boolean eb = snapshot.countable().contains(b.getItem());
                    return Boolean.compare(eb, ea);
                });

                int tried = 0;
                int attempts = 0;
                for (ItemStack opt : options) {
                    Item optItem = opt.getItem();
                    long avail = stock.getOrDefault(optItem, 0L);
                    if (avail == 0 && !snapshot.countable().contains(optItem) && tried++ >= 2) {
                        break;
                    }
                    // THE CAP TIGHTENS WITH DEPTH.
                    //
                    // A flat cap is not enough, and the measurements show why. 31 345 calls
                    // for ONE attempt is not breadth, it is the product of the depth:
                    // 4 options at each of 7 levels is 4^7 = 16 384, and the observed number
                    // is the same order. The planner is a HEURISTIC search - it needs ONE
                    // workable way to supply an ingredient, not an exhaustive proof that none
                    // exists - so the deeper it has already recursed, the less it should be
                    // willing to fan out. At the top (depth 0) the network's own recipes are
                    // worth trying properly; five levels down, a failed option is far more
                    // likely to be a dead end than a missed opportunity.
                    //
                    // This keeps the answer unchanged where it matters (an item that CAN be
                    // planned is still found - the options are sorted with the stocked and
                    // craftable ones first, so the workable option is tried early) while
                    // making a hopeless attempt cheap instead of thousands of calls.
                    int attemptCap = depth <= 1 ? MAX_INGREDIENT_ATTEMPTS : 2;
                    if (attempts++ >= attemptCap) {
                        break;
                    }
                    long lacking = need - avail;
                    Map<Item, Long> optBefore = new HashMap<>(stock);
                    int optMark = plan.runs.size();
                    stock.put(optItem, 0L);
                    if (planSnapshot(snapshot, optItem, lacking, stock, plan, visiting,
                            depth + 1)) {
                        long produced = stock.getOrDefault(optItem, 0L);
                        stock.put(optItem, Math.max(0L, produced - need));
                        supplied = true;
                        break;
                    }
                    stock.clear();
                    stock.putAll(optBefore);
                    plan.rollbackTo(optMark);
                }
            }

            if (!supplied) {
                stock.clear();
                stock.putAll(stockBefore);
                plan.rollbackTo(planMark);
                return false;
            }
        }

        plan.add(recipe, times);
        return true;
    }

    /** Snapshot twin of {@link #orderRecipes}. */
    private static List<ProcessingEntry> orderRecipesSnapshot(VeloceCountSnapshot snapshot,
                                                              Item item, Plan plan) {
        List<ProcessingEntry> all = snapshot.recipesFor(item);
        if (all.size() <= 1) {
            return all;
        }
        ResourceLocation pref = snapshot.preferred().get(item);
        if (pref != null) {
            List<ProcessingEntry> ordered = new ArrayList<>(all.size());
            for (var e : all) {
                if (e.id().equals(pref)) {
                    ordered.add(e);
                }
            }
            for (var e : all) {
                if (!e.id().equals(pref)) {
                    ordered.add(e);
                }
            }
            return ordered;
        }
        if (snapshot.prefersFurnace(item) && plan.heatRemaining > 0) {
            List<ProcessingEntry> ordered = new ArrayList<>(all.size());
            for (var e : all) {
                if (e.isFurnace()) {
                    ordered.add(e);
                }
            }
            for (var e : all) {
                if (!e.isFurnace()) {
                    ordered.add(e);
                }
            }
            return ordered;
        }
        return all;
    }

    /** Snapshot twin of {@link #countFurnaceOnly}. */
    private static long countFurnaceOnlySnapshot(VeloceCountSnapshot snapshot, Item item,
                                                 Map<Item, Long> stock, long heatOps,
                                                 List<ProcessingEntry> recipes) {
        if (recipes.isEmpty()) {
            return 0L;
        }
        long best = 0;
        for (ProcessingEntry recipe : recipes) {
            if (!recipe.isFurnace()) {
                return -1L;
            }
            long runs = Long.MAX_VALUE;
            List<Ingredient> ingredientList = recipe.ingredients();
            for (int ingIndex = 0; ingIndex < ingredientList.size(); ingIndex++) {
                Ingredient ingredient = ingredientList.get(ingIndex);
                long perIngredient = recipe.ingredientCount(ingIndex);
                long bestOption = 0;
                for (ItemStack option : ingredient.getItems()) {
                    Item raw = option.getItem();
                    // "The raw material is itself craftable" - asked of the SNAPSHOT, not
                    // of the registry, so this stays off-thread.
                    if (!snapshot.recipesFor(raw).isEmpty()) {
                        return -1L;
                    }
                    long needed = Math.max(1, option.getCount()) * perIngredient;
                    bestOption = Math.max(bestOption, stock.getOrDefault(raw, 0L) / needed);
                }
                runs = Math.min(runs, bestOption);
            }
            if (runs == Long.MAX_VALUE) {
                return -1L;
            }
            if (heatOps <= 0) {
                return 0L;
            }
            best = Math.max(best, Math.min(MAX_ESTIMATE_RESULT,
                    runs * Math.max(1, recipe.primaryResult().getCount())));
        }
        return best;
    }

    /** Snapshot twin of {@link #estimateUpperBound}. */
    private static long estimateUpperBoundSnapshot(VeloceCountSnapshot snapshot, Item item,
                                                   Map<Item, Long> stock,
                                                   List<ProcessingEntry> recipes) {
        long total = 0;
        for (long v : stock.values()) {
            total += v;
        }
        if (total <= 0) {
            return 0;
        }
        if (recipes.isEmpty()) {
            return total;
        }
        double perRawUnit = maxYieldPerRawUnitSnapshot(snapshot, item, new HashMap<>(),
                new HashSet<>(), 0);
        double bound = total * Math.max(1.0, perRawUnit);
        if (bound >= MAX_ESTIMATE_RESULT) {
            return MAX_ESTIMATE_RESULT;
        }
        return Math.max(total, (long) Math.ceil(bound));
    }

    /** Snapshot twin of {@link #maxYieldPerRawUnit}. */
    private static double maxYieldPerRawUnitSnapshot(VeloceCountSnapshot snapshot, Item item,
                                                     Map<Item, Double> memo,
                                                     Set<Item> visiting, int depth) {
        Double cached = memo.get(item);
        if (cached != null) {
            return cached;
        }
        if (depth >= UPPER_BOUND_MAX_DEPTH || !visiting.add(item)) {
            return 1.0;
        }
        try {
            double best = 1.0;
            for (var recipe : snapshot.recipesFor(item)) {
                double cost = rawCostOfRecipeSnapshot(snapshot, recipe, memo, visiting, depth);
                if (cost <= 0.0) {
                    continue;
                }
                double yield = Math.max(1, recipe.primaryResult().getCount()) / cost;
                if (yield > best) {
                    best = yield;
                }
            }
            memo.put(item, best);
            return best;
        } finally {
            visiting.remove(item);
        }
    }

    /** Snapshot twin of {@link #rawCostOfRecipe}. */
    private static double rawCostOfRecipeSnapshot(VeloceCountSnapshot snapshot,
                                                  ProcessingEntry recipe,
                                                  Map<Item, Double> memo,
                                                  Set<Item> visiting, int depth) {
        double cost = 0.0;
        List<Ingredient> ingredientList = recipe.ingredients();
        for (int ingIndex = 0; ingIndex < ingredientList.size(); ingIndex++) {
            Ingredient ing = ingredientList.get(ingIndex);
            double bestYield = 0.0;
            int checkCount = 0;
            for (ItemStack opt : nonEmpty(ing)) {
                if (checkCount++ > 6) {
                    break;
                }
                double yield = maxYieldPerRawUnitSnapshot(snapshot, opt.getItem(), memo,
                        visiting, depth + 1);
                if (yield > bestYield) {
                    bestYield = yield;
                }
                if (bestYield >= 1.0) {
                    break;
                }
            }
            if (bestYield <= 0.0) {
                return 0.0;
            }
            cost += recipe.ingredientCount(ingIndex) / bestYield;
        }
        return cost;
    }

    private static final long ESTIMATE_HEAT_OPS = 1_000_000L;

    /** Heat budget for the estimation: "there is something to burn" or "there is not". */
    private static long estimateHeatOps(long realHeatOps) {
        return realHeatOps > 0 ? ESTIMATE_HEAT_OPS : 0L;
    }

    /**
     * A safe OVERESTIMATE of the number of units that can be made from a given stock.
     *
     * <p>For every raw material present in the network we multiply its amount by the
     * largest possible yield in one chain of recipes. Example: 64 logs, and the
     * recipes "1 log -> 4 planks" and "1 plank -> 4 sticks" give a yield of 16, so
     * the upper bound is 1024 - and the bisection has room to find the true result
     * (e.g. 170 fences) instead of stopping at 64.
     *
     * <p>This is purely a bisection constraint. The result does not have to be
     * reachable - the planner decides that. What matters is that it is not TOO SMALL,
     * because then we would clip correct answers.
     */
    private static long estimateUpperBound(ServerLevel level, VelocePipeNetwork network,
                                           Item item, Map<Item, Long> stock,
                                           java.util.List<ProcessingEntry> recipes,
                                           boolean heatAvailable) {
        long total = 0;
        for (long v : stock.values()) {
            total += v;
        }
        if (total <= 0) {
            return 0;
        }
        if (recipes.isEmpty()) {
            return total;
        }

        // How many units of our item can be squeezed out of ONE unit of raw material.
        //
        // The BUG that used to be here: instead of the true chain we computed the
        // largest yield of a SINGLE recipe and multiplied it by everything in the
        // network (with a lower threshold of 4). For an item with a long chain the
        // limit came out TOO SMALL, so the bisection never checked larger values -
        // e.g. with 64 logs sticks showed up as 256, although in reality 512 come out.
        // A too-small limit clips the correct answer, so it must be a true (not a
        // rough) overestimate.
        double perRawUnit = maxYieldPerRawUnit(level, network, item,
                new HashMap<>(), new HashSet<>(), 0, heatAvailable);
        double bound = total * Math.max(1.0, perRawUnit);
        if (bound >= MAX_ESTIMATE_RESULT) {
            return MAX_ESTIMATE_RESULT;
        }
        return Math.max(total, (long) Math.ceil(bound));
    }

    /** Maximum chain depth when computing the upper bound. */
    private static final int UPPER_BOUND_MAX_DEPTH = 16;

    /**
     * How many units of {@code item} can be obtained from ONE unit of raw material.
     *
     * <p>It descends recursively through recipes, so it sees whole chains
     * (log -> planks -> sticks), not just the first step. The result is deliberately
     * an OVERESTIMATE - it is purely a bisection constraint, not an answer for the
     * player: when a recipe allows taking the same raw material in several ways, we
     * assume there is enough of it for all of them at once.
     *
     * <p>A recipe cycle and a too-deep chain are treated as a raw material
     * (yield 1.0), so that the recursion is finite.
     */
    private static double maxYieldPerRawUnit(ServerLevel level, VelocePipeNetwork network,
                                             Item item,
                                             Map<Item, Double> memo, Set<Item> visiting,
                                             int depth, boolean heatAvailable) {
        Double cached = memo.get(item);
        if (cached != null) {
            return cached;
        }
        if (depth >= UPPER_BOUND_MAX_DEPTH || !visiting.add(item)) {
            return 1.0;
        }
        try {
            double best = 1.0;
            for (var recipe : allRecipesFor(level, network, item, heatAvailable)) {
                double cost = rawCostOfRecipe(level, network, recipe, memo, visiting,
                        depth, heatAvailable);
                if (cost <= 0.0) {
                    continue;   // a recipe with no available ingredient option at all
                }
                double yield = Math.max(1, recipe.primaryResult().getCount()) / cost;
                if (yield > best) {
                    best = yield;
                }
            }
            memo.put(item, best);
            return best;
        } finally {
            visiting.remove(item);
        }
    }

    /**
     * The cost of a recipe in units of "raw material pieces" (smaller = cheaper).
     *
     * <p>Returns 0 when the recipe cannot be used (an ingredient has no option at
     * all), because then we do not include it in the maximum.
     */
    private static double rawCostOfRecipe(ServerLevel level, VelocePipeNetwork network,
                                          ProcessingEntry recipe,
                                          Map<Item, Double> memo, Set<Item> visiting,
                                          int depth, boolean heatAvailable) {
        double cost = 0.0;
        List<Ingredient> ingredientList = recipe.ingredients();
        for (int ingIndex = 0; ingIndex < ingredientList.size(); ingIndex++) {
            Ingredient ing = ingredientList.get(ingIndex);
            double bestYield = 0.0;
            int checkCount = 0;
            for (ItemStack opt : nonEmpty(ing)) {
                if (checkCount++ > 6) {
                    break;
                }
                double yield = maxYieldPerRawUnit(level, network, opt.getItem(), memo, visiting,
                        depth + 1, heatAvailable);
                if (yield > bestYield) {
                    bestYield = yield;
                }
                if (bestYield >= 1.0) {
                    break;
                }
            }
            if (bestYield <= 0.0) {
                return 0.0;
            }
            cost += recipe.ingredientCount(ingIndex) / bestYield;
        }
        return cost;
    }

    // ------------------------------------------------------------------
    // Execution phase - physically moving items
    // ------------------------------------------------------------------

    private static boolean execute(ServerLevel level, Context ctx, Plan plan) {
        // We fetch the heat sources ONCE for the whole plan execution.
        //
        // Every smelting operation used to call VeloceHeatSources.consume(), and that
        // did two full walks of the network's terminals with sorting. A plan can have
        // several hundred smelting operations (one charged electric furnace is
        // 125 operations), so that was hundreds of scans in a single tick.
        //
        // Safety: within a single execution the set of sources does not change, and
        // even if the chunk with the furnace dropped out of simulation, the retained
        // reference to the block entity is still a valid Java object - we only read
        // its own counter fields from it.
        java.util.List<com.craftingveloce.block.entity.VeloceHeatSource> heat =
                VeloceHeatSources.allIn(level, ctx.network);

        VeloceCraftTrace.log("execution: %d plan step(s), heat sources in network=%d",
                plan.runs.size(), heat.size());

        // The plan is in post-order: ingredients produced before use.
        for (PlannedRun run : plan.runs) {
            for (long i = 0; i < run.times(); i++) {
                if (!runOnce(level, ctx, run.recipe(), heat)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Pays for ONE run of a recipe.
     *
     * <p><b>Three kinds of recipes, three sources of payment:</b>
     * <ul>
     *   <li>the {@code FREE} family (crafting table, stonecutter, smithing) -
     *       pays with nothing, because it needs no machine,</li>
     *   <li>the {@code FURNACE} family - pays with one smelting operation from a furnace,</li>
     *   <li>the module family (Create/Alchemistry/Mekanism/...) - pays with one
     *       operation from that mod's machine (which itself converts this to FE).</li>
     * </ul>
     *
     * <p>The machine list is taken from {@link Context} (memoized for the duration of
     * one order), so that the network is not scanned per unit.
     *
     * <p>A missing payment is NOT a configuration error - it is an ordinary situation
     * (a furnace without fuel, a machine without power). The caller then returns the
     * taken ingredients.
     */
    private static boolean payForOperation(ServerLevel level, Context ctx,
                                           ProcessingEntry recipe,
                                           java.util.List<com.craftingveloce.block.entity.VeloceHeatSource> heat) {
        if (VeloceRecipeFamilies.isFree(recipe.type())) {
            return true;
        }
        if (recipe.isFurnace()) {
            if (VeloceHeatSources.consumeFrom(heat, 1)) {
                return true;
            }
            VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                    "recipe %s needs heat, but no powered furnace could pay for it",
                    recipe.id());
            return false;
        }
        // A module from another mod - the machine must stand in the network and have energy.
        if (VeloceProcessingSources.consumeFrom(ctx.moduleSourcesFor(recipe.type()), 1)) {
            return true;
        }
        VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                "recipe %s needs a powered machine for %s, but none could pay for it",
                recipe.id(), recipe.type());
        return false;
    }

    /**
     * WHY the plan failed - a message the player really understands.
     *
     * <p><b>The order of the questions matters</b>, because each of them leads to a
     * different fix:
     * <ol>
     *   <li>is there a furnace / module machine in the network at all (no machine),</li>
     *   <li>does that machine have fuel/power (it is idle),</li>
     *   <li>only at the end: which INGREDIENT is missing.</li>
     * </ol>
     *
     * <p>Previously the player got one generic "no such item in the network" - and
     * one could not distinguish "iron is missing" from "the furnace has no fuel".
     * Reports of "the GUI says I can, but I cannot" were therefore unsolvable.
     */
    private static CraftResult diagnosePlanFailure(ServerLevel level, Context ctx, Item item,
                                                   Map<Item, Long> stock, long amount) {
        // 1) Furnace: a furnace recipe exists, but the machine is absent or idle.
        if (!VeloceRecipeRegistry.getFurnaceRecipesFor(level, item).isEmpty()) {
            // Both of these used to arrive with no detail at all, so "no furnace in the
            // network" left the player to work out which of OUR furnaces was meant - and
            // the two are not interchangeable: velocity_furnace burns fuel, the electric
            // one has an accumulator.
            String furnaces = furnaceNames();
            if (!VeloceHeatSources.hasAnyHeatSource(level, ctx.network)) {
                return CraftResult.fail("craftingveloce.craft.error.noFurnace", furnaces);
            }
            if (!VeloceHeatSources.hasPower(level, ctx.network)) {
                // A fuel furnace that is merely still charging gets its own wording: it
                // has the coal, it just has not banked three of them yet, and "no
                // fuel/power" would send the player looking for a problem that is not
                // there. Anything else keeps the old message.
                if (VeloceHeatSources.hasColdFuelFurnace(level, ctx.network)) {
                    return CraftResult.fail("craftingveloce.craft.error.furnaceNotHot", furnaces);
                }
                return CraftResult.fail("craftingveloce.craft.error.furnaceUnpowered", furnaces);
            }
        }
        // 2) Module machines - but ONLY when no other route exists.
        //
        // THE BUG THIS FIXES (player: "trying to make planks, and it tells me there is
        // no Create saw"). A module was blamed whenever it COULD EVER make the item
        // somewhere in the game, with no regard for whether that route was needed. So a
        // network holding a crafting table and no logs got "no machine for saw" instead
        // of "missing log" - and it never reached step 3 at all, because this step
        // returned first. The player was sent to build a machine that would not have
        // helped.
        //
        // The rule now: a module can only be the REASON when it is the ONLY route. If
        // any base route exists (vanilla crafting, our own machines, the furnace), the
        // useful answer is what that route is missing, and the machines become a HINT
        // underneath it - an option, not a cause.
        //
        // The hint comes from VeloceProcessingRegistry.all(), which contains only the
        // modules that are actually INSTALLED: the compat modules register themselves
        // from inside their isPresent() gate. A player without Create therefore cannot
        // be told about a saw, without anyone having to check for Create here.
        boolean baseRouteExists =
                !VeloceRecipeRegistry.getRecipesFor(level, item, true).isEmpty();
        java.util.List<String> hintMachines = new java.util.ArrayList<>();
        // The FAMILIES, for the hint. `module.id()` is the mod's own name - "create",
        // "mekanism", "alchemistry" - which is what the line should say. The machine list
        // above is a different thing: it exists for the single-machine messages below
        // ("noModule"/"moduleUnpowered"), where naming the block is the whole point.
        java.util.Set<String> hintFamilies = new java.util.LinkedHashSet<>();
        for (VeloceProcessingModule module : VeloceProcessingRegistry.all()) {
            if (module.recipesAnywhere(level, item).isEmpty()) {
                continue;
            }
            // The machine, not just the family: the module id ("mekanism") does not tell
            // the player which block to place, and the family has twenty-three of them.
            String machine = machineNames(module);
            if (baseRouteExists) {
                hintMachines.add(machine);
                hintFamilies.add(module.id());
                continue;
            }
            if (!module.available(level, ctx.network)) {
                return CraftResult.fail("craftingveloce.craft.error.noModule", machine);
            }
            if (!module.powered(level, ctx.network)) {
                return CraftResult.fail("craftingveloce.craft.error.moduleUnpowered",
                        machine);
            }
        }
        // 3) What remains is a missing ingredient - we say which one.
        //
        // 3) What remains is a missing ingredient - we say which one, and which MODS could
        //    have made the item.
        //
        // THE HINT NAMES MODS, NOT MACHINES, and that is a deliberate retreat from what this
        // line used to print:
        //
        //   * `machineNames` collected EVERY block of a FAMILY. For one Mekanism machine it
        //     printed "veloce crusher module, veloce enrichment module, veloce combiner
        //     module, ..." - twenty-two names for an item that ONE of them can make;
        //   * for CREATE the scan came out EMPTY, because Create's modules are
        //     `VeloceKineticModuleBlock`s while the scan only looked at `VeloceFeModuleBlock`
        //     - so it fell through to the bare module id and printed "create".
        //
        // Naming the specific block cannot be fixed from here: this class is CORE and
        // `VeloceKineticModuleBlock` is a compat type it is not allowed to name, which is
        // what `validate_core_isolation` exists to prevent. Naming the MOD is both correct
        // and useful - it tells the player which integration to look at - and it is what
        // `module.id()` gives, deduplicated so one mod appears once however many of its
        // machines match.
        // WHICH ITEMS ARE MISSING, from ONE cheap walk that does not plan anything.
        //
        // The planner used to record this while it worked out that the item cannot be made, and
        // that was a mistake worth remembering: the message then depended on HOW the tree failed
        // (a cycle counted each branch it walked, so a clock needing four gold ingots reported
        // "72x"), and collecting the rest of a failing recipe cost so much planning that a fence
        // came back as "recipe tree too complex". `findMissing` walks recipes, compares amounts
        // against the stock and names what is short - no planning, no budget, no stock changes.
        String missingItems = firstMissing(level, ctx, item, stock, amount);
        if (missingItems.isEmpty()) {
            // NOTHING IS MISSING - say exactly that, and NEVER print the recipe in its place.
            //
            // The bug this replaces: an empty list fell back to naming EVERY ingredient of the
            // first recipe ("4x iron ingot, 2x piston, 2x stone pressure plate, redstone" for a
            // compactor), which reads as "you are missing all of this" while the player was
            // holding stacks of iron and redstone. A list of ingredients is not a list of what
            // is missing, and the one thing this tooltip must never do is send the player to
            // fetch something they already have. Reaching here means the plan failed for a
            // reason that is not an ingredient - the machine hints below are the useful part.
            return CraftResult.fail("craftingveloce.craft.error.notPlannable",
                    String.join(", ", hintFamilies));
        }
        return CraftResult.fail("craftingveloce.craft.error.noBase",
                missingItems,
                String.join(", ", hintFamilies));
    }

    /** One gap map as the text the tooltip shows: {@code "4x item.minecraft.gold_ingot, ..."}. */
    private static String describe(java.util.Map<String, Long> gaps) {
        StringBuilder out = new StringBuilder();
        gaps.forEach((gapName, gapAmount) -> {
            if (out.length() > 0) {
                out.append(", ");
            }
            if (gapAmount > 1L) {
                out.append(gapAmount).append("x ");
            }
            out.append(gapName);
        });
        return out.toString();
    }

    /**
     * What the request cannot supply, in the form the tooltip shows.
     *
     * <p><b>Why this is an ACCOUNTING walk and not a "does a recipe exist" walk.</b> The
     * version before this one asked, for every slot of a recipe, "is there a way to obtain
     * this?" against the SAME unchanged stock - so an ingredient used twice was approved
     * twice even when the network only held enough for one. Measured in game (crafting one
     * Alchemistry Compactor with 61 iron, 774 redstone, 5 cobblestone, 0 pistons and 0
     * pressure plates): the compactor wants TWO pistons and TWO stone pressure plates, and
     * each of those wants cobblestone. The walk checked every slot independently, found that
     * one piston was craftable, said the same for the second one and for both plates, and
     * concluded "nothing is missing" - so the caller fell through to naming the whole
     * recipe, and the player was told that iron and redstone were missing while holding
     * stacks of both.
     *
     * <p>Now the walk SPENDS what it uses, exactly like the planner: the pool starts as the
     * network stock and every resolved item is taken out of it, so the second use of an
     * ingredient sees what the first one left. What cannot be covered is reported with the
     * amount the whole request is short - the amount the player has to bring.
     *
     * <p>Two lists, and the difference matters for the message:
     * <ul>
     *   <li>{@code shortfall} - items the request runs out of. These are reported;</li>
     *   <li>{@code unresolved} - places the walk could not decide (a recipe cycle, the depth
     *       limit, the walk budget). Reported only when nothing is short, so a cycle is never
     *       silently called "available" (that is what silenced a redstone clock) and never
     *       counted once per branch either (that is what inflated it to "72x gold ingot").</li>
     * </ul>
     *
     * @param amount how many units of {@code item} the request wanted, so the amounts named are
     *               what the player has to supply for the whole request
     * @return the missing items as {@code "4x item.minecraft.gold_ingot, ..."}, or an empty
     *         string when nothing is missing
     */
    private static String firstMissing(ServerLevel level, Context ctx, Item item,
                                       Map<Item, Long> stock, long amount) {
        java.util.Map<Item, Long> absent = new java.util.LinkedHashMap<>();
        java.util.Map<Item, Long> unresolved = new java.util.LinkedHashMap<>();
        collectMissing(level, ctx, item, new HashMap<>(stock), amount, new HashSet<>(), 0,
                absent, unresolved, new int[] {MISSING_WALK_BUDGET});
        String out = describeDeficit(absent, stock);
        return out.isEmpty() ? describeDeficit(unresolved, stock) : out;
    }

    /**
     * The GROSS requirement of the items we ran out of, minus what the network holds.
     *
     * <p>Netting against the stock at the end - instead of writing down a shortfall at every
     * dead end - is what keeps the amount honest when the same base item is needed by several
     * branches: the requirement is added up once per occurrence, and what the player already
     * has is subtracted exactly once. Reporting per dead end instead would count the same
     * chest twice (the "72x gold ingot for a recipe that wants four" family of messages).
     */
    private static String describeDeficit(java.util.Map<Item, Long> gross,
                                          Map<Item, Long> stock) {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        gross.forEach((missingItem, totalNeeded) ->
                out.put(name(missingItem),
                        Math.max(1L, totalNeeded - stock.getOrDefault(missingItem, 0L))));
        return describe(out);
    }

    /**
     * Spends {@code amount} of {@code item} out of {@code pool}, or records what is short.
     *
     * <p>Iterative in spirit but written recursively like the planner, with the same recipe
     * order - so the list cannot name an ingredient from a route the planner would never take.
     * It builds no plan, takes no machine, and never touches the operation budget: that is what
     * makes it safe to run for a message (an earlier attempt to collect the same list inside the
     * PLANNER cost so much that a fence came back as "recipe tree too complex").
     */
    private static void collectMissing(ServerLevel level, Context ctx, Item item,
                                       Map<Item, Long> pool, long amount,
                                       Set<Item> visiting, int depth,
                                       java.util.Map<Item, Long> absent,
                                       java.util.Map<Item, Long> unresolved,
                                       int[] budget) {
        long have = pool.getOrDefault(item, 0L);
        if (have >= amount) {
            pool.put(item, have - amount);     // SPENT - the next slot sees less, like the planner
            return;
        }
        if (budget[0]-- <= 0 || depth >= MISSING_MAX_DEPTH || !visiting.add(item)) {
            // Could not decide. Not "fine" (a cycle is not proof of availability), and not a
            // shortfall either - the largest single requirement is the honest number here, not
            // the sum over the branches, which is what inflated a clock to "72x gold ingot".
            unresolved.merge(item, amount - have, Math::max);
            return;
        }
        try {
            List<ProcessingEntry> recipes = orderRecipes(level, ctx.network, item, ctx.preferred,
                    ctx.heatOps() > 0, ctx.network.prefersFurnace(item));
            if (recipes.isEmpty()) {
                absent.merge(item, amount, Long::sum);   // the gross need of every occurrence
                pool.put(item, 0L);
                return;
            }
            ProcessingEntry recipe = recipes.get(0);
            long perCraft = Math.max(1L, recipe.primaryResult().getCount());
            // Only what we do NOT already hold has to be crafted. Planning the full amount here
            // would report requirements for units that are already sitting in the chest - the
            // "72x gold ingot for a recipe wanting four" family of messages.
            long lacking = amount - have;
            long times = (lacking + perCraft - 1L) / perCraft;
            // What we hold of the item itself is spent first, exactly as the planner does.
            pool.put(item, 0L);
            List<Ingredient> ingredients = recipe.ingredients();
            for (int i = 0; i < ingredients.size(); i++) {
                if (!hasOptions(ingredients.get(i))) {
                    continue;
                }
                long need = times * Math.max(1L, recipe.ingredientCount(i));
                // The planner's option order (most stock first, then what the auto-crafter is
                // allowed to use), so the shortfall we name is the one it would have hit.
                List<ItemStack> options = new ArrayList<>(nonEmpty(ingredients.get(i)));
                options.sort((a, b) -> {
                    long sa = pool.getOrDefault(a.getItem(), 0L);
                    long sb = pool.getOrDefault(b.getItem(), 0L);
                    if (sa != sb) {
                        return Long.compare(sb, sa);
                    }
                    return Boolean.compare(ctx.enabledItems.contains(b.getItem()),
                            ctx.enabledItems.contains(a.getItem()));
                });
                boolean supplied = false;
                for (int oi = 0; oi < options.size(); oi++) {
                    Item optItem = options.get(oi).getItem();
                    boolean lastOption = oi == options.size() - 1;
                    if (lastOption) {
                        // Committed to the real pool: if this cannot be supplied either, the
                        // deficit is recorded here - the message then names the planner's own
                        // first choice rather than an alternative it would not have used.
                        int before = absent.size() + unresolved.size();
                        collectMissing(level, ctx, optItem, pool, need, visiting, depth + 1,
                                absent, unresolved, budget);
                        if (absent.size() + unresolved.size() == before) {
                            supplied = true;
                        }
                        break;
                    }
                    Map<Item, Long> trial = new HashMap<>(pool);
                    java.util.Map<Item, Long> trialAbsent = new java.util.LinkedHashMap<>();
                    java.util.Map<Item, Long> trialUnresolved = new java.util.LinkedHashMap<>();
                    collectMissing(level, ctx, optItem, trial, need, visiting, depth + 1,
                            trialAbsent, trialUnresolved, budget);
                    if (trialAbsent.isEmpty() && trialUnresolved.isEmpty()) {
                        pool.clear();
                        pool.putAll(trial);
                        supplied = true;
                        break;
                    }
                }
                if (!supplied && options.isEmpty()) {
                    // An ingredient with no option at all cannot be named - the recipe itself is
                    // the problem, and the caller's "could not be planned" answer says so.
                    unresolved.merge(item, 1L, Math::max);
                }
            }
        } finally {
            visiting.remove(item);
        }
    }

    /** Depth of descent when looking for the missing ingredient. */
    private static final int MISSING_MAX_DEPTH = 6;

    /**
     * How many branches the missing walk may visit.
     *
     * <p>A ceiling and not a budget: the walk answers a message, so it must never be able to
     * make the server thread work for seconds. Reaching it is treated as "could not decide",
     * which is honest - and it is 20 000 because a real tree (depth 6, a handful of options
     * per slot) stays far below it.
     */
    private static final int MISSING_WALK_BUDGET = 20_000;

    /**
     * The machine that stands in the network, could make this item, and cannot pay -
     * or an empty string when that is not the situation.
     *
     * <p>Used only to fill in the reason when execution fails and nothing was missing.
     * The furnace is checked too: a heat recipe with a furnace that has no fuel fails
     * exactly the same way, and it is the same answer the player needs.
     */
    private static String unpoweredMachine(ServerLevel level, Context ctx, Item item) {
        if (!VeloceRecipeRegistry.getFurnaceRecipesFor(level, item).isEmpty()
                && VeloceHeatSources.hasAnyHeatSource(level, ctx.network)
                && !VeloceHeatSources.hasPower(level, ctx.network)) {
            return furnaceNames();
        }
        // Asked through the SOURCES, not through module.powered().
        //
        // VeloceBrewingModule.powered() returns available() - it answers "is the machine
        // there", not "can it pay" - because the brewing stand's energy is charged by the
        // crafter at payment time rather than advertised as a power state. Asking the
        // module produced an empty answer for exactly the machine the player was looking
        // at, so the question is put to the machines themselves.
        for (VeloceProcessingModule module : VeloceProcessingRegistry.all()) {
            for (net.minecraft.world.item.crafting.RecipeType<?> type : module.recipeTypes()) {
                if (VeloceProcessingSources.hasAny(level, ctx.network, type)
                        && !VeloceProcessingSources.hasPowered(level, ctx.network, type)) {
                    return machineNames(module);
                }
            }
        }
        return "";
    }

    /** The furnaces a heat recipe can use, as translatable names. */
    private static String furnaceNames() {
        return com.craftingveloce.init.VeloceRegistry.VELOCITY_FURNACE.get().getDescriptionId()
                + ", "
                + com.craftingveloce.init.VeloceRegistry.ELECTRIC_FURNACE.get().getDescriptionId();
    }

    /**
     * The machines this module serves, as translatable names.
     *
     * <p>Built-in modules have one machine each and it is one of ours. A compat module
     * covers a whole family whose machines are {@link com.craftingveloce.block.VeloceFeModuleBlock}s;
     * their block description ids are looked up in the registry, which is exactly what the
     * player sees in the creative menu. When nothing is found the module id is used, so the
     * message still says something rather than nothing.
     */
    private static String machineNames(VeloceProcessingModule module) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (net.minecraft.world.level.block.Block block
                : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!(block instanceof com.craftingveloce.block.VeloceFeModuleBlock fe)) {
                continue;
            }
            if (fe.module().id().startsWith(module.id() + ":")) {
                names.add(block.getDescriptionId());
            }
        }
        if (!names.isEmpty()) {
            return String.join(", ", names);
        }
        switch (module.id()) {
            case "crafting" -> names.add(
                    com.craftingveloce.init.VeloceRegistry.VELOCE_CRAFTING_TABLE.get().getDescriptionId());
            case "furnace" -> names.add(furnaceNames());
            case "brewing" -> names.add(
                    com.craftingveloce.init.VeloceRegistry.BREWING_STAND.get().getDescriptionId());
            default -> names.add(module.id());
        }
        return String.join(", ", names);
    }

    private static String name(Item item) {
        // The DESCRIPTION ID, not the rendered name: these strings travel to the client,
        // which translates them itself. A resolved name would arrive in the SERVER's
        // language, so a Polish player on an English server would be told what is missing
        // in English.
        return item.getDescriptionId();
    }

    /** One run of a recipe: take the ingredients, insert the result. */
    private static boolean runOnce(ServerLevel level, Context ctx,
                                   ProcessingEntry recipe,
                                   java.util.List<com.craftingveloce.block.entity.VeloceHeatSource> heat) {
        // 1. FIRST TAKE THE INGREDIENTS, THEN PAY WITH HEAT.
        //
        // The reverse order (heat first) burned energy for nothing: when after the
        // payment it turned out that an ingredient was missing, the method returned
        // the taken items, but NOBODY EVER GAVE THE HEAT BACK. With a recipe whose
        // ingredient had just run out, every attempt ate one smelting operation from
        // the furnace and nothing came of it.
        //
        // Now: we pay the heat only once we have the complete set of ingredients.
        // Then the only cause of failure is a lack of power/fuel, and in that case we
        // also return the taken items.
        NonNullList<ItemStack> consumed = NonNullList.create();
        List<Ingredient> ingredientList = recipe.ingredients();
        for (int ingIndex = 0; ingIndex < ingredientList.size(); ingIndex++) {
            Ingredient ing = ingredientList.get(ingIndex);
            // THE SAME RULE AS IN THE PLANNER (hasOptions).
            //
            // The BUG this fixes (player report: "the GUI shows 2 crushing wheels,
            // but when crafting it says I do not have the items"): the planner skipped
            // ingredients with no options, while execution tried to "take" them and
            // failed immediately. Empty grid slots (Ingredient.EMPTY) are now removed
            // already when the recipe is built, but an ingredient with an empty tag
            // still reaches this point - and it must be skipped exactly as in the
            // plan, because otherwise the plan and the execution disagree on the
            // ingredient list.
            //
            // If the plan and the execution counted ingredients by DIFFERENT rules,
            // we get exactly this symptom: the number is computed, but crafting never
            // works.
            if (!hasOptions(ing)) {
                VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                        "recipe %s: skipping ingredient with no option at all (empty tag)",
                        recipe.id());
                continue;
            }
            // An ingredient may require several units per run
            // (Alchemistry IngredientStack, Mekanism SizedIngredient).
            // The plan takes this number into account, so execution must take it
            // too - otherwise free surplus would appear in the network.
            int units = recipe.ingredientCount(ingIndex);
            for (int unit = 0; unit < units; unit++) {
                ItemStack taken = takeOne(level, ctx, ing);
                VeloceCraftTrace.log("execution %s: ingredient %d/%d %s -> %s",
                        recipe.id(), unit + 1, units, describeOptions(ing),
                        taken.isEmpty() ? "MISSING" : (taken.getCount() + "x " + taken.getItem()));
                if (taken.isEmpty()) {
                    // WE SAY PLAINLY WHAT WAS MISSING.
                    //
                    // Without this the log only had "ingredients vanished mid-craft",
                    // while the player reported "I do not have the items" - and it was
                    // impossible to determine which ingredient the problem concerned.
                    String missingName = firstOptionName(ing);
                    ctx.lastMissingIngredient = missingName;
                    VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                            "recipe %s: missing ingredient %s (units per run: %d, options: %d)",
                            recipe.id(), describeOptions(ing), units, nonEmpty(ing).size());
                    // Return the taken ones - we do not lose items.
                    for (ItemStack s : consumed) {
                        deposit(level, ctx, s);
                    }
                    return false;
                }
                consumed.add(taken);
            }
        }

        // 2. Payment for the operation.
        //
        // A furnace pays with heat (VeloceHeatSources: electric first, then fuel),
        // a module machine pays with its own energy (VeloceProcessingSources), and
        // recipes needing no infrastructure (crafting table, stonecutter, smithing)
        // pay with nothing - that is why we recognize them by family and not by
        // "is it not a furnace".
        //
        // On failure we take NOTHING and return the taken items.
        boolean paid = payForOperation(level, ctx, recipe, heat);
        VeloceCraftTrace.log("execution %s: payment (%s) -> %s", recipe.id(),
                recipe.isFurnace() ? "heat" : (VeloceRecipeFamilies.isFree(recipe.type())
                        ? "none" : "module machine"), paid ? "OK" : "MISSING");
        if (!paid) {
            for (ItemStack s : consumed) {
                deposit(level, ctx, s);
            }
            return false;
        }

        // 3. Results.
        //
        // All of them, not only the primary one: Fission has two, and Create crushing
        // is sometimes a bit more. We roll the dice PER RESULT (resultChances), so the
        // player sometimes gets more, never less than planned - the plan only sees
        // guaranteed results (see ProcessingEntry).
        List<ItemStack> results = recipe.results();
        for (int i = 0; i < results.size(); i++) {
            ItemStack single = results.get(i);
            if (single.isEmpty()) {
                continue;
            }
            float chance = i < recipe.resultChances().size()
                    ? recipe.resultChances().get(i) : 1.0f;
            if (chance < 1.0f && level.random.nextFloat() >= chance) {
                continue;
            }
            VeloceCraftTrace.log("execution %s: result %dx %s", recipe.id(),
                    single.getCount(), single.getItem());
            deposit(level, ctx, single.copy());
        }
        return true;
    }

    /**
     * Takes one unit matching the ingredient.
     *
     * <p><b>The order is critical and must be symmetric to {@link #deposit}.</b>
     * deposit puts the results into the crafter buffers FIRST, so takeOne must look
     * for them there BEFORE the network. Without that, multi-stage crafting
     * (planks -> fence) failed: the plan knew the planks would be there, because
     * deposit puts them there, but takeOne could not find them and returned EMPTY.
     * The symptom in the log: "ingredients vanished mid-craft", and in the game -
     * materials were left unused.
     *
     * <p>The order: crafter buffers -> network -> player inventory.
     *
     * <p>The buffers stay FIRST because the symmetry with {@link #deposit} is what makes
     * multi-stage crafting work at all. After that the network comes before the player, for
     * the owner's reason: what is already in the system is spent before what the player is
     * carrying, and the inventory is the last resort rather than the first choice.
     */
    private static ItemStack takeOne(ServerLevel level, Context ctx, Ingredient ing) {
        for (ItemStack opt : nonEmpty(ing)) {
            Item item = opt.getItem();

            // 1. Crafter buffers - this is where deposit puts intermediate results.
            if (ctx.buffers != null) {
                for (var buf : ctx.buffers) {
                    ItemStack fromBuf = extractOneFromBuffer(buf, item);
                    if (!fromBuf.isEmpty()) {
                        return fromBuf;
                    }
                }
            }

            // 2. Ordinary network endpoints (chests, RS).
            ItemStack fromNet = ctx.network.extractItem(level, item, 1);
            if (!fromNet.isEmpty()) {
                return fromNet;
            }

            // 3. The player's inventory - only what the network could not supply.
            if (ctx.inventory != null) {
                ItemStack fromInv = ctx.inventory.extract(item, 1);
                if (!fromInv.isEmpty()) {
                    return fromInv;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** Takes one unit of the item out of the crafter buffer. */
    private static ItemStack extractOneFromBuffer(
            com.craftingveloce.inventory.VeloceCraftingBuffer buf, Item item) {
        for (int slot = 0; slot < buf.getContainerSize(); slot++) {
            ItemStack inSlot = buf.getItem(slot);
            if (!inSlot.isEmpty() && inSlot.getItem() == item) {
                ItemStack taken = buf.removeItem(slot, 1);
                if (!taken.isEmpty()) {
                    return taken;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Inserts the result into the network.
     *
     * <p>Order: first the crafter buffer (scratch storage for the surplus), then the
     * ordinary network endpoints (chests). Thanks to that, when 4 planks are made
     * from 1 log and the player wanted 1 - the remaining 3 stay in the crafter buffer
     * and are normally available to the whole network.
     */
    private static void deposit(ServerLevel level, Context ctx, ItemStack stack) {
        ItemStack leftover = insertWhereverPossible(ctx, stack);
        if (!leftover.isEmpty()) {
            // The BUG that used to be here: the remainder after going through ALL
            // endpoints was silently discarded. With a full network, crafted items
            // were therefore lost - as well as the INGREDIENTS returned on a failed
            // execution (runOnce returns them through deposit). Now the remainder is
            // dropped on the ground at the block that requested the crafting.
            dropLeftover(level, ctx, leftover);
        }
    }

    /**
     * Inserts the stack wherever possible and returns what is left.
     *
     * <p>Order: the crafter buffer (scratch storage for the surplus), then the
     * ordinary network endpoints (chests).
     */
    private static ItemStack insertWhereverPossible(Context ctx, ItemStack stack) {
        ItemStack remaining = stack;
        if (remaining.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // 1. Crafter buffers.
        if (ctx.buffers != null) {
            for (var buf : ctx.buffers) {
                remaining = buf.insert(remaining);
                if (remaining.isEmpty()) {
                    return ItemStack.EMPTY;
                }
            }
        }
        // 2. Ordinary network endpoints.
        //
        // EVERY endpoint returns the REMAINDER and it is exactly that remainder we
        // pass on. The previous version passed the same, full stack to the successive
        // endpoints: if the first accepted PART of it and returned false, the second
        // got the whole thing and could accept it - meaning the accepted part was in
        // the network TWICE.
        for (var endpoint : ctx.network.getEndpoints().values()) {
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }
            remaining = endpoint.insertItemLeftover(ctx.level, remaining);
        }
        return remaining;
    }

    /** As a fallback, drops the remainder at the block that ordered the crafting. */
    private static void dropLeftover(ServerLevel level, Context ctx, ItemStack leftover) {
        if (ctx.dropPos == null) {
            VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                    "network full and no drop position - losing %s", leftover);
            return;
        }
        net.minecraft.world.level.block.Block.popResource(level, ctx.dropPos, leftover);
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "network full - dropped %s at %s", leftover, ctx.dropPos);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Name of the first ingredient option (for the player-facing message). */
    private static String firstOptionName(Ingredient ing) {
        List<ItemStack> options = nonEmpty(ing);
        return options.isEmpty() ? "" : options.get(0).getItem().getDescriptionId();
    }

    /** A short description of the ingredient for the log: the list of item ids matching it. */
    private static String describeOptions(Ingredient ing) {
        StringBuilder sb = new StringBuilder("[");
        for (ItemStack option : nonEmpty(ing)) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(option.getItem()));
        }
        return sb.append(']').toString();
    }

    /**
     * Does this ingredient have ANY option (something to substitute it with)?
     *
     * <p>ONE place with this rule for the planner and for execution - a divergence
     * between these two places gives the symptom "the number is computed, but
     * crafting never works".
     */
    private static boolean hasOptions(Ingredient ing) {
        for (ItemStack option : ing.getItems()) {
            if (!option.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static List<ItemStack> nonEmpty(Ingredient ing) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : ing.getItems()) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** Recipes in the order of the player's preference. */
    private static List<ProcessingEntry> orderRecipes(
            ServerLevel level, VelocePipeNetwork network, Item item,
            Map<Item, ResourceLocation> preferred,
            boolean heatAvailable, boolean furnaceFirst) {
        List<ProcessingEntry> all = allRecipesFor(level, network, item, heatAvailable);
        if (all.size() <= 1) {
            return all;
        }

        // 1. The SPECIFIC recipe chosen in the crafter always takes precedence -
        //    it is the player's most precise decision.
        ResourceLocation pref = preferred.get(item);
        if (pref != null) {
            List<ProcessingEntry> ordered = new ArrayList<>(all.size());
            for (var e : all) {
                if (e.id().equals(pref)) {
                    ordered.add(e);
                }
            }
            for (var e : all) {
                if (!e.id().equals(pref)) {
                    ordered.add(e);
                }
            }
            return ordered;
        }

        // 2. The "furnace or crafting" preference from the controller.
        //
        //    NOTE: this is a PREFERENCE, not a filter. Smelting goes first, but the
        //    crafting recipes REMAIN in later positions - if the furnace has nothing
        //    to pay with (heatAvailable == false), the list is untouched and the
        //    planner simply uses crafting. Thanks to that the "I prefer the furnace"
        //    choice does not take away the player's ability to make the item
        //    differently.
        if (furnaceFirst && heatAvailable) {
            List<ProcessingEntry> ordered = new ArrayList<>(all.size());
            for (var e : all) {
                if (e.isFurnace()) {
                    ordered.add(e);
                }
            }
            for (var e : all) {
                if (!e.isFurnace()) {
                    ordered.add(e);
                }
            }
            return ordered;
        }

        // 3. Without a preference: the default order (crafting before furnace).
        return all;
    }

        /**
     * A snapshot of everything available for crafting.
     *
     * <p><b>It MUST be symmetric with {@link #deposit}.</b> deposit puts the results
     * into the crafter buffers first, so if the plan does not see them, it assumes
     * the ingredient is absent - even though deposit just put it there. This was the
     * source of the "ingredients vanished mid-craft" error in multi-stage crafting
     * (planks -> fence).
     *
     * <p>The order of sources: network + inventory + crafter buffers.
     *
     * <p>The network state may be supplied from outside, so that
     * {@link #ensureAvailable} does not scan the network TWICE: once to check
     * availability, and a moment later a second time for the planning snapshot.
     * With a forced scan (force=true) that is a full walk over all network
     * inventories.
     */

    private static Map<Item, Long> snapshotStock(Context ctx, Map<Item, Long> networkCounts,
                                                Item requested) {
        Map<Item, Long> stock = new HashMap<>(networkCounts);
        if (ctx.inventory != null) {
            for (Item it : ctx.inventory.allItems()) {
                // THE REQUESTED ITEM IS NOT AN INGREDIENT OF ITSELF.
                //
                // Merging the player's inventory wholesale meant that asking for something
                // they were already carrying put it in the planning stock - and the planner,
                // needing 1 more, simply "took" it from the stock and reported success
                // WITHOUT CRAFTING ANYTHING. The hand-over then found nothing in the buffers
                // and nothing in the network (it can only deliver from there) and reported
                // `dropFull`. That is the whole of "it works when I have no doors and fails
                // when I do", and it is why the log in the player's inventory was never
                // consumed: the plan never needed it.
                //
                // The player's inventory is a source of INGREDIENTS. The thing they asked
                // for has to be produced into the network, because that is the only place
                // the terminal can hand it over from.
                if (it == requested) {
                    continue;
                }
                stock.merge(it, (long) ctx.inventory.count(it), Long::sum);
            }
        }
        // NOTE: we do NOT add the crafter buffers here.
        //
        // The buffer is already a network endpoint (CraftingBufferEndpoint - see
        // scanAndBuildNetwork), so networkCounts already contains it. Adding it a
        // second time counted the buffer contents TWICE: the planner saw 4 planks
        // where there were 2, planned the craft, and only blew up during execution
        // with the "ingredients vanished mid-craft" error.
        //
        // It was visible exactly like this:
        //   plan for minecraft:oak_fence: 1 recipe run(s) to execute
        //   execution failed for minecraft:oak_fence - ingredients vanished mid-craft
        return stock;
    }

    /**
     * The player inventory as a source of ingredients.
     *
     * <p><b>NOTE: this is currently DEAD CODE.</b> The interface has no
     * implementation, and both places that create a {@link Context} pass
     * {@code null}:
     * <ul>
     *   <li>{@code VeloceTerminalBlockEntity.craftItemFromNetwork}</li>
     *   <li>{@code VeloceExtractorBlockEntity.craftFromNetwork}</li>
     * </ul>
     *
     * <p><b>Now wired up for the terminal, and only for the terminal.</b> The owner asked
     * for the network to be able to draw on what the player is carrying, so
     * {@code VeloceTerminalBlockEntity} passes an implementation of this interface over
     * {@code player.getInventory()}. A machine has no player - the extractor crafts on a
     * timer with nobody present - so it still passes {@code null} and behaves exactly as
     * before.
     *
     * <p><b>The priority is network first, player last.</b> Every branch below consults the
     * player's inventory only after the network has failed to supply the item. Wiring this
     * up without inverting the priority would have emptied a player's pockets while a chest
     * full of the same item stood in the same network.
     */
    public interface ItemInventory {
        int count(Item item);

        ItemStack extract(Item item, int max);

        /** All items in the inventory (for availability simulation). */
        Set<Item> allItems();
    }
}
