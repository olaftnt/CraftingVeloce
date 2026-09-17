package com.craftingveloce.block;

/**
 * A casing that the player BUILDS element by element.
 *
 * <p><b>How it works in the game.</b> The player places an empty casing
 * ({@code veloce_integrale}), and then adds base blocks to it: one millstone per
 * click, one mechanical crafter grid per click. The first click REPLACES the
 * casing with the machine (see {@code VeloceIntegraleConversions}), and every
 * next one adds another element - which is why the machine is empty until the
 * player puts something in.
 *
 * <p>Without this interface, a machine placed from the creative tab would show a
 * finished block inside (and the player: "when I place it from creative, it is
 * just one block render, like ordinary crafting - that is a bug").
 */
public interface VeloceCaseBuildable {

    /**
     * Adds one element to the interior of the casing.
     *
     * @return {@code true} when the element fit (then the caller takes the
     *         item from the player's hand)
     */
    boolean addPart();
}
