package com.craftingveloce.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashSet;
import java.util.Set;

public record SyncCraftingTableStatePKT(BlockPos pos, Set<Item> enabledItems) implements CustomPacketPayload {

    public static final Type<SyncCraftingTableStatePKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("craftingveloce", "sync_crafting_table_state"));

    public static final StreamCodec<FriendlyByteBuf, SyncCraftingTableStatePKT> STREAM_CODEC =
            StreamCodec.of(SyncCraftingTableStatePKT::encode, SyncCraftingTableStatePKT::decode);

    private static void encode(FriendlyByteBuf buf, SyncCraftingTableStatePKT pkt) {
        buf.writeBlockPos(pkt.pos);
        buf.writeInt(pkt.enabledItems.size());
        for (Item item : pkt.enabledItems) {
            buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(item));
        }
    }

    private static SyncCraftingTableStatePKT decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int count = buf.readInt();
        Set<Item> items = new HashSet<>();
        for (int i = 0; i < count; i++) {
            ResourceLocation rl = buf.readResourceLocation();
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item != null) items.add(item);
        }
        return new SyncCraftingTableStatePKT(pos, items);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncCraftingTableStatePKT pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            com.craftingveloce.client.ClientTerminalHelper.updateCraftingTableState(pkt.pos(), pkt.enabledItems());
        });
    }
}
