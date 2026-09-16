package com.craftingveloce.block.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * Maszyna, ktora umie opisac sie do GUI (predkosc, SU, sieć).
 *
 * <p>Rdzen nie zna typow Create, wiec okno modulu pyta o dane przez ten
 * interfejs - a wypelnia go ten, kto te liczby naprawde ma (block entity
 * maszyny kinetycznej w {@code compat/create}).
 */
public interface VeloceModuleInfoSource {

    /** Pola okna modulu (predkosc, zapotrzebowanie, sieć) - liczone na serwerze. */
    CompoundTag moduleInfo(ServerLevel level);
}
