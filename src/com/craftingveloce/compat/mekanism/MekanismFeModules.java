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
     * Kruszarka: ruda/sztaba -> pyl.
     *
     * <p><b>Koszt jak w elektrycznym piecu.</b> Piecyk bierze 200 000 FE za
     * przepalenie i ma 25 000 000 FE bufora, wiec moduły maja te sama skale:
     * koszt jednej operacji to 200 000 FE (dawniej 4 000 - bylo praktycznie
     * darmowe), a akumulator 25 000 000 FE.
     */
    public static final FeModule CRUSHER = new FeModule(
            "mekanism:crusher", "Veloce Crusher Module",
            200_000, 25_000_000, MekanismRecipeFamily::crushing);

    /**
     * Wzbogacanie: ruda -> 3 sztaby. Ten sam koszt co kruszarka.
     */
    public static final FeModule ENRICHMENT = new FeModule(
            "mekanism:enriching", "Veloce Enrichment Module",
            200_000, 25_000_000, MekanismRecipeFamily::enriching);

    /**
     * Laczenie: dwa itemy -> jeden (Combiner). Ten sam koszt.
     */
    public static final FeModule COMBINER = new FeModule(
            "mekanism:combining", "Veloce Combiner Module",
            200_000, 25_000_000, MekanismRecipeFamily::combining);

    /**
     * Pilowanie: item -> deski + losowe trociny (Precision Sawmill).
     */
    public static final FeModule SAWMILL = new FeModule(
            "mekanism:sawing", "Veloce Sawmill Module",
            200_000, 25_000_000, MekanismRecipeFamily::sawing);

    /** Wszystkie maszyny itemowe v1 - do rejestracji i zakladki kreatywnej. */
    public static final List<FeModule> ALL = List.of(CRUSHER, ENRICHMENT, COMBINER, SAWMILL);
}
