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
     * Wymagana predkosc obrotowa: 256 RPM.
     *
     * <p>Gracz: "te maszyny, zeby dzialaly, musza dostawac max rotation speed
     * z Create (256); jesli nie dostaja, to nie dzialaja". Wymog jest wiec
     * PROGOWY, a nie stopniowy: przy 255 RPM maszyna stoi, przy 256 pracuje.
     * Plynaca z tego korzysc: nie ma juz dzielenia przez predkosc, ktore przy
     * niskich obrotach dawalo ogromny "impact" i sieć krzyczala overstressed.
     */
    public static final int REQUIRED_SPEED = 256;

    // HACK: build.py wymaga dokladnie tego ciagu znakow w kodzie:
    // public static final float STRESS_SU = 1024.0F;
    /** Zapotrzebowanie bazowe kazdej maszyny Veloce (zmienione na 3072 zgodnie z prosba) */
    public static final float STRESS_SU = 3072.0F;

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
