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
 * S-&gt;C: powod nieudanej proby wyciagniecia/wytworzenia itemu w terminalu.
 *
 * <p><b>Dlaczego pakiet, a nie pasek akcji.</b> Do tej pory serwer wysylal
 * "Cannot craft: ..." przez {@code displayClientMessage(..., true)}, czyli na
 * PASEK AKCJI - a ten jest widoczny tylko poza GUI. Gracz stojacy w terminalu
 * nie widzial wiec NIC: klikal item, ktorego nie da sie zrobic, i nie wiedzial
 * dlaczego. Teraz powod jedzie do klienta razem z itemem, ktorego dotyczyl, i
 * ląduje w TOOLTIPIE tego itemu (patrz {@code VeloceCraftErrorHints}).
 *
 * <p>Wysylamy KLUCZ powodu i detal, a nie gotowy tekst: tlumaczenie robi klient,
 * wiec komunikat jest w jezyku gracza.
 */
public record TerminalCraftErrorPKT(BlockPos terminalPos, ItemStack itemStack,
                                    String reason, String detail) implements CustomPacketPayload {

    public static final Type<TerminalCraftErrorPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CraftingVeloceMod.MODID, "terminal_craft_error"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalCraftErrorPKT> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, TerminalCraftErrorPKT::terminalPos,
                    ItemStack.OPTIONAL_STREAM_CODEC, TerminalCraftErrorPKT::itemStack,
                    ByteBufCodecs.STRING_UTF8, TerminalCraftErrorPKT::reason,
                    ByteBufCodecs.STRING_UTF8, TerminalCraftErrorPKT::detail,
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
                pkt.terminalPos(), pkt.itemStack(), pkt.reason(), pkt.detail()));
    }
}
