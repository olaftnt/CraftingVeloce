package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceTerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C->S: the player puts items into the network storage (the "arrow" slot in the
 * GUI).
 *
 * <p><b>The packet carries ONLY INTENT, not items.</b> This matters.
 *
 * <p>The previous version sent a copy of the stack from the client, and the
 * server put it into the network - but NEVER took the item away from the player.
 * The item therefore existed both in the barrel and in the inventory at the same
 * time: a physical DUPLICATION. On top of that the client cleared the cursor
 * only on its own side, so the server handed it back at the next
 * synchronisation.
 *
 * <p>Now the server reads the player state itself, takes away exactly as much as
 * it managed to insert, and sends the changes back. The client does not touch
 * anything locally.
 */
public record TerminalStoreItemPKT(BlockPos terminalPos, int mode) implements CustomPacketPayload {

    /** What the player is holding on the cursor. */
    public static final int MODE_CURSOR = 0;
    /** The whole inventory EXCEPT the hotbar. */
    public static final int MODE_INVENTORY = 1;
    /** Literally everything, hotbar included. */
    public static final int MODE_EVERYTHING = 2;

    public static final Type<TerminalStoreItemPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CraftingVeloceMod.MODID, "terminal_store_item"));

    public static final StreamCodec<FriendlyByteBuf, TerminalStoreItemPKT> STREAM_CODEC =
            StreamCodec.of(TerminalStoreItemPKT::encode, TerminalStoreItemPKT::decode);

    private static void encode(FriendlyByteBuf buf, TerminalStoreItemPKT pkt) {
        buf.writeBlockPos(pkt.terminalPos);
        buf.writeVarInt(pkt.mode);
    }

    private static TerminalStoreItemPKT decode(FriendlyByteBuf buf) {
        return new TerminalStoreItemPKT(buf.readBlockPos(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TerminalStoreItemPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (player.distanceToSqr(pkt.terminalPos().getX() + 0.5, pkt.terminalPos().getY() + 0.5,
                    pkt.terminalPos().getZ() + 0.5) > 64.0) {
                if (!com.craftingveloce.item.VeloceTabletItem.hasValidTabletFor(player, pkt.terminalPos())) {
                    return;
                }
            }
            if (pkt.mode() < MODE_CURSOR || pkt.mode() > MODE_EVERYTHING) {
                return;
            }
            BlockEntity be = player.level().getBlockEntity(pkt.terminalPos());
            if (be instanceof VeloceTerminalBlockEntity terminal) {
                terminal.storeFromPlayer(player, pkt.mode());
            }
        });
    }
}
