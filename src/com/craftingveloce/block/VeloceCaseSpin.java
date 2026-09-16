package com.craftingveloce.block;

/**
 * Maszyna, ktora ma w obudowie RUSZAJACE sie elementy (kola mlynskie, oczka).
 *
 * <p><b>Po co interfejs w rdzeniu.</b> Renderer obudowy dziala w rdzeniu i nie
 * moze znac typow Create - a to wlasnie maszyna kinetyczna Create wie, z jaka
 * predkoscia sie kreci. Rdzen pyta wiec o dwie liczby, a kto je zna, ten je
 * implementuje (patrz {@code VeloceKineticModuleBlockEntity} w compat/create).
 *
 * <p>Bez tego renderer musialby albo znac Create (zlamanie izolacji: mod nie
 * wstalby bez Create), albo rysowac elementy nieruchomo - czyli klamac.
 */
public interface VeloceCaseSpin {

    /** Ile elementow pokazac w obudowie (0 = brak, renderer rysuje zwykla zawartosc). */
    int caseParts();

    /**
     * Predkosc obrotu elementow w stopniach na tick.
     *
     * <p>Zero oznacza "stoi" - wtedy elementy sa nieruchome, ale nadal widoczne
     * (maszyna zbudowana, a nie napedzana).
     */
    float caseSpinDegreesPerTick();
}
