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
 *   <li>{@code craftable} - itemy z receptura wykonywalna bez energii
 *       (crafting / stonecutting / smithing)</li>
 *   <li>{@code craftingEnabled} - itemy, ktore crafter realnie potrafi zrobic
 *       (wlaczone; model opt-out, wiec to jest "wszystko oprocz wylaczonych")</li>
 *   <li>{@code furnaceCraftable} - itemy z receptura PIECA (smelting /
 *       blasting / smoking); niezaleznie od tego, czy piec jest w sieci</li>
 *   <li>{@code furnaceInNetwork} / {@code furnacePowered} - czy w sieci stoi
 *       jakikolwiek piec i czy ktorys jest zasilony. Trzy stany daja trzy
 *       rozne komunikaty: brak pieca, piec bez paliwa, piec gotowy</li>
 *   <li>{@code furnacePreferred} - itemy, dla ktorych gracz woli PRZEPALANIE</li>
 * </ul>
 */
public record OpenControllerScreenPKT(BlockPos pos,
                                      Map<Item, Long> stock,
                                      Set<Item> craftable,
                                      Set<Item> craftingEnabled,
                                      Set<Item> furnaceCraftable,
                                      boolean furnaceInNetwork,
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

        // Trzy zbiory itemow kodujemy tym samym kodem - wczesniej bylo to
        // trzy razy przeklejone, wiec kazda zmiana formatu wymagala trzech
        // zgodnych poprawek.
        writeItems(buf, pkt.craftable);
        writeItems(buf, pkt.craftingEnabled);
        writeItems(buf, pkt.furnaceCraftable);

        buf.writeBoolean(pkt.furnaceInNetwork);
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

        Set<Item> craftable = readItems(buf);
        Set<Item> craftingEnabled = readItems(buf);
        Set<Item> furnaceCraftable = readItems(buf);

        boolean furnaceInNetwork = buf.readBoolean();
        boolean furnacePowered = buf.readBoolean();

        int preferredSize = buf.readInt();
        Set<Item> furnacePreferred = new HashSet<>();
        for (int i = 0; i < preferredSize; i++) {
            Item item = BuiltInRegistries.ITEM.get(buf.readResourceLocation());
            if (item != null) {
                furnacePreferred.add(item);
            }
        }

        return new OpenControllerScreenPKT(pos, stock, craftable, craftingEnabled,
                furnaceCraftable, furnaceInNetwork, furnacePowered, furnacePreferred);
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
                .openControllerScreen(pkt.pos(), pkt.stock(), pkt.craftable(),
                        pkt.craftingEnabled(), pkt.furnaceCraftable(),
                        pkt.furnaceInNetwork(), pkt.furnacePowered(), pkt.furnacePreferred()));
    }
}
