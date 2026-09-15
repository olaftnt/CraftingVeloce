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

public record OpenCraftingTableScreenPKT(BlockPos pos, Set<Item> enabledItems) implements CustomPacketPayload {

    public static final Type<OpenCraftingTableScreenPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("craftingveloce", "open_crafting_table_screen"));

    public static final StreamCodec<FriendlyByteBuf, OpenCraftingTableScreenPKT> STREAM_CODEC =
            StreamCodec.of(OpenCraftingTableScreenPKT::encode, OpenCraftingTableScreenPKT::decode);

    private static void encode(FriendlyByteBuf buf, OpenCraftingTableScreenPKT pkt) {
        buf.writeBlockPos(pkt.pos);
        buf.writeInt(pkt.enabledItems.size());
        for (Item item : pkt.enabledItems) {
            buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(item));
        }
    }

    private static OpenCraftingTableScreenPKT decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int count = buf.readInt();
        Set<Item> items = new HashSet<>();
        for (int i = 0; i < count; i++) {
            ResourceLocation rl = buf.readResourceLocation();
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item != null) items.add(item);
        }
        return new OpenCraftingTableScreenPKT(pos, items);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenCraftingTableScreenPKT pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            com.craftingveloce.client.ClientTerminalHelper.openCraftingTableScreen(pkt.pos(), pkt.enabledItems());
        });
    }
}
