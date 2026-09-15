package com.craftingveloce.crafting;

import com.craftingveloce.block.entity.VeloceHeatSource;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Zasilone zrodla ciepla (piece) podlaczone do sieci.
 *
 * <p><b>Po co osobna klasa.</b> To samo pytanie zadaje sobie trzech klientow:
 * <ul>
 *   <li>auto-crafter - "czym moge przepalic?" (bierze pierwsze z listy),</li>
 *   <li>kontroler - "czy moge pokazac receptury pieca jako dostepne?",</li>
 *   <li>diagnostyka - "dlaczego receptury pieca sa niedostepne?".</li>
 * </ul>
 * Bez wspolnego miejsca kazdy z nich musialby sam przechodzic liste wezlow,
 * sprawdzac typ block entity i pytac o zasilanie - czyli dokladnie to, co
 * juz raz sie rozjechalo przy rozpoznawaniu wezlow.
 *
 * <p><b>Sortowanie.</b> Lista jest posortowana po {@link VeloceHeatSource#heatPriority()}
 * (mniejszy = wazniejszy), wiec wolajacy, ktory chce po prostu "nastepne
 * zrodlo", bierze element 0 - i dostaje piec elektryczny przed paliwowym.
 */
public final class VeloceHeatSources {

    private VeloceHeatSources() {
    }

    /** Wszystkie zrodla ciepla w sieci - takze te bez paliwa/pradu. */
    public static List<VeloceHeatSource> allIn(ServerLevel level, VelocePipeNetwork network) {
        List<VeloceHeatSource> out = new ArrayList<>();
        if (network == null) {
            return out;
        }
        // Kopiujemy i sortujemy pozycje, zeby kolejnosc byla powtarzalna -
        // network.getTerminals() to zbior bez gwarantowanej kolejnosci.
        List<BlockPos> nodes = new ArrayList<>(network.getTerminals());
        nodes.sort(Comparator.comparingLong(BlockPos::asLong));

        for (BlockPos pos : nodes) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof VeloceHeatSource heat) {
                out.add(heat);
            }
        }
        out.sort(Comparator.comparingInt(VeloceHeatSource::heatPriority));
        return out;
    }

    /**
     * Zasilone zrodla ciepla, w kolejnosci uzycia (najwazniejsze pierwsze).
     *
     * <p>Pusta lista oznacza: receptury pieca NIE sa dostepne. To jest jedyne
     * miejsce, ktore odpowiada na to pytanie - kontroler i crafter MUSZA
     * patrzec tutaj, zeby nie rozjechaly sie w ocenie.
     */
    public static List<VeloceHeatSource> poweredIn(ServerLevel level, VelocePipeNetwork network) {
        List<VeloceHeatSource> out = new ArrayList<>();
        for (VeloceHeatSource heat : allIn(level, network)) {
            if (heat.isPowered()) {
                out.add(heat);
            }
        }
        return out;
    }

    /**
     * Czy sieć ma COKOLWIEK, co moze przepalac - nawet bez paliwa.
     *
     * <p>Rozroznienie ma znaczenie dla kontrolera: brak pieca to "nie ma
     * receptur pieca", a piec bez paliwa to "jest receptura, ale piec stoi".
     * To dwie rozne informacje dla gracza i dwie rozne podpowiedzi.
     */
    public static boolean hasAnyHeatSource(ServerLevel level, VelocePipeNetwork network) {
        return !allIn(level, network).isEmpty();
    }

    /** Czy w sieci jest zasilony piec - czyli czy receptury pieca sa realnie uzywalne. */
    public static boolean hasPower(ServerLevel level, VelocePipeNetwork network) {
        for (VeloceHeatSource heat : allIn(level, network)) {
            if (heat.isPowered()) {
                return true;
            }
        }
        return false;
    }
}
