package com.craftingveloce.crafting;

import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Stock delta for the client: what CHANGED, not the whole network state.
 *
 * <p><b>The problem this solves.</b> The controller polled the server once per
 * second, and the response was the ENTIRE network stock. With a network holding
 * thousands of different items that is thousands of entries per second, even
 * though the stock almost never changes: a player standing at the controller is
 * not moving items, and yet the server serialized and sent the same picture
 * every time. The bigger the network, the more traffic - exactly in the style
 * of "the longer I play, the worse it gets".
 *
 * <p><b>The solution.</b> We send only the entries that differ from the
 * previously sent ones, plus the list of items that disappeared. The values are
 * ABSOLUTE (and not "by how much it grew"), so applying the same delta twice
 * breaks nothing - this matters, because several players may be looking at the
 * same controller and each of them has their own copy of the stock.
 *
 * <p><b>Full dump once a minute.</b> If anything ever desynchronized the client
 * from the server (a bug in the code, a reload), the delta alone would not let
 * it get back in sync - that is why every {@link #FULL_RESYNC_TICKS} ticks a
 * full stock is sent. This insurance costs one larger packet per minute.
 */
public final class VeloceStockDeltas {

    /** Every this many ticks a full stock dump (60 s) - protection against desync. */
    private static final long FULL_RESYNC_TICKS = 1200L;

    /**
     * The delta to send.
     *
     * @param changed new entries or entries with a changed count (absolute values)
     * @param removed items that are no longer in the network
     * @param full    whether this is a full dump (the client should replace the stock, not merge)
     */
    public record Delta(Map<Item, Long> changed, Set<Item> removed, boolean full) {
    }

    private final Map<Item, Long> last = new HashMap<>();
    private long lastFullTick = Long.MIN_VALUE;

    /**
     * Computes the delta against the previous send and remembers the new state.
     *
     * @param current  the current network stock
     * @param gameTime game time (for the periodic full dump)
     */
    public Delta diff(Map<Item, Long> current, long gameTime) {
        boolean full = lastFullTick == Long.MIN_VALUE
                || gameTime - lastFullTick >= FULL_RESYNC_TICKS;

        Map<Item, Long> changed = new HashMap<>();
        for (Map.Entry<Item, Long> entry : current.entrySet()) {
            Long previous = last.get(entry.getKey());
            if (full || previous == null || !previous.equals(entry.getValue())) {
                changed.put(entry.getKey(), entry.getValue());
            }
        }
        Set<Item> removed = new HashSet<>();
        for (Item item : last.keySet()) {
            if (!current.containsKey(item)) {
                removed.add(item);
            }
        }

        last.clear();
        last.putAll(current);
        if (full) {
            lastFullTick = gameTime;
        }
        return new Delta(changed, removed, full);
    }

    /** Forgets the remembered state - the next delta will be a full dump. */
    public void reset() {
        last.clear();
        lastFullTick = Long.MIN_VALUE;
    }
}
