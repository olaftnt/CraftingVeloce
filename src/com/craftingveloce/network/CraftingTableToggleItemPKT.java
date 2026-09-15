package com.craftingveloce.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity;

public record CraftingTableToggleItemPKT(BlockPos pos, Item item) implements CustomPacketPayload {

    public static final Type<CraftingTableToggleItemPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("craftingveloce", "crafting_table_toggle_item"));

    public static final StreamCodec<FriendlyByteBuf, CraftingTableToggleItemPKT> STREAM_CODEC =
            StreamCodec.of(CraftingTableToggleItemPKT::encode, CraftingTableToggleItemPKT::decode);

    private static void encode(FriendlyByteBuf buf, CraftingTableToggleItemPKT pkt) {
        buf.writeBlockPos(pkt.pos);
        buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(pkt.item));
    }

    private static CraftingTableToggleItemPKT decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        ResourceLocation rl = buf.readResourceLocation();
        Item item = BuiltInRegistries.ITEM.get(rl);
        return new CraftingTableToggleItemPKT(pos, item);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CraftingTableToggleItemPKT pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) {
                ServerLevel level = sp.serverLevel();
                BlockEntity be = level.getBlockEntity(pkt.pos());
                if (be instanceof VeloceCraftingTableBlockEntity ctBE) {
                    ctBE.toggleItem(pkt.item());
                    ctBE.syncToWatchers(level);
                }
            }
        });
    }
}
