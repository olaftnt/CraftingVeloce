package com.craftingveloce.compat.mekanism;

import com.craftingveloce.crafting.FeModule;

import java.util.List;

/**
 * Mekanism module machines - one row of data per machine.
 *
 * <p>The four Mekanism item machines (crusher, enrichment, combining,
 * sawing) differ ONLY in recipe type, FE cost and label. A machine description
 * is the {@link FeModule} record from the core, not a separate class - otherwise
 * there would be four copies of the same energy logic.
 *
 * <p>The recipe type is a supplier ({@code Supplier}), because DeferredHolders of
 * a foreign mod are bound only after the registration events, and these constants
 * are created while the block classes load (in the mod constructor).
 */
public final class MekanismFeModules {

    private MekanismFeModules() {
    }

    /**
     * Crusher: ore/ingot -> dust.
     *
     * <p><b>Cost as in the electric furnace.</b> The furnace takes 200 000 FE per
     * smelt and has a 25 000 000 FE buffer, so the modules have the same scale:
     * the cost of one operation is 200 000 FE (formerly 4 000 - it was practically
     * free), and the accumulator is 25 000 000 FE.
     */
    public static final FeModule CRUSHER = new FeModule(
            "mekanism:crusher", "Veloce Crusher Module",
            200_000, 25_000_000, MekanismRecipeFamily::crushing);

    /**
     * Enrichment: ore -> 3 ingots. The same cost as the crusher.
     */
    public static final FeModule ENRICHMENT = new FeModule(
            "mekanism:enriching", "Veloce Enrichment Module",
            200_000, 25_000_000, MekanismRecipeFamily::enriching);

    /**
     * Combining: two items -> one (Combiner). The same cost.
     */
    public static final FeModule COMBINER = new FeModule(
            "mekanism:combining", "Veloce Combiner Module",
            200_000, 25_000_000, MekanismRecipeFamily::combining);

    /**
     * Sawing: item -> planks + random sawdust (Precision Sawmill).
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


    /** All v1 item machines - for registration and the creative tab. */
    public static final List<FeModule> ALL = List.of(CRUSHER, ENRICHMENT, COMBINER, SAWMILL, SMELTING, COMPRESSING, METALLURGIC_INFUSING, PURIFYING, INJECTING, CRYSTALLIZING, DISSOLUTION, WASHING, SEPARATING, REACTION, ROTARY, ACTIVATING, CENTRIFUGING, NUCLEOSYNTHESIZING, PIGMENT_EXTRACTING, PIGMENT_MIXING, PAINTING, OXIDIZING, CHEMICAL_INFUSING);
}
