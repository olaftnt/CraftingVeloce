package com.craftingveloce.compat.mekanism;

import com.craftingveloce.crafting.ProcessingEntry;
import com.craftingveloce.crafting.VeloceProcessingModule;
import com.craftingveloce.crafting.VeloceProcessingRegistry;
import com.craftingveloce.crafting.VeloceProcessingSources;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Mekanism module in the Veloce core.
 *
 * <p><b>This is the whole core-side integration.</b> The rest of the core
 * (planner, number counting, controller) does not know that Mekanism exists - it
 * just asks this module the same thing it asks any other:
 * <ul>
 *   <li>{@link #producible} - what I can make (only families whose machine
 *       stands in the network; otherwise a crusher without a saw would promise
 *       planks),</li>
 *   <li>{@link #available} / {@link #powered} - whether the machine stands and
 *       whether it has power,</li>
 *   <li>{@link #recipesFor} - the concrete recipes with their unit counts.</li>
 * </ul>
 *
 * <p>It registers in {@code FMLCommonSetupEvent}, because Mekanism's recipe
 * types (DeferredHolders) are bound only after the registration events.
 */
public final class MekanismModule implements VeloceProcessingModule {

    /** A single instance - the module has no state. */
    private static final MekanismModule INSTANCE = new MekanismModule();

    private MekanismModule() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(FMLCommonSetupEvent.class, event -> {
            try (var ignored = com.craftingveloce.util.VeloceProfiler.sectionAlways("load.compat.mekanism.module")) {
            VeloceProcessingRegistry.register(INSTANCE);
            com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                    "[Veloce][COMPAT] {}: machine module registered ({} machine(s) active, "
                            + "{} switched off)",
                    ID, MekanismFeModules.ALL.size(), MekanismFeModules.DISABLED.size());
            // Named one per line, because "16 disabled" is not something anyone can
            // check against the block list, and "why is there no crystallizer" is the
            // question this is here to answer.
            for (com.craftingveloce.crafting.FeModule module : MekanismFeModules.EVERY) {
                if (MekanismFeModules.isDisabled(module)) {
                    com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                            "[Veloce][COMPAT] {}: DISABLED until the network carries gases "
                                    + "and fluids - {}", ID, module.id());
                }
            }
            }
        });
    }

    private static final String ID = "mekanism";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<RecipeType<?>> recipeTypes() {
        return MekanismRecipeFamily.types();
    }

    /**
     * The items that the machines STANDING in the network can make.
     *
     * <p>A family without its own machine contributes nothing: a crusher cannot
     * saw, so sawing recipes cannot count as producible, even if the mod itself
     * is present.
     */
    @Override
    public Set<Item> producible(ServerLevel level, VelocePipeNetwork network) {
        Set<Item> out = new HashSet<>();
        for (RecipeType<?> type : recipeTypes()) {
            // Without power the machine is not available to the player, so it
            // cannot appear on the "what we can do" list (the player: "if they
            // have no power, we do not want them in the network as available").
            if (!VeloceProcessingSources.hasPowered(level, network, type)) {
                continue;
            }
            out.addAll(MekanismRecipeHarvest.index(level, type).keySet());
        }
        return out;
    }

    @Override
    public boolean available(ServerLevel level, VelocePipeNetwork network) {
        return VeloceProcessingSources.hasAnyOf(level, network, recipeTypes());
    }

    @Override
    public boolean powered(ServerLevel level, VelocePipeNetwork network) {
        return VeloceProcessingSources.hasPoweredAny(level, network, recipeTypes());
    }

    /**
     * Recipes for the given item - ONLY from families whose machine is powered.
     *
     * <p>The per-family filter is necessary here: the mere fact that the module
     * is "powered" (because somewhere there is a crusher with power) does not
     * mean that sawing recipes may be used - that requires a saw.
     */
    @Override
    public List<ProcessingEntry> recipesFor(ServerLevel level, VelocePipeNetwork network,
                                            Item item) {
        List<ProcessingEntry> out = new ArrayList<>();
        for (RecipeType<?> type : recipeTypes()) {
            if (!VeloceProcessingSources.hasPowered(level, network, type)) {
                continue;
            }
            out.addAll(MekanismRecipeHarvest.forItem(level, type, item));
        }
        return out;
    }

    /**
     * Recipes for the given item WITHOUT looking at machines and power.
     *
     * <p>For diagnostic tools (the getitems command): the player asks "how is
     * this made", not "can I make it right now". The planner still uses
     * recipesFor, which requires the machine and power.
     */
    @Override
    public List<ProcessingEntry> recipesAnywhere(ServerLevel level, Item item) {
        List<ProcessingEntry> out = new ArrayList<>();
        for (RecipeType<?> type : recipeTypes()) {
            out.addAll(MekanismRecipeHarvest.forItem(level, type, item));
        }
        return out;
    }

    @Override
    public void warmRecipeIndex(ServerLevel level) {
        for (RecipeType<?> type : recipeTypes()) {
            MekanismRecipeHarvest.index(level, type);
        }
    }

    @Override
    public void invalidate() {
        MekanismRecipeHarvest.invalidate();
    }
}
