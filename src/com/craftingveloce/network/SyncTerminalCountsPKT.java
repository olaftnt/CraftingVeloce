package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.client.ClientTerminalHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;

/**
 * S→C: liczby itemow w sieci.
 *
 * <p>Niesie DWIE mapy:
 * <ul>
 *   <li>{@code itemCounts} - ile sztuk fizycznie jest w sieci (zielona liczba)</li>
 *   <li>{@code craftableCounts} - ile sztuk da sie jeszcze dorobic
 *       auto-craftingiem z tego, co jest (zolta liczba "+N").
 *       Zero/brak wpisu = nie da sie nic dorobic.</li>
 * </ul>
 */
public record SyncTerminalCountsPKT(Map<Item, Long> itemCounts,
                                    Map<Item, Long> craftableCounts)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncTerminalCountsPKT> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CraftingVeloceMod.MODID, "sync_terminal_counts"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncTerminalCountsPKT> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SyncTerminalCountsPKT decode(RegistryFriendlyByteBuf buf) {
            Map<Item, Long> counts = readMap(buf);
            Map<Item, Long> craftable = readMap(buf);
            return new SyncTerminalCountsPKT(counts, craftable);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, SyncTerminalCountsPKT pkt) {
            writeMap(buf, pkt.itemCounts());
            writeMap(buf, pkt.craftableCounts());
        }

        private Map<Item, Long> readMap(RegistryFriendlyByteBuf buf) {
            int size = buf.readVarInt();
            Map<Item, Long> map = new HashMap<>(size);
            for (int i = 0; i < size; i++) {
                int itemId = buf.readVarInt();
                long count = buf.readVarLong();
                Item item = BuiltInRegistries.ITEM.byId(itemId);
                if (item != null) {
                    map.put(item, count);
                }
            }
            return map;
        }

        private void writeMap(RegistryFriendlyByteBuf buf, Map<Item, Long> map) {
            buf.writeVarInt(map.size());
            for (Map.Entry<Item, Long> entry : map.entrySet()) {
                buf.writeVarInt(BuiltInRegistries.ITEM.getId(entry.getKey()));
                buf.writeVarLong(entry.getValue());
            }
        }
    };

    /** Wsteczna zgodnosc: sam stock, bez craftable. */
    public SyncTerminalCountsPKT(Map<Item, Long> itemCounts) {
        this(itemCounts, Map.of());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncTerminalCountsPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> ClientTerminalHelper.handleSyncCounts(
                pkt.itemCounts(), pkt.craftableCounts()));
    }
}
