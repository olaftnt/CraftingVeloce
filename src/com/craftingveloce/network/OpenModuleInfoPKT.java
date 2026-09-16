package com.craftingveloce.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S-&gt;C: otwiera GUI modulu (predkosc, SU, sieć).
 *
 * <p>Dane liczy SERWER (ma prawdziwe liczby sieci kinetycznej i rur) i wysyla
 * je razem z otwarciem okna. Klient ich nie zgaduje ani nie dopytywuje co
 * klatke - okno dostaje gotowy zestaw pol.
 */
public record OpenModuleInfoPKT(BlockPos pos, CompoundTag info) implements CustomPacketPayload {

    public static final Type<OpenModuleInfoPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("craftingveloce", "open_module_info"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenModuleInfoPKT> STREAM_CODEC =
            StreamCodec.of(OpenModuleInfoPKT::encode, OpenModuleInfoPKT::decode);

    private static void encode(RegistryFriendlyByteBuf buf, OpenModuleInfoPKT pkt) {
        buf.writeBlockPos(pkt.pos);
        buf.writeNbt(pkt.info);
    }

    private static OpenModuleInfoPKT decode(RegistryFriendlyByteBuf buf) {
        return new OpenModuleInfoPKT(buf.readBlockPos(), buf.readNbt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenModuleInfoPKT pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.craftingveloce.client.gui.VeloceModuleInfoScreen
                .open(pkt.pos(), pkt.info()));
    }
}
