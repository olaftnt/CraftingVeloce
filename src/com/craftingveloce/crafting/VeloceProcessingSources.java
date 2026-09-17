package com.craftingveloce.crafting;

import com.craftingveloce.block.entity.VeloceProcessingSource;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Registry of module machines connected to the network - the counterpart of
 * {@link VeloceHeatSources}, but for modules from other mods.
 *
 * <p><b>Why separate from heat.</b> There is one furnace and it has its own
 * narrow semantics (fuel/power, one smelting). There are many module machines,
 * each handles its own recipe type, and their energy uses different units
 * (FE/op). The only thing they share is the shape of the question: "is there a
 * machine in the network for this recipe type, and is it powered?".
 *
 * <p><b>Three states, as with the furnace</b> (the controller tells them
 * apart):
 * <ul>
 *   <li>no machine - {@link #hasAny} = false,</li>
 *   <li>machine without power - {@link #hasAny} = true, {@link #hasPowered} = false,</li>
 *   <li>powered machine - {@link #hasPowered} = true.</li>
 * </ul>
 */
public final class VeloceProcessingSources {

    private VeloceProcessingSources() {
    }

    /** All module machines in the network, in order of use. */
    public static List<VeloceProcessingSource> allIn(ServerLevel level, VelocePipeNetwork network) {
        List<VeloceProcessingSource> out =
                VeloceNetworkSources.scan(level, network, VeloceProcessingSource.class);
        out.sort(Comparator.comparingInt(VeloceProcessingSource::processingPriority));
        return out;
    }

    /** Machines handling the given recipe type (also without power). */
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

    /**
     * The side of the largest BUILT grid among machines of this type.
     *
     * <p>A Create mechanical crafter has no "size" in a single block: the player
     * places cells and the grid has exactly that many slots. The number of slots
     * is the number of built elements, and the grid side is its square root
     * (25 cells = a 5x5 grid). When the machine has no elements, the side is 0
     * and no grid recipe fits - which means you cannot craft with an unbuilt
     * crafter.
     */
    public static int maxGridSide(ServerLevel level, VelocePipeNetwork network,
                                  RecipeType<?> type) {
        return (int) Math.floor(Math.sqrt(maxParts(level, network, type)));
    }

    /**
     * The largest number of BUILT slots among machines of this type.
     *
     * <p>The side alone is not enough: a 3x3 recipe needs NINE slots, while with
     * eight built the side is 3 - that is why the planner needs both numbers.
     */
    public static int maxParts(ServerLevel level, VelocePipeNetwork network,
                               RecipeType<?> type) {
        int best = 0;
        for (VeloceProcessingSource source : forType(level, network, type)) {
            best = Math.max(best, Math.max(0, source.availableParts()));
        }
        return best;
    }

    /** Whether ANY machine for this recipe type stands in the network. */
    public static boolean hasAny(ServerLevel level, VelocePipeNetwork network, RecipeType<?> type) {
        return !forType(level, network, type).isEmpty();
    }

    /** Whether a machine for this recipe type is powered right now. */
    public static boolean hasPowered(ServerLevel level, VelocePipeNetwork network, RecipeType<?> type) {
        for (VeloceProcessingSource source : forType(level, network, type)) {
            if (source.isPowered()) {
                return true;
            }
        }
        return false;
    }

    /** Whether a machine for ANY of the given families is present and powered. */
    public static boolean hasPoweredAny(ServerLevel level, VelocePipeNetwork network,
                                        Iterable<RecipeType<?>> types) {
        for (RecipeType<?> type : types) {
            if (hasPowered(level, network, type)) {
                return true;
            }
        }
        return false;
    }

    /** Whether any machine for any of the given families is present (even unpowered). */
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
     * Takes energy for operations from an ALREADY FETCHED list of machines.
     *
     * <p>Semantics as with the furnace: we count the sum over all machines, but
     * we take only from the powered ones, in priority order. When there is not
     * enough power for the whole thing, the method returns {@code false} and
     * takes NOTHING (we check the sum first).
     *
     * @param sources machines for this recipe type (see {@link #forType})
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
     * Takes energy for operations, fetching the list of machines by itself.
     *
     * <p>Convenient for one-off callers. The plan execution path (hot, once per
     * every unit) uses {@link #consumeFrom} with the list fetched ONCE, so as
     * not to scan the network hundreds of times in a single tick.
     */
    public static boolean consume(ServerLevel level, VelocePipeNetwork network,
                                  RecipeType<?> type, long operations) {
        return consumeFrom(forType(level, network, type), operations);
    }
}
