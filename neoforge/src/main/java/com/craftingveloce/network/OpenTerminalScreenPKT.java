package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record OpenTerminalScreenPKT(BlockPos terminalPos) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenTerminalScreenPKT> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CraftingVeloceMod.MODID, "open_terminal_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenTerminalScreenPKT> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenTerminalScreenPKT::terminalPos,
            OpenTerminalScreenPKT::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenTerminalScreenPKT pkt, IPayloadContext context) {
        com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                "[Veloce] OpenTerminalScreenPKT received: {}", pkt.terminalPos());
        context.enqueueWork(() -> {
            com.craftingveloce.client.ClientTerminalHelper.openTerminalScreen(pkt.terminalPos());
        });
    }
}
