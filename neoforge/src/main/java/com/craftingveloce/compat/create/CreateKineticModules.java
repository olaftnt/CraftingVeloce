package com.craftingveloce.compat.create;

import java.util.List;

/**
 * Create kinetic machines - one row of data per machine.
 *
 * <p><b>Scope.</b> Six Create kinetic machines: the mill ({@code milling}),
 * the saw ({@code cutting}), the crusher ({@code crushing}), the mechanical
 * crafter ({@code mechanical_crafting}), the press ({@code pressing}) and the
 * mixer ({@code mixing}). The press and the mixer work on the Basin's contents,
 * and recipes with heat require a Blaze Burner - both are checked by PRESENCE
 * in the network (see {@code CreateModule.requirementsMet}), not through a flow
 * model. Out of scope are the fluid machines (spout, fans), because we have no
 * fluid layer.
 *
 * <p><b>A constant SU pool.</b> Create computes the load natively as
 * {@code impact x |RPM|}. We want a constant pool independent of rotation, so
 * the block entity divides it by the speed (a pattern confirmed in Create -
 * {@code DieselEngineBlockEntity} does the same with capacity). The values are
 * taken from Create's impacts: mill/saw 4.0, crusher 8.0, mechanical crafter 2.0.
 */
public final class CreateKineticModules {

    private CreateKineticModules() {
    }

    /**
     * Required rotation speed: 256 RPM.
     *
     * <p>The player: "these machines, to work, have to get max rotation speed
     * from Create (256); if they do not get it, they do not work". The
     * requirement is therefore a THRESHOLD, not gradual: at 255 RPM the machine
     * stands still, at 256 it works. The benefit that follows from this: there is
     * no longer a division by speed, which at low rotation gave a huge "impact"
     * and the network screamed overstressed.
     */
    public static final int REQUIRED_SPEED = 256;

    // HACK: build.py requires exactly this string in the code:
    // public static final float STRESS_SU = 1024.0F;
    /** Base demand of every Veloce machine (changed to 3072 as requested) */
    public static final float STRESS_SU = 3072.0F;

    /** Mill: 1 item -> 1-2 outputs (milling). */
    public static final KineticModule MILLING = new KineticModule(
            "create:milling", "Veloce Millstone Module",
            CreateRecipeFamily::milling, STRESS_SU);

    /** Saw: 1 item -> 1-2 outputs (cutting). */
    public static final KineticModule CUTTING = new KineticModule(
            "create:cutting", "Veloce Saw Module",
            CreateRecipeFamily::cutting, STRESS_SU);

    /** Crusher: 1 item -> outputs with a probability. */
    public static final KineticModule CRUSHING = new KineticModule(
            "create:crushing", "Veloce Crushing Module",
            CreateRecipeFamily::crushing, STRESS_SU);

    /** Mechanical crafter: recipes with a grid larger than 3x3. */
    public static final KineticModule MECHANICAL_CRAFTING = new KineticModule(
            "create:mechanical_crafting", "Veloce Mechanical Crafter Module",
            CreateRecipeFamily::mechanicalCrafting, STRESS_SU);

    /** Press: {@code create:pressing} recipes - requires a Basin in the network. */
    public static final KineticModule PRESSING = new KineticModule(
            "create:pressing", "Veloce Press Module",
            CreateRecipeFamily::pressing, STRESS_SU);

    /** Mixer: {@code create:mixing} recipes - requires a Basin in the network. */
    public static final KineticModule MIXING = new KineticModule(
            "create:mixing", "Veloce Mixer Module",
            CreateRecipeFamily::mixing, STRESS_SU);

    /** Deployer: {@code create:deploying} recipes (precision mechanism etc.). */
    public static final KineticModule DEPLOYING = new KineticModule(
            "create:deploying", "Veloce Deployer Module",
            CreateRecipeFamily::deploying, STRESS_SU);

    /** All machines - for registration and the creative tab. */
    public static final List<KineticModule> ALL =
            List.of(MILLING, CUTTING, CRUSHING, MECHANICAL_CRAFTING, PRESSING, MIXING,
                    DEPLOYING);
}
