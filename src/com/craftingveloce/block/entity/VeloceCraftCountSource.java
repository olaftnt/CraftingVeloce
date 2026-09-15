package com.craftingveloce.block.entity;

import com.craftingveloce.crafting.VeloceAutoCrafter;
import net.minecraft.world.item.Item;

import java.util.Collection;

/**
 * Blok, ktory potrafi policzyc "ile da sie jeszcze dorobic" dla swojej sieci.
 *
 * <p><b>Po co wspolny interfejs.</b> Terminal liczyl te wartosci dla widocznej
 * strony, a kontroler potrzebuje DOKLADNIE tego samego - tych samych liczb,
 * z tej samej logiki. Bez wspolnego interfejsu pakiet
 * {@code RequestCraftableCountsPKT} musialby znac oba typy blokow i rozgalezac
 * sie po nich, a kazdy kolejny ekran pokazujacy liczby dokladalby trzecia
 * galez. To ten sam blad, ktory juz raz rozjechal liste wezlow sieci i liste
 * typow receptur pieca.
 *
 * <p>Implementacja MUSI sama znalezc swoja siec (terminal i kontroler robia to
 * identycznie) i sama zadbac o swoj budzet czasu - liczenie drzewa receptur
 * dla calej strony to realna praca na watku serwera.
 */
public interface VeloceCraftCountSource {

    /**
     * Liczby craftowalne dla podanych itemow.
     *
     * @param items itemy widoczne na ekranie gracza
     * @return liczby + informacja, czy policzono WSZYSTKIE zadane itemy
     *         (niepelny wynik nie moze kasowac poprawnych liczb u klienta)
     */
    VeloceAutoCrafter.BatchResult computeCraftableCounts(Collection<Item> items);
}
