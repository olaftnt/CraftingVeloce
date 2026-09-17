package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record OpenFilterPickerPKT(BlockPos pos, int filterIndex) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenFilterPickerPKT> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CraftingVeloceMod.MODID, "open_filter_picker"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenFilterPickerPKT> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenFilterPickerPKT::pos,
            ByteBufCodecs.VAR_INT, OpenFilterPickerPKT::filterIndex,
            OpenFilterPickerPKT::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenFilterPickerPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            com.craftingveloce.client.ClientTerminalHelper.openFilterPickerScreen(pkt.pos(), pkt.filterIndex());
        });
    }
}
