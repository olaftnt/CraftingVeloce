package com.craftingveloce.network.pipe;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Pulling power from FOREIGN sources attached to the pipe network.
 *
 * <p>Player: "our module can pull power from an energy cube ... but only in
 * that direction; other mods cannot use our cables; if it is full, it does not
 * try to pull; and we want limits, because an energy cube has a maximum
 * transfer of FE/tick".
 *
 * <p>The rules, all in this one place:
 * <ol>
 *   <li>a full battery = ZERO attempts (we do not even ask the sources),</li>
 *   <li>budget = min(free space, the machine's intake limit),</li>
 *   <li>the transfer is requested from the SOURCE ({@code extractEnergy}) - it
 *       limits itself to its own FE/tick, so it is impossible to "suddenly pull
 *       a ridiculous amount",</li>
 *   <li>if the receiver accepts less, we give the surplus back to the source
 *       (no energy is lost),</li>
 *   <li>we do not pull in chunks: an unloaded source is skipped.</li>
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

    /** How much FE per tick we try to take from a single source at most. */
    public static final int MAX_PER_SOURCE_PER_TICK = 1_000_000;

    private VeloceEnergyPull() {
    }

    /**
     * Pulls power from the network's foreign sources into {@code receiver}.
     *
     * @param maxRate the machine's intake limit per tick (its own, alongside the
     *                source's limit)
     * @return how much FE REALLY went into the receiver (0 = nothing needed or
     *         nothing available)
     */

    /**
     * Finds foreign energy sources in the network on demand.
     *
     * <p>Endpoints are created during the network scan, and the scan runs on
     * topology changes - an Energy Cube placed next to an existing pipe does not
     * change the topology, so the list stayed empty and the machines had nothing
     * to pull from. This pass checks the neighbours of all pipes of the network
     * and adds the sources it finds.
     *
     * <p>Our own blocks are skipped - machines are only receivers and cannot be a
     * source for each other.
     */
    private static void discover(ServerLevel level, VelocePipeNetwork network) {
        if (!network.getEnergyEndpoints().isEmpty()) {
            return;
        }
        // A traversal over the PIPES connected to the network (BFS), not just
        // over the list from the network: the player's log showed "pipes=1",
        // even though the furnace IS on a network with an Energy Cube - the pipe
        // list was incomplete, so the neighbour search ended at one pipe and
        // never reached the cube.
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
                    continue;   // our machines are only receivers
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
            LOG.info("[Veloce][ENERGY] sources found: {} (pipes scanned={}, sources={})",
                    found, visited.size(), network.getEnergyEndpoints());
        } else if (System.currentTimeMillis() - lastDiscoverLog > 30_000L) {
            lastDiscoverLog = System.currentTimeMillis();
            LOG.info("[Veloce][ENERGY] no sources: pipes scanned={}, neighbours={}, "
                            + "neighbour blocks={} - if an Energy Cube is in the list, it does not expose "
                            + "Forge Energy from that side",
                    visited.size(), neighbours, seen);
        }
    }


    /**
     * The side that last gave power - like the connection cache in Pipez.
     *
     * <p>Pipez does not look for a handler every tick: it resolves it once on
     * connection and remembers it. Here that map does the job: first we try the
     * side that already worked (because Mekanism's Energy Cube often gives power
     * from only one), and only then do we walk the remaining ones.
     */
    private static final java.util.Map<BlockPos, net.minecraft.core.Direction> KNOWN_SIDE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The block's side that gives energy (the remembered one first, then the rest). */
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
            return 0;   // a full battery - zero pull attempts
        }
        // DIAGNOSTICS (player: "it does not work"): if the machine has free space
        // while the network knows NO foreign energy source, then the problem is
        // in detection, not in the draw. Log at most once every 5 s, to avoid
        // spamming. If the network does not know any sources (e.g. an Energy Cube
        // placed AFTER the network scan, or a network rebuilt from a save), find
        // them now - otherwise the draw will never start and it looks like "it
        // does not work".
int budget = Math.min(free, maxRate);
        int total = 0;
        for (BlockPos pos : network.getEnergyEndpoints()) {
            if (budget <= 0) {
                break;
            }
            if (!level.isLoaded(pos)) {
                continue;   // we do not pull in chunks just for power
            }
            // We look for the source on EVERY side of the block: Mekanism's
            // Energy Cube may not give energy from the side we are looking from.
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
                                .append(st == null ? "none"
                                        : (st.canExtract()
                                                ? ("extract=" + st.extractEnergy(MAX_PER_SOURCE_PER_TICK, true))
                                                : "cannot-give"))
                                .append(' ');
                    }
                    LOG.info("[Veloce][ENERGY] source {} gives no energy: state={}/{}, "
                                    + "sides: {}",
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
                LOG.info("[Veloce][ENERGY] took {} FE from {} ({} FE total this tick)",
                        accepted, pos.toShortString(), total);
            }
        }
        if (System.currentTimeMillis() - lastSummaryLog > 30_000L) {
            lastSummaryLog = System.currentTimeMillis();
            LOG.info("[Veloce][ENERGY] draw: took {} FE, sources={}, free={} FE",
                    total, network.getEnergyEndpoints().size(),
                    receiver.getMaxEnergyStored() - receiver.getEnergyStored());
        }
        return total;
    }
}
