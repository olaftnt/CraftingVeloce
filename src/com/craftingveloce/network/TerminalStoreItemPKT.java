package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceTomTerminalBlockEntity;
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
 * C→S: gracz wrzuca item z kursora do magazynu sieci (slot "strzalki" w GUI).
 *
 * <p><b>To NIE kasuje itemu.</b> W vanilla slot na tym miejscu niszczy
 * przedmiot; u nas trafia on do pierwszego magazynu sieci, ktory go przyjmie.
 * Jesli nic go nie przyjmie, item wraca na kursor - nigdy nie znika.
 *
 * @param wholeStack true = cala zawartosc kursora (shift), false = jedna sztuka
 */
public record TerminalStoreItemPKT(BlockPos terminalPos, ItemStack stack, boolean wholeStack)
        implements CustomPacketPayload {

    public static final Type<TerminalStoreItemPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CraftingVeloceMod.MODID, "terminal_store_item"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalStoreItemPKT> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, TerminalStoreItemPKT::terminalPos,
                    ItemStack.OPTIONAL_STREAM_CODEC, TerminalStoreItemPKT::stack,
                    ByteBufCodecs.BOOL, TerminalStoreItemPKT::wholeStack,
                    TerminalStoreItemPKT::new);

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
            if (pkt.stack().isEmpty()) {
                return;
            }
            BlockEntity be = player.level().getBlockEntity(pkt.terminalPos());
            if (be instanceof VeloceTomTerminalBlockEntity terminal) {
                terminal.storeFromPlayer(player, pkt.stack(), pkt.wholeStack());
            }
        });
    }

    /** Zgodnosc ze starym konstruktorem dwuargumentowym. */
    public TerminalStoreItemPKT(BlockPos pos, ItemStack stack) {
        this(pos, stack, false);
    }
}
