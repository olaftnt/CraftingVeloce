package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C->S: pulling an item out of the auto-crafter buffer.
 *
 * <p>The crafter buffer plays the role of storage (surplus production), so the
 * player can browse it and pull items out of it - just like from the terminal.
 * Insertion is blocked on purpose: the buffer is meant to be a cache of
 * production, not another chest to fill by hand.
 */
public record BufferPullItemPKT(BlockPos pos, ItemStack itemStack, int count)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BufferPullItemPKT> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    CraftingVeloceMod.MODID, "buffer_pull_item"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BufferPullItemPKT> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, BufferPullItemPKT::pos,
                    ItemStack.OPTIONAL_STREAM_CODEC, BufferPullItemPKT::itemStack,
                    ByteBufCodecs.VAR_INT, BufferPullItemPKT::count,
                    BufferPullItemPKT::new
            );

    public BufferPullItemPKT {
        itemStack = itemStack.copy();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BufferPullItemPKT pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (pkt.itemStack().isEmpty() || pkt.count() <= 0) {
                return;
            }
            if (player.distanceToSqr(pkt.pos().getX() + 0.5, pkt.pos().getY() + 0.5,
                    pkt.pos().getZ() + 0.5) > 64.0) {
                return;
            }

            BlockEntity be = player.level().getBlockEntity(pkt.pos());
            if (!(be instanceof VeloceCraftingTableBlockEntity crafter)) {
                return;
            }

            var buffer = crafter.getBuffer();
            var item = pkt.itemStack().getItem();
            int remaining = Math.min(pkt.count(), pkt.itemStack().getMaxStackSize());

            ItemStack taken = ItemStack.EMPTY;
            for (int slot = 0; slot < buffer.getContainerSize() && remaining > 0; slot++) {
                ItemStack inSlot = buffer.getItem(slot);
                if (inSlot.isEmpty() || inSlot.getItem() != item) {
                    continue;
                }
                int take = Math.min(remaining, inSlot.getCount());
                ItemStack got = buffer.removeItem(slot, take);
                if (got.isEmpty()) {
                    continue;
                }
                if (taken.isEmpty()) {
                    taken = got.copy();
                } else {
                    taken.grow(got.getCount());
                }
                remaining -= got.getCount();
            }

            if (taken.isEmpty()) {
                return;
            }

            ItemStack leftover = ItemHandlerHelper.insertItemStacked(
                    new PlayerMainInvWrapper(player.getInventory()), taken, false);
            if (!leftover.isEmpty()) {
                // It did not fit - it goes back into the buffer so nothing is lost.
                buffer.insert(leftover);
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                                "craftingveloce.message.inventoryFull")
                                .withStyle(net.minecraft.ChatFormatting.GOLD), true);
            }

            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F, 1.0F);

            // Refresh the GUI view so the pulled items disappear right away.
            crafter.syncToPlayer(player);
            TerminalPullItemPKT.resyncInventories(player);
        });
    }
}
