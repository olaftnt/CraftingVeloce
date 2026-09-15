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
import java.util.Map;

/**
 * S→C: tempo przeplywu itemow w kontrolerze.
 *
 * <p>Niesie tylko to, co sie zmienia z sekundy na sekunde:
 * <ul>
 *   <li>{@code perMinute} / {@code perHour} - tempo w sztukach na sekunde dla
 *       itemow, ktore REALNIE sie ruszaja. Brak wpisu = stoi, wiec nie
 *       wysylamy setek zer.</li>
 *   <li>{@code coveredMinute} / {@code coveredHour} - ile sekund realnie
 *       obejmuje okno. Gdy serwer stoi od 3 minut, okno godzinowe ma 180 s i
 *       klient musi o tym powiedziec, zamiast udawac pelna godzine.</li>
 * </ul>
 */
public record SyncControllerFlowPKT(BlockPos pos,
                                    Map<Item, Float> perMinute,
                                    Map<Item, Float> perHour,
                                    float coveredMinute,
                                    float coveredHour)
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
        writeRates(buf, pkt.perMinute);
        writeRates(buf, pkt.perHour);
        buf.writeFloat(pkt.coveredMinute);
        buf.writeFloat(pkt.coveredHour);
    }

    private static SyncControllerFlowPKT decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        Map<Item, Float> perMinute = readRates(buf);
        Map<Item, Float> perHour = readRates(buf);
        float coveredMinute = buf.readFloat();
        float coveredHour = buf.readFloat();
        return new SyncControllerFlowPKT(pos, perMinute, perHour, coveredMinute, coveredHour);
    }

    private static void writeRates(FriendlyByteBuf buf, Map<Item, Float> rates) {
        buf.writeInt(rates.size());
        for (Map.Entry<Item, Float> e : rates.entrySet()) {
            buf.writeVarInt(BuiltInRegistries.ITEM.getId(e.getKey()));
            buf.writeVarInt(Math.round(e.getValue() * RATE_SCALE));
        }
    }

    private static Map<Item, Float> readRates(FriendlyByteBuf buf) {
        int size = buf.readInt();
        Map<Item, Float> out = new HashMap<>(Math.max(4, size));
        for (int i = 0; i < size; i++) {
            Item item = BuiltInRegistries.ITEM.byId(buf.readVarInt());
            float rate = buf.readVarInt() / RATE_SCALE;
            out.put(item, rate);
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
                screen.updateFlow(pkt.pos(), pkt.perMinute(), pkt.perHour(),
                        pkt.coveredMinute(), pkt.coveredHour());
            }
        });
    }
}
