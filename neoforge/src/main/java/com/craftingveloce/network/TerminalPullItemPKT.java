package com.craftingveloce.network;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceTerminalBlockEntity;
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
import net.neoforged.neoforge.network.PacketDistributor;
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

    /**
     * Sends the player the REASON for the failure - it ends up in this item's tooltip.
     *
     * <p>Until now {@code displayClientMessage(..., true)} was used here, i.e.
     * the action bar. In the terminal GUI the action bar is NOT VISIBLE, so the
     * player clicked an item that could not be made and never learned why. The
     * reason and the detail (both are TRANSLATION KEYS) now travel in the packet
     * together with the item, and the client assembles the message from them in
     * its own language.
     */
    private static void sendCraftError(ServerPlayer serverPlayer, TerminalPullItemPKT pkt,
                                       VeloceTerminalBlockEntity.PullResult pulled) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer,
                new TerminalCraftErrorPKT(pkt.terminalPos(), pkt.itemStack(),
                        pulled.reason(), pulled.detail(), pulled.hint()));
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
            if (!(be instanceof VeloceTerminalBlockEntity terminalBE)) {
                serverPlayer.displayClientMessage(Component.translatable("craftingveloce.message.terminalNotFound")
                        .withStyle(net.minecraft.ChatFormatting.RED), true);
                resyncInventories(serverPlayer);
                return;
            }

            int toPull = Math.min(pkt.count(), pkt.itemStack().getMaxStackSize());
            // FULL TRACE of this one attempt (see VeloceCraftTrace): the player
            // clicked "pull", so we log everything that decides the outcome.
            com.craftingveloce.crafting.VeloceCraftTrace.begin(
                    "terminal " + pkt.terminalPos() + " player="
                            + serverPlayer.getGameProfile().getName()
                            + " item=" + pkt.itemStack().getHoverName().getString()
                            + " x" + toPull);
            VeloceTerminalBlockEntity.PullResult pulled;
            try {
                // The player is passed in because they are a crafting source: the network is
                // used first and what they carry covers the shortfall.
                pulled = terminalBE.extractWithReason(pkt.itemStack(), toPull, true, serverPlayer);
            } catch (Throwable t) {
                // An exception in the craft path MUST be in the trace - otherwise
                // all you see is an interrupted series of lines with no cause.
                com.craftingveloce.crafting.VeloceCraftTrace.exception(
                        "terminal pull " + pkt.itemStack(), t);
                com.craftingveloce.crafting.VeloceCraftTrace.end("EXCEPTION: " + t);
                throw t;
            }
            com.craftingveloce.crafting.VeloceCraftTrace.end(pulled.stack().isEmpty()
                    ? ("MISSING (" + pulled.reason() + " " + pulled.detail() + ")")
                    : ("delivered " + pulled.stack().getCount() + "x "
                            + pulled.stack().getItem()));

            if (pulled.stack().isEmpty()) {
                // A REASON, AND NOT JUST "THERE IS NONE", and in the place where
                // the player will see it: in the TOOLTIP of the clicked item
                // (the action bar is hidden under the terminal GUI).
                //
                // Previously every failure ended with an identical
                // "Item not in network: X" - the player could not tell a missing
                // ingredient from a missing recipe, a machine without power, or a
                // plan that did not fit in the budget. The report "the GUI shows
                // that I can, but I cannot make it" was then unsolvable.
                sendCraftError(serverPlayer, pkt, pulled);
                resyncInventories(serverPlayer);
                return;
            }

            ItemStack extracted = pulled.stack();
            // FROM HERE THE ITEMS ARE OUT OF THE NETWORK AND IN A LOCAL VARIABLE, so every step
            // is guarded: if anything between this line and the insertion throws, the stack is
            // put back instead of vanishing with the exception. Measured: a NoClassDefFoundError
            // raised in this very window silently ate a stack the player had already paid for -
            // it left the chest, nothing arrived, and the log showed only the exception.
            com.craftingveloce.crafting.VeloceCraftTrace.log(
                    "delivering to the player: %s",
                    com.craftingveloce.crafting.VeloceCraftTrace.fingerprint(extracted));
            try {
                // The "how many more can I make" number decreases by what just left
                // (-1 for a single unit, -64 for a stack), and the player gets a
                // fresh snapshot right away - otherwise the number in the GUI stayed
                // stale.
                if (serverPlayer.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                    var net = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(serverLevel)
                            .getNetworkForTerminal(serverLevel, pkt.terminalPos());
                    if (net != null) {
                        // ONLY when we really CRAFTED, and not when the item was in
                        // stock. Player: "when I take something out of the inventory,
                        // you still subtract -1 from how many I can craft". We count
                        // the stock BEFORE the pull: if the network had enough units,
                        // it was stock, and stock does not change the "how many more
                        // can be made" number.
                        long stock = net.getAllItemCounts(serverLevel)
                                .getOrDefault(extracted.getItem(), 0L);
                        if (stock < extracted.getCount()) {
                            net.noteCrafted(extracted.getItem(), extracted.getCount());
                        }
                        com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(serverLevel)
                                .markDirty();
                        PacketDistributor.sendToPlayer(serverPlayer,
                                // An EMPTY maker map on purpose: it is the controller that shows
                                // items it cannot make as a red icon with a tooltip to explain,
                                // and the terminal does not draw that line at all.
                                new SyncCraftableCountsPKT(pkt.terminalPos(),
                                        net.getCraftableMemo(), java.util.Map.of(), false));
                    }
                }

                ItemStack leftover = ItemHandlerHelper.insertItemStacked(
                        new PlayerMainInvWrapper(serverPlayer.getInventory()), extracted, false);
                if (!leftover.isEmpty()) {
                    // THE LEFTOVER MUST NOT BE DESTROYED, and it used to be: the old code called
                    // insertIntoStorage on the terminal's own network and THREW AWAY what that
                    // returned, so a full inventory plus a network that would not take the item back
                    // meant the item simply stopped existing. Same defect as the stack that vanished
                    // whole, one step further along the same path - and the helper below is the one
                    // that tries the network, then the player, then the ground.
                    if (serverPlayer.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                        VeloceTerminalBlockEntity.returnStackToNetworkOrPlayer(
                                sl, pkt.terminalPos(), serverPlayer, leftover);
                    }
                    serverPlayer.displayClientMessage(Component.translatable("craftingveloce.message.inventoryFull")
                            .withStyle(net.minecraft.ChatFormatting.GOLD), true);
                }

                // WHAT PHYSICALLY LANDED IN THE INVENTORY, read back rather than assumed.
                // Together with the two lines above this closes the question the trace could
                // never answer: if the same components are named here as in "took from stock",
                // nothing in this mod emptied the item - whatever the player is holding came out
                // of the chest that way.
                for (int slot = 0;
                        slot < serverPlayer.getInventory().getContainerSize(); slot++) {
                    ItemStack in = serverPlayer.getInventory().getItem(slot);
                    if (!in.isEmpty() && ItemStack.isSameItemSameComponents(in, extracted)) {
                        com.craftingveloce.crafting.VeloceCraftTrace.log(
                                "player slot %d now holds: %s", slot,
                                com.craftingveloce.crafting.VeloceCraftTrace.fingerprint(in));
                        break;
                    }
                }
                serverPlayer.level().playSound(null, serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(),
                        SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F, 1.0F);
                resyncInventories(serverPlayer);
                terminalBE.syncCountsToAllWatchers();
            } catch (Throwable t) {
                // The items are already out of the network - give them back before letting the
                // failure continue, so "an error happened" can never mean "your items are gone".
                if (serverPlayer.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                    VeloceTerminalBlockEntity.returnStackToNetworkOrPlayer(
                            sl, pkt.terminalPos(), serverPlayer, extracted);
                }
                throw t;
            }
        });
    }
}
