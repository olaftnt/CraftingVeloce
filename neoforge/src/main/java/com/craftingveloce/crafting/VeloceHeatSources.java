package com.craftingveloce.crafting;

import com.craftingveloce.block.entity.VeloceHeatSource;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.util.VeloceLog;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Powered heat sources (furnaces) connected to the network.
 *
 * <p><b>Why a separate class.</b> Three clients ask themselves the same
 * question:
 * <ul>
 *   <li>auto-crafter - "what can I smelt with?" (takes the first from the list),</li>
 *   <li>controller - "can I show furnace recipes as available?",</li>
 *   <li>diagnostics - "why are furnace recipes unavailable?".</li>
 * </ul>
 * Without a shared place, each of them would have to walk the node list itself,
 * check the block entity type and ask about power - that is, exactly what
 * already drifted apart once during node recognition.
 *
 * <p><b>Sorting.</b> The list is sorted by
 * {@link VeloceHeatSource#heatPriority()} (smaller = more important), so a
 * caller that simply wants "the next source" takes element 0 - and gets the
 * electric furnace before the fuel one.
 */
public final class VeloceHeatSources {

    private VeloceHeatSources() {
    }

    /** All heat sources in the network - including those without fuel/power. */
    public static List<VeloceHeatSource> allIn(ServerLevel level, VelocePipeNetwork network) {
        // The network scan lives in VeloceNetworkSources - one loop for all
        // source registries (furnace, module machines). Three copies of the
        // same loop are three places to forget about isLoaded/sorting.
        List<VeloceHeatSource> out = VeloceNetworkSources.scan(level, network, VeloceHeatSource.class);
        out.sort(Comparator.comparingInt(VeloceHeatSource::heatPriority));
        return out;
    }

    /**
     * Powered heat sources, in order of use (most important first).
     *
     * <p>An empty list means: furnace recipes are NOT available. This is the
     * only place that answers that question - the controller and the crafter
     * MUST look here, so that they do not drift apart in their assessment.
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
     * Whether the network has ANYTHING that can smelt - even without fuel.
     *
     * <p>The distinction matters for the controller: no furnace means "there
     * are no furnace recipes", while a furnace without fuel means "the recipe
     * exists, but the furnace is idle". Those are two different pieces of
     * information for the player and two different hints.
     */
    public static boolean hasAnyHeatSource(ServerLevel level, VelocePipeNetwork network) {
        return !allIn(level, network).isEmpty();
    }

    /** Whether the network has a powered furnace - that is, whether furnace recipes are actually usable. */
    public static boolean hasPower(ServerLevel level, VelocePipeNetwork network) {
        for (VeloceHeatSource heat : allIn(level, network)) {
            if (heat.isPowered()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a FUEL furnace stands in the network with an accumulator below one smelt.
     *
     * <p>Only used to pick the right words for the failure. "No fuel/power" is true but
     * useless when the player has just fed the furnace coal: one instant smelt costs
     * one whole coal, so a furnace that has not finished banking one IS cold, and the
     * player needs to hear that rather than "no fuel" while looking at fuel. The
     * electric furnace is deliberately not included - its accumulator has nothing to
     * do with coal, and "not hot enough" would be a wrong explanation for it.
     */
    public static boolean hasColdFuelFurnace(ServerLevel level, VelocePipeNetwork network) {
        for (VeloceHeatSource heat : allIn(level, network)) {
            if (heat instanceof com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity fuel
                    && !fuel.isHotEnough()) {
                return true;
            }
        }
        return false;
    }

    /**
     * How many smelting operations the network can perform RIGHT NOW in TOTAL.
     *
     * <p>This is the budget for the planner: there is no point planning a
     * hundred smelts when the furnace can handle three. The caller does not
     * need to know whether the heat comes from power or from coal.
     */
    public static long totalOperations(ServerLevel level, VelocePipeNetwork network) {
        long total = 0;
        for (VeloceHeatSource heat : allIn(level, network)) {
            total += Math.max(0L, heat.availableOperations());
        }
        return total;
    }

    /**
     * Takes heat for {@code operations} smelts, from the best source.
     *
     * <p><b>Fallback along the way.</b> We take from one source as much as it
     * has (but no more than needed), and draw the rest from the next ones.
     * Thanks to that, consuming 5 smelts with an electric furnace holding 3
     * does not fail - 3 come from power, 2 from fuel, exactly as the
     * specification describes ("only when power runs out, fall back to fuel").
     *
     * <p><b>Performance:</b> this is the convenient variant for a caller that
     * does not yet have a source list - it fetches one itself. The plan
     * execution path (hot, called once per smelted unit) uses
     * {@link #consumeFrom} with a list fetched ONCE, so that it does not scan
     * the network hundreds of times in a single tick.
     *
     * @return {@code true} when the WHOLE amount could be taken; on
     *         {@code false} we take nothing (the caller must then abort the
     *         operation)
     */
    public static boolean consume(ServerLevel level, VelocePipeNetwork network, long operations) {
        return consumeFrom(allIn(level, network), operations);
    }

    /**
     * Takes heat from an ALREADY FETCHED source list.
     *
     * <p><b>Why a separate method.</b> {@link #consume} is called once for
     * EVERY smelted unit in the plan execution loop, and each call did TWO full
     * passes over the network terminals with sorting (once through
     * {@code totalOperations}, once through {@code poweredIn}). With a plan of
     * several hundred smelts (a single fully charged electric furnace is 125
     * operations) that meant hundreds of scans and sorts in ONE tick.
     *
     * <p>The source list does not change during a single plan execution, so the
     * caller fetches it ONCE and passes it here. The semantics are identical to
     * {@link #consume}: the total is summed over all sources, but we take only
     * from powered ones, in priority order (electric before fuel - see
     * {@link #allIn}).
     */
    public static boolean consumeFrom(List<VeloceHeatSource> sources, long operations) {
        if (operations <= 0) {
            return true;
        }
        long total = 0;
        for (VeloceHeatSource heat : sources) {
            total += Math.max(0L, heat.availableOperations());
        }
        // WHO is in the list, and how much each can still pay. This is the decisive
        // evidence for "the electric furnace does not draw from its accumulator":
        // either it is absent from the list, present but not isPowered(), or present
        // and actually charged - three different faults that look identical to the
        // player, because in the last case the accumulator IS drained and only the
        // gauge is stale.
        if (VeloceLog.Craft.isDetailEnabled(VeloceLog.Side.SERVER)) {
            StringBuilder who = new StringBuilder();
            for (VeloceHeatSource heat : sources) {
                who.append(heat.heatSourceName())
                        .append("[prio=").append(heat.heatPriority())
                        .append(" powered=").append(heat.isPowered())
                        .append(" ops=").append(heat.availableOperations())
                        .append("] ");
            }
            VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                    "[VELOCE-DEBUG] heat payment: need %s op(s), total available %s, sources: %s",
                    operations, total, who.toString().trim());
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
                continue;   // exactly the same thing poweredIn() filtered on
            }
            long take = Math.min(left, Math.max(0L, heat.availableOperations()));
            if (take <= 0) {
                continue;
            }
            long before = heat.availableOperations();
            heat.consumeOperations(take);
            VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                    "[VELOCE-DEBUG] heat payment: %s paid %s op(s), ops %s -> %s (powered=%s now)",
                    heat.heatSourceName(), take, before, heat.availableOperations(),
                    heat.isPowered());
            left -= take;
        }
        return left <= 0;
    }
}
