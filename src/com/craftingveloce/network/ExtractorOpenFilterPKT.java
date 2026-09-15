package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ExtractorOpenFilterPKT(BlockPos pos, int filterIndex) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ExtractorOpenFilterPKT> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CraftingVeloceMod.MODID, "extractor_open_filter"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExtractorOpenFilterPKT> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ExtractorOpenFilterPKT::pos,
            ByteBufCodecs.VAR_INT, ExtractorOpenFilterPKT::filterIndex,
            ExtractorOpenFilterPKT::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ExtractorOpenFilterPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (player.distanceToSqr(pkt.pos().getX() + 0.5, pkt.pos().getY() + 0.5, pkt.pos().getZ() + 0.5) > 64.0) {
                return;
            }
            // Send back to client to open the picker
            PacketDistributor.sendToPlayer(player, new OpenFilterPickerPKT(pkt.pos(), pkt.filterIndex()));
        });
    }
}
