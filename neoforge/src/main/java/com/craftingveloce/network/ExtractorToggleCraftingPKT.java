package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceExtractorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C->S: toggles auto-crafting for a single extractor filter slot.
 *
 * <p>A right-click on an OCCUPIED filter slot changes only whether the extractor
 * may order a craft from the crafter for this item, or should take only what already
 * lies in the network. The item selection itself stays unchanged.
 */
public record ExtractorToggleCraftingPKT(BlockPos pos, int filterIndex) implements CustomPacketPayload {

    public static final Type<ExtractorToggleCraftingPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CraftingVeloceMod.MODID, "extractor_toggle_crafting"));

    public static final StreamCodec<FriendlyByteBuf, ExtractorToggleCraftingPKT> STREAM_CODEC =
            StreamCodec.of(ExtractorToggleCraftingPKT::encode, ExtractorToggleCraftingPKT::decode);

    private static void encode(FriendlyByteBuf buf, ExtractorToggleCraftingPKT pkt) {
        buf.writeBlockPos(pkt.pos);
        buf.writeVarInt(pkt.filterIndex);
    }

    private static ExtractorToggleCraftingPKT decode(FriendlyByteBuf buf) {
        return new ExtractorToggleCraftingPKT(buf.readBlockPos(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ExtractorToggleCraftingPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (player.distanceToSqr(pkt.pos().getX() + 0.5, pkt.pos().getY() + 0.5,
                    pkt.pos().getZ() + 0.5) > 64.0) {
                return;
            }
            if (pkt.filterIndex() < 0 || pkt.filterIndex() >= 9) {
                return;
            }
            BlockEntity be = player.level().getBlockEntity(pkt.pos());
            if (be instanceof VeloceExtractorBlockEntity extractor) {
                extractor.toggleCraftingAllowed(pkt.filterIndex());
            }
        });
    }
}
