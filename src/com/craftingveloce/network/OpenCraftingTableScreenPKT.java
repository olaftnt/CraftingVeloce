package com.craftingveloce.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * S→C: opens the crafting table (auto-crafter) GUI.
 *
 * <p>It carries two sets of information:
 * <ul>
 *   <li>{@code enabledItems} - items with auto-crafting enabled</li>
 *   <li>{@code preferredRecipes} - item -> id of the highest priority recipe
 *       (for items with multiple recipes; selection with shift+scroll)</li>
 * </ul>
 */
public record OpenCraftingTableScreenPKT(BlockPos pos, Set<Item> enabledItems,
                                         Map<Item, ResourceLocation> preferredRecipes,
                                         java.util.List<ItemStack> bufferContents)
        implements CustomPacketPayload {

    public static final Type<OpenCraftingTableScreenPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("craftingveloce", "open_crafting_table_screen"));

    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, OpenCraftingTableScreenPKT> STREAM_CODEC =
            StreamCodec.of(OpenCraftingTableScreenPKT::encode, OpenCraftingTableScreenPKT::decode);

    private static void encode(net.minecraft.network.RegistryFriendlyByteBuf buf, OpenCraftingTableScreenPKT pkt) {
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
        // Buffer contents (production cache) - displayed in the GUI.
        buf.writeInt(pkt.bufferContents.size());
        for (ItemStack st : pkt.bufferContents) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, st);
        }
    }

    private static OpenCraftingTableScreenPKT decode(net.minecraft.network.RegistryFriendlyByteBuf buf) {
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
        int bufCount = buf.readInt();
        java.util.List<ItemStack> buffer = new java.util.ArrayList<>(bufCount);
        for (int i = 0; i < bufCount; i++) {
            buffer.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        }
        return new OpenCraftingTableScreenPKT(pos, items, prefs, buffer);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenCraftingTableScreenPKT pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.craftingveloce.client.ClientTerminalHelper
                .openCraftingTableScreen(pkt.pos(), pkt.enabledItems(), pkt.preferredRecipes(),
                        pkt.bufferContents()));
    }
}
