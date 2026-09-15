package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceTomTerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record TerminalPullItemPKT(BlockPos terminalPos, ItemStack itemStack, int count) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TerminalPullItemPKT> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CraftingVeloceMod.MODID, "terminal_pull_item"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalPullItemPKT> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, TerminalPullItemPKT::terminalPos,
            ItemStack.OPTIONAL_STREAM_CODEC, TerminalPullItemPKT::itemStack,
            ByteBufCodecs.VAR_INT, TerminalPullItemPKT::count,
            TerminalPullItemPKT::new
    );

    public TerminalPullItemPKT {
        itemStack = itemStack.copy();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void resyncInventories(ServerPlayer serverPlayer) {
        serverPlayer.inventoryMenu.broadcastFullState();
        if (serverPlayer.containerMenu != serverPlayer.inventoryMenu) {
            serverPlayer.containerMenu.broadcastFullState();
        }
    }

    public static void handle(TerminalPullItemPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            if (pkt.itemStack().isEmpty() || pkt.count() <= 0) return;

            if (serverPlayer.distanceToSqr(pkt.terminalPos().getX() + 0.5, pkt.terminalPos().getY() + 0.5, pkt.terminalPos().getZ() + 0.5) > 64.0) {
                return;
            }

            BlockEntity be = serverPlayer.level().getBlockEntity(pkt.terminalPos());
            if (!(be instanceof VeloceTomTerminalBlockEntity terminalBE)) {
                serverPlayer.displayClientMessage(Component.literal("§c[CraftingVeloce] Terminal block not found!"), true);
                resyncInventories(serverPlayer);
                return;
            }

            int toPull = Math.min(pkt.count(), pkt.itemStack().getMaxStackSize());
            ItemStack extracted = terminalBE.extractItemFromConnectedNetwork(pkt.itemStack(), toPull);

            if (extracted.isEmpty()) {
                String itemName = pkt.itemStack().getHoverName().getString();
                serverPlayer.displayClientMessage(Component.literal("§c[CraftingVeloce] Item not in network: " + itemName), true);
                resyncInventories(serverPlayer);
                return;
            }

            ItemStack leftover = ItemHandlerHelper.insertItemStacked(new PlayerMainInvWrapper(serverPlayer.getInventory()), extracted, false);
            if (!leftover.isEmpty()) {
                // Return leftover back to terminal if inventory was partially full
                terminalBE.pushStack(leftover);
                serverPlayer.displayClientMessage(Component.literal("§6[CraftingVeloce] Inventory full!"), true);
            }

            serverPlayer.level().playSound(null, serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(),
                    SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F, 1.0F);
            resyncInventories(serverPlayer);
        });
    }
}
