package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceThresholdSensorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C→S: zapisuje konfiguracje czujnika progu (prog + tryb).
 *
 * <p>Jedna wiadomosc na oba ustawienia, bo gracz zmienia je w tym samym oknie
 * i serwer i tak potrzebuje ich razem - osobne pakiety znacznie tylko wiecej
 * sciezek do utrzymania.
 *
 * <p>Filtr leci osobno, wspolnym pakietem {@link SetFilterPKT} - czujnik ma
 * jeden filtr, wiec obsluguje go ten sam mechanizm co ekstraktor i piec.
 */
public record SensorConfigPKT(BlockPos pos, long threshold, boolean highMode)
        implements CustomPacketPayload {

    public static final Type<SensorConfigPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CraftingVeloceMod.MODID, "sensor_config"));

    public static final StreamCodec<FriendlyByteBuf, SensorConfigPKT> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeBlockPos(pkt.pos);
                        buf.writeVarLong(pkt.threshold);
                        buf.writeBoolean(pkt.highMode);
                    },
                    buf -> new SensorConfigPKT(buf.readBlockPos(),
                            buf.readVarLong(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SensorConfigPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // Nie ustawiamy progu czujnikowi na drugim koncu swiata.
            if (player.distanceToSqr(pkt.pos().getX() + 0.5, pkt.pos().getY() + 0.5,
                    pkt.pos().getZ() + 0.5) > 64.0) {
                return;
            }
            if (player.level().getBlockEntity(pkt.pos())
                    instanceof VeloceThresholdSensorBlockEntity sensor) {
                // Kolejnosc bez znaczenia - oba settery tylko znacza block
                // entity jako zmienione i dosylaja stan.
                sensor.setThreshold(pkt.threshold());
                sensor.setMode(pkt.highMode()
                        ? VeloceThresholdSensorBlockEntity.Mode.HIGH
                        : VeloceThresholdSensorBlockEntity.Mode.LOW);
            }
        });
    }
}
