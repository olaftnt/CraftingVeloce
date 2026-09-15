package com.craftingveloce.compat.mekanism;

import net.minecraft.world.item.crafting.RecipeType;

import java.util.List;
import java.util.function.Supplier;

/**
 * JEDEN opis maszyny modulu Mekanism - jeden wiersz danych, nie jedna klasa.
 *
 * <p><b>Po co.</b> Cztery maszyny itemowe Mekanism (kruszarka, wzbogacanie,
 * laczenie, pilowanie) roznia sie TYLKO trzema rzeczami: typem receptury,
 * kosztem FE i etykieta. Pisanie dla kazdej osobnej klasy block entity
 * znaczyloby cztery kopie tej samej logiki energii - czyli dokladnie ten
 * rodzaj duplikatu, ktory w tym projekcie juz kilka razy sie rozjechal.
 *
 * <p><b>Dlaczego {@link Supplier}, a nie gotowy typ receptury.</b> Typy
 * receptur Mekanism to DeferredHoldery, wiazane dopiero po zdarzeniach
 * rejestracji. Instancje tego rekordu powstaja przy ladowaniu klas blokow
 * (czyli w konstruktorze moda), wiec typ MUSI byc rozwiazany pozniej -
 * dostawca robi to leniwie, w momencie pierwszego uzycia.
 *
 * @param id             identyfikator do logow, np. {@code "mekanism:crusher"}
 * @param label          etykieta maszyny (logi, komunikat o energii)
 * @param fePerOperation koszt jednej operacji w FE (jak w Mekanism)
 * @param capacity       pojemnosc akumulatora w FE
 * @param recipeType     typ receptury obslugiwany przez te maszyne (leniwie)
 */
public record FeModule(String id, String label, int fePerOperation, int capacity,
                       Supplier<RecipeType<?>> recipeType) {

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
