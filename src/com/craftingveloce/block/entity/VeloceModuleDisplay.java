package com.craftingveloce.block.entity;

import net.minecraft.nbt.CompoundTag;

/**
 * Liczby maszyny do okna - po OBU stronach (serwer i klient).
 *
 * <p>Okno jest zwyklym kontenerem (jak piec), wiec menu czyta te wartosci
 * z block entity u klienta. Dlatego ten interfejs zwraca tylko to, co klient
 * naprawde ma: predkosc kinetyczna jest synchronizowana przez Create, a energia
 * przez nasz block entity. Stan sieci rur (liczony na serwerze) tu NIE wchodzi -
 * okno pokazuje tylko predkosc, SU i energie.
 *
 * <p>Wariant rozpoznaje obecnosc klucza {@code energy} (maszyna na FE) albo jego
 * brak (maszyna kinetyczna).
 */
public interface VeloceModuleDisplay {

    /** Pola okna: energy/energyCapacity/fePerOperation/operations/powered
     *  albo speed/requiredSpeed/suDraw/parts/enoughSpeed. */
    CompoundTag moduleDisplay();
}
