package com.craftingveloce.crafting;

import com.craftingveloce.util.VeloceLog;
import com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A coherent view of all auto-crafters in the network.
 *
 * <p>Collects information from the Veloce Crafting Table blocks connected to
 * the network: which items have auto-crafting enabled and which recipe has
 * priority (for items with multiple recipes the player can choose a recipe with
 * shift+scroll).
 */
public final class VeloceCraftingRegistry {

    private VeloceCraftingRegistry() {
    }

    /**
     * Crafters in the network, in DETERMINISTIC order.
     *
     * <p>{@code network.getTerminals()} is a set with no defined order, so
     * picking the "first crafter" was random and could change between runs. We
     * sort by position so that the behaviour is repeatable: with several
     * crafters in the network the same one always wins.
     */
    static java.util.List<VeloceCraftingTableBlockEntity> crafters(
            ServerLevel level, VelocePipeNetwork network) {
        java.util.List<BlockPos> sorted = new java.util.ArrayList<>(network.getTerminals());
        sorted.sort(java.util.Comparator
                .comparingInt((BlockPos p) -> p.getX())
                .thenComparingInt(p -> p.getY())
                .thenComparingInt(p -> p.getZ()));
        java.util.List<VeloceCraftingTableBlockEntity> out = new java.util.ArrayList<>();
        int unreadable = 0;
        for (BlockPos pos : sorted) {
            // blockEntityIfLoaded, and NOT getBlockEntity: on the server the latter
            // WOULD LOAD the chunk of a node that stands far away and is unloaded -
            // meaning every request for numbers from the GUI would load it in a loop.
            BlockEntity be = com.craftingveloce.network.pipe.VeloceChunkLoader
                    .blockEntityIfLoaded(level, pos);
            if (be instanceof VeloceCraftingTableBlockEntity crafter) {
                // The frame shares the block entity with the table - an empty one is not a crafter.
                if (crafter.isActiveCrafter()) {
                    out.add(crafter);
                } else {
                    continue;
                }
            } else if (be == null) {
                // A node that CANNOT BE READ (the chunk is not loaded).
                //
                // We count it and report it, because otherwise the failure is SILENT:
                // the crafter drops off the list, `getAllEnabledItems` returns an empty
                // set and all auto-crafting switches off without a single trace
                // in the log - and the symptom ("auto-crafting OFF on everything") is
                // identical to a bug that was already here once. If anyone had seen
                // this in the log, they would look for the cause in force-loads,
                // and not in recipes.
                unreadable++;
            }
        }
        if (unreadable > 0) {
            VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                    "%d node(s) of the network could not be read (chunk not loaded) - "
                            + "auto-crafting may look disabled for everything; "
                            + "check /cv chunk status",
                    unreadable);
        }
        return out;
    }

    /**
     * Finds a crafter in the network that has auto-crafting enabled for the given item.
     *
     * @return the first such block entity, or {@code null}
     */
    @Nullable
    public static VeloceCraftingTableBlockEntity findEnabledCrafter(ServerLevel level,
                                                                    VelocePipeNetwork network,
                                                                    Item item) {
        for (VeloceCraftingTableBlockEntity crafter : crafters(level, network)) {
            if (crafter.isEnabled(item)) {
                return crafter;
            }
        }
        return null;
    }

    /**
     * WHY this item is not in the set of craftable ones - the concrete reason.
     *
     * <p>Used by the terminal (a message for the player and a diagnostic trace).
     * We ask in an order that points to the FIX:
     * <ol>
     *   <li>no crafter (for recipes without infrastructure),</li>
     *   <li>item disabled in the crafter,</li>
     *   <li>no furnace / furnace without fuel,</li>
     *   <li>the module's machine is not standing / has no power,</li>
     *   <li>as a last resort: no recipe.</li>
     * </ol>
     */
    public static DisabledReason whyNotCraftable(ServerLevel level, VelocePipeNetwork network,
                                                 Item item) {
        boolean freeRecipe = !VeloceRecipeRegistry.getRecipesFor(level, item).isEmpty();
        List<VeloceCraftingTableBlockEntity> crafters = crafters(level, network);
        if (freeRecipe && crafters.isEmpty()) {
            return new DisabledReason("craftingveloce.craft.error.noCrafter", "");
        }
        if (freeRecipe) {
            boolean disabledSomewhere = false;
            for (VeloceCraftingTableBlockEntity crafter : crafters) {
                if (!crafter.isEnabled(item)) {
                    disabledSomewhere = true;
                    break;
                }
            }
            if (disabledSomewhere) {
                return new DisabledReason("craftingveloce.craft.error.crafterDisabled", "");
            }
        }
        if (!VeloceRecipeRegistry.getFurnaceRecipesFor(level, item).isEmpty()) {
            if (!VeloceHeatSources.hasAnyHeatSource(level, network)) {
                return new DisabledReason("craftingveloce.craft.error.noFurnace", "");
            }
            if (!VeloceHeatSources.hasPower(level, network)) {
                // See VeloceAutoCrafter: a fuel furnace that is still banking its first
                // one coal is "not hot enough", not out of fuel - the two are the same
                // state to hasPower() and must not be the same words to the player.
                if (VeloceHeatSources.hasColdFuelFurnace(level, network)) {
                    return new DisabledReason("craftingveloce.craft.error.furnaceNotHot", "");
                }
                return new DisabledReason("craftingveloce.craft.error.furnaceUnpowered", "");
            }
        }
        for (VeloceProcessingModule module : VeloceProcessingRegistry.all()) {
            if (module.recipesAnywhere(level, item).isEmpty()) {
                continue;
            }
            if (!module.available(level, network)) {
                return new DisabledReason("craftingveloce.craft.error.noModule", module.id());
            }
            if (!module.powered(level, network)) {
                return new DisabledReason("craftingveloce.craft.error.moduleUnpowered",
                        module.id());
            }
        }
        return new DisabledReason("craftingveloce.craft.error.disabled", "");
    }

    /** The reason why an item is not craftable: language key + detail. */
    public record DisabledReason(String reasonKey, String detail) {
    }

    /** Whether any crafter in the network exists with a recipe enabled for the item. */
    public static boolean isCraftingEnabled(ServerLevel level, VelocePipeNetwork network, Item item) {
        return findEnabledCrafter(level, network, item) != null;
    }

    /**
     * Builds the map of preferred recipes from the settings of all crafters in
     * the network. On a conflict the crafter closer to the beginning of the
     * terminal set wins (stable order).
     */
    public static Map<Item, ResourceLocation> getPreferredRecipes(ServerLevel level,
                                                                  VelocePipeNetwork network) {
        // Measured: this walks every crafter in the network, and it runs on the server thread
        // as one of the two arguments of the snapshot capture - so on a network with many
        // crafters it is part of what the player waits for when the terminal opens.
        try (var ignored = com.craftingveloce.util.VeloceProfiler.section("server.registry.getPreferredRecipes")) {
            Map<Item, ResourceLocation> out = new HashMap<>();
            for (VeloceCraftingTableBlockEntity crafter : crafters(level, network)) {
                for (Map.Entry<Item, ResourceLocation> e : crafter.getPreferredRecipes().entrySet()) {
                    out.putIfAbsent(e.getKey(), e.getValue());
                }
            }
            return out;
        }
    }

    /**
     * The items the auto-crafters in this network can actually make.
     *
     * <p>MIND the semantics: the crafter works in an opt-out model (everything
     * enabled by default, the player saves only the exceptions). This method must
     * therefore return the difference: all craftable items MINUS those the player
     * disabled in any crafter.
     *
     * <p>Returning only the exceptions would be a bug - the engine would get a
     * list of items that are allowed to be crafted, and instead it would get a
     * list of disabled ones, that is the exact opposite.
     */
    /**
     * The MODS whose machines could make this item, as one string: "create, mekanism".
     *
     * <p><b>What question this answers.</b> Not "can the network make it right now" - the
     * caller only asks about items it is already showing as unavailable - but "which
     * integration would have to be involved at all". So it looks at every installed module
     * and keeps the ones with a recipe for the item, regardless of whether their machine is
     * placed or powered. That is the useful answer for a player staring at a red item: it
     * says where to go and look.
     *
     * <p>Names the MOD and not the machine, for two reasons that are both structural:
     * a module is a whole FAMILY whose machines are not distinguishable from the core
     * without naming compat types, and a family name is what tells the player which mod to
     * attend to. Deduplicated, so a mod appears once however many of its machines match.
     */
    public static String modsThatCanMake(ServerLevel level, Item item) {
        // ONE CALL PER UNAVAILABLE ITEM ON THE PAGE, and each call asks every installed module
        // whether it has a recipe for that item - so its cost is "red items x modules x recipe
        // lookups". That is the shape that made a fully red page much more expensive than a
        // single query, and the unit count here is what makes it visible in the report.
        java.util.Set<String> mods = new java.util.LinkedHashSet<>();
        try (var ignored = com.craftingveloce.util.VeloceProfiler.section("server.registry.modsThatCanMake")) {
            for (VeloceProcessingModule module : VeloceProcessingRegistry.all()) {
                if (!module.recipesAnywhere(level, item).isEmpty()) {
                    mods.add(module.id());
                }
            }
        }
        return String.join(", ", mods);
    }

    public static java.util.Set<Item> getAllEnabledItems(ServerLevel level, VelocePipeNetwork network) {
        // SUM OF MODULES: each processing module says what it can do - and only
        // when its machine stands in the network and is able to work.
        //
        // The BUG this fixes (a player report): previously this method required a
        // CRAFTER for EVERYTHING - no crafter zeroed the whole list. A network
        // with only a furnace therefore could not make glass from sand, even
        // though that is a purely furnace recipe. Now the furnace module is
        // enough on its own, and a crafter is needed only for its own (crafting)
        // recipes.
        java.util.Set<Item> out = new java.util.HashSet<>();
        // Measured, and with a unit count: the loop is short (one entry per installed module)
        // but `module.producible` is NOT - it asks a module for every item its machines can
        // make, and on the first call it may build that module's recipe index. A big number in
        // the unit column next to a big total is the answer to "why does the first opening cost
        // more than the ones after it".
        try (var ignored = com.craftingveloce.util.VeloceProfiler.section("server.registry.getAllEnabledItems")) {
            for (VeloceProcessingModule module : VeloceProcessingRegistry.all()) {
                if (!module.available(level, network) || !module.powered(level, network)) {
                    continue;   // no machine, or the machine is stopped
                }
                java.util.Set<Item> made = module.producible(level, network);
                com.craftingveloce.util.VeloceProfiler.count("server.registry.getAllEnabledItems", made.size());
                out.addAll(made);
            }
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    /**
     * Buffers of all crafters in the network - a cache for surplus production.
     * Stable order (terminal order) so that the results are predictable.
     */
    public static java.util.List<com.craftingveloce.inventory.VeloceCraftingBuffer> getBuffers(
            ServerLevel level, VelocePipeNetwork network) {
        java.util.List<com.craftingveloce.inventory.VeloceCraftingBuffer> out = new java.util.ArrayList<>();
        for (VeloceCraftingTableBlockEntity crafter : crafters(level, network)) {
            out.add(crafter.getBuffer());
        }
        return out;
    }
}
