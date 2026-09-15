package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Jedno miejsce, ktore odpowiada na pytanie "jakie maszyny stoja w tej sieci".
 *
 * <p><b>Po co.</b> Kazdy rejestr zrodel ({@code VeloceHeatSources},
 * {@code VeloceProcessingSources}) zaczynal od tej samej petli: skopiuj
 * terminale, posortuj po pozycji, pomin niezaladowane chunki, odczytaj block
 * entity i wybierz interesujacy interfejs. Trzy kopie tej samej petli to trzy
 * miejsca, w ktorych mozna zapomniec o {@code isLoaded} albo o sortowaniu -
 * a wlasnie takie rozjazdy byly w tym projekcie zrodlem bledow "raz widzi,
 * raz nie widzi".
 *
 * <p><b>Trzy rzeczy, ktore ta petla robi dobrze</b> (i ktore musza zostac):
 * <ol>
 *   <li>{@code network == null} to pusta lista, nie wyjatek,</li>
 *   <li>sortowanie po {@link BlockPos#asLong()} daje powtarzalna kolejnosc
 *       ({@code getTerminals()} to zbior bez gwarancji kolejnosci),</li>
 *   <li>{@code level.isLoaded(pos)} pomija niewczytane chunki zamiast
 *       wczytywac je w trakcie ticku.</li>
 * </ol>
 */
public final class VeloceNetworkSources {

    private VeloceNetworkSources() {
    }

    /** Maszyny danego typu stojace w sieci, w kolejnosci pozycji. */
    public static <T> List<T> scan(ServerLevel level, VelocePipeNetwork network, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (network == null) {
            return out;
        }
        List<BlockPos> nodes = new ArrayList<>(network.getTerminals());
        nodes.sort(Comparator.comparingLong(BlockPos::asLong));
        for (BlockPos pos : nodes) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (type.isInstance(be)) {
                out.add(type.cast(be));
            }
        }
        return out;
    }
}
