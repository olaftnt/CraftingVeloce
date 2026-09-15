package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceTomTerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C→S: gracz otworzyl albo zamknal terminal.
 *
 * <p><b>Po co to jest.</b> Terminal trzymal liste "kto patrzy" i wysylal jej
 * pelna mape stocku (plus mape craftowalnosci) co sekunde. Wpisy znikaly tylko
 * gdy gracz sie rozlaczyl albo odszedl dalej niz 64 klocki - czyli po zamknieciu
 * GUI gracz stojacy obok terminala dostawal te pakiety BEZ KOŃCA, mimo ze nic
 * nie ogladal. Przy duzej sieci to kilka kilobajtow na sekunde na terminal,
 * plus kopiowanie obu map i pakowanie ich po stronie serwera.
 *
 * <p>Teraz klient mowi wprost, kiedy przestal patrzec (patrz
 * {@code VeloceTerminalScreen.removed()}).
 */
public record TerminalWatcherPKT(BlockPos pos, boolean watching) implements CustomPacketPayload {

    public static final Type<TerminalWatcherPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CraftingVeloceMod.MODID, "terminal_watcher"));

    public static final StreamCodec<FriendlyByteBuf, TerminalWatcherPKT> STREAM_CODEC =
            StreamCodec.of(TerminalWatcherPKT::encode, TerminalWatcherPKT::decode);

    private static void encode(FriendlyByteBuf buf, TerminalWatcherPKT pkt) {
        buf.writeBlockPos(pkt.pos);
        buf.writeBoolean(pkt.watching);
    }

    private static TerminalWatcherPKT decode(FriendlyByteBuf buf) {
        return new TerminalWatcherPKT(buf.readBlockPos(), buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TerminalWatcherPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // Bezpiecznik: nie ufamy klientowi na slowo co do odleglosci.
            if (player.distanceToSqr(pkt.pos().getX() + 0.5, pkt.pos().getY() + 0.5,
                    pkt.pos().getZ() + 0.5) > 256.0) {
                return;
            }
            BlockEntity be = player.level().getBlockEntity(pkt.pos());
            if (be instanceof VeloceTomTerminalBlockEntity terminal) {
                terminal.setPlayerWatching(player, pkt.watching());
            }
        });
    }
}
