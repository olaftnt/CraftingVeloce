package com.craftingveloce.block.entity;

import net.minecraft.world.item.crafting.RecipeType;

import java.util.Set;

/**
 * Maszyna modulu, ktora potrafi wykonac receptury z innego moda.
 *
 * <p><b>Po co wspolny interfejs.</b> Dokladnie z tego samego powodu, co
 * {@link VeloceHeatSource}: crafter ma obslugiwac wiele maszyn z wielu modow
 * (Create, Alchemistry, Mekanism, a potem kolejne), a bez wspolnego interfejsu
 * musialby znac kazdy typ z osobna - i kazdy nowy modul wymagalby zmiany
 * w rdzeniu. Jednostka jest jedna: <b>OPERACJA</b> (jedno wykonanie receptury),
 * a kazda maszyna sama przelicza swoja energie na operacje.
 *
 * <p><b>To jest jedyne, czego rdzen wymaga od modulu.</b> Maszyna:
 * <ul>
 *   <li>mowi, jakie TYPY RECEPTUR obsluguje ({@link #recipeTypes()}) - dzieki
 *       temu rdzen nie musi znac nazw rodzin z innych modow,</li>
 *   <li>mowi, ile operacji jeszcze uciagnie ({@link #availableOperations()})
 *       i pozwala je zabrac ({@link #consumeOperations(long)}),</li>
 *   <li>mowi, czy jest w ogole zasilana ({@link #isPowered()}) - kontroler
 *       rozroznia "brak maszyny" od "maszyna bez pradu".</li>
 * </ul>
 */
public interface VeloceProcessingSource {

    /** Krotki identyfikator modulu, np. {@code "mekanism:crusher"}. Do logow. */
    String moduleId();

    /**
     * Typy receptur, ktore TA maszyna potrafi wykonac.
     *
     * <p>Moze byc wiecej niz jeden (np. maszyna wielofunkcyjna), ale w praktyce
     * to jeden typ - i po nim rdzen kojarzy recepture z maszyna.
     */
    Set<RecipeType<?>> recipeTypes();

    /** Ile operacji (wykonan receptury) ta maszyna jeszcze uciagnie. */
    long availableOperations();

    /**
     * Zabiera energie na {@code operations} operacji.
     *
     * <p>Wolajacy MUSI najpierw sprawdzic {@link #availableOperations()}.
     * Implementacja nie zejdzie ponizej zera, ale to nie jest miejsce na
     * kontrole bledow.
     */
    void consumeOperations(long operations);

    /** Czy maszyna jest w ogole zasilana (ma dosc energii na jedna operacje). */
    boolean isPowered();

    /**
     * Priorytet przy wyborze maszyny. <b>Mniejszy = wazniejszy.</b>
     *
     * <p>Ten sam sposob co {@link VeloceHeatSource#heatPriority()}: gdy w sieci
     * stoi kilka maszyn tej samej rodziny, crafter wybiera pierwsza z listy,
     * a lista jest deterministyczna.
     */
    default int processingPriority() {
        return 0;
    }

    /** Etykieta do logow i raportu (np. "Veloce Crusher Module"). */
    String sourceName();
}
