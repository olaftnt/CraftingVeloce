package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S→C: otwiera ekran magazynu auto-craftera.
 *
 * <p>To zwykly ekran kontenera (jak skrzynia) ze scrollbarem, a nie creative
 * inventory. Dzieki temu nie ma slotow armor ani craftingu 2x2, i nic nie
 * wskakuje na slot glowy.
 */
public record OpenCrafterStoragePKT(BlockPos pos) implements CustomPacketPayload {

    public static final Type<OpenCrafterStoragePKT> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CraftingVeloceMod.MODID, "open_crafter_storage"));

    public static final StreamCodec<FriendlyByteBuf, OpenCrafterStoragePKT> STREAM_CODEC =
            StreamCodec.of(OpenCrafterStoragePKT::encode, OpenCrafterStoragePKT::decode);

    private static void encode(FriendlyByteBuf buf, OpenCrafterStoragePKT pkt) {
        buf.writeBlockPos(pkt.pos);
    }

    private static OpenCrafterStoragePKT decode(FriendlyByteBuf buf) {
        return new OpenCrafterStoragePKT(buf.readBlockPos());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Otwiera ekran po stronie serwera (wlasciwy sposob dla kontenerow). */
    public static void openFor(ServerPlayer player, BlockPos pos) {
        BlockEntity be = player.level().getBlockEntity(pos);
        if (!(be instanceof VeloceCraftingTableBlockEntity crafter)) {
            return;
        }
        player.openMenu(new net.minecraft.world.MenuProvider() {
            @Override
            public net.minecraft.network.chat.Component getDisplayName() {
                return net.minecraft.network.chat.Component.translatable(
                        "block.craftingveloce.veloce_crafting_table");
            }

            @Override
            public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
                    int windowId, net.minecraft.world.entity.player.Inventory inv,
                    net.minecraft.world.entity.player.Player p) {
                return new com.craftingveloce.inventory.VeloceCrafterStorageMenu(
                        windowId, inv, crafter.getBuffer());
            }
        }, buf -> buf.writeBlockPos(pos));
    }

    public static void handle(OpenCrafterStoragePKT pkt, IPayloadContext context) {
        // Nic nie robimy po stronie klienta - ekran otwiera openMenu.
    }
}
