package com.craftingveloce.crafting;

import com.craftingveloce.block.entity.VeloceProcessingSource;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rejestr maszyn modulow podlaczonych do sieci - analog
 * {@link VeloceHeatSources}, ale dla modulow z innych modow.
 *
 * <p><b>Dlaczego osobno od ciepla.</b> Piec jest jeden i ma wlasna, waska
 * semantyke (paliwo/prad, jedno przepalenie). Maszyn modulow jest wiele, kazda
 * obsluguje swoj typ receptury, a ich energia ma inne jednostki (FE/op).
 * Wspolny jest tylko ksztalt pytania: "czy w sieci stoi maszyna dla tego typu
 * receptury i czy jest zasilona?".
 *
 * <p><b>Trzy stany, jak przy piecu</b> (kontroler je rozroznia):
 * <ul>
 *   <li>brak maszyny - {@link #hasAny} = false,</li>
 *   <li>maszyna bez pradu - {@link #hasAny} = true, {@link #hasPowered} = false,</li>
 *   <li>maszyna zasilona - {@link #hasPowered} = true.</li>
 * </ul>
 */
public final class VeloceProcessingSources {

    private VeloceProcessingSources() {
    }

    /** Wszystkie maszyny modulow w sieci, w kolejnosci uzycia. */
    public static List<VeloceProcessingSource> allIn(ServerLevel level, VelocePipeNetwork network) {
        List<VeloceProcessingSource> out =
                VeloceNetworkSources.scan(level, network, VeloceProcessingSource.class);
        out.sort(Comparator.comparingInt(VeloceProcessingSource::processingPriority));
        return out;
    }

    /** Maszyny obslugujace dany typ receptury (takze bez pradu). */
    public static List<VeloceProcessingSource> forType(ServerLevel level, VelocePipeNetwork network,
                                                      RecipeType<?> type) {
        List<VeloceProcessingSource> out = new ArrayList<>();
        for (VeloceProcessingSource source : allIn(level, network)) {
            if (source.recipeTypes().contains(type)) {
                out.add(source);
            }
        }
        return out;
    }

    /** Czy w sieci stoi JAKAKOLWIEK maszyna dla tego typu receptury. */
    public static boolean hasAny(ServerLevel level, VelocePipeNetwork network, RecipeType<?> type) {
        return !forType(level, network, type).isEmpty();
    }

    /** Czy maszyna dla tego typu receptury jest teraz zasilona. */
    public static boolean hasPowered(ServerLevel level, VelocePipeNetwork network, RecipeType<?> type) {
        for (VeloceProcessingSource source : forType(level, network, type)) {
            if (source.isPowered()) {
                return true;
            }
        }
        return false;
    }

    /** Czy stoi i jest zasilona maszyna dla KTOREJS z podanych rodzin. */
    public static boolean hasPoweredAny(ServerLevel level, VelocePipeNetwork network,
                                        Iterable<RecipeType<?>> types) {
        for (RecipeType<?> type : types) {
            if (hasPowered(level, network, type)) {
                return true;
            }
        }
        return false;
    }

    /** Czy stoi jakakolwiek maszyna dla ktorejs z podanych rodzin (nawet bez pradu). */
    public static boolean hasAnyOf(ServerLevel level, VelocePipeNetwork network,
                                   Iterable<RecipeType<?>> types) {
        for (RecipeType<?> type : types) {
            if (hasAny(level, network, type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Zabiera energie na operacje z JUZ POBIERANEJ listy maszyn.
     *
     * <p>Semantyka jak przy piecu: liczymy sume po wszystkich maszynach, ale
     * zabieramy wylacznie z zasilonych, w kolejnosci priorytetu. Gdy zabraknie
     * pradu na calosc, metoda zwraca {@code false} i NIE zabiera niczego
     * (najpierw sprawdzamy sume).
     *
     * @param sources maszyny dla tego typu receptury (patrz {@link #forType})
     */
    public static boolean consumeFrom(List<VeloceProcessingSource> sources, long operations) {
        if (operations <= 0) {
            return true;
        }
        long total = 0;
        for (VeloceProcessingSource source : sources) {
            total += Math.max(0L, source.availableOperations());
        }
        if (total < operations) {
            return false;
        }
        long left = operations;
        for (VeloceProcessingSource source : sources) {
            if (left <= 0) {
                break;
            }
            if (!source.isPowered()) {
                continue;
            }
            long take = Math.min(left, Math.max(0L, source.availableOperations()));
            if (take <= 0) {
                continue;
            }
            source.consumeOperations(take);
            left -= take;
        }
        return left <= 0;
    }

    /**
     * Zabiera energie na operacje, pobierajac liste maszyn samodzielnie.
     *
     * <p>Wygodne dla wolajacych jednostkowych. Sciezka wykonania planu (goraca,
     * raz na kazda sztuke) uzywa {@link #consumeFrom} z lista pobrana RAZ, zeby
     * nie skanowac sieci setki razy w jednym ticku.
     */
    public static boolean consume(ServerLevel level, VelocePipeNetwork network,
                                  RecipeType<?> type, long operations) {
        return consumeFrom(forType(level, network, type), operations);
    }
}
