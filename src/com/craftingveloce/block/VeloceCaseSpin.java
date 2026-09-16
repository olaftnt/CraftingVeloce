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

    /**
     * Ile kolumn ma uklad elementow w obudowie.
     *
     * <p>Kola mlynskie stoja obok siebie (kolumny = liczba kol), a oczka
     * craftera rosna w slupku: 1x2, 1x3, ... az do 9x9.
     */
    int caseGridColumns();

    /** Ile rzedow ma uklad elementow w obudowie (patrz {@link #caseGridColumns()}). */
    int caseGridRows();

    /**
     * Czy ta maszyna jest BUDOWANA z elementow (kola, oczka).
     *
     * <p>Rozroznia dwie sytuacje, ktore wygladaja tak samo, gdy licznik jest
     * zerowy: kruszarka bez kol ma byc PUSTA obudowa, a mlynek bez kol (bo ich
     * nie potrzebuje) ma pokazywac swoj klocek bazowy.
     */
    boolean caseBuiltFromParts();

    /**
     * Czy elementy krecA sie KAZDY wokol siebie (kola mlynskie), czy caly
     * uklad razem, wokol srodka obudowy (oczka craftera, kazda inna maszyna).
     *
     * <p>Gracz: "craftery krecA sie jak beyblade - to nie o to chodzi, maja sie
     * krecic wszystkie razem wokol srodka wlasnej osi, tak jak kazdy inny
     * render, np. crafting czy furnace".
     */
    boolean casePartsSpinIndividually();
}
