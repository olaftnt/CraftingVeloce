package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceTomTerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C→S: gracz wrzuca itemy do magazynu sieci (slot "strzalki" w GUI).
 *
 * <p><b>Pakiet niesie TYLKO INTENCJE, nie itemy.</b> To jest istotne.
 *
 * <p>Poprzednia wersja przysylala kopie stosu z klienta, a serwer wrzucal ja
 * do sieci - ale NIGDY nie zabieral itemu graczowi. Item istnial wiec
 * jednoczesnie w beczce i w ekwipunku: fizyczna DUPLIKACJA. Klient dodatkowo
 * czyscil kursor tylko u siebie, wiec serwer oddawal go z powrotem przy
 * najblizszej synchronizacji.
 *
 * <p>Teraz serwer sam czyta stan gracza, zabiera dokladnie tyle, ile udalo sie
 * wlozyc, i odsyla zmiany. Klient nie rusza u siebie niczego.
 */
public record TerminalStoreItemPKT(BlockPos terminalPos, int mode) implements CustomPacketPayload {

    /** To, co gracz trzyma na kursorze. */
    public static final int MODE_CURSOR = 0;
    /** Caly ekwipunek OPROCZ hotbara. */
    public static final int MODE_INVENTORY = 1;
    /** Doslownie wszystko, hotbar tez. */
    public static final int MODE_EVERYTHING = 2;

    public static final Type<TerminalStoreItemPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CraftingVeloceMod.MODID, "terminal_store_item"));

    public static final StreamCodec<FriendlyByteBuf, TerminalStoreItemPKT> STREAM_CODEC =
            StreamCodec.of(TerminalStoreItemPKT::encode, TerminalStoreItemPKT::decode);

    private static void encode(FriendlyByteBuf buf, TerminalStoreItemPKT pkt) {
        buf.writeBlockPos(pkt.terminalPos);
        buf.writeVarInt(pkt.mode);
    }

    private static TerminalStoreItemPKT decode(FriendlyByteBuf buf) {
        return new TerminalStoreItemPKT(buf.readBlockPos(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TerminalStoreItemPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (player.distanceToSqr(pkt.terminalPos().getX() + 0.5, pkt.terminalPos().getY() + 0.5,
                    pkt.terminalPos().getZ() + 0.5) > 64.0) {
                return;
            }
            if (pkt.mode() < MODE_CURSOR || pkt.mode() > MODE_EVERYTHING) {
                return;
            }
            BlockEntity be = player.level().getBlockEntity(pkt.terminalPos());
            if (be instanceof VeloceTomTerminalBlockEntity terminal) {
                terminal.storeFromPlayer(player, pkt.mode());
            }
        });
    }
}
