package com.craftingveloce.compat.create;

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
 * The Create module in the Veloce core.
 *
 * <p>The core asks this module the same thing it asks any other: what it can do,
 * whether the machine stands in the network, whether it is powered, and what
 * recipes it has. The rest (planner, number counting, controller) does not know
 * Create.
 *
 * <p>It registers in {@code FMLCommonSetupEvent}, because Create's recipe type
 * DeferredHolders are bound only after the registration events.
 */
public final class CreateModule implements VeloceProcessingModule {

    /** A single instance - the module has no state. */
    private static final CreateModule INSTANCE = new CreateModule();

    private static final String ID = "create";

    private CreateModule() {
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
        return CreateRecipeFamily.types();
    }

    /**
     * The items that the machines STANDING in the network can make.
     *
     * <p>A family without its own machine contributes nothing - the mill alone
     * does not promise the crusher's outputs, even if Create is present.
     */
    @Override
    public Set<Item> producible(ServerLevel level, VelocePipeNetwork network) {
        Set<Item> out = new HashSet<>();
        for (RecipeType<?> type : recipeTypes()) {
            // A machine WITHOUT power is not "available" - the player cannot use
            // it, so it cannot appear on the "what we can do" list either.
            if (!VeloceProcessingSources.hasPowered(level, network, type)) {
                continue;
            }
            int side = VeloceProcessingSources.maxGridSide(level, network, type);
            int parts = VeloceProcessingSources.maxParts(level, network, type);
            CreateRecipeHarvest.index(level, type).forEach((item, entries) -> {
                for (ProcessingEntry entry : entries) {
                    if (entry.fitsGrid(side, parts)
                            && requirementsMet(level, network, type, entry)) {
                        out.add(item);
                        return;
                    }
                }
            });
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
     * Recipes for the given item - ONLY from families whose machine is POWERED.
     *
     * <p>The per-family filter is necessary: a spinning mill does not mean that
     * the crusher's recipes may be used.
     */
    @Override
    public List<ProcessingEntry> recipesFor(ServerLevel level, VelocePipeNetwork network,
                                            Item item) {
        List<ProcessingEntry> out = new ArrayList<>();
        for (RecipeType<?> type : recipeTypes()) {
            if (!VeloceProcessingSources.hasPowered(level, network, type)) {
                continue;
            }
            // A recipe with a grid is included only when the machine has enough
            // built cells (the mechanical crafter is built out of cells).
            int side = VeloceProcessingSources.maxGridSide(level, network, type);
            int parts = VeloceProcessingSources.maxParts(level, network, type);
            for (ProcessingEntry entry : CreateRecipeHarvest.forItem(level, type, item)) {
                if (entry.fitsGrid(side, parts) && requirementsMet(level, network, type, entry)) {
                    out.add(entry);
                }
            }
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
            out.addAll(CreateRecipeHarvest.forItem(level, type, item));
        }
        return out;
    }

    /**
     * Additional recipe requirements: heat and the Basin.
     *
     * <p>The player described it outright: "if something needs a mixer, then
     * there is a mixer; if there is only a basin in any inventory, then we count
     * the basin as satisfied, and if it has to be a heated blaze burner, then we
     * also count that as satisfied as long as we have a blaze burner". That is
     * why we do NOT build a flow model of the network - we ask only about the
     * presence of the item in the network.
     */
    private static boolean requirementsMet(ServerLevel level, VelocePipeNetwork network,
                                           RecipeType<?> type, ProcessingEntry entry) {
        if (entry.requiresHeat() && !hasItem(level, network, "blaze_burner")) {
            return false;
        }
        if (needsBasin(type) && !hasItem(level, network, "basin")) {
            return false;
        }
        return true;
    }

    /**
     * The press, the mixer and compacting work on the Basin's contents - without a Basin
     * there is nothing to mix or to compact.
     */
    private static boolean needsBasin(RecipeType<?> type) {
        return type == CreateRecipeFamily.pressing()
                || type == CreateRecipeFamily.mixing()
                || type == CreateRecipeFamily.compacting();
    }

    /** Whether the network has a Create item (in storage or in the crafter's buffer). */
    private static boolean hasItem(ServerLevel level, VelocePipeNetwork network, String path) {
        Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", path));
        return item != net.minecraft.world.item.Items.AIR
                && network.getAllItemCounts(level).containsKey(item);
    }

    @Override
    public void invalidate() {
        CreateRecipeHarvest.invalidate();
    }
}
