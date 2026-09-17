package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Module recipes (from other mods) for a given item.
 *
 * <p><b>Why a separate class.</b> The planner must see the recipes of ALL
 * modules the same way it sees vanilla recipes - otherwise the automation would
 * never use a crusher or a compactor, even though they stand in the network.
 * This place gathers them into a single stream, asking only the
 * {@link VeloceProcessingModule} interface:
 * <ol>
 *   <li>the module must be AVAILABLE (its machine stands there) and POWERED
 *       (it has power/fuel) - otherwise its recipes are not executable and must
 *       not make it into the plan,</li>
 *   <li>the module itself translates its recipe into the common
 *       {@link ProcessingEntry}.</li>
 * </ol>
 *
 * <p>Thanks to that the core does not know a single name from a foreign mod -
 * adding another module is a registration and a recipe implementation, with no
 * changes here.
 *
 * <p><b>Per-tick memory.</b> The question "which modules are powered" requires
 * walking the network nodes and reading their block entities. The planner asks
 * it for EVERY item under consideration (and when counting quantities -
 * hundreds of times per single click), so without memory it would be the same
 * thing that was once done with the furnace: hundreds of network scans in a
 * single tick. Within one tick and one network the answer does not change.
 */
public final class VeloceModuleRecipes {

    private VeloceModuleRecipes() {
    }

    private static VelocePipeNetwork cacheNetwork;
    private static long cacheTick = Long.MIN_VALUE;
    private static List<VeloceProcessingModule> cacheModules = List.of();

    /** Recipes of all powered modules that produce {@code item}. */
    public static List<ProcessingEntry> forItem(ServerLevel level, VelocePipeNetwork network,
                                                Item item) {
        if (item == null) {
            return List.of();
        }
        List<VeloceProcessingModule> active = activeModules(level, network);
        if (active.isEmpty()) {
            return List.of();
        }
        List<ProcessingEntry> out = new ArrayList<>();
        for (VeloceProcessingModule module : active) {
            List<ProcessingEntry> recipes = module.recipesFor(level, network, item);
            if (recipes != null && !recipes.isEmpty()) {
                out.addAll(recipes);
            }
        }
        return out;
    }

    /** Whether at least one powered module has a recipe for this item. */
    public static boolean hasAny(ServerLevel level, VelocePipeNetwork network, Item item) {
        return !forItem(level, network, item).isEmpty();
    }

    /** Modules that have a machine in this network and can pay with it right now. */
    private static List<VeloceProcessingModule> activeModules(ServerLevel level,
                                                             VelocePipeNetwork network) {
        long now = level.getGameTime();
        if (network == cacheNetwork && now == cacheTick) {
            return cacheModules;
        }
        List<VeloceProcessingModule> out = new ArrayList<>();
        for (VeloceProcessingModule module : VeloceProcessingRegistry.all()) {
            if (module.available(level, network) && module.powered(level, network)) {
                out.add(module);
            }
        }
        cacheNetwork = network;
        cacheTick = now;
        cacheModules = out;
        return out;
    }

    /** Clears the memory - called on world change, just like the other caches. */
    public static void invalidate() {
        cacheNetwork = null;
        cacheTick = Long.MIN_VALUE;
        cacheModules = List.of();
    }
}
