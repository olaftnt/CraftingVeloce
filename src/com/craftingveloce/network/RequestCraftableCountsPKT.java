package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceTomTerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * C→S: prosba o policzenie "ile da sie dorobic" dla podanych itemow.
 *
 * <p>Klient wysyla tylko itemy widoczne na ekranie (jeden ekran creative to
 * ok. 45 slotow), a nie wszystkie ~850 craftowalnych. Serwer odpowiada
 * {@link SyncCraftableCountsPKT}.
 *
 * <p>To rozwiazuje problem wydajnosciowy: wczesniej serwer liczyl craftowalnosc
 * dla wszystkich itemow co sekunde, rekurencyjnie, i sie zadlawial.
 */
public record RequestCraftableCountsPKT(BlockPos pos, List<Item> items)
        implements CustomPacketPayload {

    public static final Type<RequestCraftableCountsPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CraftingVeloceMod.MODID, "request_craftable_counts"));

    public static final StreamCodec<FriendlyByteBuf, RequestCraftableCountsPKT> STREAM_CODEC =
            StreamCodec.of(RequestCraftableCountsPKT::encode, RequestCraftableCountsPKT::decode);

    private static void encode(FriendlyByteBuf buf, RequestCraftableCountsPKT pkt) {
        buf.writeBlockPos(pkt.pos);
        buf.writeInt(pkt.items.size());
        for (Item item : pkt.items) {
            buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(item));
        }
    }

    private static RequestCraftableCountsPKT decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int n = buf.readInt();
        List<Item> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Item item = BuiltInRegistries.ITEM.get(buf.readResourceLocation());
            if (item != null) {
                items.add(item);
            }
        }
        return new RequestCraftableCountsPKT(pos, items);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestCraftableCountsPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            BlockEntity be = player.level().getBlockEntity(pkt.pos());
            if (!(be instanceof VeloceTomTerminalBlockEntity terminal)) {
                return;
            }
            Map<Item, Long> counts = terminal.computeCraftableCounts(pkt.items());
            PacketDistributor.sendToPlayer(player,
                    new SyncCraftableCountsPKT(pkt.pos(), counts));
        });
    }
}
