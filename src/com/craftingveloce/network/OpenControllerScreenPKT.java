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
 * S→C: otwiera GUI Veloce Controller z pelnym obrazem sieci.
 *
 * <p>Niesie:
 * <ul>
 *   <li>{@code stock} - ile sztuk kazdego itemu jest fizycznie w sieci</li>
 *   <li>{@code craftable} - itemy z receptura wykonywalna bez energii</li>
 *   <li>{@code craftingEnabled} - itemy, ktore crafter potrafi zrobic (wlaczone)</li>
 *   <li>{@code hotbar} - zawartosc hotbara gracza (do kolorow ikon)</li>
 * </ul>
 */
public record OpenControllerScreenPKT(BlockPos pos,
                                      Map<Item, Long> stock,
                                      Set<Item> craftable,
                                      Set<Item> craftingEnabled,
                                      Map<Item, Integer> hotbar)
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

        buf.writeInt(pkt.craftable.size());
        for (Item i : pkt.craftable) {
            buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(i));
        }

        buf.writeInt(pkt.craftingEnabled.size());
        for (Item i : pkt.craftingEnabled) {
            buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(i));
        }

        buf.writeInt(pkt.hotbar.size());
        for (Map.Entry<Item, Integer> e : pkt.hotbar.entrySet()) {
            buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(e.getKey()));
            buf.writeVarInt(e.getValue());
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

        int craftableSize = buf.readInt();
        Set<Item> craftable = new HashSet<>();
        for (int i = 0; i < craftableSize; i++) {
            Item item = BuiltInRegistries.ITEM.get(buf.readResourceLocation());
            if (item != null) {
                craftable.add(item);
            }
        }

        int enabledSize = buf.readInt();
        Set<Item> craftingEnabled = new HashSet<>();
        for (int i = 0; i < enabledSize; i++) {
            Item item = BuiltInRegistries.ITEM.get(buf.readResourceLocation());
            if (item != null) {
                craftingEnabled.add(item);
            }
        }

        int hotbarSize = buf.readInt();
        Map<Item, Integer> hotbar = new HashMap<>();
        for (int i = 0; i < hotbarSize; i++) {
            Item item = BuiltInRegistries.ITEM.get(buf.readResourceLocation());
            int n = buf.readVarInt();
            if (item != null) {
                hotbar.put(item, n);
            }
        }

        return new OpenControllerScreenPKT(pos, stock, craftable, craftingEnabled, hotbar);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenControllerScreenPKT pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.craftingveloce.client.ClientTerminalHelper
                .openControllerScreen(pkt.pos(), pkt.stock(), pkt.craftable(),
                        pkt.craftingEnabled(), pkt.hotbar()));
    }
}
