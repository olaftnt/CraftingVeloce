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

    /**
     * Ile przepalen siec moze teraz wykonac LACZNIE.
     *
     * <p>To jest budzet dla planera: nie ma sensu planowac stu przepalen, gdy
     * piec uciagnie trzy. Wolajacy nie musi przy tym wiedziec, czy cieplo
     * pochodzi z pradu, czy z wegla.
     */
    public static long totalOperations(ServerLevel level, VelocePipeNetwork network) {
        long total = 0;
        for (VeloceHeatSource heat : allIn(level, network)) {
            total += Math.max(0L, heat.availableOperations());
        }
        return total;
    }

    /**
     * Zabiera cieplo na {@code operations} przepalen, z najlepszego zrodla.
     *
     * <p><b>Fallback w trakcie.</b> Bierzemy z jednego zrodla tyle, ile ono ma
     * (ale nie wiecej niz potrzeba), a reszte dobieramy z kolejnych. Dzieki
     * temu zuzycie 5 przepalen przy elektrycznym majacym 3 nie konczy sie
     * niepowodzeniem - 3 ida z pradu, 2 z paliwa, dokladnie tak, jak opisuje
     * specyfikacja ("dopiero gdy zabraknie pradu, fallback na paliwowy").
     *
     * <p><b>Wydajnosc:</b> to wygodny wariant dla wolajacego, ktory nie ma
     * jeszcze listy zrodel - pobiera ja sam. Sciezka wykonania planu (gorąca,
     * wolana raz na kazda przepalona sztuke) uzywa {@link #consumeFrom} z lista
     * pobrana RAZ, zeby nie skanowac sieci setki razy w jednym ticku.
     *
     * @return {@code true} gdy udalo sie zabrac CALOSC; przy {@code false}
     *         nie zabieramy niczego (wolajacy ma wtedy przerwac operacje)
     */
    public static boolean consume(ServerLevel level, VelocePipeNetwork network, long operations) {
        return consumeFrom(allIn(level, network), operations);
    }

    /**
     * Zabiera cieplo z JUZ POBIERANEJ listy zrodel.
     *
     * <p><b>Po co osobna metoda.</b> {@link #consume} jest wolane raz na KAZDA
     * przepalona sztuke w petli wykonania planu, a kazde wywolanie robilo DWA
     * pelne przejscia po terminalach sieci z sortowaniem (raz przez
     * {@code totalOperations}, raz przez {@code poweredIn}). Przy planie na
     * kilkaset przepalen (jeden w pelni naladowany piec elektryczny to 125
     * operacji) dawalo to setki skanow i sortowan w JEDNYM ticku.
     *
     * <p>Lista zrodel nie zmienia sie w trakcie jednego wykonania planu, wiec
     * wolajacy pobiera ja RAZ i podaje tutaj. Semantyka jest identyczna jak
     * w {@link #consume}: suma liczona po wszystkich zrodlach, ale zabieramy
     * wylacznie z zasilonych, w kolejnosci priorytetu (elektryczny przed
     * paliwowym - patrz {@link #allIn}).
     */
    public static boolean consumeFrom(List<VeloceHeatSource> sources, long operations) {
        if (operations <= 0) {
            return true;
        }
        long total = 0;
        for (VeloceHeatSource heat : sources) {
            total += Math.max(0L, heat.availableOperations());
        }
        if (total < operations) {
            return false;
        }
        long left = operations;
        for (VeloceHeatSource heat : sources) {
            if (left <= 0) {
                break;
            }
            if (!heat.isPowered()) {
                continue;   // dokladnie to samo, co filtrowalo poweredIn()
            }
            long take = Math.min(left, Math.max(0L, heat.availableOperations()));
            if (take <= 0) {
                continue;
            }
            heat.consumeOperations(take);
            left -= take;
        }
        return left <= 0;
    }
}
