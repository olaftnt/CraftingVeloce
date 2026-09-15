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
import java.util.Map;

/**
 * S→C: tempo przeplywu itemow w kontrolerze.
 *
 * <p>Niesie tylko to, co sie zmienia z sekundy na sekunde: dla kazdego itemu,
 * ktory REALNIE sie rusza, netto oraz osobno ile przyszlo i ile ubylo.
 * Brak wpisu = stoi, wiec nie wysylamy setek zer.
 *
 * <p><b>Stock jedzie tym samym pakietem.</b> Wczesniej kontroler dostawal
 * pelny obraz sieci TYLKO przy otwarciu, wiec wyjecie itemu ze skrzynki przy
 * otwartym GUI nie zmienialo ani liczby, ani koloru ikony - trzeba bylo
 * zamknac i otworzyc okno. Terminal od zawsze odswieza stock co sekunde
 * (SyncTerminalCountsPKT), wiec kontroler robi teraz to samo. Przy okazji to
 * ten sam skan sieci, ktory i tak wykonuje zapytanie o tempo.
 *
 * <p>Okna nie da sie juz "nie miec": okno minutowe liczy sie od drugiej
 * migawki, a godzinowe od pierwszej (patrz {@link VeloceFlowTracker}). Dzieki
 * temu ten pakiet nie musi nosic informacji "ile sekund objelo okno" - nie ma
 * stanu, w ktorym klient mialby pokazac "zbieram dane".
 */
public record SyncControllerFlowPKT(BlockPos pos,
                                    Map<Item, Long> stock,
                                    Map<Item, VeloceFlowTracker.Movement> perMinute,
                                    Map<Item, VeloceFlowTracker.Movement> perHour)
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
        writeStock(buf, pkt.stock);
        writeMovements(buf, pkt.perMinute);
        writeMovements(buf, pkt.perHour);
    }

    private static SyncControllerFlowPKT decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        Map<Item, Long> stock = readStock(buf);
        Map<Item, VeloceFlowTracker.Movement> perMinute = readMovements(buf);
        Map<Item, VeloceFlowTracker.Movement> perHour = readMovements(buf);
        return new SyncControllerFlowPKT(pos, stock, perMinute, perHour);
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

    private static void writeMovements(FriendlyByteBuf buf,
                                       Map<Item, VeloceFlowTracker.Movement> movements) {
        buf.writeInt(movements.size());
        for (Map.Entry<Item, VeloceFlowTracker.Movement> e : movements.entrySet()) {
            VeloceFlowTracker.Movement m = e.getValue();
            buf.writeVarInt(BuiltInRegistries.ITEM.getId(e.getKey()));
            buf.writeVarInt(Math.round(m.net() * RATE_SCALE));
            buf.writeVarInt(Math.round(m.gain() * RATE_SCALE));
            buf.writeVarInt(Math.round(m.loss() * RATE_SCALE));
        }
    }

    private static Map<Item, VeloceFlowTracker.Movement> readMovements(FriendlyByteBuf buf) {
        int size = buf.readInt();
        Map<Item, VeloceFlowTracker.Movement> out = new HashMap<>(Math.max(4, size));
        for (int i = 0; i < size; i++) {
            Item item = BuiltInRegistries.ITEM.byId(buf.readVarInt());
            float net = buf.readVarInt() / RATE_SCALE;
            float gain = buf.readVarInt() / RATE_SCALE;
            float loss = buf.readVarInt() / RATE_SCALE;
            out.put(item, new VeloceFlowTracker.Movement(net, gain, loss));
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
                screen.updateFlow(pkt.pos(), pkt.stock(), pkt.perMinute(), pkt.perHour());
            }
        });
    }
}
