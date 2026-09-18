package com.craftingveloce.block;

/**
 * A machine that a ROTATIONAL network drives, described in the three numbers a test
 * needs in order to prove that it is really taking power.
 *
 * <p><b>Why an interface in the core.</b> The same reason as {@link VeloceCaseSpin}: the
 * command that measures this runs in the core, and the class that knows the answers is
 * Create's {@code KineticBlockEntity}, which lives in {@code compat/create} and must not
 * be named outside it - the mod has to start with Create absent. So the core asks the
 * questions and {@code compat/create} answers them, and nothing in the command has to
 * know that Create exists.
 *
 * <p><b>Why the raw numbers and not the machine's status text.</b>
 * {@code VeloceModuleInfoSource} answers "what does a player need to know", which is a
 * sentence for a tooltip. A test cannot use that: a tooltip saying "not enough force" is
 * equally consistent with a machine that is correctly starved and with one that was never
 * connected to anything at all. Only the speed itself separates those two.
 */
public interface VeloceKineticInfo {

    /**
     * The speed of the network driving this machine, in RPM.
     *
     * <p><b>Zero is the value worth testing for.</b> It means the machine is placed and
     * built but is not attached to a turning network - which is the exact state a freshly
     * placed module sat in for as long as the placement path failed to attach it to the
     * rotation network it was plainly bolted to.
     */
    float kineticRpm();

    /**
     * Create's stress impact for this machine: SU PER RPM.
     *
     * <p>Create computes a network's load as {@code impact x |RPM|}, so this is the rate
     * and not the total. An impact of {@code 4.0} costs 1024 SU once the network turns at
     * the required 256 RPM, and 1024 SU at every other speed too, because the two factors
     * cancel.
     */
    float kineticStressImpact();

    /**
     * Whether a rotation source is attached at all.
     *
     * <p>Different from a speed of zero: a machine that is attached to a network which is
     * merely standing still has a source and no speed, while a machine in the middle of
     * nowhere has neither.
     */
    boolean kineticHasSource();
}
