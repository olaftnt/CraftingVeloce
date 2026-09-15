package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.client.ClientTerminalHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

public record SyncExtractorFiltersPKT(BlockPos pos, List<ItemStack> filters) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SyncExtractorFiltersPKT> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CraftingVeloceMod.MODID, "sync_extractor_filters"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncExtractorFiltersPKT> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SyncExtractorFiltersPKT::pos,
            ItemStack.OPTIONAL_LIST_STREAM_CODEC, SyncExtractorFiltersPKT::filters,
            SyncExtractorFiltersPKT::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncExtractorFiltersPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientTerminalHelper.handleSyncExtractorFilters(pkt.pos(), pkt.filters());
        });
    }
}
