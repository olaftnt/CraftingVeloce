package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S-&gt;C: puts a phrase into the terminal's search box, so the visible page is KNOWN.
 *
 * <p><b>Why this exists at all.</b> The "+N" numbers are computed for whatever the screen
 * is showing, which makes them untestable by default: an automated run opens the terminal
 * and the page is the creative inventory's default tab - oak logs and planks - so a probe
 * reports numbers for items nobody asked about. That is exactly how a previous round
 * "verified" the counts against a page that could not contain the item under test.
 *
 * <p>With this packet the test says which page it means ("andesite"), waits, and then reads
 * what the GUI ended up drawing for THAT page. The assertion finally concerns the item in
 * the report.
 *
 * <p>Only the phrase travels; the screen does the filtering through the same vanilla search
 * box a player types into, so the test exercises the real path rather than a shortcut.
 */
public record SetTerminalSearchPKT(String phrase) implements CustomPacketPayload {

    public static final Type<SetTerminalSearchPKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CraftingVeloceMod.MODID, "set_terminal_search"));

    public static final StreamCodec<FriendlyByteBuf, SetTerminalSearchPKT> STREAM_CODEC =
            StreamCodec.of(SetTerminalSearchPKT::encode, SetTerminalSearchPKT::decode);

    private static void encode(FriendlyByteBuf buf, SetTerminalSearchPKT pkt) {
        buf.writeUtf(pkt.phrase, 256);
    }

    private static SetTerminalSearchPKT decode(FriendlyByteBuf buf) {
        return new SetTerminalSearchPKT(buf.readUtf(256));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetTerminalSearchPKT pkt, IPayloadContext context) {
        context.enqueueWork(() ->
                com.craftingveloce.client.ClientTerminalHelper.setTerminalSearch(pkt.phrase()));
    }
}
