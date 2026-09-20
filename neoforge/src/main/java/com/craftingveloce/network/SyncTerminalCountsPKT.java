package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.client.ClientTerminalHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;

/**
 * S->C: the item counts in the network.
 *
 * <p>It carries TWO maps:
 * <ul>
 *   <li>{@code itemCounts} - how many units are physically in the network (the green number)</li>
 *   <li>{@code craftableCounts} - how many more units can be made by
 *       auto-crafting from what is there (the yellow "+N" number).
 *       Zero/missing entry = nothing more can be made.</li>
 * </ul>
 */
public record SyncTerminalCountsPKT(Map<Item, Long> itemCounts,
                                    Map<Item, Long> craftableCounts)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncTerminalCountsPKT> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CraftingVeloceMod.MODID, "sync_terminal_counts"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncTerminalCountsPKT> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SyncTerminalCountsPKT decode(RegistryFriendlyByteBuf buf) {
            // On the network thread, not the game thread - so this cannot be what freezes the
            // frame, but it IS on the path between the click and the numbers and it carries the
            // whole network stock. Measured so the two can be told apart instead of blamed on
            // each other; the unit count is the number of item types on the wire.
            try (var ignored = com.craftingveloce.util.VeloceProfiler
                    .section("net.SyncTerminalCounts.decode")) {
                Map<Item, Long> counts = readMap(buf);
                Map<Item, Long> craftable = readMap(buf);
                com.craftingveloce.util.VeloceProfiler.count(
                        "net.SyncTerminalCounts.decode", counts.size() + craftable.size());
                return new SyncTerminalCountsPKT(counts, craftable);
            }
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, SyncTerminalCountsPKT pkt) {
            // The ENCODE side runs on the SERVER thread (it is the terminal's own sync on the
            // right-click) - so unlike decode, this one can be felt as the click hanging.
            try (var ignored = com.craftingveloce.util.VeloceProfiler
                    .section("net.SyncTerminalCounts.encode")) {
                writeMap(buf, pkt.itemCounts());
                writeMap(buf, pkt.craftableCounts());
            }
        }

        private Map<Item, Long> readMap(RegistryFriendlyByteBuf buf) {
            int size = buf.readVarInt();
            Map<Item, Long> map = new HashMap<>(size);
            for (int i = 0; i < size; i++) {
                int itemId = buf.readVarInt();
                long count = buf.readVarLong();
                Item item = BuiltInRegistries.ITEM.byId(itemId);
                if (item != null) {
                    map.put(item, count);
                }
            }
            return map;
        }

        private void writeMap(RegistryFriendlyByteBuf buf, Map<Item, Long> map) {
            buf.writeVarInt(map.size());
            for (Map.Entry<Item, Long> entry : map.entrySet()) {
                buf.writeVarInt(BuiltInRegistries.ITEM.getId(entry.getKey()));
                buf.writeVarLong(entry.getValue());
            }
        }
    };

    /** Backward compatibility: the stock alone, without craftable. */
    public SyncTerminalCountsPKT(Map<Item, Long> itemCounts) {
        this(itemCounts, Map.of());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncTerminalCountsPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> ClientTerminalHelper.handleSyncCounts(
                pkt.itemCounts(), pkt.craftableCounts()));
    }
}
