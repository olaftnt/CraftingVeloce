package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C→S: ustaw preferencje "crafting czy piec" dla jednego itemu w sieci.
 *
 * <p><b>Co to zmienia.</b> Item moze miec jednoczesnie recepture craftingowa
 * (crafter) i przepalania (piec). Ta preferencja mowi tylko, KTORA droga ma
 * byc probowana pierwsza - druga zostaje dostepna, jesli pierwszej zabraknie
 * skladnikow albo ciepla. To nie jest filtr i nie blokuje niczego.
 *
 * <p><b>Dlaczego per siec, a nie per piec.</b> Gracz wybiera "wolę crafting"
 * albo "wolę furnace", a nie konkretny piec z sieci. Preferencja jest wiec
 * trzymana w sieci (VelocePipeNetwork) i przetrwa restart swiata.
 *
 * <p>Klient wysyla STAN DOCELOWY, nie "przelacz" - dzieki temu dwuklik nie
 * zostawia serwera i klienta w roznych stanach.
 */
public record ControllerPreferKindPKT(BlockPos pos, Item item, boolean preferFurnace)
        implements CustomPacketPayload {

    public static final Type<ControllerPreferKindPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CraftingVeloceMod.MODID, "controller_prefer_kind"));

    public static final StreamCodec<FriendlyByteBuf, ControllerPreferKindPKT> STREAM_CODEC =
            StreamCodec.of(ControllerPreferKindPKT::encode, ControllerPreferKindPKT::decode);

    private static void encode(FriendlyByteBuf buf, ControllerPreferKindPKT pkt) {
        buf.writeBlockPos(pkt.pos);
        buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(pkt.item));
        buf.writeBoolean(pkt.preferFurnace);
    }

    private static ControllerPreferKindPKT decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        ResourceLocation id = buf.readResourceLocation();
        Item item = BuiltInRegistries.ITEM.get(id);
        return new ControllerPreferKindPKT(pos, item, buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ControllerPreferKindPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // Bezpiecznik odleglosci - tak samo jak w pozostalych pakietach.
            if (player.distanceToSqr(pkt.pos().getX() + 0.5, pkt.pos().getY() + 0.5,
                    pkt.pos().getZ() + 0.5) > 64.0) {
                return;
            }
            if (pkt.item() == null || pkt.item() == net.minecraft.world.item.Items.AIR) {
                return;
            }
            ServerLevel level = player.serverLevel();
            var manager = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(level);
            var network = manager.getNetworkForTerminal(level, pkt.pos());
            if (network == null) {
                return;
            }
            network.setPrefersFurnace(pkt.item(), pkt.preferFurnace());
            // Zmiana musi trafic do zapisu swiata (preferencja ma przetrwac restart).
            manager.setDirty();
            com.craftingveloce.util.VeloceLog.Craft.success(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "preferencja dla %s: %s",
                    pkt.item(),
                    pkt.preferFurnace() ? "FURNACE pierwszy" : "CRAFTING pierwszy");
        });
    }
}
