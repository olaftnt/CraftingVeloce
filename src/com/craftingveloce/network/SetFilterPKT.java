package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceFilterHost;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C→S: ustaw (albo skasuj) filtr na bloku, ktory je ma.
 *
 * <p>Jeden pakiet dla ekstraktora i dla Velocity Furnace, bo operacja jest
 * identyczna: "na pozycji X, filtr numer N, item Y". Rozgalezienie po typie
 * bloku robi po stronie serwera wspolny interfejs {@link VeloceFilterHost},
 * wiec dodanie kolejnego bloku z filtrami nie wymaga ani nowego pakietu,
 * ani nowej sciezki otwierania wyboru itemu.
 */
public record SetFilterPKT(BlockPos pos, int filterIndex, ItemStack filterItem) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SetFilterPKT> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CraftingVeloceMod.MODID, "set_filter"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetFilterPKT> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetFilterPKT::pos,
            ByteBufCodecs.VAR_INT, SetFilterPKT::filterIndex,
            ItemStack.OPTIONAL_STREAM_CODEC, SetFilterPKT::filterItem,
            SetFilterPKT::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetFilterPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (player.distanceToSqr(pkt.pos().getX() + 0.5, pkt.pos().getY() + 0.5, pkt.pos().getZ() + 0.5) > 64.0) {
                return;
            }
            // Granice sprawdzamy TU, a nie w kazdym bloku z osobna - filtr
            // numer 40 nie ma prawa wywalic block entity.
            BlockEntity be = player.level().getBlockEntity(pkt.pos());
            if (be instanceof VeloceFilterHost host
                    && pkt.filterIndex() >= 0 && pkt.filterIndex() < host.filterCount()) {
                host.setFilterAt(pkt.filterIndex(), pkt.filterItem());
            }
        });
    }
}
