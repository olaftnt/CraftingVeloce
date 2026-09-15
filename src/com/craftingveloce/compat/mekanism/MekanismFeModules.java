package com.craftingveloce.compat.mekanism;

import com.craftingveloce.crafting.FeModule;

import java.util.List;

/**
 * Maszyny modulu Mekanism - jeden wiersz danych na maszyne.
 *
 * <p>Cztery maszyny itemowe Mekanism (kruszarka, wzbogacanie, laczenie,
 * pilowanie) roznia sie TYLKO typem receptury, kosztem FE i etykieta. Opis
 * maszyny to rekord {@link FeModule} z rdzenia, a nie osobna klasa - inaczej
 * powstaloby cztery kopie tej samej logiki energii.
 *
 * <p>Typ receptury jest dostawca ({@code Supplier}), bo DeferredHoldery obcego
 * moda sa wiazane dopiero po zdarzeniach rejestracji, a te stale powstaja przy
 * ladowaniu klas blokow (w konstruktorze moda).
 */
public final class MekanismFeModules {

    private MekanismFeModules() {
    }

    /**
     * Kruszarka: ruda/sztaba -> pyl. 20 FE/t * 200 t = 4000 FE.
     */
    public static final FeModule CRUSHER = new FeModule(
            "mekanism:crusher", "Veloce Crusher Module",
            4_000, 40_000, MekanismRecipeFamily::crushing);

    /**
     * Wzbogacanie: ruda -> 3 sztaby. Ten sam koszt co kruszarka.
     */
    public static final FeModule ENRICHMENT = new FeModule(
            "mekanism:enriching", "Veloce Enrichment Module",
            4_000, 40_000, MekanismRecipeFamily::enriching);

    /**
     * Laczenie: dwa itemy -> jeden (Combiner). Ten sam koszt.
     */
    public static final FeModule COMBINER = new FeModule(
            "mekanism:combining", "Veloce Combiner Module",
            4_000, 40_000, MekanismRecipeFamily::combining);

    /**
     * Pilowanie: item -> deski + losowe trociny (Precision Sawmill).
     */
    public static final FeModule SAWMILL = new FeModule(
            "mekanism:sawing", "Veloce Sawmill Module",
            4_000, 40_000, MekanismRecipeFamily::sawing);

    /** Wszystkie maszyny itemowe v1 - do rejestracji i zakladki kreatywnej. */
    public static final List<FeModule> ALL = List.of(CRUSHER, ENRICHMENT, COMBINER, SAWMILL);
}
