package com.craftingveloce.block.entity;

/**
 * Zrodlo "instant przepalania" dla auto-craftera.
 *
 * <p><b>Po co wspolny interfejs.</b> Crafter ma obslugiwac DWA rodzaje piecow
 * (paliwowy i elektryczny) i priorytetyzowac elektryczny. Bez wspolnego
 * interfejsu musialby znac oba typy i znac ich jednostki (burn ticks vs FE),
 * a kazdy nowy piec wymagalby zmian w crafterze.
 *
 * <p>Jednostka jest jedna: <b>OPERACJA</b> - jedno instantowe przepalenie.
 * Kazdy piec sam przelicza swoja energie na operacje:
 * <ul>
 *   <li>paliwowy: {@code burnTicks / SMELT_HEAT_COST}</li>
 *   <li>elektryczny: {@code storedFe / FE_PER_SMELT}</li>
 * </ul>
 * Dzieki temu crafter nie wie i nie musi wiedziec, czym piec jest zasilany.
 */
public interface VeloceHeatSource {

    /**
     * Ile instantowych przepalen piec jeszcze uciagnie.
     *
     * <p>Zero oznacza "nie zasilony" - crafter musi wtedy NIE uzywac receptur
     * pieca dla tego zrodla.
     */
    long availableOperations();

    /**
     * Zabiera energie na {@code operations} przepalen.
     *
     * <p>Wolajacy MUSI najpierw sprawdzic {@link #availableOperations()}.
     * Implementacja i tak nie zejdzie ponizej zera, ale nie jest to miejsce
     * na kontrole bledow.
     */
    void consumeOperations(long operations);

    /** Czy piec jest w ogole zasilony (pali sie / ma FE). */
    boolean isPowered();

    /**
     * Priorytet przy wyborze zrodla. <b>Mniejszy = wazniejszy.</b>
     *
     * <p>Crafter bierze zrodlo o najnizszym priorytecie, wiec piec elektryczny
     * (0) wygrywa z paliwowym (1) - zgodnie z ustaleniem, ze elektryczny ma
     * byc uzywany pierwszy, a paliwowy jest fallbackiem.
     */
    int heatPriority();

    /** Etykieta do logow i raportu (np. "Velocity Furnace"). */
    String heatSourceName();
}
