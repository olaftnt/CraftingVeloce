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
                            new SyncCraftableCountsPKT(pkt.pos(), memo, false));
                }
            }

            // 2) Only now the current logic: it counts the VISIBLE page and
            //    writes the result into the cache (the cache teaches itself -
            //    without a separate background build and without loading the
            //    server).
            // Point 3: we count ONLY what is NOT in the network cache. Once
            // computed, a number is shared by the whole network, so a second
            // terminal (or the same GUI opened a second time) does not count
            // the same thing from scratch - it gets the entry from the cache.
            java.util.Map<Item, Long> result = new java.util.HashMap<>();
            java.util.List<Item> unknown = new java.util.ArrayList<>(pkt.items());
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
            // replace them a moment later. That is the point of carrying the VISIBLE list
            // in the packet - the screen's own items are the priority, and the cache
            // exists for everything else.
            boolean complete = true;
            if (!unknown.isEmpty()) {
                var computed = source.computeCraftableCounts(unknown);
                result.putAll(computed.counts());
                complete = computed.complete();
                if (network != null) {
                    network.rememberCraftable(computed.counts());
                    // The cache has to survive a world restart - without setDirty
                    // SavedData will not be written (see markDirty).
                    com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(serverLevel)
                            .markDirty();
                }
            }
            PacketDistributor.sendToPlayer(player,
                    new SyncCraftableCountsPKT(pkt.pos(), result, complete));
        });
    }
}
