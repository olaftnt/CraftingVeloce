package com.craftingveloce.block.entity;

import net.minecraft.world.item.ItemStack;

/**
 * Blok, ktory ma liste FILTROW wybieranych przez gracza.
 *
 * <p>Dwa bloki w modzie maja filtry i oba wybiera sie tak samo - przez ekran
 * wyboru itemu: ekstraktor (co ma wyciagac z sieci) i Velocity Furnace (jakie
 * paliwo ma zaciagac). Bez wspolnego interfejsu kazdy z nich potrzebowalby
 * wlasnego pakietu "ustaw filtr", wlasnej sciezki otwierania wyboru i wlasnego
 * ponownego otwarcia ekranu - czyli trzech kopii tej samej logiki, ktore
 * predzej czy pozniej rozjechalyby sie tak, jak rozjechala sie lista wezlow
 * sieci i lista typow receptur pieca.
 *
 * <p>Filtry NIE sa przedmiotami w swiecie - to tylko wybor "jaki item".
 * Dlatego ich sloty sa widmami i nie da sie ich wypelnic przeciaganiem.
 */
public interface VeloceFilterHost {

    /** Ile filtrów ma ten blok. */
    int filterCount();

    /** Filtr o danym numerze (pusty stos = brak filtra). */
    ItemStack getFilterAt(int index);

    /** Ustawia filtr; pusty stos kasuje filtr. */
    void setFilterAt(int index, ItemStack stack);
}
