package com.craftingveloce.compat.alchemistry;

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
 * The Alchemistry module in the Veloce core.
 *
 * <p>The core asks this module the same things as any other one: what it can do,
 * whether its machine stands in the network, whether it has power, and what
 * recipes it has. Everything else (the planner, the number crunching, the
 * controller) knows nothing about Alchemistry.
 *
 * <p>It registers in {@code FMLCommonSetupEvent}, because the DeferredHolders of
 * recipe types are only bound after the registration events.
 */
public final class AlchemistryModule implements VeloceProcessingModule {

    /** A single instance - the module is stateless. */
    private static final AlchemistryModule INSTANCE = new AlchemistryModule();

    private static final String ID = "alchemistry";

    private AlchemistryModule() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(FMLCommonSetupEvent.class, event -> {
            VeloceProcessingRegistry.register(INSTANCE);
            com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                    "[Veloce][COMPAT] {}: machine module registered", ID);
        });
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<RecipeType<?>> recipeTypes() {
        return AlchemistryRecipeFamily.types();
    }

    /**
     * Items that the machines STANDING in the network can make.
     *
     * <p>A family without its machine contributes nothing - the compactor alone
     * does not promise fusion outputs, even if the mod is present.
     */
    @Override
    public Set<Item> producible(ServerLevel level, VelocePipeNetwork network) {
        Set<Item> out = new HashSet<>();
        for (RecipeType<?> type : recipeTypes()) {
            // Without power the machine is not available to the player, so it
            // cannot appear on the "what we can do" list (player: "if they have
            // no power, we do not want them in the network as available").
            if (!VeloceProcessingSources.hasPowered(level, network, type)) {
                continue;
            }
            out.addAll(AlchemistryRecipeHarvest.index(level, type).keySet());
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
     * <p>The per-family filter is necessary: a powered compactor does not mean
     * that fusion recipes may be used.
     */
    @Override
    public List<ProcessingEntry> recipesFor(ServerLevel level, VelocePipeNetwork network,
                                            Item item) {
        List<ProcessingEntry> out = new ArrayList<>();
        for (RecipeType<?> type : recipeTypes()) {
            if (!VeloceProcessingSources.hasPowered(level, network, type)) {
                continue;
            }
            out.addAll(AlchemistryRecipeHarvest.forItem(level, type, item));
        }
        return out;
    }

    /**
     * Recipes for the given item WITHOUT looking at machines and power.
     *
     * <p>For diagnostic tools (the getitems command): the player asks "how is
     * this made", not "can I make this now". The planner still uses recipesFor,
     * which requires a machine and power.
     */
    @Override
    public List<ProcessingEntry> recipesAnywhere(ServerLevel level, Item item) {
        List<ProcessingEntry> out = new ArrayList<>();
        for (RecipeType<?> type : recipeTypes()) {
            out.addAll(AlchemistryRecipeHarvest.forItem(level, type, item));
        }
        return out;
    }

    @Override
    public void invalidate() {
        AlchemistryRecipeHarvest.invalidate();
    }
}
