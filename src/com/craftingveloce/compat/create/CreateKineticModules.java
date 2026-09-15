package com.craftingveloce.compat.create;

import java.util.List;

/**
 * Maszyny kinetyczne Create - jeden wiersz danych na maszyne.
 *
 * <p><b>Zakres v1.</b> Cztery maszyny item -> item, ktore nie potrzebuja
 * Basenu ani ciepla:
 * <ul>
 *   <li>mlyn ({@code create:milling}),</li>
 *   <li>piła ({@code create:cutting}),</li>
 *   <li>kruszarka ({@code create:crushing}) - wyniki z prawdopodobienstwem,</li>
 *   <li>mechanical crafter ({@code create:mechanical_crafting}) - siatki
 *       wieksze niz 3x3.</li>
 * </ul>
 *
 * <p><b>Poza zakresem v1</b> (potrzebuja Basenu albo ciepla): press, mixer,
 * compacting/basin i spout. To nie sa maszyny "item -> item" z wlasnym
 * wejsciem - pracuja na zawartosci Basenu pod soba, wiec wymagaja osobnego
 * modelu (i, dla ciepla, blaze burnera).
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

    /** Mlyn: 1 item -> 1-2 wyniki (mielenie). */
    public static final KineticModule MILLING = new KineticModule(
            "create:milling", "Veloce Millstone Module",
            CreateRecipeFamily::milling, 4.0f);

    /** Piła: 1 item -> 1-2 wyniki (ciecie). */
    public static final KineticModule CUTTING = new KineticModule(
            "create:cutting", "Veloce Saw Module",
            CreateRecipeFamily::cutting, 4.0f);

    /** Kruszarka: 1 item -> wyniki z prawdopodobienstwem. */
    public static final KineticModule CRUSHING = new KineticModule(
            "create:crushing", "Veloce Crushing Module",
            CreateRecipeFamily::crushing, 8.0f);

    /** Mechanical crafter: receptury z siatka wieksza niz 3x3. */
    public static final KineticModule MECHANICAL_CRAFTING = new KineticModule(
            "create:mechanical_crafting", "Veloce Mechanical Crafter Module",
            CreateRecipeFamily::mechanicalCrafting, 2.0f);

    /** Wszystkie maszyny v1 - do rejestracji i zakladki kreatywnej. */
    public static final List<KineticModule> ALL =
            List.of(MILLING, CUTTING, CRUSHING, MECHANICAL_CRAFTING);
}
