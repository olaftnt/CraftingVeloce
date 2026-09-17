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
 * C->S: sets the "crafting or furnace" preference for a single item in the
 * network.
 *
 * <p><b>What this changes.</b> An item may have both a crafting recipe
 * (crafter) and a smelting recipe (furnace). This preference only says WHICH
 * route should be tried first - the other one stays available if the first runs
 * out of ingredients or heat. It is not a filter and it blocks nothing.
 *
 * <p><b>Why per network and not per furnace.</b> The player chooses "I prefer
 * crafting" or "I prefer furnace", not a specific furnace in the network. The
 * preference is therefore held in the network (VelocePipeNetwork) and survives
 * a world restart.
 *
 * <p>The client sends the TARGET STATE, not a "toggle" - thanks to that a double
 * click does not leave the server and the client in different states.
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
            // Distance guard - exactly as in the other packets.
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
            // The change has to reach the world save (the preference must survive a restart).
            manager.setDirty();
            com.craftingveloce.util.VeloceLog.Craft.success(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "preference for %s: %s",
                    pkt.item(),
                    pkt.preferFurnace() ? "FURNACE first" : "CRAFTING first");
        });
    }
}
