package com.craftingveloce.client.gui;

import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The "how many more can be made" numbers for VISIBLE items.
 *
 * <p><b>Why a separate class.</b> The terminal had this whole mechanic inside itself:
 * collecting visible items, a page signature (so it does not ask every tick), and
 * stitching the answer together from two sources (the immediate answer and the
 * background). The controller needs exactly the same thing, and copying it a second
 * time would guarantee that after a few changes the numbers would start to differ
 * between the two screens. So we keep it in one place and both screens have their
 * own instance.
 *
 * <p><b>Where these numbers come from.</b> The server computes them on demand, for
 * the given list of items, within one shared time budget ({@code countCraftableBatch})
 * and sends back ready values. The background (cache) recomputes the rest of the
 * network, but the player does not have to wait for that to see current numbers
 * where they are looking.
 */
public final class VeloceCraftableCounts {

    /** item -> how many more can be made (the yellow "+N" number). */
    private final Map<Item, Long> counts = new HashMap<>();

    /** Items from the last request - so we know which entries to refresh. */
    private final Set<Item> lastRequested = new HashSet<>();

    /**
     * Signature of the last requested page.
     *
     * <p>It lets us detect a change of screen contents (a different tab, scrolling)
     * and request numbers for the new page, without sending a request every tick.
     */
    private int lastSignature;

    /** Whether numbers have already been requested after the screen was opened. */
    private boolean initialRequestSent;

    /**
     * Whether the last answer was INCOMPLETE.
     *
     * <p>The server computes a batch within a shared time budget and stops when it
     * runs out - in the log this shows up as "45 item(s) -> 3 result(s) (complete=false)".
     * With such an answer some items did not get a new number and kept the old one.
     * Without a retry this persisted UNTIL THE VIEW CHANGED, because the request only
     * goes out when the signature changes - meaning a number could get frozen at the
     * value from the start of the session (player report: "I take out half the sand,
     * and it still shows 25").
     */
    private boolean partial;

    /** How many ticks to wait before retrying when the answer was incomplete. */
    private static final int RETRY_INTERVAL_TICKS = 10;
    private int retryCooldown;

    /**
     * Whether to request the numbers ONE MORE TIME in the first tick after the screen
     * is opened.
     *
     * <p><b>Why.</b> {@code init()} runs before vanilla manages to fill the grid slots
     * (with a restored phrase we saw this in the log: a request for 45 positions, while
     * numbers appeared for only some of them). So the first request goes out right away
     * (so the player does not wait), and the second one - after the slots are filled -
     * in the nearest tick.
     */
    private boolean repeatFirstRequest;

    /** A sample of item names for the diagnostic log (at most {@code limit}). */
    private static String sample(List<Item> items, int limit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size() && i < limit; i++) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(items.get(i)).getPath());
        }
        if (items.size() > limit) {
            sb.append(", ...");
        }
        return sb.toString();
    }

    /** How many more can be made for this item; 0 when there is no entry. */
    public long get(Item item) {
        return counts.getOrDefault(item, 0L);
    }

    /** Puts in values computed elsewhere (e.g. a background snapshot from the server). */
    public void putAll(Map<Item, Long> craftable) {
        if (craftable != null && !craftable.isEmpty()) {
            counts.putAll(craftable);
        }
    }

    /**
     * Requests numbers for the items visible on the screen.
     *
     * @param pos          position of the block whose network is to be computed
     * @param slots        screen slots
     * @param isPlayerSlot whether the given slot belongs to the player (we skip those)
     * @param force        true = send even when the signature has not changed
     */
    public void request(BlockPos pos, List<Slot> slots,
                        Predicate<Slot> isPlayerSlot, boolean force) {
        if (pos == null || slots == null) {
            return;
        }
        // HashSet for duplicate detection: this runs every tick, and contains() on a
        // list would be an O(n) scan for every slot - that is, O(n^2) per tick.
        List<Item> visible = new ArrayList<>();
        Set<Item> seen = new HashSet<>();
        int signature = 1;
        // Counters for the log: without them it is impossible to tell WHETHER and WHAT
        // the client actually covered with the request (the player suspected the request
        // was not going out at all).
        int totalSlots = 0;
        int playerSlots = 0;
        int emptySlots = 0;
        int duplicates = 0;
        for (Slot slot : slots) {
            totalSlots++;
            if (slot == null || !slot.hasItem() || isPlayerSlot.test(slot)) {
                if (slot == null || !slot.hasItem()) {
                    emptySlots++;
                } else {
                    playerSlots++;
                }
                continue;
            }
            // The PROXY, not the raw stack, and this is the whole fix for potions.
            //
            // The network counts its stock through VelocePotionMapper.getProxy - a real
            // water bottle is reported as craftingveloce:potion_water - and the craftable
            // set is keyed the same way. The terminal, however, DISPLAYS the raw stack
            // (minecraft:potion). Asking about minecraft:potion therefore asked about an
            // item that is in no recipe and in no craftable set, and the answer was
            // always zero, which is not drawn. Every other item is unaffected:
            // getProxy(stack) returns the item itself for anything that is not a potion.
            Item item = com.craftingveloce.util.VelocePotionMapper.getProxy(slot.getItem());
            if (seen.add(item)) {
                visible.add(item);
                // Signature: the composition and order of visible items.
                signature = signature * 31 + item.hashCode();
            }
            // Hard limit: the server rejects requests larger than MAX_ITEMS.
            // A visible page is a few dozen positions, but with an unusual slot
            // layout there may be more - better to send a truncated list than
            // one that the server rejects as a whole.
            if (visible.size() >= com.craftingveloce.network.RequestCraftableCountsPKT.MAX_ITEMS) {
                break;
            }
        }
        if (visible.isEmpty()) {
            com.craftingveloce.util.VeloceLog.Gui.detail(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "craftable counts: NOTHING to compute (slots=%d, player=%d, empty=%d)",
                    totalSlots, playerSlots, emptySlots);
            return;
        }
        // We retry as long as the previous answer was incomplete - otherwise the items
        // from the tail of the list would never get recomputed. With a throttle,
        // because every request costs the server up to 25 ms.
        boolean retry = this.partial && --this.retryCooldown <= 0;
        boolean firstRepeat = this.repeatFirstRequest;
        this.repeatFirstRequest = false;
        if (!force && !retry && !firstRepeat && initialRequestSent && signature == lastSignature) {
            return;
        }
        this.retryCooldown = RETRY_INTERVAL_TICKS;
        lastSignature = signature;
        initialRequestSent = true;

        // ORDER MATTERS: the server computes the batch in the given order and stops
        // when the budget runs out. Items WITHOUT a computed number therefore go
        // FIRST - only the client knows what it is missing. Without this, after every
        // retry the same positions were computed and the rest kept old (or no) values.
        int unknown = 0;
        for (Item it : visible) {
            if (!counts.containsKey(it)) {
                unknown++;
            }
        }
        visible.sort(java.util.Comparator.comparingInt(it -> counts.containsKey(it) ? 1 : 0));

        com.craftingveloce.util.VeloceLog.Gui.detail(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "asking for craftable counts: %d item(s), %d without a value "
                        + "(first=%s, retry=%s; slots=%d, player=%d, empty=%d) -> %s",
                visible.size(), unknown, firstRepeat, retry,
                totalSlots, playerSlots, emptySlots, sample(visible, 8));

        lastRequested.clear();
        lastRequested.addAll(visible);
        PacketDistributor.sendToServer(
                new com.craftingveloce.network.RequestCraftableCountsPKT(pos, visible));
    }

    /** After the screen is opened the signature starts from scratch. */
    public void resetRequestState() {
        initialRequestSent = false;
        partial = false;
        retryCooldown = 0;
        repeatFirstRequest = true;
    }

    /**
     * The server's answer with numbers for the visible items.
     *
     * <p>We update ONLY the items we asked about. If we overwrote the whole map, the
     * background (cache) and the immediate answer would overwrite each other and the
     * numbers would flicker.
     *
     * <p>We also remove entries for requested items that are absent from the result.
     * The server has been sending ZEROS for a while now (an explicit answer "nothing
     * more can be done"), but the cleanup stays as a safeguard for answers without
     * those zeros.
     */
    public void update(Map<Item, Long> craftable, boolean complete) {
        // An incomplete answer = we need to ask again (see the partial field).
        this.partial = !complete;

        if (complete) {
            for (Item it : lastRequested) {
                counts.remove(it);
            }
            counts.putAll(craftable);
        } else {
            // The server did not get through everything (the network is not ready yet
            // or the budget ran out). We only add what came in - we do NOT delete
            // previous values. Without this the numbers disappeared and never came back.
            counts.putAll(craftable);
        }
        reportState(complete);
    }

    /**
     * Logs the state AFTER merging the answer: how many REQUESTED items have a value,
     * how many have zero, and how many are missing.
     *
     * <p>It distinguishes two completely different causes of "no number": a missing
     * entry = the item was not computed (the request did not cover it), zero = it was
     * computed and it really cannot be made. Previously the log ran BEFORE the merge,
     * so it showed the state from before the answer and was misleading.
     */
    private void reportState(boolean complete) {
        int withValue = 0;
        int zero = 0;
        java.util.List<Item> absent = new java.util.ArrayList<>();
        for (Item it : lastRequested) {
            Long v = counts.get(it);
            if (v == null) {
                absent.add(it);
            } else if (v == 0L) {
                zero++;
            } else {
                withValue++;
            }
        }
        com.craftingveloce.util.VeloceLog.Gui.detail(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "counts AFTER merge for %d requested item(s): %d with a value, "
                        + "%d with ZERO, %d without any entry (complete=%s) -> %s",
                lastRequested.size(), withValue, zero, absent.size(), complete,
                sample(absent, 8));
    }
}
