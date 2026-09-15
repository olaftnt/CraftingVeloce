package com.craftingveloce.network;

import com.craftingveloce.crafting.VeloceFlowTracker;
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
 * S→C: tempo przeplywu i ZMIANA stocku w kontrolerze.
 *
 * <p><b>Tempo.</b> Serwer odsyla wylacznie itemy, ktore maja jednokierunkowy
 * przyrost/ubytek ({@link VeloceFlowTracker#steadyRates()}) - brak wpisu
 * znaczy "stoi albo sie szarpie" i w tooltipie nie ma wtedy zadnej linii tempa.
 *
 * <p><b>Stock jako ROZNICA, nie calosc.</b> Wczesniej kazda odpowiedz (raz na
 * sekunde) niosla CALY obraz sieci. Przy sieci z tysiacami roznych itemow to
 * tysiace wpisow na sekunde, choc stock prawie nigdy sie nie zmienia - im
 * wieksza siec, tym wiekszy ruch, bez zadnego pozytku. Teraz leca tylko wpisy
 * zmienione ({@code changed}) oraz itemy, ktore zniknely ({@code removed}) -
 * patrz {@link com.craftingveloce.crafting.VeloceStockDeltas}.
 *
 * <p>{@code full} mowi klientowi, ze ma ZASTAPIC stock tym, co przyszlo (pelny
 * zrzut co minute), a nie scalac go z poprzednim. Wartosci w {@code changed}
 * sa BEZWZGLEDNE, wiec zastosowanie tej samej roznicy dwa razy nic nie psuje -
 * kilku graczy moze patrzec w ten sam kontroler i kazdy ma wlasna kopie.
 */
public record SyncControllerFlowPKT(BlockPos pos,
                                    Map<Item, Long> changed,
                                    Set<Item> removed,
                                    Map<Item, Float> rates,
                                    boolean full)
        implements CustomPacketPayload {

    public static final Type<SyncControllerFlowPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("craftingveloce", "sync_controller_flow"));

    public static final StreamCodec<FriendlyByteBuf, SyncControllerFlowPKT> STREAM_CODEC =
            StreamCodec.of(SyncControllerFlowPKT::encode, SyncControllerFlowPKT::decode);

    /** Tempo zapisujemy jako setne czesci sztuki - float w pakiecie to 4 bajty
     *  szumu, a varint setnych jest mniejszy i dokladnie tak dokladny, jak
     *  pokazuje tooltip. */
    private static final float RATE_SCALE = 100f;

    private static void encode(FriendlyByteBuf buf, SyncControllerFlowPKT pkt) {
        buf.writeBlockPos(pkt.pos);
        buf.writeBoolean(pkt.full);
        writeStock(buf, pkt.changed);
        writeRemoved(buf, pkt.removed);
        writeRates(buf, pkt.rates);
    }

    private static SyncControllerFlowPKT decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean full = buf.readBoolean();
        Map<Item, Long> changed = readStock(buf);
        Set<Item> removed = readRemoved(buf);
        Map<Item, Float> rates = readRates(buf);
        return new SyncControllerFlowPKT(pos, changed, removed, rates, full);
    }

    /** Stock: ten sam format co w SyncTerminalCountsPKT (id itemu + varint dlugi). */
    private static void writeStock(FriendlyByteBuf buf, Map<Item, Long> stock) {
        buf.writeVarInt(stock.size());
        for (Map.Entry<Item, Long> e : stock.entrySet()) {
            buf.writeVarInt(BuiltInRegistries.ITEM.getId(e.getKey()));
            buf.writeVarLong(e.getValue());
        }
    }

    private static Map<Item, Long> readStock(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<Item, Long> out = new HashMap<>(Math.max(4, size));
        for (int i = 0; i < size; i++) {
            Item item = BuiltInRegistries.ITEM.byId(buf.readVarInt());
            long count = buf.readVarLong();
            if (item != null) {
                out.put(item, count);
            }
        }
        return out;
    }

    /** Znikniete itemy: same identyfikatory - ich liczb juz nie ma. */
    private static void writeRemoved(FriendlyByteBuf buf, Set<Item> removed) {
        buf.writeVarInt(removed.size());
        for (Item item : removed) {
            buf.writeVarInt(BuiltInRegistries.ITEM.getId(item));
        }
    }

    private static Set<Item> readRemoved(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Set<Item> out = new HashSet<>(Math.max(4, size));
        for (int i = 0; i < size; i++) {
            Item item = BuiltInRegistries.ITEM.byId(buf.readVarInt());
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    /** Tempo: id itemu + setne czesci sztuki na sekunde. */
    private static void writeRates(FriendlyByteBuf buf, Map<Item, Float> rates) {
        buf.writeVarInt(rates.size());
        for (Map.Entry<Item, Float> e : rates.entrySet()) {
            buf.writeVarInt(BuiltInRegistries.ITEM.getId(e.getKey()));
            buf.writeVarInt(Math.round(e.getValue() * RATE_SCALE));
        }
    }

    private static Map<Item, Float> readRates(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<Item, Float> out = new HashMap<>(Math.max(4, size));
        for (int i = 0; i < size; i++) {
            Item item = BuiltInRegistries.ITEM.byId(buf.readVarInt());
            float rate = buf.readVarInt() / RATE_SCALE;
            if (item != null) {
                out.put(item, rate);
            }
        }
        return out;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncControllerFlowPKT pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (net.minecraft.client.Minecraft.getInstance().screen
                    instanceof com.craftingveloce.client.gui.VeloceControllerScreen screen) {
                screen.updateFlow(pkt.pos(), pkt.changed(), pkt.removed(),
                        pkt.rates(), pkt.full());
            }
        });
    }
}
