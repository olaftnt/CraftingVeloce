package com.craftingveloce.block;

/**
 * A machine that has MOVING elements in its casing (mill wheels, crafter eyes).
 *
 * <p><b>Why an interface in the core.</b> The casing renderer runs in the core
 * and cannot know Create's types - and it is exactly Create's kinetic machine
 * that knows at what speed it is spinning. So the core asks for two numbers, and
 * whoever knows them implements them (see {@code VeloceKineticModuleBlockEntity}
 * in compat/create).
 *
 * <p>Without this the renderer would either have to know Create (a broken
 * isolation: the mod would not start without Create), or draw the elements
 * motionless - that is, lie.
 */
public interface VeloceCaseSpin {

    /** How many elements to show in the casing (0 = none, the renderer draws the plain contents). */
    int caseParts();

    /**
     * The rotation speed of the elements in degrees per tick.
     *
     * <p>Zero means "stopped" - the elements are then motionless, but still
     * visible (a machine that is built, but not powered).
     */
    float caseSpinDegreesPerTick();

    /**
     * How many columns the element layout in the casing has.
     *
     * <p>Mill wheels stand next to each other (columns = the number of wheels),
     * and the crafter's eyes grow in a column: 1x2, 1x3, ... up to 9x9.
     */
    int caseGridColumns();

    /** How many rows the element layout in the casing has (see {@link #caseGridColumns()}). */
    int caseGridRows();

    /**
     * Whether this machine is BUILT from elements (wheels, eyes).
     *
     * <p>It distinguishes two situations that look the same when the counter is
     * zero: a crusher without wheels is supposed to be an EMPTY casing, while a
     * mill without wheels (because it does not need them) is supposed to show
     * its base block.
     */
    boolean caseBuiltFromParts();

    /**
     * Whether the elements spin EACH around itself (mill wheels), or the whole
     * layout together, around the centre of the casing (the crafter's eyes, any
     * other machine).
     *
     * <p>Player: "the crafters spin like a beyblade - that is not it, they are
     * supposed to all spin together around the centre of their own axis, just
     * like any other render, e.g. crafting or furnace".
     */
    boolean casePartsSpinIndividually();
}
