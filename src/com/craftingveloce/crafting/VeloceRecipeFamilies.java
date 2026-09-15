package com.craftingveloce.crafting;

import net.minecraft.world.item.crafting.RecipeType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * JEDNO zrodlo prawdy o rodzinach receptur Veloce.
 *
 * <p><b>Po co osobna klasa.</b> Lista typow receptur byla dotad trzymana
 * w dwoch miejscach ({@code VeloceRecipeRegistry.FREE_TYPES} i kopia
 * w {@code VeloceRecipeGraph.FREE_TYPES}). Dwie kopie tej samej reguly zawsze
 * predzej czy pozniej sie rozjezdzaja - w tym projekcie zdarzylo sie to juz
 * kilka razy (lista wezlow sieci, lista packetow, uklad GUI).
 *
 * <p><b>Rodziny modow sa REJESTROWANE dynamicznie.</b> Typy receptur z innych
 * modow (Create, Alchemistry, Mekanism) nie istnieja bez tych modow, wiec nie
 * moga byc stala w rdzeniu - rejestruje je modul {@code compat/*} po sprawdzeniu
 * obecnosci moda (patrz {@link #registerModFamily}).
 */
public final class VeloceRecipeFamilies {

    private VeloceRecipeFamilies() {
    }

    /** Bez infrastruktury: crafting table, stonecutter, smithing table. */
    public static final Set<RecipeType<?>> FREE = Set.of(
            RecipeType.CRAFTING,
            RecipeType.STONECUTTING,
            RecipeType.SMITHING
    );

    /**
     * Wymaga ZASILONEGO pieca w sieci.
     *
     * <p><b>CELOWO OSOBNA RODZINA.</b> Te receptury wymagaja paliwa, wiec NIE
     * moga byc w {@link #FREE}: auto-crafter uznalby wtedy, ze potrafi zrobic
     * sztabke zelaza z rudy za darmo - bez pieca i bez paliwa.
     */
    public static final Set<RecipeType<?>> FURNACE = Set.of(
            RecipeType.SMELTING,
            RecipeType.BLASTING,
            RecipeType.SMOKING
    );

    /** Rodziny z innych modow: id moda -> jego typy receptur. */
    private static final Map<String, Set<RecipeType<?>>> MOD_FAMILIES = new LinkedHashMap<>();

    /** Rejestruje rodzine receptur z innego moda (wolane z modulu {@code compat/*}). */
    public static synchronized void registerModFamily(String id, Set<RecipeType<?>> types) {
        MOD_FAMILIES.put(id, Set.copyOf(types));
    }

    /** Id modow, ktore zarejestrowaly swoje rodziny - do diagnostyki. */
    public static synchronized Set<String> modFamilies() {
        return Set.copyOf(MOD_FAMILIES.keySet());
    }

    public static boolean isFree(RecipeType<?> type) {
        return FREE.contains(type);
    }

    public static boolean isFurnace(RecipeType<?> type) {
        return FURNACE.contains(type);
    }

    public static synchronized boolean isModFamily(RecipeType<?> type) {
        for (Set<RecipeType<?>> types : MOD_FAMILIES.values()) {
            if (types.contains(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Czy znamy ten typ receptury - czyli czy jakis modul Veloce go obsluguje.
     *
     * <p>Uzywane do logu przy budowie indeksu: typ spoza znanych rodzin
     * pomijamy, ale chcemy o tym wiedziec.
     */
    public static boolean isKnown(RecipeType<?> type) {
        return isFree(type) || isFurnace(type) || isModFamily(type);
    }
}
