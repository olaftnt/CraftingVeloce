package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;

/**
 * S->C: a response with the "how many more can be made" counts for the given items.
 *
 * <p>Sent only in response to {@link RequestCraftableCountsPKT}, that is, for
 * the items currently visible in the terminal.
 *
 * <p>The {@code complete} field says whether the server ACTUALLY got through all
 * the requested items. When the network is not ready yet (no crafter, no
 * network, an interrupted budget), the response has {@code complete = false} and
 * the client does NOT discard the previous values - otherwise the counts
 * disappeared and never came back.
 */
public record SyncCraftableCountsPKT(BlockPos pos, Map<Item, Long> counts, boolean complete)
        implements CustomPacketPayload {

    public static final Type<SyncCraftableCountsPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CraftingVeloceMod.MODID, "sync_craftable_counts"));

    public static final StreamCodec<FriendlyByteBuf, SyncCraftableCountsPKT> STREAM_CODEC =
            StreamCodec.of(SyncCraftableCountsPKT::encode, SyncCraftableCountsPKT::decode);

    private static void encode(FriendlyByteBuf buf, SyncCraftableCountsPKT pkt) {
        buf.writeBlockPos(pkt.pos);
        buf.writeBoolean(pkt.complete);
        buf.writeInt(pkt.counts.size());
        for (Map.Entry<Item, Long> e : pkt.counts.entrySet()) {
            buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(e.getKey()));
            buf.writeVarLong(e.getValue());
        }
    }

    private static SyncCraftableCountsPKT decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean complete = buf.readBoolean();
        int n = buf.readInt();
        Map<Item, Long> counts = new HashMap<>(n);
        for (int i = 0; i < n; i++) {
            Item item = BuiltInRegistries.ITEM.get(buf.readResourceLocation());
            long v = buf.readVarLong();
            if (item != null) {
                counts.put(item, v);
            }
        }
        return new SyncCraftableCountsPKT(pos, counts, complete);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncCraftableCountsPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> com.craftingveloce.client.ClientTerminalHelper
                .handleCraftableCounts(pkt.pos(), pkt.counts(), pkt.complete()));
    }
}
