package com.craftingveloce.block.entity;

import net.minecraft.world.item.crafting.RecipeType;

import java.util.Set;

/**
 * A module machine that is able to execute recipes from another mod.
 *
 * <p><b>Why a common interface.</b> For exactly the same reason as
 * {@link VeloceHeatSource}: the crafter has to handle many machines from many
 * mods (Create, Alchemistry, Mekanism, and then more), and without a common
 * interface it would have to know every type separately - and every new module
 * would require a change in the core. The unit is one: an <b>OPERATION</b>
 * (a single execution of a recipe), and each machine converts its own energy
 * into operations itself.
 *
 * <p><b>This is the only thing the core requires of a module.</b> A machine:
 * <ul>
 *   <li>says what RECIPE TYPES it handles ({@link #recipeTypes()}) - thanks to
 *       that the core does not have to know the family names from other mods,</li>
 *   <li>says how many operations it can still sustain
 *       ({@link #availableOperations()}) and allows them to be taken
 *       ({@link #consumeOperations(long)}),</li>
 *   <li>says whether it is powered at all ({@link #isPowered()}) - the
 *       controller distinguishes "no machine" from "machine without
 *       power".</li>
 * </ul>
 */
public interface VeloceProcessingSource {

    /** A short module identifier, e.g. {@code "mekanism:crusher"}. For logs. */
    String moduleId();

    /**
     * Recipe types that THIS machine is able to execute.
     *
     * <p>There may be more than one (e.g. a multi-purpose machine), but in
     * practice it is one type - and that is what the core uses to associate
     * a recipe with a machine.
     */
    Set<RecipeType<?>> recipeTypes();

    /** How many operations (recipe executions) this machine can still sustain. */
    long availableOperations();

    /**
     * Takes energy for {@code operations} operations.
     *
     * <p>The caller MUST first check {@link #availableOperations()}. The
     * implementation will not go below zero, but this is not the place for
     * error handling.
     */
    void consumeOperations(long operations);

    /** Whether the machine is powered at all (it has enough energy for one operation). */
    boolean isPowered();

    /**
     * Priority when choosing a machine. <b>Smaller = more important.</b>
     *
     * <p>The same approach as {@link VeloceHeatSource#heatPriority()}: when
     * several machines of the same family stand in the network, the crafter
     * picks the first from the list, and the list is deterministic.
     */
    default int processingPriority() {
        return 0;
    }

    /** A label for logs and the report (e.g. "Veloce Crusher Module"). */
    String sourceName();
    /**
     * How many machine elements are BUILT (millstones, crafter cells).
     *
     * <p>Zero by default: an ordinary machine has no component parts. The Create
     * mechanical crafter is built from cells and that is the only field its grid
     * has, which is why the planner asks for this number before running a recipe
     * with a grid.
     */
    default int availableParts() {
        return 0;
    }

}
