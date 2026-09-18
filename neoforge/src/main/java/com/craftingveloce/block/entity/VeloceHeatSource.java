package com.craftingveloce.block.entity;

/**
 * The "instant smelting" source for the auto-crafter.
 *
 * <p><b>Why a shared interface.</b> The crafter has to handle TWO kinds of
 * furnaces (fuel-powered and electric) and prioritize the electric one. Without a
 * shared interface it would have to know both types and their units (burn ticks
 * vs FE), and every new furnace would require changes in the crafter.
 *
 * <p>There is one unit: an <b>OPERATION</b> - one instant smelt.
 * Each furnace converts its own energy into operations by itself:
 * <ul>
 *   <li>fuel-powered: {@code accumulatorFe / FE_PER_SMELT} - the accumulator is
 *       filled by burning fuel, one FE per burn tick</li>
 *   <li>electric: {@code storedFe / FE_PER_SMELT}</li>
 * </ul>
 * Thanks to that the crafter does not know, and does not need to know, what
 * powers the furnace.
 *
 * <p><b>An "operation" is a WHOLE smelt, never a fraction of one.</b> A source that
 * cannot pay for one full operation reports {@code availableOperations() == 0} and
 * {@link #isPowered()} {@code == false} - so it counts as ABSENT rather than as a
 * failure. That is what lets a network hold several furnaces and keep crafting
 * while one of them runs dry: the dry one is skipped, and the next one pays.
 */
public interface VeloceHeatSource {

    /**
     * How many instant smelts the furnace can still handle.
     *
     * <p>Zero means "not powered" - the crafter must then NOT use the furnace's
     * recipes for that source.
     */
    long availableOperations();

    /**
     * Takes energy for {@code operations} smelts.
     *
     * <p>The caller MUST check {@link #availableOperations()} first.
     * The implementation will not go below zero anyway, but this is not the place
     * for error checking.
     */
    void consumeOperations(long operations);

    /** Whether the furnace is powered at all (it is burning / it has FE). */
    boolean isPowered();

    /**
     * Priority when choosing a source. <b>Smaller = more important.</b>
     *
     * <p>The crafter takes the source with the lowest priority, so the electric
     * furnace (0) beats the fuel-powered one (1) - in line with the agreement that
     * the electric one is to be used first and the fuel-powered one is the fallback.
     */
    int heatPriority();

    /** Label for logs and reports (e.g. "Velocity Furnace"). */
    String heatSourceName();
}
