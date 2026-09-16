package com.craftingveloce.compat;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;

/**
 * Mody, z ktorymi Veloce ma opcjonalne integracje.
 *
 * <p><b>Po co enum, a nie sam string w kazdym miejscu.</b> Identyfikator moda
 * byl dotad wpisywany recznie tam, gdzie trzeba bylo sprawdzic obecnosc. Enum
 * daje jedno miejsce z lista i jedno miejsce z pytaniem "czy jest?".
 *
 * <p><b>Dlaczego {@code LoadingModList} przed {@code ModList}.</b> Czesc decyzji
 * (np. czy w ogole rejestrowac klasy z obcym typem) zapada bardzo wczesnie, gdy
 * {@code ModList} jeszcze nie istnieje. {@code LoadingModList} dziala od startu
 * loadera. Gdy jest juz dostepny {@code ModList}, uzywamy jego - to warstwa
 * wyzej i nie wymaga czytania plikow modow.
 *
 * <p><b>Ta klasa nie ma prawa zawierac typow obcych modow.</b> Jest ladowana
 * ZAWSZE (takze bez tych modow), wiec kazdy obcy typ w polu albo sygnaturze
 * wywalilby moda przy samym ladowaniu klasy - {@code NoClassDefFoundError}
 * leci przy linkowaniu, zanim jakikolwiek {@code try/catch} zdazy zadzialac.
 */
public enum VeloceMods {

    /** Create - maszyny kinetyczne (mlyn, piła, prasa, basen, kruszarka). */
    CREATE("create"),

    /** Alchemistry - maszyny chemiczne zasilane FE (compactor, combiner...). */
    ALCHEMISTRY("alchemistry"),

    /** Mekanism - maszyny itemowe zasilane FE (crusher, enrichment...). */
    MEKANISM("mekanism"),

    /**
     * JEI - podglad przepisow; nasze klocki jako katalizatory kategorii.
     *
     * <p>Jako jedyny z tej listy NIE jest wolany z {@code CraftingVeloceMod}:
     * JEI samo znajduje nasz plugin po adnotacji {@code @JeiPlugin}, a ten plik
     * powstaje przy starcie JEI. Wpis jest tu po to, zeby lista modow byla
     * kompletna i zeby bylo widac w logu, czy JEI jest obecne.
     */
    JEI("jei"),

    /**
     * Jade - podpowiedź przy celowniku; opisuje nasze maszyny.
     *
     * <p>Tak jak JEI: plugin ({@code compat/jade/VeloceJadePlugin}) znajduje samo
     * Jade po adnotacji {@code @WailaPlugin}, wiec rdzen go nie wola. Wpis jest
     * tu dla logu obecnosci i dla kontroli w buildzie.
     */
    JADE("jade");

    private final String id;

    VeloceMods(String id) {
        this.id = id;
    }

    /** Identyfikator moda w rozumieniu loadera, np. {@code "create"}. */
    public String id() {
        return id;
    }

    /** Czy ten mod jest obecny w tej instancji gry. */
    public boolean isLoaded() {
        LoadingModList loading = LoadingModList.get();
        if (loading != null) {
            return loading.getModFileById(id) != null;
        }
        ModList list = ModList.get();
        return list != null && list.isLoaded(id);
    }

    /**
     * Uruchamia akcje tylko wtedy, gdy mod jest obecny.
     *
     * <p>UWAGA: to NIE chroni przed {@code NoClassDefFoundError} w klasie
     * akcji (patrz komentarz klasy). Akcja ma prawo odwolywac sie do klas
     * z obcym typem, ale sama decyzja musi byc podjeta PRZED jej wywolaniem -
     * dlatego wolajacy sprawdza {@link #isLoaded()} w klasie-bramce bez obcych
     * typow, a dopiero potem siega do klasy z integracja.
     */
    public static void executeIfInstalled(VeloceMods mod, Runnable action) {
        if (mod.isLoaded()) {
            action.run();
        }
    }
}
