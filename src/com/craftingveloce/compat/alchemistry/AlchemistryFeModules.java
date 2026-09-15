package com.craftingveloce.compat.alchemistry;

import com.craftingveloce.crafting.FeModule;

import java.util.List;

/**
 * Maszyny modulu Alchemistry - jeden wiersz danych na maszyne.
 *
 * <p>Cztery maszyny itemowe (compactor, combiner, fission, fusion) roznia sie
 * tylko typem receptury, kosztem FE i etykieta, wiec korzystaja z tej samej
 * maszyny rdzenia ({@code VeloceFeModuleBlock} + {@code VeloceFeModuleBlockEntity}).
 *
 * <p><b>Koszty</b> sa przepisane z domyslnych wartosci Alchemistry
 * ({@code Config.COMMON.<maszyna>EnergyPerTick} x {@code TicksPerOperation}):
 * <ul>
 *   <li>compactor: 50 FE/t * 50 t = 2 500 FE,</li>
 *   <li>combiner: 200 FE/t * 50 t = 10 000 FE,</li>
 *   <li>fission i fusion: 300 FE/t * 50 t = 15 000 FE.</li>
 * </ul>
 * Bufor 100 000 FE - tyle ma kazda maszyna Alchemistry.
 *
 * <p><b>Typy receptur jako dostawca</b> - DeferredHoldery obcego moda sa
 * wiazane dopiero po zdarzeniach rejestracji, a te stale powstaja w
 * konstruktorze moda.
 *
 * <p><b>Czego tu nie ma.</b> Dissolver jest probabilistyczny (ProbabilitySet),
 * a Liquifier/Atomizer pracuja na plynnym chemicznym - oba wymagaja osobnej
 * polityki i warstwy plynow (pozniejsze etapy).
 */
public final class AlchemistryFeModules {

    private AlchemistryFeModules() {
    }

    /** Compactor: 1 item (x count) -> 1 item. */
    public static final FeModule COMPACTOR = new FeModule(
            "alchemistry:compactor", "Veloce Compactor Module",
            2_500, 100_000, AlchemistryRecipeFamily::compactor);

    /** Combiner: N itemow (kazdy z wlasnym count) -> 1 item. */
    public static final FeModule COMBINER = new FeModule(
            "alchemistry:combiner", "Veloce Alchemistry Combiner Module",
            10_000, 100_000, AlchemistryRecipeFamily::combiner);

    /** Fission: 1 item -> 2 itemy. */
    public static final FeModule FISSION = new FeModule(
            "alchemistry:fission", "Veloce Fission Module",
            15_000, 100_000, AlchemistryRecipeFamily::fission);

    /** Fusion: 2 itemy -> 1 item. */
    public static final FeModule FUSION = new FeModule(
            "alchemistry:fusion", "Veloce Fusion Module",
            15_000, 100_000, AlchemistryRecipeFamily::fusion);

    /** Wszystkie maszyny itemowe v1 - do rejestracji i zakladki kreatywnej. */
    public static final List<FeModule> ALL = List.of(COMPACTOR, COMBINER, FISSION, FUSION);
}
