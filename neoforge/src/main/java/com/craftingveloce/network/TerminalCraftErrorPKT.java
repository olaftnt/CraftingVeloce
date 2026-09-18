package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S-&gt;C: the reason an attempt to withdraw/craft an item in the terminal failed.
 *
 * <p><b>Why a packet and not the action bar.</b> Until now the server sent
 * "Cannot craft: ..." through {@code displayClientMessage(..., true)}, that is,
 * to the ACTION BAR - and that is only visible outside a GUI. A player standing
 * in the terminal therefore saw NOTHING: they clicked an item that cannot be
 * made and did not know why. Now the reason travels to the client together with
 * the item it concerned, and lands in the TOOLTIP of that item (see
 * {@code VeloceCraftErrorHints}).
 *
 * <p>We send the reason KEY and the detail, not the finished text: the client
 * does the translation, so the message is in the player's language.
 */
public record TerminalCraftErrorPKT(BlockPos terminalPos, ItemStack itemStack,
                                    String reason, String detail,
                                    String hint) implements CustomPacketPayload {

    public static final Type<TerminalCraftErrorPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CraftingVeloceMod.MODID, "terminal_craft_error"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalCraftErrorPKT> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, TerminalCraftErrorPKT::terminalPos,
                    ItemStack.OPTIONAL_STREAM_CODEC, TerminalCraftErrorPKT::itemStack,
                    ByteBufCodecs.STRING_UTF8, TerminalCraftErrorPKT::reason,
                    ByteBufCodecs.STRING_UTF8, TerminalCraftErrorPKT::detail,
                    ByteBufCodecs.STRING_UTF8, TerminalCraftErrorPKT::hint,
                    TerminalCraftErrorPKT::new
            );

    public TerminalCraftErrorPKT {
        itemStack = itemStack.copy();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TerminalCraftErrorPKT pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.craftingveloce.client.ClientTerminalHelper.handleCraftError(
                pkt.terminalPos(), pkt.itemStack(), pkt.reason(), pkt.detail(), pkt.hint()));
    }
}
