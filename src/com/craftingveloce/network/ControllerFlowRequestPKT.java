package com.craftingveloce.network;

import com.craftingveloce.block.entity.VeloceControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C→S: "odswiez mi tempo przeplywu w kontrolerze".
 *
 * <p>Klient wysyla to raz na sekunde, dopoki ma otwarte GUI kontrolera.
 *
 * <p><b>Dlaczego zapytanie, a nie subskrypcja.</b> Serwer nie musi pamietac,
 * kto patrzy - a wiec nie ma czego zgubic, gdy klient padnie, wyjdzie z gry
 * albo teleportuje sie poza zasieg. Serwer robi dokladnie tyle pracy, o ile
 * ktos poprosil, i tylko w tym momencie.
 *
 * <p>Celowo OSOBNY pakiet od {@link OpenControllerScreenPKT}: tamten niesie
 * stock i trzy zbiory itemow (ciezkie, zmieniaja sie rzadko), a tutaj leci
 * tylko mapa temp. Wysylanie pelnego obrazu sieci co sekunde bylo by
 * marnowaniem pasma dokladnie po to, zeby odswiezyc kilka liczb.
 */
public record ControllerFlowRequestPKT(BlockPos pos) implements CustomPacketPayload {

    public static final Type<ControllerFlowRequestPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("craftingveloce", "controller_flow_request"));

    public static final StreamCodec<FriendlyByteBuf, ControllerFlowRequestPKT> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> buf.writeBlockPos(pkt.pos),
                    buf -> new ControllerFlowRequestPKT(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ControllerFlowRequestPKT pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.level() instanceof ServerLevel sl)) {
                return;
            }
            // Zasieg: nie obslugujemy zapytan o kontroler, ktorego gracz nie ma
            // nawet w zaladowanym chunku - inaczej kazdy moglby zamowic prace
            // serwera dla dowolnej pozycji w swiecie.
            if (!player.blockPosition().closerThan(pkt.pos(),
                    VeloceControllerBlockEntity.MAX_FLOW_REQUEST_DISTANCE)) {
                return;
            }
            if (sl.getBlockEntity(pkt.pos()) instanceof VeloceControllerBlockEntity ctrl) {
                ctrl.sendFlowTo(player);
            }
        });
    }
}
