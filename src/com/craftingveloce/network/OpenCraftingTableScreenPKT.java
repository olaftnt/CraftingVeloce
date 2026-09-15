package com.craftingveloce.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * S→C: otwiera GUI crafting table (auto-craftera).
 *
 * <p>Niesie dwa zbiory informacji:
 * <ul>
 *   <li>{@code enabledItems} - itemy z wlaczonym auto-craftingiem</li>
 *   <li>{@code preferredRecipes} - item -> id receptury o najwyzszym priorytecie
 *       (dla itemow z wieloma recepturami; wybor shift+scroll)</li>
 * </ul>
 */
public record OpenCraftingTableScreenPKT(BlockPos pos, Set<Item> enabledItems,
                                         Map<Item, ResourceLocation> preferredRecipes)
        implements CustomPacketPayload {

    public static final Type<OpenCraftingTableScreenPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("craftingveloce", "open_crafting_table_screen"));

    public static final StreamCodec<FriendlyByteBuf, OpenCraftingTableScreenPKT> STREAM_CODEC =
            StreamCodec.of(OpenCraftingTableScreenPKT::encode, OpenCraftingTableScreenPKT::decode);

    private static void encode(FriendlyByteBuf buf, OpenCraftingTableScreenPKT pkt) {
        buf.writeBlockPos(pkt.pos);
        buf.writeInt(pkt.enabledItems.size());
        for (Item item : pkt.enabledItems) {
            buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(item));
        }
        buf.writeInt(pkt.preferredRecipes.size());
        for (Map.Entry<Item, ResourceLocation> e : pkt.preferredRecipes.entrySet()) {
            buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(e.getKey()));
            buf.writeResourceLocation(e.getValue());
        }
    }

    private static OpenCraftingTableScreenPKT decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int count = buf.readInt();
        Set<Item> items = new HashSet<>();
        for (int i = 0; i < count; i++) {
            ResourceLocation rl = buf.readResourceLocation();
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item != null) {
                items.add(item);
            }
        }
        int prefCount = buf.readInt();
        Map<Item, ResourceLocation> prefs = new HashMap<>();
        for (int i = 0; i < prefCount; i++) {
            ResourceLocation itemKey = buf.readResourceLocation();
            ResourceLocation recipeId = buf.readResourceLocation();
            Item item = BuiltInRegistries.ITEM.get(itemKey);
            if (item != null) {
                prefs.put(item, recipeId);
            }
        }
        return new OpenCraftingTableScreenPKT(pos, items, prefs);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenCraftingTableScreenPKT pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.craftingveloce.client.ClientTerminalHelper
                .openCraftingTableScreen(pkt.pos(), pkt.enabledItems(), pkt.preferredRecipes()));
    }
}
