package com.craftingveloce.crafting;

import net.minecraft.world.item.crafting.RecipeType;

import java.util.function.Supplier;

/**
 * JEDEN opis maszyny modulu zasilanej FE - dane, nie klasa.
 *
 * <p><b>Po co.</b> Maszyny modulow (kruszarka, wzbogacanie, compactor, ...)
 * roznia sie TYLKO trzema rzeczami: typem receptury, kosztem energii i
 * etykieta. Osobna klasa block entity dla kazdej znaczylaby kopie tej samej
 * logiki energii - czyli dokladnie ten rodzaj duplikatu, ktory w tym projekcie
 * juz kilka razy sie rozjechal.
 *
 * <p><b>Dlaczego {@link Supplier}, a nie gotowy typ receptury.</b> Typy
 * receptur obcych modow to DeferredHoldery, wiazane dopiero po zdarzeniach
 * rejestracji. Instancje tego rekordu powstaja przy ladowaniu klas blokow
 * (czyli w konstruktorze moda), wiec typ MUSI byc rozwiazany pozniej -
 * dostawca robi to leniwie, w momencie pierwszego uzycia.
 *
 * @param id             identyfikator do logow, np. {@code "mekanism:crusher"}
 * @param label          etykieta maszyny (logi, komunikat o energii)
 * @param fePerOperation koszt jednej operacji w FE
 * @param capacity       pojemnosc akumulatora w FE
 * @param recipeType     typ receptury obslugiwany przez te maszyne (leniwie)
 */
public record FeModule(String id, String label, int fePerOperation, int capacity,
                       Supplier<RecipeType<?>> recipeType) {
}
