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

public record SyncTerminalCountsPKT(Map<Item, Long> itemCounts) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncTerminalCountsPKT> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CraftingVeloceMod.MODID, "sync_terminal_counts"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncTerminalCountsPKT> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SyncTerminalCountsPKT decode(RegistryFriendlyByteBuf buf) {
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
            return new SyncTerminalCountsPKT(map);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, SyncTerminalCountsPKT pkt) {
            Map<Item, Long> map = pkt.itemCounts();
            buf.writeVarInt(map.size());
            for (Map.Entry<Item, Long> entry : map.entrySet()) {
                int itemId = BuiltInRegistries.ITEM.getId(entry.getKey());
                buf.writeVarInt(itemId);
                buf.writeVarLong(entry.getValue());
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncTerminalCountsPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientTerminalHelper.handleSyncCounts(pkt.itemCounts());
        });
    }
}
