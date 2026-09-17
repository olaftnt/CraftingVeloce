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
 * S->C: opens the Veloce Controller GUI with the full picture of the network.
 *
 * <p>It carries:
 * <ul>
 *   <li>{@code stock} - how many pieces of each item are physically in the
 *       network</li>
 *   <li>{@code craftingEnabled} - items the crafter can actually make
 *       (enabled; opt-out model, so this is "everything except the disabled
 *       ones")</li>
 *   <li>{@code furnaceCraftable} - items with a FURNACE recipe (smelting /
 *       blasting / smoking); regardless of whether a furnace is in the
 *       network</li>
 *   <li>{@code furnacePowered} - whether any furnace is powered; only then are
 *       furnace recipes real (and only then does the item get a yellow
 *       background)</li>
 *   <li>{@code furnacePreferred} - items for which the player prefers
 *       SMELTING</li>
 * </ul>
 *
 * <p><b>What is NOT here.</b> {@code craftable} ("has a recipe, but the crafter
 * may have it disabled") and {@code furnaceInNetwork} ("any furnace at all is
 * present") served only the texts explaining why something was unavailable - the
 * player asked for them to be removed, so they disappeared along with them.
 * Availability is visible from the background colour of the icon.
 */
public record OpenControllerScreenPKT(BlockPos pos,
                                      Map<Item, Long> stock,
                                      Set<Item> craftingEnabled,
                                      Set<Item> furnaceCraftable,
                                      boolean furnacePowered,
                                      Set<Item> furnacePreferred)
        implements CustomPacketPayload {

    public static final Type<OpenControllerScreenPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("craftingveloce", "open_controller_screen"));

    public static final StreamCodec<FriendlyByteBuf, OpenControllerScreenPKT> STREAM_CODEC =
            StreamCodec.of(OpenControllerScreenPKT::encode, OpenControllerScreenPKT::decode);

    private static void encode(FriendlyByteBuf buf, OpenControllerScreenPKT pkt) {
        buf.writeBlockPos(pkt.pos);

        buf.writeInt(pkt.stock.size());
        for (Map.Entry<Item, Long> e : pkt.stock.entrySet()) {
            buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(e.getKey()));
            buf.writeVarLong(e.getValue());
        }

        // We encode the item sets with the same code - previously this was
        // copy-pasted three times, so every format change required three
        // matching fixes.
        writeItems(buf, pkt.craftingEnabled);
        writeItems(buf, pkt.furnaceCraftable);

        buf.writeBoolean(pkt.furnacePowered);

        buf.writeInt(pkt.furnacePreferred.size());
        for (Item it : pkt.furnacePreferred) {
            buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(it));
        }
    }

    private static OpenControllerScreenPKT decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();

        int stockSize = buf.readInt();
        Map<Item, Long> stock = new HashMap<>();
        for (int i = 0; i < stockSize; i++) {
            Item item = BuiltInRegistries.ITEM.get(buf.readResourceLocation());
            long n = buf.readVarLong();
            if (item != null) {
                stock.put(item, n);
            }
        }

        Set<Item> craftingEnabled = readItems(buf);
        Set<Item> furnaceCraftable = readItems(buf);

        boolean furnacePowered = buf.readBoolean();

        int preferredSize = buf.readInt();
        Set<Item> furnacePreferred = new HashSet<>();
        for (int i = 0; i < preferredSize; i++) {
            Item item = BuiltInRegistries.ITEM.get(buf.readResourceLocation());
            if (item != null) {
                furnacePreferred.add(item);
            }
        }

        return new OpenControllerScreenPKT(pos, stock, craftingEnabled,
                furnaceCraftable, furnacePowered, furnacePreferred);
    }

    private static void writeItems(FriendlyByteBuf buf, Set<Item> items) {
        buf.writeInt(items.size());
        for (Item i : items) {
            buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(i));
        }
    }

    private static Set<Item> readItems(FriendlyByteBuf buf) {
        int size = buf.readInt();
        Set<Item> out = new HashSet<>();
        for (int i = 0; i < size; i++) {
            Item item = BuiltInRegistries.ITEM.get(buf.readResourceLocation());
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenControllerScreenPKT pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.craftingveloce.client.ClientTerminalHelper
                .openControllerScreen(pkt.pos(), pkt.stock(),
                        pkt.craftingEnabled(), pkt.furnaceCraftable(),
                        pkt.furnacePowered(), pkt.furnacePreferred()));
    }
}
