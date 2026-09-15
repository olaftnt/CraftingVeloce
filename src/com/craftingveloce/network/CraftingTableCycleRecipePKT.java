package com.craftingveloce.network;

import com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C→S: ustawia preferowana recepture dla itemu (shift+scroll w GUI).
 *
 * <p>Dotyczy itemow z wieloma recepturami - gracz wybiera, ktora crafter
 * ma uzyc jako pierwsza. Jesli wybrana receptura jest chwilowo niewykonalna
 * (brakuje skladnikow), silnik auto-craftowania sam sprobuje kolejnych.
 */
public record CraftingTableCycleRecipePKT(BlockPos pos, Item item, ResourceLocation recipeId)
        implements CustomPacketPayload {

    public static final Type<CraftingTableCycleRecipePKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("craftingveloce", "crafting_table_cycle_recipe"));

    public static final StreamCodec<FriendlyByteBuf, CraftingTableCycleRecipePKT> STREAM_CODEC =
            StreamCodec.of(CraftingTableCycleRecipePKT::encode, CraftingTableCycleRecipePKT::decode);

    private static void encode(FriendlyByteBuf buf, CraftingTableCycleRecipePKT pkt) {
        buf.writeBlockPos(pkt.pos);
        buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(pkt.item));
        buf.writeResourceLocation(pkt.recipeId);
    }

    private static CraftingTableCycleRecipePKT decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        Item item = BuiltInRegistries.ITEM.get(buf.readResourceLocation());
        ResourceLocation recipeId = buf.readResourceLocation();
        return new CraftingTableCycleRecipePKT(pos, item, recipeId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CraftingTableCycleRecipePKT pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) {
                // Bezpiecznik odleglosci - spojnie z pozostalymi pakietami.
                if (sp.distanceToSqr(pkt.pos().getX() + 0.5, pkt.pos().getY() + 0.5,
                        pkt.pos().getZ() + 0.5) > 64.0) {
                    return;
                }
                ServerLevel level = sp.serverLevel();
                BlockEntity be = level.getBlockEntity(pkt.pos());
                if (be instanceof VeloceCraftingTableBlockEntity ctBE) {
                    ctBE.setPreferredRecipe(pkt.item(), pkt.recipeId());
                }
            }
        });
    }
}
