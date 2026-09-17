package com.craftingveloce.compat.alchemistry;

import com.craftingveloce.crafting.FeModule;

import java.util.List;

/**
 * Alchemistry module machines - one row of data per machine.
 *
 * <p>Four item machines (compactor, combiner, fission, fusion) differ
 * only in the recipe type, the FE cost and the label, so they use the same core
 * machine ({@code VeloceFeModuleBlock} + {@code VeloceFeModuleBlockEntity}).
 *
 * <p>The <b>costs</b> are copied from the Alchemistry default values
 * ({@code Config.COMMON.<machine>EnergyPerTick} x {@code TicksPerOperation}):
 * <ul>
 *   <li>compactor: 50 FE/t * 50 t = 2 500 FE,</li>
 *   <li>combiner: 200 FE/t * 50 t = 10 000 FE,</li>
 *   <li>fission and fusion: 300 FE/t * 50 t = 15 000 FE.</li>
 * </ul>
 * A buffer of 100 000 FE - that is what every Alchemistry machine has.
 *
 * <p><b>Recipe types as a supplier</b> - DeferredHolders of a foreign mod are
 * bound only after the registration events, and these constants are created in the
 * mod constructor.
 *
 * <p><b>Cost as in an electric furnace.</b> A buffer of 25 000 000 FE and a per-operation
 * cost scaled x50 (reference point: a small furnace 200 000 FE per
 * smelting operation) - compactor 125 000, combiner 500 000, fission/fusion 750 000 FE.
 *
 * <p><b>What is not here.</b> The Dissolver is probabilistic (ProbabilitySet),
 * and the Liquifier/Atomizer work on a liquid chemical - both require a separate
 * policy and a fluid layer (later stages).
 */
public final class AlchemistryFeModules {

    private AlchemistryFeModules() {
    }

    /** Compactor: 1 item (x count) -> 1 item. */
    public static final FeModule COMPACTOR = new FeModule(
            "alchemistry:compactor", "Veloce Compactor Module",
            125_000, 25_000_000, AlchemistryRecipeFamily::compactor);

    /** Combiner: N items (each with its own count) -> 1 item. */
    public static final FeModule COMBINER = new FeModule(
            "alchemistry:combiner", "Veloce Alchemistry Combiner Module",
            500_000, 25_000_000, AlchemistryRecipeFamily::combiner);

    /** Fission: 1 item -> 2 items. */
    public static final FeModule FISSION = new FeModule(
            "alchemistry:fission", "Veloce Fission Module",
            750_000, 25_000_000, AlchemistryRecipeFamily::fission);

    public static final FeModule FUSION = new FeModule(
            "alchemistry:fusion", "Veloce Fusion Module",
            750_000, 25_000_000, AlchemistryRecipeFamily::fusion);

    public static final FeModule DISSOLVER = new FeModule(
            "alchemistry:dissolver", "Veloce Dissolver Module",
            250_000, 25_000_000, AlchemistryRecipeFamily::dissolver);

    public static final FeModule LIQUIFIER = new FeModule(
            "alchemistry:liquifier", "Veloce Liquifier Module",
            250_000, 25_000_000, AlchemistryRecipeFamily::liquifier);

    public static final FeModule ATOMIZER = new FeModule(
            "alchemistry:atomizer", "Veloce Atomizer Module",
            250_000, 25_000_000, AlchemistryRecipeFamily::atomizer);

    /** All v1 item machines - for registration and the creative tab. */
    public static final List<FeModule> ALL = List.of(COMPACTOR, COMBINER, FISSION, FUSION, DISSOLVER, LIQUIFIER, ATOMIZER);
}
