package com.craftingveloce.network;

import com.craftingveloce.crafting.VeloceFlowTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * S->C: flow rate and STOCK CHANGE in the controller.
 *
 * <p><b>Rate.</b> The server sends back only the items that have a one-way
 * gain/loss ({@link VeloceFlowTracker#steadyRates()}) - a missing entry means
 * "standing still or fluctuating" and then the tooltip shows no rate line at
 * all.
 *
 * <p><b>Stock as a DIFFERENCE, not the whole.</b> Previously every response
 * (once per second) carried the WHOLE picture of the network. On a network with
 * thousands of different items that is thousands of entries per second, even
 * though the stock almost never changes - the larger the network, the more
 * traffic, with no benefit at all. Now only the changed entries
 * ({@code changed}) and the items that disappeared ({@code removed}) are sent -
 * see {@link com.craftingveloce.crafting.VeloceStockDeltas}.
 *
 * <p>{@code full} tells the client to REPLACE the stock with what arrived (a
 * full dump once a minute) rather than merge it with the previous one. Values in
 * {@code changed} are ABSOLUTE, so applying the same difference twice breaks
 * nothing - several players can look at the same controller and each has its own
 * copy.
 */
public record SyncControllerFlowPKT(BlockPos pos,
                                    Map<Item, Long> changed,
                                    Set<Item> removed,
                                    Map<Item, Float> rates,
                                    boolean full)
        implements CustomPacketPayload {

    public static final Type<SyncControllerFlowPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("craftingveloce", "sync_controller_flow"));

    public static final StreamCodec<FriendlyByteBuf, SyncControllerFlowPKT> STREAM_CODEC =
            StreamCodec.of(SyncControllerFlowPKT::encode, SyncControllerFlowPKT::decode);

    /** We store the rate as hundredths of a piece - a float in the packet is 4
     *  bytes of noise, while a varint of hundredths is smaller and exactly as
     *  precise as the tooltip shows. */
    private static final float RATE_SCALE = 100f;

    private static void encode(FriendlyByteBuf buf, SyncControllerFlowPKT pkt) {
        buf.writeBlockPos(pkt.pos);
        buf.writeBoolean(pkt.full);
        writeStock(buf, pkt.changed);
        writeRemoved(buf, pkt.removed);
        writeRates(buf, pkt.rates);
    }

    private static SyncControllerFlowPKT decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean full = buf.readBoolean();
        Map<Item, Long> changed = readStock(buf);
        Set<Item> removed = readRemoved(buf);
        Map<Item, Float> rates = readRates(buf);
        return new SyncControllerFlowPKT(pos, changed, removed, rates, full);
    }

    /** Stock: the same format as in SyncTerminalCountsPKT (item id + varint long). */
    private static void writeStock(FriendlyByteBuf buf, Map<Item, Long> stock) {
        buf.writeVarInt(stock.size());
        for (Map.Entry<Item, Long> e : stock.entrySet()) {
            buf.writeVarInt(BuiltInRegistries.ITEM.getId(e.getKey()));
            buf.writeVarLong(e.getValue());
        }
    }

    private static Map<Item, Long> readStock(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<Item, Long> out = new HashMap<>(Math.max(4, size));
        for (int i = 0; i < size; i++) {
            Item item = BuiltInRegistries.ITEM.byId(buf.readVarInt());
            long count = buf.readVarLong();
            if (item != null) {
                out.put(item, count);
            }
        }
        return out;
    }

    /** Removed items: identifiers only - their counts are gone. */
    private static void writeRemoved(FriendlyByteBuf buf, Set<Item> removed) {
        buf.writeVarInt(removed.size());
        for (Item item : removed) {
            buf.writeVarInt(BuiltInRegistries.ITEM.getId(item));
        }
    }

    private static Set<Item> readRemoved(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Set<Item> out = new HashSet<>(Math.max(4, size));
        for (int i = 0; i < size; i++) {
            Item item = BuiltInRegistries.ITEM.byId(buf.readVarInt());
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    /** Rate: item id + hundredths of a piece per second. */
    private static void writeRates(FriendlyByteBuf buf, Map<Item, Float> rates) {
        buf.writeVarInt(rates.size());
        for (Map.Entry<Item, Float> e : rates.entrySet()) {
            buf.writeVarInt(BuiltInRegistries.ITEM.getId(e.getKey()));
            buf.writeVarInt(Math.round(e.getValue() * RATE_SCALE));
        }
    }

    private static Map<Item, Float> readRates(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<Item, Float> out = new HashMap<>(Math.max(4, size));
        for (int i = 0; i < size; i++) {
            Item item = BuiltInRegistries.ITEM.byId(buf.readVarInt());
            float rate = buf.readVarInt() / RATE_SCALE;
            if (item != null) {
                out.put(item, rate);
            }
        }
        return out;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncControllerFlowPKT pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (net.minecraft.client.Minecraft.getInstance().screen
                    instanceof com.craftingveloce.client.gui.VeloceControllerScreen screen) {
                screen.updateFlow(pkt.pos(), pkt.changed(), pkt.removed(),
                        pkt.rates(), pkt.full());
            }
        });
    }
}
