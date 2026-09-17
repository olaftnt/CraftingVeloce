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
 * C->S: the player opened or closed the terminal.
 *
 * <p><b>Why this exists.</b> The terminal kept a "who is watching" list and
 * sent it the full stock map (plus the craftability map) every second. Entries
 * disappeared only when the player disconnected or walked further than 64
 * blocks - so after closing the GUI, a player standing next to the terminal
 * kept receiving those packets ENDLESSLY, even though they were not looking at
 * anything. With a large network that is several kilobytes per second per
 * terminal, plus copying both maps and serializing them on the server side.
 *
 * <p>Now the client says explicitly when it stopped watching (see
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
            // Safety check: we do not take the client's word on distance.
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
