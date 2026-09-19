package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceTerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * C->S: a request to count "how many can still be crafted" for the given items.
 *
 * <p>The client sends only the items visible on the screen (one creative screen
 * is about 45 slots), not all ~850 craftable ones. The server responds with
 * {@link SyncCraftableCountsPKT}.
 *
 * <p>This solves the performance problem: previously the server counted
 * craftability for all items once per second, recursively, and choked on it.
 */
public record RequestCraftableCountsPKT(BlockPos pos, List<Item> items)
        implements CustomPacketPayload {

    public static final Type<RequestCraftableCountsPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CraftingVeloceMod.MODID, "request_craftable_counts"));

    public static final StreamCodec<FriendlyByteBuf, RequestCraftableCountsPKT> STREAM_CODEC =
            StreamCodec.of(RequestCraftableCountsPKT::encode, RequestCraftableCountsPKT::decode);

    private static void encode(FriendlyByteBuf buf, RequestCraftableCountsPKT pkt) {
        buf.writeBlockPos(pkt.pos);
        // We never send more than the other side will accept - otherwise our own
        // request would be rejected as malformed.
        if (pkt.items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException(
                    "RequestCraftableCountsPKT: too many items (" + pkt.items.size() + ")");
        }
        buf.writeInt(pkt.items.size());
        for (Item item : pkt.items) {
            buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(item));
        }
    }

    /**
     * The upper limit on the number of items in a single request.
     *
     * <p><b>Security.</b> {@code n} comes FROM THE CLIENT and before this change
     * it was used without any bound: {@code new ArrayList<>(n)} with a huge
     * {@code n} is an immediate allocation attempt (OutOfMemoryError), and the
     * reading loop could keep going until a buffer error. A malicious or simply
     * broken client could therefore take down the server with a single packet.
     *
     * <p>The limit is generous - the terminal sees at most a few dozen entries
     * per page, so a hundred with room to spare is enough.
     */
    public static final int MAX_ITEMS = 256;

    private static RequestCraftableCountsPKT decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int n = buf.readInt();
        // We reject requests outside a sensible range instead of trying to
        // allocate them. A negative value is an error here too, not "zero items".
        if (n < 0 || n > MAX_ITEMS) {
            throw new io.netty.handler.codec.DecoderException(
                    "RequestCraftableCountsPKT: invalid item count " + n
                            + " (max " + MAX_ITEMS + ")");
        }
        List<Item> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Item item = BuiltInRegistries.ITEM.get(buf.readResourceLocation());
            if (item != null) {
                items.add(item);
            }
        }
        return new RequestCraftableCountsPKT(pos, items);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestCraftableCountsPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // We do not count numbers for a terminal at the other end of the
            // world. Every other packet of ours on the server side has such a
            // safety catch - this one did not, and it is the only one that
            // orders the server to do real work (planning the recipe tree for
            // the visible page).
            if (player.distanceToSqr(pkt.pos().getX() + 0.5, pkt.pos().getY() + 0.5,
                    pkt.pos().getZ() + 0.5) > 64.0) {
                return;
            }
            // Branching by INTERFACE, not by a concrete block: thanks to that
            // the controller (and any future screen with numbers) works without
            // changing this packet.
            BlockEntity be = player.level().getBlockEntity(pkt.pos());
            if (!(be instanceof com.craftingveloce.block.entity.VeloceCraftCountSource source)) {
                return;
            }
            // The network of this machine - the cache comes from it (one per
            // network, shared by the terminal and the controller).
            var serverLevel = (net.minecraft.server.level.ServerLevel) player.level();
            var network = com.craftingveloce.network.pipe.VelocePipeNetworkManager
                    .get(serverLevel)
                    .getNetworkForTerminal(serverLevel, pkt.pos());

            // 1) IMMEDIATELY the cache: the player opens the GUI and instantly
            //    sees the numbers the network has already computed. Without this
            //    the counting of the visible page started from scratch and the
            //    numbers "came in" one after another.
            if (network != null) {
                var memo = network.getCraftableMemo();
                if (!memo.isEmpty()) {
                    PacketDistributor.sendToPlayer(player,
                            new SyncCraftableCountsPKT(pkt.pos(), memo, java.util.Map.of(), false));
                }
            }

            // 2) THE COUNT ITSELF GOES TO A WORKER THREAD.
            //
            // This used to be a synchronous call carrying a 25 ms budget, and the log is
            // unambiguous about what that produced:
            //
            //   instant craftable count for 38 item(s) -> 0 result(s) in 25 ms (complete=false)
            //   craftable count for minecraft:andesite: ran out of the estimation budget
            //       after checking up to 0 unit(s) (recipes=2, hi=383)
            //
            // Not ONE item of the page got a number. The work is a recipe-tree search and
            // the bisection runs it log2(upperBound) times per item, so milliseconds were
            // never going to be enough.
            //
            // The server thread now does only what MUST happen on it: capture a frozen
            // snapshot (one stock read, the countable set, the recipe closure). Everything
            // after that is arithmetic on that snapshot and runs off-thread without a time
            // budget - see VeloceCountWorker.
            java.util.List<Item> unknown = new java.util.ArrayList<>(pkt.items());
            if (unknown.isEmpty()) {
                return;
            }
            var snapshot = com.craftingveloce.crafting.VeloceCountSnapshot.capture(
                    serverLevel, network, unknown,
                    com.craftingveloce.crafting.VeloceCraftingRegistry
                            .getAllEnabledItems(serverLevel, network),
                    com.craftingveloce.crafting.VeloceCraftingRegistry
                            .getPreferredRecipes(serverLevel, network),
                    // The terminal gets the player as well: its numbers are "how many can I
                    // have", and what the player is carrying is part of that answer. The
                    // controller is unaffected - it has no player.
                    source instanceof com.craftingveloce.block.entity.VeloceTerminalBlockEntity
                            ? new com.craftingveloce.crafting.VelocePlayerInventory(player)
                            : null);

            // EVERY requested item is recomputed, including the ones the cache already
            // knows.
            //
            // This used to serve a cached value whenever there was one and compute only
            // the rest, on the theory that the cache is the answer. It is not: the cache
            // is what the network computed at some earlier moment, and everything that
            // decides a number moves without the screen moving - a machine gets charged,
            // a background pass finishes, the crafter feeds itself. A visible item whose
            // cached value was reused could therefore keep a number frozen for the whole
            // session, and the only way to shake it loose was to change the view.
            //
            // The instant answer is not lost: the cached snapshot was already sent just
            // above, so the player sees numbers immediately and these recomputed ones
            // replace them a moment later.
            long generation = GENERATIONS.merge(pkt.pos(), 1L, Long::sum);

            // ONE PAGE PER TERMINAL IN FLIGHT - keyed on the REQUEST, not on a bare flag.
            //
            // The screen re-requests its visible page every 20 ticks (VISIBLE_REFRESH_TICKS)
            // so the numbers keep up with a network that moves on its own. A page with ONE
            // expensive item can now take hundreds of milliseconds off-thread, so those
            // repeats pile up: the measured log was "queued=9 active=1", the queue filled,
            // and the rejection policy discarded the very request the screen was waiting for.
            //
            // Two attempts at a plain "is something running" boolean both failed, and the
            // second failure is the instructive one: the flag was set before submitting and
            // cleared only by the finishing job, so as soon as the page CHANGED (a search, a
            // tab, a scroll) the new page's request was skipped as a "repeat" of the old one
            // - and because the old job's answer was then discarded as stale, nothing ever
            // cleared the flag. Measured: six consecutive skips, the new page never computed.
            //
            // Keying on the requested item list fixes both halves at once: a repeat of the
            // SAME page is skipped (it carries no new information), while a request for a
            // DIFFERENT page always goes through.
            int pageSignature = pkt.items().hashCode();
            var running = IN_FLIGHT.get(pkt.pos());
            if (running != null && running.page() == pageSignature) {
                com.craftingveloce.util.VeloceLog.Craft.detail(
                        com.craftingveloce.util.VeloceLog.Side.SERVER,
                        "the same page for %s is already being counted - skipping this repeat",
                        pkt.pos().toShortString());
                return;
            }
            IN_FLIGHT.put(pkt.pos(), new InFlight(generation, pageSignature));
            // Remember WHICH page this terminal is now showing - this is what the arriving
            // answer is checked against (see PAGE_SIGNATURES).
            PAGE_SIGNATURES.put(pkt.pos(), pageSignature);

            final var level = serverLevel;
            final var net = network;
            final var requested = java.util.List.copyOf(pkt.items());
            final BlockPos pos = pkt.pos();
            com.craftingveloce.crafting.VeloceCountWorker.submit(player.server, snapshot,
                    unknown, generation, (computed, gen) -> {
                        // ---- BACK ON THE SERVER THREAD ----
                        // CLEAR FIRST, whatever generation this is. The mark means "a count
                        // for this terminal is being worked on", so whoever finishes clears
                        // it; clearing only when the job was still the newest left a
                        // superseded job's mark behind and disabled counting for this
                        // terminal permanently (the new page was then skipped as a repeat of
                        // the old one, forever).
                        IN_FLIGHT.remove(pos);
                        // THE FRESHLY COUNTED PAGE GOES INTO THE CACHE, here in the handler
                        // body and on the server thread.
                        //
                        // It used to live in deliverCounts, which is also called with the
                        // cached snapshot itself - so writing it there would re-store what
                        // was just read. More importantly the cache must be written by the
                        // code that OWNS the decision, not by a helper that also serves the
                        // instant path: `validate_craftable_cache` checks exactly this link,
                        // because a cache that never fills up makes the instant answer
                        // disappear silently and the numbers go back to loading one by one.
                        if (net != null && !computed.counts().isEmpty()) {
                            net.rememberCraftable(computed.counts());
                            // The cache has to survive a world restart - without setDirty
                            // SavedData will not be written (see markDirty).
                            com.craftingveloce.network.pipe.VelocePipeNetworkManager
                                    .get(level).markDirty();
                        }
                        // A newer request for the same terminal has already been made, so
                        // IS THIS ANSWER STILL ABOUT THE PAGE ON SCREEN?
                        //
                        // Compare the PAGE, not the generation counter. The counter moves on
                        // every request, including the screen's own periodic refresh
                        // (VISIBLE_REFRESH_TICKS, every 20 ticks) - so a slow but perfectly
                        // valid answer was ALWAYS one generation behind by the time it
                        // arrived, and was thrown away. Measured:
                        //
                        //   count worker: 35 item(s) ... in 566 ms (complete=true)
                        //   dropping a stale counted page for BlockPos{...} (gen 14, now 15)
                        //
                        // The numbers were computed correctly and then discarded, forever, so
                        // the screen showed "=none" for every icon while the worker was doing
                        // its job. That is the same user-visible symptom as a failed count,
                        // which is why it survived several rounds of looking at the counting.
                        //
                        // What actually matters is whether the answer describes the page the
                        // player is looking at NOW. If the requested items are still on
                        // screen, the answer is good however many repeats happened meanwhile
                        // (a repeat asks for the SAME items - that is the whole point of the
                        // in-flight guard above). If the page changed, it is stale.
                        Integer currentPage = PAGE_SIGNATURES.get(pos);
                        if (currentPage != null && currentPage != pageSignature) {
                            com.craftingveloce.util.VeloceLog.Craft.detail(
                                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                                    "dropping a counted page for %s - the screen moved to a "
                                            + "different page while it was computed", pos);
                            return;
                        }
                        deliverCounts(player, level, net, pos, requested,
                                computed.counts(), computed.complete());
                    }, pos.toShortString());
        });
    }

    /**
     * The page (item list) most recently requested per terminal.
     *
     * <p>This - not the generation counter - is what decides whether an arriving answer is
     * still wanted. See the note at the check: a counter cannot distinguish "the screen
     * asked again for the same items" from "the screen moved on", and treating the first as
     * stale discarded every valid answer on a slow page.
     */
    private static final java.util.concurrent.ConcurrentHashMap<BlockPos, Integer> PAGE_SIGNATURES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Request generation per terminal - the guard against a stale answer.
     *
     * <p>A worker result can arrive AFTER the player has already asked about a different
     * page. Without this the older result would be sent second and the newer numbers would
     * be overwritten by older ones - a number that is right, then wrong, then right again
     * as the player scrolls.
     */
    private static final java.util.concurrent.ConcurrentHashMap<BlockPos, Long> GENERATIONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The newest generation handed to the worker per terminal, i.e. "is a count running".
     *
     * <p>Used to skip the screen's periodic repeat requests while the previous one is still
     * being computed - see the long note in the handler. It is not a lock: it only ever
     * decides whether to START work, and a stale entry costs one skipped refresh.
     */
    private static final java.util.concurrent.ConcurrentHashMap<BlockPos, InFlight> IN_FLIGHT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A count currently being worked on for one terminal.
     *
     * @param generation which request this is (for the stale-answer check)
     * @param page       hashCode of the requested item list - what "the same page" means
     */
    private record InFlight(long generation, int page) {
    }

    /**
     * Sends the counted page to the client. <b>Server thread only.</b>
     *
     * <p>Split out of the handler because it now runs in two situations that are far apart
     * in time: the immediate cached answer, and the worker's result arriving later.
     */
    private static void deliverCounts(ServerPlayer player, net.minecraft.server.level.ServerLevel level,
                                      com.craftingveloce.network.pipe.VelocePipeNetwork network,
                                      BlockPos pos, java.util.List<Item> requested,
                                      java.util.Map<Item, Long> counts, boolean complete) {
        // WHICH MODS COULD MAKE THE UNAVAILABLE ONES.
        //
        // Computed HERE and not from the stock delta, and that is the whole point: an
        // item the GUI paints red is by definition one the network does NOT have, so it
        // never appears in a stock change - the first attempt at this sent the list
        // alongside the stock and the tooltip was empty for every red item.
        //
        // Only items that came out at zero are asked about: those are the red ones, and
        // the work is then bounded by what is actually on screen rather than by the
        // request.
        java.util.Map<Item, String> madeBy = new java.util.HashMap<>();
        for (Item shown : requested) {
            // ONLY the count decides. There was a third clause here - "skip it if the
            // crafter has it enabled" - and it was exactly backwards: an item is painted
            // RED because it is NOT in that set, so the clause discarded precisely the
            // items the tooltip asks about and every red icon came back with an empty
            // list. It also called `getAllEnabledItems` once per item, which walks the
            // whole module registry each time.
            if (counts.getOrDefault(shown, 0L) > 0L || madeBy.containsKey(shown)) {
                continue;   // available, or already asked about
            }
            madeBy.put(shown, com.craftingveloce.crafting.VeloceCraftingRegistry
                    .modsThatCanMake(level, shown));
        }
        PacketDistributor.sendToPlayer(player,
                new SyncCraftableCountsPKT(pos, counts, madeBy, complete));
    }
}
