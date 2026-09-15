package com.craftingveloce.compat.create;

import net.minecraft.world.item.crafting.RecipeType;

import java.util.function.Supplier;

/**
 * JEDEN opis maszyny kinetycznej Create - dane, nie klasa.
 *
 * <p>Maszyny kinetyczne (mlyn, piła, kruszarka, mechanical crafter) roznia sie
 * tylko typem receptury, etykieta i poborem SU, wiec korzystaja z jednego
 * bloku i jednego block entity.
 *
 * <p><b>Dlaczego {@link Supplier}, a nie gotowy typ receptury.</b> Typy
 * receptur Create to DeferredHoldery wiazane dopiero po zdarzeniach
 * rejestracji, a te stale powstaja w konstruktorze moda - typ musi byc
 * rozwiazany leniwie.
 *
 * @param id           identyfikator do logow, np. {@code "create:milling"}
 * @param label        etykieta maszyny (logi, komunikaty)
 * @param recipeType   typ receptury obslugiwany przez maszyne (leniwie)
 * @param constantSu   stala pula SU pobierana z sieci kinetycznej (patrz
 *                     {@code VeloceKineticModuleBlockEntity.calculateStressApplied})
 */
public record KineticModule(String id, String label, Supplier<RecipeType<?>> recipeType,
                            float constantSu) {
}
