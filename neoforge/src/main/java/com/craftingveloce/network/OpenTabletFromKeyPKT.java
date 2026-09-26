package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.item.VeloceTabletItem;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record OpenTabletFromKeyPKT() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenTabletFromKeyPKT> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CraftingVeloceMod.MODID, "open_tablet_from_key"));

    public static final StreamCodec<ByteBuf, OpenTabletFromKeyPKT> STREAM_CODEC =
            StreamCodec.unit(new OpenTabletFromKeyPKT());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenTabletFromKeyPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                VeloceTabletItem.openBestTablet(serverPlayer);
            }
        });
    }
}
