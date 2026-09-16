package com.craftingveloce.compat.create;

import java.util.List;

/**
 * Maszyny kinetyczne Create - jeden wiersz danych na maszyne.
 *
 * <p><b>Zakres.</b> Szesc maszyn kinetycznych Create: mlyn ({@code milling}),
 * pila ({@code cutting}), kruszarka ({@code crushing}), mechanical crafter
 * ({@code mechanical_crafting}), prasa ({@code pressing}) i mixer
 * ({@code mixing}). Prasa i mixer pracuja na zawartosci Basenu, a receptury
 * z cieplem wymagaja Blaze Burnera - jedno i drugie jest sprawdzane po
 * OBECNOSCI w sieci (patrz {@code CreateModule.requirementsMet}), a nie przez
 * model przeplywow. Poza zakresem sa maszyny na plynnym (spout, fany), bo nie
 * mamy warstwy plynow.
 *
 * <p><b>Stala pula SU.</b> Create liczy obciazenie natywnie jako
 * {@code impact x |RPM|}. My chcemy stala pule niezalezna od obrotow, wiec
 * block entity dzieli ja przez predkosc (wzorzec potwierdzony w Create -
 * {@code DieselEngineBlockEntity} robi tak samo z capacity). Wartosci wziete
 * z impactow Create: mlyn/piła 4.0, kruszarka 8.0, mechanical crafter 2.0.
 */
public final class CreateKineticModules {

    private CreateKineticModules() {
    }

    /**
     * Bazowe zapotrzebowanie KAZDEGO modulu: 2048 SU przy 256 RPM.
     *
     * <p><b>Staly pobor calkowity, nie staly "impact".</b> Create liczy pobor
     * sieci jako {@code impact x |RPM|}, wiec zeby CALKOWITE zapotrzebowanie
     * bylo stale niezaleznie od predkosci, block entity dzieli te liczbe przez
     * predkosc ({@code VeloceKineticModuleBlockEntity.calculateStressApplied}).
     * Dzieki temu:
     * <ul>
     *   <li>przy 256 RPM impact = 8, czyli 8 x 256 = 2048 SU,</li>
     *   <li>przy 64 RPM impact = 32, czyli 32 x 64 = 2048 SU,</li>
     *   <li>przy 512 RPM impact = 4, czyli 4 x 512 = 2048 SU.</li>
     * </ul>
     * Bilans jest wiec identyczny przy kazdym przelozeniu - przekladnie nie
     * daja darmowej mocy ani nie powoduja strat. Cztery Large Water Wheele
     * (4 x 512 SU) pokrywaja dokladnie jeden modul.
     */
    public static final float STRESS_SU = 2048.0F;

    /** Mlyn: 1 item -> 1-2 wyniki (mielenie). */
    public static final KineticModule MILLING = new KineticModule(
            "create:milling", "Veloce Millstone Module",
            CreateRecipeFamily::milling, STRESS_SU);

    /** Piła: 1 item -> 1-2 wyniki (ciecie). */
    public static final KineticModule CUTTING = new KineticModule(
            "create:cutting", "Veloce Saw Module",
            CreateRecipeFamily::cutting, STRESS_SU);

    /** Kruszarka: 1 item -> wyniki z prawdopodobienstwem. */
    public static final KineticModule CRUSHING = new KineticModule(
            "create:crushing", "Veloce Crushing Module",
            CreateRecipeFamily::crushing, STRESS_SU);

    /** Mechanical crafter: receptury z siatka wieksza niz 3x3. */
    public static final KineticModule MECHANICAL_CRAFTING = new KineticModule(
            "create:mechanical_crafting", "Veloce Mechanical Crafter Module",
            CreateRecipeFamily::mechanicalCrafting, STRESS_SU);

    /** Prasa: receptury {@code create:pressing} - wymaga Basenu w sieci. */
    public static final KineticModule PRESSING = new KineticModule(
            "create:pressing", "Veloce Press Module",
            CreateRecipeFamily::pressing, STRESS_SU);

    /** Mixer: receptury {@code create:mixing} - wymaga Basenu w sieci. */
    public static final KineticModule MIXING = new KineticModule(
            "create:mixing", "Veloce Mixer Module",
            CreateRecipeFamily::mixing, STRESS_SU);

    /** Deployer: receptury {@code create:deploying} (precision mechanism itd.). */
    public static final KineticModule DEPLOYING = new KineticModule(
            "create:deploying", "Veloce Deployer Module",
            CreateRecipeFamily::deploying, STRESS_SU);

    /** Wszystkie maszyny - do rejestracji i zakladki kreatywnej. */
    public static final List<KineticModule> ALL =
            List.of(MILLING, CUTTING, CRUSHING, MECHANICAL_CRAFTING, PRESSING, MIXING,
                    DEPLOYING);
}
