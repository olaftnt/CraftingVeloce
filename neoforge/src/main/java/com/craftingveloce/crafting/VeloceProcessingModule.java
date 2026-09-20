package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.List;
import java.util.Set;

/**
 * ONE way of processing things in the network.
 *
 * <p><b>Why this interface exists.</b> Until now the core had two ways
 * hard-coded: the crafter (crafting recipes) and the furnace (smelting). It was
 * enough to remove the crafter from the network for a network with only a
 * furnace to stop being able to make glass from sand - even though glass is made
 * EXCLUSIVELY in a furnace and a crafter is not needed for it. The same rigidity
 * would have blocked every further module (Create, Alchemistry, Mekanism,
 * enchanting, smithing...).
 *
 * <p>Now the core knows only this interface and asks EVERY registered module the
 * same thing:
 * <ul>
 *   <li>{@link #available} - whether this module's machine stands in the network,</li>
 *   <li>{@link #powered} - whether that machine is currently able to work,</li>
 *   <li>{@link #producible} - what this module can make.</li>
 * </ul>
 *
 * <p>Adding a new module = one registration in
 * {@link VeloceProcessingRegistry} (or in {@code compat/*}) - without touching
 * the planner, the number crunching or the controller.
 */
public interface VeloceProcessingModule {

    /** Short identifier for logs and diagnostics, e.g. {@code "crafting"}, {@code "furnace"}. */
    String id();

    /** Recipe types handled by this module (a family from {@link VeloceRecipeFamilies}). */
    Set<RecipeType<?>> recipeTypes();

    /**
     * The items this module CAN make - without looking at power.
     *
     * <p>The result may depend on the network (e.g. the crafting module subtracts
     * items disabled in the crafter), which is why it receives the level and the
     * network.
     */
    Set<Item> producible(ServerLevel level, VelocePipeNetwork network);

    /** Whether this module's machine stands in the network - even without fuel/power. */
    boolean available(ServerLevel level, VelocePipeNetwork network);

    /** Whether this module's machine is currently able to perform an operation. */
    boolean powered(ServerLevel level, VelocePipeNetwork network);

    /**
     * This module's recipes that produce the given item.
     *
     * <p><b>Why.</b> The list of "what I can make" alone ({@link #producible}) is
     * not enough for the planner - it needs concrete recipes with ingredients and
     * item counts to compute how much can be made from what is in the network.
     * Modules from other mods have their own recipe models, so they are the ones
     * that translate them into the common {@link ProcessingEntry}.
     *
     * <p>Empty by default: a module that only adds ready-made recipes to the
     * common index (like the furnace) does not have to implement anything.
     *
     * <p>The module receives the network because what determines which recipes are
     * feasible are the MACHINES standing in the network (and whether they have
     * power) - and a module may handle several families at once (e.g. the crusher
     * and the saw), each with its own machine.
     */
    default List<ProcessingEntry> recipesFor(ServerLevel level, VelocePipeNetwork network,
                                             Item item) {
        return List.of();
    }

    /**
     * This module's recipes for the given item WITHOUT looking at machines and power.
     *
     * <p><b>Why a separate method.</b> {@link #recipesFor} answers the planner's
     * question: "what can I make NOW" - so it requires a machine in the network
     * and power. Tools (e.g. the {@code /cv getitems} command) ask something else:
     * "HOW is this even made" - and they must get the recipe also when the player
     * does not have the machine yet. Without that distinction the command claimed
     * that an item has no recipe, even though the recipe exists (e.g. Create's
     * mechanical crafting) - and that is the worst kind of bug: missing
     * information pretending to be information.
     */
    default List<ProcessingEntry> recipesAnywhere(ServerLevel level, Item item) {
        return List.of();
    }

    /**
     * Builds this module's recipe indexes AHEAD of the first real query.
     *
     * <p><b>Why this exists.</b> A module's index is built lazily, on the first query for it -
     * and the first query in a session is made by the terminal, on the SERVER thread, while the
     * player waits for the page. The measured cost in a 339-mod pack was not small: 365 ms in one
     * call of {@code modsThatCanMake} (which asks every module) plus 105 ms for the vanilla
     * crafting index, all of it landing on the first page after the world loads.
     *
     * <p>The indexes depend only on the recipe manager, so they can be built from any thread -
     * and the mod already has a thread for exactly this kind of work
     * ({@code VeloceCountWorker}). {@code VeloceRecipeWarmup} runs this method on it when the
     * world starts, so the first page finds the indexes warm and costs microseconds instead of
     * hundreds of milliseconds.
     *
     * <p>Nothing by default: a module without an index has nothing to warm.
     *
     * <p><b>Threading.</b> Implementations MUST be safe to call from the counting worker while
     * the server thread is reading the same index. That is not a formality - a plain HashMap
     * touched by both was a real bug here, and was fixed in the harvest classes.
     */
    default void warmRecipeIndex(ServerLevel level) {
    }

    /**
     * Clears the module's memory (recipe indexes).
     *
     * <p>Called on world change / data reload. Nothing by default - a module
     * without memory has nothing to clear. The core does not need to know any
     * module in order to call this.
     */
    default void invalidate() {
    }
}
