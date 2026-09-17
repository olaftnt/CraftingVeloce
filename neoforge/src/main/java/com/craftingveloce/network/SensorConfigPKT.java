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
 * C->S: saves the threshold sensor configuration (threshold + mode).
 *
 * <p>One message for both settings, because the player changes them in the same
 * window and the server needs them together anyway - separate packets would only
 * mean more code paths to maintain.
 *
 * <p>The filter travels separately, in the shared {@link SetFilterPKT} packet -
 * the sensor has one filter, so it is handled by the same mechanism as the
 * extractor and the furnace.
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
            // We do not set the threshold on a sensor at the other end of the world.
            if (player.distanceToSqr(pkt.pos().getX() + 0.5, pkt.pos().getY() + 0.5,
                    pkt.pos().getZ() + 0.5) > 64.0) {
                return;
            }
            if (player.level().getBlockEntity(pkt.pos())
                    instanceof VeloceThresholdSensorBlockEntity sensor) {
                // The order does not matter - both setters only mark the block
                // entity as changed and resend the state.
                sensor.setThreshold(pkt.threshold());
                sensor.setMode(pkt.highMode()
                        ? VeloceThresholdSensorBlockEntity.Mode.HIGH
                        : VeloceThresholdSensorBlockEntity.Mode.LOW);
            }
        });
    }
}
