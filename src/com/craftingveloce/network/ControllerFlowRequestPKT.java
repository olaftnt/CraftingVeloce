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
 * C->S: "refresh the flow rate in the controller for me".
 *
 * <p>The client sends this once per second, as long as it has the controller GUI
 * open.
 *
 * <p><b>Why a request and not a subscription.</b> The server does not have to
 * remember who is watching - and therefore has nothing to lose when the client
 * crashes, leaves the game or teleports out of range. The server does exactly as
 * much work as someone asked for, and only at that moment.
 *
 * <p>Deliberately a SEPARATE packet from {@link OpenControllerScreenPKT}: that
 * one carries the stock and three sets of items (heavy, they change rarely),
 * while here only the map of rates travels. Sending a full picture of the network
 * every second would be a waste of bandwidth just to refresh a few numbers.
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
            // Range: we do not serve requests for a controller the player does not
            // even have in a loaded chunk - otherwise anyone could order server
            // work for any position in the world.
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
