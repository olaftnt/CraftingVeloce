package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C-&gt;S: what the terminal GUI on this client is actually showing.
 *
 * <p><b>Why this has to exist.</b> Every other test in this project drives the server:
 * it runs a command and reads what the command replied. The craftable "+N" numbers are
 * not the server's opinion - they are what the CLIENT asked for and managed to match up
 * afterwards, and both halves can fail silently:
 *
 * <ul>
 *   <li>if the potion is not in the visible grid, the client never asks about it, and
 *       nothing anywhere records that - the number simply is not there;</li>
 *   <li>if the answer comes back under a key the screen does not look up, the lookup
 *       returns zero, and zero is not drawn by design.</li>
 * </ul>
 *
 * <p>Both look identical from the server: a correct count sitting in a map, and a screen
 * showing nothing. This packet closes that gap by making the client say what it has.
 *
 * @param pos            terminal the GUI was opened on
 * @param slotsWithItems how many grid slots hold an item
 * @param distinctItems  how many distinct items those slots represent
 * @param withCounts     how many of them ended up with a number above zero
 * @param potionReport   one entry per potion-ish item: {@code raw=count(proxyKey)}
 */
public record GuiCountsProbePKT(BlockPos pos, int slotsWithItems, int distinctItems,
                                int withCounts, boolean potionCounted,
                                String potionReport) implements CustomPacketPayload {

    public static final Type<GuiCountsProbePKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CraftingVeloceMod.MODID, "gui_counts_probe"));

    public static final StreamCodec<FriendlyByteBuf, GuiCountsProbePKT> STREAM_CODEC =
            StreamCodec.of(GuiCountsProbePKT::encode, GuiCountsProbePKT::decode);

    private static void encode(FriendlyByteBuf buf, GuiCountsProbePKT pkt) {
        buf.writeBlockPos(pkt.pos);
        buf.writeVarInt(pkt.slotsWithItems);
        buf.writeVarInt(pkt.distinctItems);
        buf.writeVarInt(pkt.withCounts);
        buf.writeBoolean(pkt.potionCounted);
        buf.writeUtf(pkt.potionReport, 4096);
    }

    private static GuiCountsProbePKT decode(FriendlyByteBuf buf) {
        return new GuiCountsProbePKT(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readBoolean(), buf.readUtf(4096));
    }

    /** Last report received - read by {@code /cv guitest result}. */
    private static volatile String last = "none";

    public static String last() {
        return last;
    }

    public static void reset() {
        last = "none";
    }

    public static void handle(GuiCountsProbePKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            last = "slots=" + pkt.slotsWithItems
                    + " distinct=" + pkt.distinctItems
                    + " withCounts=" + pkt.withCounts
                    + " potionCounted=" + pkt.potionCounted
                    + " potions=[" + pkt.potionReport + "]";
            com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                    "[Veloce] terminal GUI probe at {}: {}", pkt.pos, last);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
