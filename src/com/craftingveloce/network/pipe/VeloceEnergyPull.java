package com.craftingveloce.network.pipe;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Sciaganie pradu z OBCYCH zrodel podpietych do sieci rur.
 *
 * <p>Gracz: "nasz modul moze sciagnac prad z energy cuba ... ale tylko w te
 * strone; inne moduly nie moga uzywac naszych kabli; jesli jest pelny, to nie
 * probuje sciagac; i chcemy limity, bo energy cube ma maksymalny transfer
 * FE/tick".
 *
 * <p>Zasady, wszystkie w tym jednym miejscu:
 * <ol>
 *   <li>pelny akumulator = ZERO prob (nie pytamy nawet zrodel),</li>
 *   <li>budzet = min(wolne miejsce, limit odbioru maszyny),</li>
 *   <li>o transfer pyta sie ZRODLO ({@code extractEnergy}) - ono samo ogranicza
 *       sie do swojego FE/tick, wiec nie da sie "nagle sciagnac zajebiscie duzo",</li>
 *   <li>jesli odbiornik przyjmie mniej, nadwyzke oddajemy z powrotem do zrodla
 *       (zadna energia nie ginie),</li>
 *   <li>nie wciagamy chunkow: niezaladowane zrodlo jest pomijane.</li>
 * </ol>
 */
public final class VeloceEnergyPull {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("craftingveloce-energy");
    private static long lastEmptyLog;
    private static long lastDiscoverLog;
    private static long lastPullLog;
    private static long lastPullLog2;
    private static long lastSourceLog;
    private static long lastSummaryLog;

    /** Ile FE na tick najwyzej probujemy wziac z jednego zrodla. */
    public static final int MAX_PER_SOURCE_PER_TICK = 1_000_000;

    private VeloceEnergyPull() {
    }

    /**
     * Sciaga prad z obcych zrodel sieci do {@code receiver}.
     *
     * @param maxRate limit odbioru maszyny na tick (wlasny, obok limitu zrodla)
     * @return ile FE NAPRAWDE weszlo do odbiornika (0 = nic nie trzeba/nie ma)
     */

    /**
     * Doszukuje obce zrodla energii w sieci na zadanie.
     *
     * <p>Endpointy powstaja w skanie sieci, a skan chodzi po zmianach topologii -
     * Energy Cube postawiony obok istniejacej rury nie zmienia topologii, wiec
     * lista zostawala pusta i maszyny nie mialy z czego sciagac. Ten przebieg
     * sprawdza sasiadow wszystkich rur sieci i dopisuje znalezione zrodla.
     *
     * <p>Nasze bloki sa pomijane - maszyny sa tylko odbiornikami i nie moga byc
     * dla siebie zrodlem.
     */
    private static void discover(ServerLevel level, VelocePipeNetwork network) {
        if (!network.getEnergyEndpoints().isEmpty()) {
            return;
        }
        // Przejscie po RURACH polaczonych z siecia (BFS), a nie tylko po liscie
        // z sieci: log gracza pokazal "rur=1", mimo ze piec JEST na sieci
        // z Energy Cubem - lista rur byla niepelna, wiec szukanie sasiadow
        // konczylo sie na jednej rurze i nigdy nie dochodzilo do cube'a.
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>(network.getPipes());
        java.util.Set<BlockPos> visited = new java.util.LinkedHashSet<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        int neighbours = 0;
        int found = 0;
        while (!queue.isEmpty() && visited.size() < 512) {
            BlockPos pipe = queue.poll();
            if (!visited.add(pipe) || !level.isLoaded(pipe)) {
                continue;
            }
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockPos side = pipe.relative(dir);
                if (!level.isLoaded(side)) {
                    continue;
                }
                var state = level.getBlockState(side);
                if (state.getBlock() instanceof com.craftingveloce.block.VelocePipeBlock) {
                    if (!visited.contains(side)) {
                        queue.add(side);
                    }
                    continue;
                }
                if (state.getBlock() instanceof VeloceNetworkNode) {
                    continue;   // nasze maszyny sa tylko odbiornikami
                }
                neighbours++;
                if (seen.size() < 6) {
                    seen.add(state.getBlock() + " @" + side.toShortString());
                }
                if (level.getCapability(Capabilities.EnergyStorage.BLOCK, side,
                        dir.getOpposite()) != null) {
                    network.addEnergyEndpoint(side);
                    found++;
                }
            }
        }
        if (found > 0) {
            LOG.info("[Veloce][ENERGY] znalezione zrodla: {} (przejrzane rury={}, zrodla={})",
                    found, visited.size(), network.getEnergyEndpoints());
        } else if (System.currentTimeMillis() - lastDiscoverLog > 30_000L) {
            lastDiscoverLog = System.currentTimeMillis();
            LOG.info("[Veloce][ENERGY] brak zrodel: przejrzane rury={}, sasiadow={}, "
                            + "sasiedzi={} - jesli Energy Cube jest na liscie, to nie wystawia "
                            + "Forge Energy z tej strony",
                    visited.size(), neighbours, seen);
        }
    }


    /**
     * Strona, ktora ostatnio oddala prad - jak cache polaczenia w Pipezie.
     *
     * <p>Pipez nie szuka handlera co tick: rozwiazuje go raz przy polaczeniu
     * i pamieta. U nas robi to ta mapa: najpierw probujemy strone, ktora juz
     * zadzialala (bo Energy Cube Mekanismu czesto oddaje prad tylko jedną),
     * a dopiero potem obchodzimy pozostale.
     */
    private static final java.util.Map<BlockPos, net.minecraft.core.Direction> KNOWN_SIDE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Strona bloku, ktora oddaje energie (najpierw zapamietana, potem reszta). */
    private static IEnergyStorage findExtracting(ServerLevel level, BlockPos pos) {
        net.minecraft.core.Direction known = KNOWN_SIDE.get(pos);
        if (known != null) {
            IEnergyStorage st = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, known);
            if (st != null && st.canExtract() && st.extractEnergy(MAX_PER_SOURCE_PER_TICK, true) > 0) {
                return st;
            }
            KNOWN_SIDE.remove(pos);
        }
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
            IEnergyStorage st = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
            if (st != null && st.canExtract() && st.extractEnergy(MAX_PER_SOURCE_PER_TICK, true) > 0) {
                KNOWN_SIDE.put(pos.immutable(), side);
                return st;
            }
        }
        IEnergyStorage any = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
        return any != null && any.canExtract() && any.extractEnergy(MAX_PER_SOURCE_PER_TICK, true) > 0 ? any : null;
    }

    public static int pull(ServerLevel level, VelocePipeNetwork network,
                           IEnergyStorage receiver, int maxRate) {
        if (level == null || network == null || receiver == null || maxRate <= 0) {
            return 0;
        }
        int free = receiver.getMaxEnergyStored() - receiver.getEnergyStored();
        if (free <= 0) {
            return 0;   // pelny akumulator - zero prob sciagania
        }
        // DIAGNOSTYKA (gracz: "nie dziala"): jesli maszyna ma wolne miejsce,
        // a siec nie zna ZADNEGO obcego zrodla energii, to problem jest
        // w wykrywaniu, a nie w poborze. Log max raz na 5 s, zeby nie spamowac.
        // Jesli siec nie zna zrodel (np. Energy Cube postawiony PO skanie sieci
        // albo siec byla odbudowana z zapisu), znajdz je teraz - inaczej pobor
        // nigdy nie ruszy i wyglada to jak "nie dziala".
        discover(level, network);

        int budget = Math.min(free, maxRate);
        int total = 0;
        for (BlockPos pos : network.getEnergyEndpoints()) {
            if (budget <= 0) {
                break;
            }
            if (!level.isLoaded(pos)) {
                continue;   // nie wciagamy chunkow dla pradu
            }
            // Zrodlo szukamy na KAZDEJ stronie bloku: Energy Cube Mekanismu
            // potrafi nie oddawac energii z tej strony, od ktorej patrzymy.
            IEnergyStorage source = findExtracting(level, pos);
            if (source == null) {
                if (System.currentTimeMillis() - lastSourceLog > 30_000L) {
                    lastSourceLog = System.currentTimeMillis();
                    IEnergyStorage any = level.getCapability(
                            Capabilities.EnergyStorage.BLOCK, pos, null);
                    StringBuilder sides = new StringBuilder();
                    for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                        IEnergyStorage st = level.getCapability(
                                Capabilities.EnergyStorage.BLOCK, pos, d);
                        sides.append(d).append(':')
                                .append(st == null ? "brak"
                                        : (st.canExtract()
                                                ? ("extract=" + st.extractEnergy(MAX_PER_SOURCE_PER_TICK, true))
                                                : "nie-moze-oddac"))
                                .append(' ');
                    }
                    LOG.info("[Veloce][ENERGY] zrodlo {} nie oddaje energii: stan={}/{}, "
                                    + "strony: {}",
                            pos.toShortString(),
                            any == null ? -1 : any.getEnergyStored(),
                            any == null ? -1 : any.getMaxEnergyStored(), sides.toString().trim());
                }
                continue;
            }
            int taken = source.extractEnergy(Math.min(budget, MAX_PER_SOURCE_PER_TICK), false);
            if (taken <= 0) {
                continue;
            }
            int accepted = receiver.receiveEnergy(taken, false);
            if (accepted < taken) {
                source.receiveEnergy(taken - accepted, false);
            }
            total += accepted;
            budget -= accepted;
            if (accepted > 0 && System.currentTimeMillis() - lastPullLog2 > 30_000L) {
                lastPullLog2 = System.currentTimeMillis();
                LOG.info("[Veloce][ENERGY] wzielo {} FE ze {} (razem {} FE w tym ticku)",
                        accepted, pos.toShortString(), total);
            }
        }
        if (System.currentTimeMillis() - lastSummaryLog > 30_000L) {
            lastSummaryLog = System.currentTimeMillis();
            LOG.info("[Veloce][ENERGY] pobor: wzielo {} FE, zrodel={}, wolne={} FE",
                    total, network.getEnergyEndpoints().size(),
                    receiver.getMaxEnergyStored() - receiver.getEnergyStored());
        }
        return total;
    }
}
