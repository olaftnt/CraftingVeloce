package com.craftingveloce.crafting;

import com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry of processing modules - the only place that knows WHAT the network can do.
 *
 * <p><b>Rule.</b> Whoever asks "can item X be made and how much of it" asks this
 * registry (see {@link VeloceCraftingRegistry#getAllEnabledItems}). Thanks to that:
 * <ul>
 *   <li>a network with only a furnace can smelt (and does not need a crafter for it),</li>
 *   <li>a network with only a crafter can craft,</li>
 *   <li>a new module (also from another mod) just needs to be registered - the rest
 *       of the core requires no change.</li>
 * </ul>
 *
 * <p><b>Order matters only for the logs</b> - modules do not compete with each
 * other for the result, because each one adds its own items to a common sum.
 */
public final class VeloceProcessingRegistry {

    private VeloceProcessingRegistry() {
    }

    private static final Map<String, VeloceProcessingModule> MODULES = new LinkedHashMap<>();
    private static boolean builtinsRegistered;

    /** Registers a module. A repeated registration of the same id is ignored. */
    public static synchronized void register(VeloceProcessingModule module) {
        if (module == null || module.id() == null) {
            return;
        }
        MODULES.putIfAbsent(module.id(), module);
    }

    /** Built-in core modules: crafter and furnace. */
    private static synchronized void registerBuiltins() {
        if (builtinsRegistered) {
            return;
        }
        builtinsRegistered = true;
        register(new CraftingModule());
        register(new FurnaceModule());
        register(new VeloceBrewingModule());
    }

    /** All modules (built-in + those registered by integrations). */
    public static synchronized List<VeloceProcessingModule> all() {
        registerBuiltins();
        return new ArrayList<>(MODULES.values());
    }

    /** Which module handles a given recipe type - for diagnostics and gating. */
    public static VeloceProcessingModule moduleFor(RecipeType<?> type) {
        for (VeloceProcessingModule module : all()) {
            if (module.recipeTypes().contains(type)) {
                return module;
            }
        }
        return null;
    }

    /** The ids of all registered modules - for the startup log. */
    public static List<String> ids() {
        List<String> out = new ArrayList<>();
        for (VeloceProcessingModule module : all()) {
            out.add(module.id());
        }
        return out;
    }

    /**
     * Clears the cache of ALL modules (recipe indexes).
     *
     * <p>Modules keep their own recipe indexes, so after a data reload they must
     * be cleared - but the core cannot know their types. That is why each module
     * clears itself, and the core only asks for it.
     */
    public static void invalidateAll() {
        for (VeloceProcessingModule module : all()) {
            module.invalidate();
        }
    }

    // ------------------------------------------------------------------
    // Built-in modules
    // ------------------------------------------------------------------

    /**
     * Crafter: recipes without infrastructure (crafting table, stonecutter,
     * smithing). Requires at least one crafter in the network, and its disable
     * list applies ONLY to those recipes.
     */
    private static final class CraftingModule implements VeloceProcessingModule {

        @Override
        public String id() {
            return "crafting";
        }

        @Override
        public Set<RecipeType<?>> recipeTypes() {
            return VeloceRecipeFamilies.FREE;
        }

        @Override
        public Set<Item> producible(ServerLevel level, VelocePipeNetwork network) {
            List<VeloceCraftingTableBlockEntity> crafters =
                    VeloceCraftingRegistry.crafters(level, network);
            if (crafters.isEmpty()) {
                return Set.of();
            }
            Set<Item> items = new HashSet<>(VeloceRecipeRegistry.getAllCraftableItems(level));
            // The player's disables (opt-out model) concern only these recipes -
            // which is exactly why this filter is HERE and not in the common sum
            // of modules.
            items.removeAll(crafters.get(0).getDisabledItems());
            return items;
        }

        @Override
        public boolean available(ServerLevel level, VelocePipeNetwork network) {
            return !VeloceCraftingRegistry.crafters(level, network).isEmpty();
        }

        @Override
        public boolean powered(ServerLevel level, VelocePipeNetwork network) {
            // The crafter needs neither fuel nor power - if it is there, it works.
            return available(level, network);
        }
    }

    /**
     * Furnace: smelting (smelting, blasting, smoking).
     *
     * <p><b>The BUG this fixes.</b> Previously a missing crafter in the network
     * zeroed the WHOLE list of possibilities, so a network with only a furnace
     * could not make glass from sand - even though that is an EXCLUSIVELY
     * furnace recipe. Now the furnace is a separate module and is enough on its own.
     */
    private static final class FurnaceModule implements VeloceProcessingModule {

        @Override
        public String id() {
            return "furnace";
        }

        @Override
        public Set<RecipeType<?>> recipeTypes() {
            return VeloceRecipeFamilies.FURNACE;
        }

        @Override
        public Set<Item> producible(ServerLevel level, VelocePipeNetwork network) {
            return VeloceRecipeRegistry.getAllFurnaceCraftableItems(level);
        }

        @Override
        public boolean available(ServerLevel level, VelocePipeNetwork network) {
            return VeloceHeatSources.hasAnyHeatSource(level, network);
        }

        @Override
        public boolean powered(ServerLevel level, VelocePipeNetwork network) {
            return VeloceHeatSources.hasPower(level, network);
        }
    }
}
