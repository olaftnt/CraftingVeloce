package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceExtractorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ExtractorSetFilterPKT(BlockPos pos, int filterIndex, ItemStack filterItem) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ExtractorSetFilterPKT> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CraftingVeloceMod.MODID, "extractor_set_filter"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExtractorSetFilterPKT> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ExtractorSetFilterPKT::pos,
            ByteBufCodecs.VAR_INT, ExtractorSetFilterPKT::filterIndex,
            ItemStack.OPTIONAL_STREAM_CODEC, ExtractorSetFilterPKT::filterItem,
            ExtractorSetFilterPKT::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ExtractorSetFilterPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (player.distanceToSqr(pkt.pos().getX() + 0.5, pkt.pos().getY() + 0.5, pkt.pos().getZ() + 0.5) > 64.0) {
                return;
            }
            BlockEntity be = player.level().getBlockEntity(pkt.pos());
            if (be instanceof VeloceExtractorBlockEntity extractorBE) {
                extractorBE.setFilter(pkt.filterIndex(), pkt.filterItem());
            }
        });
    }
}
