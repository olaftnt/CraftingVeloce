package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C→S: prosba o otwarcie magazynu auto-craftera.
 *
 * <p>Klient nie moze sam otworzyc ekranu kontenera - musi o to poprosic
 * serwer, bo to serwer tworzy menu i wysyla jego zawartosc.
 */
public record OpenStorageRequestPKT(BlockPos pos) implements CustomPacketPayload {

    public static final Type<OpenStorageRequestPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CraftingVeloceMod.MODID, "open_storage_request"));

    public static final StreamCodec<FriendlyByteBuf, OpenStorageRequestPKT> STREAM_CODEC =
            StreamCodec.of(OpenStorageRequestPKT::encode, OpenStorageRequestPKT::decode);

    private static void encode(FriendlyByteBuf buf, OpenStorageRequestPKT pkt) {
        buf.writeBlockPos(pkt.pos);
    }

    private static OpenStorageRequestPKT decode(FriendlyByteBuf buf) {
        return new OpenStorageRequestPKT(buf.readBlockPos());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenStorageRequestPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                com.craftingveloce.util.VeloceLog.Gui.success(
                        com.craftingveloce.util.VeloceLog.Side.SERVER,
                        "player requested crafter storage at %s", pkt.pos());
                OpenCrafterStoragePKT.openFor(sp, pkt.pos());
            }
        });
    }
}
