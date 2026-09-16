package com.craftingveloce.compat.jade;

import com.craftingveloce.block.entity.VeloceModuleInfoSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/**
 * Dane serwera dla Jade: opis naszej maszyny.
 *
 * <p><b>Dlaczego dane serwera, a nie odczyt po stronie klienta.</b> Liczby
 * (predkosc wymagana, pobor SU, ile operacji wyjdzie z akumulatora, stan sieci
 * rur) zna tylko serwer - i to on je liczy dla okna po prawym kliku. Klient
 * dostaje je NBT-em przez Jade, tak samo jak dostaje je pakietem dla okna,
 * wiec oba miejsca pokazuja dokladnie te same wartosci, a klient nic nie zgaduje.
 *
 * <p>Rejestrujemy sie dla WSZYSTKICH block entity i filtrujemy po rdzeniowym
 * interfejsie {@link VeloceModuleInfoSource} - dzieki temu ta klasa nie musi
 * znac ani jednego typu z Create/Mekanism/Alchemistry (a musi dzialac, gdy
 * ktoregokolwiek z nich nie ma).
 */
public enum VeloceModuleDataProvider implements IServerDataProvider<BlockAccessor> {

    INSTANCE;

    @Override
    public ResourceLocation getUid() {
        return VeloceJadePlugin.MODULE_INFO_UID;
    }

    /**
     * Pytamy o dane TYLKO nasze maszyny.
     *
     * <p>Bez tego Jade wysylalby (i liczyl) opis dla kazdego block entity
     * w swiecie, na ktory gracz spojrzy - a liczenie planu sieci nie jest darmowe.
     */
    @Override
    public boolean shouldRequestData(BlockAccessor accessor) {
        return accessor.getBlockEntity() instanceof VeloceModuleInfoSource;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (accessor.getBlockEntity() instanceof VeloceModuleInfoSource source
                && accessor.getLevel() instanceof ServerLevel level) {
            data.merge(source.moduleInfo(level));
        }
    }
}
