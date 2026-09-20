package com.craftingveloce.crafting;

import com.craftingveloce.block.entity.VeloceHeatSource;
import com.craftingveloce.block.entity.VeloceProcessingSource;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The COMPLETE trace of one crafting attempt - for diagnosing "it says I can,
 * but I cannot".
 *
 * <p><b>Why a separate mechanism.</b> The mod's regular log is filtered by config
 * (levels), so exactly when something is broken, the most important lines are
 * missing. This trace always logs (raw INFO), but ONLY within a single player
 * action - see {@link #begin(String)}. Thanks to that:
 * <ul>
 *   <li>the player clicks "extract item" and gets a complete description of the decision,</li>
 *   <li>background automations (extractor, buffers) do NOT clutter the log - their path
 *       does not start a trace.</li>
 * </ul>
 *
 * <p><b>Format.</b> Every line has the prefix {@code [Veloce][CRAFT-TRACE][#N]},
 * where N is the action number. It is enough to copy all lines with the same N.
 *
 * <p>The trace is per thread (ThreadLocal), because player crafting runs entirely
 * on the server thread.
 */
public final class VeloceCraftTrace {

    private VeloceCraftTrace() {
    }

    private static final AtomicLong IDS = new AtomicLong();
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    /** Whether a traced action is currently running on this thread. */
    public static boolean active() {
        return CURRENT.get() != null;
    }

    /**
     * Opens a trace and returns its number - or does nothing when debugging is off.
     *
     * <p><b>Why this is gated.</b> A trace is not one line: one terminal click writes the whole
     * plan - the network environment, every module, the recipe list, every planning step - which
     * measured 400 to 800 lines PER CLICK. It was opened unconditionally and written through the
     * plain logger, so a released build quietly filled {@code latest.log} with hundreds of lines
     * for every item a player took, and built all those strings on the server thread while doing
     * it.
     *
     * <p>The switch is {@code debugEnabled} - the same one that turns on the ordinary detailed
     * logging - so a player reporting a problem can turn it on and get exactly the trace that was
     * always there, while everyone else gets a log they can read.
     *
     * @return 0 when tracing is off; every other method of this class then does nothing
     */
    public static long begin(String what) {
        if (!debugEnabled()) {
            return 0L;
        }
        long id = IDS.incrementAndGet();
        CURRENT.set(id);
        out(id, "========== START #%d: %s ==========", id, what);
        return id;
    }

    /** The debug switch, and never a throw - the config may not be loaded during startup. */
    private static boolean debugEnabled() {
        try {
            return com.craftingveloce.config.VeloceConfig.DEBUG_ENABLED.get();
        } catch (Throwable notLoadedYet) {
            return false;
        }
    }

    /** Closes the trace (safe to call always - does nothing without an active trace). */
    public static void end(String result) {
        Long id = CURRENT.get();
        if (id == null) {
            return;
        }
        out(id, "========== END #%d: %s ==========", id, result);
        CURRENT.remove();
    }

    /** A trace line (only within an active action). */
    public static void log(String fmt, Object... args) {
        Long id = CURRENT.get();
        if (id == null) {
            return;
        }
        out(id, fmt, args);
    }

    private static void out(long id, String fmt, Object... args) {
        String body = args.length == 0 ? fmt : String.format(fmt, args);
        com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                "[Veloce][CRAFT-TRACE][#{}] {}", id, body);
    }

    /**
     * An exception within a traced action - with a stack trace, under the same number.
     *
     * <p>Without this, an exception from a module would fly "beside" the trace and it
     * would not be visible at which step it aborted the whole attempt.
     */
    public static void exception(String context, Throwable thrown) {
        Long id = CURRENT.get();
        if (id == null || thrown == null) {
            return;
        }
        com.craftingveloce.CraftingVeloceMod.LOGGER.warn(
                "[Veloce][CRAFT-TRACE][#" + id + "] EXCEPTION in " + context + ": " + thrown,
                thrown);
    }

    // ------------------------------------------------------------------
    // Dumps - one method per question
    // ------------------------------------------------------------------

    /**
     * What the network HAS for this item: modules (whether the machine is there,
     * whether it has power, how many recipes), furnace (sources, how many
     * operations, whether powered), crafters.
     */
    public static void dumpEnvironment(ServerLevel level, VelocePipeNetwork network, Item item) {
        if (!active()) {
            return;
        }
        log("--- NETWORK ENVIRONMENT ---");
        log("network: %s, terminals(nodes)=%d, endpoints=%d",
                network == null ? "NONE" : network.getId(),
                network == null ? 0 : network.getTerminals().size(),
                network == null ? 0 : network.getEndpoints().size());
        if (network == null) {
            return;
        }

        log("processing modules (id | machine present | powered | recipes for this item):");
        for (VeloceProcessingModule module : VeloceProcessingRegistry.all()) {
            boolean available = module.available(level, network);
            boolean powered = module.powered(level, network);
            int recipes = module.recipesAnywhere(level, item).size();
            log("  - %-10s available=%-5s powered=%-5s recipes=%d  types=%s",
                    module.id(), available, powered, recipes, typeNames(module.recipeTypes()));
        }

        List<VeloceHeatSource> heat = VeloceHeatSources.allIn(level, network);
        log("furnace: sources=%d, hasAny=%s, hasPower=%s, total operations=%d",
                heat.size(), VeloceHeatSources.hasAnyHeatSource(level, network),
                VeloceHeatSources.hasPower(level, network),
                VeloceHeatSources.totalOperations(level, network));
        for (VeloceHeatSource source : heat) {
            log("  - %s: operations=%d, powered=%s",
                    source.heatSourceName(), source.availableOperations(),
                    source.isPowered());
        }

        List<VeloceProcessingSource> machines = VeloceProcessingSources.allIn(level, network);
        log("module machines: %d", machines.size());
        for (VeloceProcessingSource machine : machines) {
            log("  - %s: types=%s, operations=%d, powered=%s",
                    machine.sourceName(), typeNames(machine.recipeTypes()),
                    machine.availableOperations(), machine.isPowered());
        }

        log("crafters: %d, item in the set of enabled=%s",
                VeloceCraftingRegistry.crafters(level, network).size(),
                VeloceCraftingRegistry.getAllEnabledItems(level, network).contains(item));
    }

    /**
     * All recipes for this item, per source, with ingredients and item counts.
     * This answers the question "do we even know how to make this".
     */
    public static void dumpRecipes(ServerLevel level, VelocePipeNetwork network, Item item) {
        if (!active()) {
            return;
        }
        log("--- RECIPES for %s (%s) ---", name(item), id(item));
        dumpOne("vanilla (crafting/stonecutting/smithing)",
                VeloceRecipeRegistry.getRecipesFor(level, item));
        dumpOne("furnace", VeloceRecipeRegistry.getFurnaceRecipesFor(level, item));
        for (VeloceProcessingModule module : VeloceProcessingRegistry.all()) {
            dumpOne("module " + module.id(), module.recipesAnywhere(level, item));
        }
    }

    private static void dumpOne(String label, List<ProcessingEntry> recipes) {
        log("  %s: %d recipe(s)", label, recipes.size());
        for (ProcessingEntry recipe : recipes) {
            log("    * %s [%s] -> %s", recipe.id(), VeloceRecipeFinder.typeName(recipe.type()),
                    results(recipe));
            for (int i = 0; i < recipe.ingredients().size(); i++) {
                Ingredient ing = recipe.ingredients().get(i);
                log("        ingredient %d x%d: %s", i + 1, recipe.ingredientCount(i),
                        ingredientOptions(ing));
            }
        }
    }

    /** Stock for the item and its ingredients (from the recipes above), with a line limit. */
    public static void dumpStock(Map<Item, Long> stock, Item item, List<ProcessingEntry> recipes) {
        if (!active()) {
            return;
        }
        long total = 0;
        for (long value : stock.values()) {
            total += value;
        }
        log("--- STOCK: %d distinct item(s), %d unit(s) total ---", stock.size(), total);
        log("  requested %s: %d unit(s)", name(item), stock.getOrDefault(item, 0L));
        int lines = 0;
        for (ProcessingEntry recipe : recipes) {
            for (int i = 0; i < recipe.ingredients().size(); i++) {
                for (ItemStack option : recipe.ingredients().get(i).getItems()) {
                    if (option.isEmpty() || lines++ > 60) {
                        continue;
                    }
                    long have = stock.getOrDefault(option.getItem(), 0L);
                    log("  ingredient %s: %d unit(s) (need %d)", name(option.getItem()), have,
                            recipe.ingredientCount(i));
                }
            }
            if (lines > 60) {
                log("  ... (skipping further ingredients - line limit)");
                break;
            }
        }
    }

    private static String results(ProcessingEntry recipe) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < recipe.results().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(recipe.results().get(i).getCount()).append("x ")
                    .append(name(recipe.results().get(i).getItem()));
            sb.append(" @").append(recipe.resultChances().get(i));
        }
        return sb.toString();
    }

    private static String ingredientOptions(Ingredient ing) {
        ItemStack[] options = ing.getItems();
        if (options.length == 0) {
            return "(no options - empty tag)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < options.length && i < 6; i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(id(options[i].getItem()));
        }
        if (options.length > 6) {
            sb.append(" | ... (").append(options.length).append(" option(s))");
        }
        return sb.toString();
    }

    private static String typeNames(Collection<RecipeType<?>> types) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (RecipeType<?> type : types) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(VeloceRecipeFinder.typeName(type));
            first = false;
        }
        return sb.append(']').toString();
    }

    /**
     * What a stack IS, including its data components - for the "my backpack came out empty" class
     * of report.
     *
     * <p><b>Why the count and the item name are not enough.</b> The trace has always printed
     * "delivered 1x sophisticatedbackpacks:diamond_backpack", which is exactly the same line
     * whether the backpack still holds three stacks of ore or nothing at all. Every attempt to
     * answer "does the network eat the contents?" therefore ended in argument instead of a
     * measurement. This prints the data component keys, so the log itself says whether the stack
     * that left the chest and the stack that reached the player still carry anything.
     *
     * <p>The keys are printed rather than their values: a full inventory component would be
     * unreadable, and the KEY is what distinguishes "no components at all" (the item was
     * rebuilt from its item id somewhere) from "components present" (it is the real stack).
     */
    public static String fingerprint(ItemStack stack) {
        if (stack == null) {
            return "null";
        }
        if (stack.isEmpty()) {
            return "empty";
        }
        var patch = stack.getComponentsPatch();
        StringBuilder out = new StringBuilder();
        out.append(id(stack.getItem())).append(" x").append(stack.getCount());
        if (patch.isEmpty()) {
            out.append(" components=NONE");
        } else {
            out.append(" components=");
            boolean first = true;
            for (var entry : patch.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(entry.getKey());
            }
        }
        return out.toString();
    }

    public static String name(Item item) {
        return new ItemStack(item).getHoverName().getString();
    }

    public static String id(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? String.valueOf(item) : key.toString();
    }
}
