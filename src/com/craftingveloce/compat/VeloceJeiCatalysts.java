package com.craftingveloce.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ItemLike;

/**
 * Spis kategorii przepisow, ktore obsluguja NASZE klocki - dane dla JEI.
 *
 * <p><b>Po co osobny spis, a nie rejestracja w pluginie JEI.</b> Plugin JEI jest
 * klasa z obcymi typami ({@code mezz.jei}), wiec moze byc zaladowany TYLKO gdy
 * JEI jest obecne. Tabele "ktory nasz klocek robi ktory przepis" znaja natomiast
 * moduly integracji (Create, Mekanism, Alchemistry) - i to one ja wypelniaja,
 * bez zadnego kontaktu z JEI. Dzieki temu:
 * <ul>
 *   <li>plugin JEI nie importuje ani jednej klasy Create/Mekanism/Alchemistry -
 *       dziala nawet, gdy z tych modow nie ma NIC (a wtedy po prostu nie ma
 *       czego szukac w {@link #all()}),</li>
 *   <li>brak JEI nic nie psuje: spis sobie lezy, nikt go nie czyta.</li>
 * </ul>
 *
 * <p><b>UID kategorii to UID KATEGORII JEI, a nie nasz typ przepisu.</b> To dwa
 * rozne identyfikatory i najlatwiejszy blad w tym miejscu: pila Create ma
 * kategorie JEI {@code create:sawing}, choc typ przepisu nazywa sie
 * {@code create:cutting}. UID-y ustalone z bajtkodu modow (Create:
 * {@code Create.asResource(name)} z {@code build("sawing", ...)}, Mekanism:
 * {@code RecipeTypeRegistryObject.getId()}, Alchemistry:
 * {@code RecipeType.create("alchemistry", ...)}), a nie zgadywane z nazw.
 *
 * <p><b>Ta klasa nie ma prawa zawierac typow obcych modow</b> - jest ladowana
 * ZAWSZE (wpisuje do niej rdzen, a takze moduly integracji), takze bez JEI.
 */
public final class VeloceJeiCatalysts {

    /**
     * Jedna pozycja spisu: kategoria przepisu + nasz klocek, ktory ja obsluguje.
     *
     * <p>Klocek jest {@link Supplier}, a nie gotowym przedmiotem, bo rejestr
     * przedmiotow nie jest jeszcze gotowy w chwili wypelniania tego spisu.
     */
    public record Catalyst(ResourceLocation category, Supplier<? extends ItemLike> item) {
    }

    private static final List<Catalyst> CATALYSTS = new ArrayList<>();

    private VeloceJeiCatalysts() {
    }

    /** Dopisuje klocek do kategorii o podanym UID, np. {@code "create:crushing"}. */
    public static void register(String category, Supplier<? extends ItemLike> item) {
        register(ResourceLocation.parse(category), item);
    }

    /** Dopisuje klocek do kategorii (wersja dla gotowego UID). */
    public static void register(ResourceLocation category, Supplier<? extends ItemLike> item) {
        Catalyst catalyst = new Catalyst(category, item);
        if (!CATALYSTS.contains(catalyst)) {
            CATALYSTS.add(catalyst);
        }
    }

    /**
     * Kategorie, ktore obsluguje sam rdzen - bez zadnego obcego moda.
     *
     * <p>Stol Veloce jest pelnoprawnym stolem rzemieslniczym, wiec w JEI ma sie
     * pokazac na liscie "w tym mozna to zrobic" obok stolu z Minecrafta.
     */
    public static void registerDefaults() {
        register("minecraft:crafting",
                () -> com.craftingveloce.init.VeloceRegistry.VELOCE_CRAFTING_TABLE_ITEM.get());
    }

    /** Kopia spisu - plugin JEI czyta go dopiero, gdy JEI sie zaladuje. */
    public static List<Catalyst> all() {
        return List.copyOf(CATALYSTS);
    }
}
