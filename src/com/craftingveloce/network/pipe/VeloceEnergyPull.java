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
        if (network.getEnergyEndpoints().isEmpty()
                && System.currentTimeMillis() - lastEmptyLog > 5_000L) {
            lastEmptyLog = System.currentTimeMillis();
            LOG.info("[Veloce][ENERGY] maszyna {} ma wolne {} FE, ale siec nie zna "
                            + "zadnego obcego zrodla (endpointy=0) - sprawdz, czy Energy Cube "
                            + "stoi PRZY RURZE i czy siec byla skanowana po jego postawieniu",
                    receiver.getEnergyStored(), free);
        }
        int budget = Math.min(free, maxRate);
        int total = 0;
        for (BlockPos pos : network.getEnergyEndpoints()) {
            if (budget <= 0) {
                break;
            }
            if (!level.isLoaded(pos)) {
                continue;   // nie wciagamy chunkow dla pradu
            }
            IEnergyStorage source = level.getCapability(
                    Capabilities.EnergyStorage.BLOCK, pos, null);
            if (source == null || !source.canExtract()) {
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
        }
        return total;
    }
}
