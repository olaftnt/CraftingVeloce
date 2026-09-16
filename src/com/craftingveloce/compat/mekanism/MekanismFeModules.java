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

    public static final FeModule SMELTING = new FeModule(
            "mekanism:smelting", "Veloce Energized Smelter Module",
            200_000, 25_000_000, MekanismRecipeFamily::smelting);

    public static final FeModule COMPRESSING = new FeModule(
            "mekanism:compressing", "Veloce Osmium Compressor Module",
            200_000, 25_000_000, MekanismRecipeFamily::compressing);

    public static final FeModule METALLURGIC_INFUSING = new FeModule(
            "mekanism:metallurgic_infusing", "Veloce Metallurgic Infuser Module",
            200_000, 25_000_000, MekanismRecipeFamily::metallurgic_infusing);

    public static final FeModule PURIFYING = new FeModule(
            "mekanism:purifying", "Veloce Purification Chamber Module",
            200_000, 25_000_000, MekanismRecipeFamily::purifying);

    public static final FeModule INJECTING = new FeModule(
            "mekanism:injecting", "Veloce Chemical Injection Chamber Module",
            200_000, 25_000_000, MekanismRecipeFamily::injecting);

    public static final FeModule CRYSTALLIZING = new FeModule(
            "mekanism:crystallizing", "Veloce Chemical Crystallizer Module",
            200_000, 25_000_000, MekanismRecipeFamily::crystallizing);

    public static final FeModule DISSOLUTION = new FeModule(
            "mekanism:dissolution", "Veloce Chemical Dissolution Chamber Module",
            200_000, 25_000_000, MekanismRecipeFamily::dissolution);

    public static final FeModule WASHING = new FeModule(
            "mekanism:washing", "Veloce Chemical Washer Module",
            200_000, 25_000_000, MekanismRecipeFamily::washing);

    public static final FeModule SEPARATING = new FeModule(
            "mekanism:separating", "Veloce Electrolytic Separator Module",
            200_000, 25_000_000, MekanismRecipeFamily::separating);

    public static final FeModule REACTION = new FeModule(
            "mekanism:reaction", "Veloce Pressurized Reaction Chamber Module",
            200_000, 25_000_000, MekanismRecipeFamily::reaction);

    public static final FeModule ROTARY = new FeModule(
            "mekanism:rotary", "Veloce Rotary Condensentrator Module",
            200_000, 25_000_000, MekanismRecipeFamily::rotary);

    public static final FeModule ACTIVATING = new FeModule(
            "mekanism:activating", "Veloce Solar Neutron Activator Module",
            200_000, 25_000_000, MekanismRecipeFamily::activating);

    public static final FeModule CENTRIFUGING = new FeModule(
            "mekanism:centrifuging", "Veloce Isotopic Centrifuge Module",
            200_000, 25_000_000, MekanismRecipeFamily::centrifuging);

    public static final FeModule NUCLEOSYNTHESIZING = new FeModule(
            "mekanism:nucleosynthesizing", "Veloce Antiprotonic Nucleosynthesizer Module",
            200_000, 25_000_000, MekanismRecipeFamily::nucleosynthesizing);

    public static final FeModule PIGMENT_EXTRACTING = new FeModule(
            "mekanism:pigment_extracting", "Veloce Pigment Extractor Module",
            200_000, 25_000_000, MekanismRecipeFamily::pigment_extracting);

    public static final FeModule PIGMENT_MIXING = new FeModule(
            "mekanism:pigment_mixing", "Veloce Pigment Mixer Module",
            200_000, 25_000_000, MekanismRecipeFamily::pigment_mixing);

    public static final FeModule PAINTING = new FeModule(
            "mekanism:painting", "Veloce Painting Machine Module",
            200_000, 25_000_000, MekanismRecipeFamily::painting);

    public static final FeModule OXIDIZING = new FeModule(
            "mekanism:oxidizing", "Veloce Chemical Oxidizer Module",
            200_000, 25_000_000, MekanismRecipeFamily::oxidizing);

    public static final FeModule CHEMICAL_INFUSING = new FeModule(
            "mekanism:chemical_infusing", "Veloce Chemical Infuser Module",
            200_000, 25_000_000, MekanismRecipeFamily::chemical_infusing);


    /** Wszystkie maszyny itemowe v1 - do rejestracji i zakladki kreatywnej. */
    public static final List<FeModule> ALL = List.of(CRUSHER, ENRICHMENT, COMBINER, SAWMILL, SMELTING, COMPRESSING, METALLURGIC_INFUSING, PURIFYING, INJECTING, CRYSTALLIZING, DISSOLUTION, WASHING, SEPARATING, REACTION, ROTARY, ACTIVATING, CENTRIFUGING, NUCLEOSYNTHESIZING, PIGMENT_EXTRACTING, PIGMENT_MIXING, PAINTING, OXIDIZING, CHEMICAL_INFUSING);
}
