package com.craftingveloce.compat.create;

import java.util.List;
import java.util.Set;

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

    /**
     * Stress every Veloce kinetic machine draws, at the required speed.
     *
     * <p><b>The number the player sees.</b> Create computes the load as
     * {@code impact x |RPM|}, and {@code calculateStressApplied} answers
     * {@code STRESS_SU / REQUIRED_SPEED} - so at the required 256 RPM the machine draws
     * exactly this many SU. One number for every machine, and it does not depend on how
     * many crushing wheels or crafters the casing holds: a pair of wheels is one machine
     * here, not two mills.
     *
     * <p>It was 3072 (12 impact x 256 RPM), which read as "this one machine costs as much
     * as three of Create's". 1024 = 4 impact x 256 RPM, the same as a Create millstone.
     */
    public static final float STRESS_SU = 1024.0F;

    /** Mill: 1 item -> 1-2 outputs (milling). */
    public static final KineticModule MILLING = new KineticModule(
            "create:milling", "Veloce Millstone Module",
            () -> Set.of(CreateRecipeFamily.milling()), STRESS_SU);

    /** Saw: 1 item -> 1-2 outputs (cutting). */
    public static final KineticModule CUTTING = new KineticModule(
            "create:cutting", "Veloce Saw Module",
            () -> Set.of(CreateRecipeFamily.cutting()), STRESS_SU);

    /** Crusher: 1 item -> outputs with a probability. */
    public static final KineticModule CRUSHING = new KineticModule(
            "create:crushing", "Veloce Crushing Module",
            () -> Set.of(CreateRecipeFamily.crushing()), STRESS_SU);

    /** Mechanical crafter: recipes with a grid larger than 3x3. */
    public static final KineticModule MECHANICAL_CRAFTING = new KineticModule(
            "create:mechanical_crafting", "Veloce Mechanical Crafter Module",
            () -> Set.of(CreateRecipeFamily.mechanicalCrafting()), STRESS_SU);

    /**
     * Press: {@code create:pressing} on a belt AND {@code create:compacting} over a Basin.
     *
     * <p>Two types, one machine - the same shape as the Deployer. A press with a Basin
     * under it compacts; the network looks machines up BY TYPE, so both have to be
     * declared or the basin half of the press is invisible.
     */
    public static final KineticModule PRESSING = new KineticModule(
            "create:pressing", "Veloce Press Module",
            () -> Set.of(CreateRecipeFamily.pressing(), CreateRecipeFamily.compacting()),
            STRESS_SU);

    /** Mixer: {@code create:mixing} recipes - requires a Basin in the network. */
    public static final KineticModule MIXING = new KineticModule(
            "create:mixing", "Veloce Mixer Module",
            () -> Set.of(CreateRecipeFamily.mixing()), STRESS_SU);

    /**
     * Deployer: {@code create:deploying}, {@code create:item_application} AND
     * {@code create:sandpaper_polishing}.
     *
     * <p>Two types on one machine, and that is Create's own shape rather than a
     * convenience: Create registers the Deployer as the catalyst for both of its deployer
     * JEI categories. The second type is where every CASING comes from, so without it a
     * network with a Deployer in it could not make a single casing - which is what the
     * player reported, with JEI plainly showing the machine they had.
     */
    public static final KineticModule DEPLOYING = new KineticModule(
            "create:deploying", "Veloce Deployer Module",
            () -> Set.of(CreateRecipeFamily.deploying(),
                    CreateRecipeFamily.itemApplication(),
                    CreateRecipeFamily.sandpaperPolishing()), STRESS_SU);

    /** All machines - for registration and the creative tab. */
    public static final List<KineticModule> ALL =
            List.of(MILLING, CUTTING, CRUSHING, MECHANICAL_CRAFTING, PRESSING, MIXING,
                    DEPLOYING);
}
