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
     * Wysyla graczowi POWOD niepowodzenia - trafi do tooltipa tego itemu.
     *
     * <p>Do tej pory szedl tu {@code displayClientMessage(..., true)}, czyli
     * pasek akcji. W GUI terminala paska akcji NIE WIDAC, wiec gracz klikal
     * item, ktorego sie nie da zrobic, i nie dowiadywal sie dlaczego. Powod
     * i detal (oba to KLUCZE tlumaczen) jedzie teraz pakietem razem z itemem,
     * a klient sklada z nich komunikat w swoim jezyku.
     */
    private static void sendCraftError(ServerPlayer serverPlayer, TerminalPullItemPKT pkt,
                                       VeloceTomTerminalBlockEntity.PullResult pulled) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer,
                new TerminalCraftErrorPKT(pkt.terminalPos(), pkt.itemStack(),
                        pulled.reason(), pulled.detail()));
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
                serverPlayer.displayClientMessage(Component.translatable("craftingveloce.message.terminalNotFound")
                        .withStyle(net.minecraft.ChatFormatting.RED), true);
                resyncInventories(serverPlayer);
                return;
            }

            int toPull = Math.min(pkt.count(), pkt.itemStack().getMaxStackSize());
            // PELNY SLAD tej jednej proby (patrz VeloceCraftTrace): gracz kliknal
            // "wyciagnij", wiec logujemy wszystko, co decyduje o wyniku.
            com.craftingveloce.crafting.VeloceCraftTrace.begin(
                    "terminal " + pkt.terminalPos() + " gracz="
                            + serverPlayer.getGameProfile().getName()
                            + " item=" + pkt.itemStack().getHoverName().getString()
                            + " x" + toPull);
            VeloceTomTerminalBlockEntity.PullResult pulled;
            try {
                pulled = terminalBE.extractWithReason(pkt.itemStack(), toPull, true);
            } catch (Throwable t) {
                // Wyjatek w sciezce craftu MUSI byc w sladzie - inaczej widac
                // tylko przerwany ciag linii bez przyczyny.
                com.craftingveloce.crafting.VeloceCraftTrace.exception(
                        "terminal pull " + pkt.itemStack(), t);
                com.craftingveloce.crafting.VeloceCraftTrace.end("WYJATEK: " + t);
                throw t;
            }
            com.craftingveloce.crafting.VeloceCraftTrace.end(pulled.stack().isEmpty()
                    ? ("BRAK (" + pulled.reason() + " " + pulled.detail() + ")")
                    : ("dostarczono " + pulled.stack().getCount() + "x "
                            + pulled.stack().getItem()));

            if (pulled.stack().isEmpty()) {
                // POWOD, A NIE TYLKO "NIE MA", i to w miejscu, gdzie gracz go
                // zobaczy: w TOOLTIPIE kliknietego itemu (pasek akcji jest
                // schowany pod GUI terminala).
                //
                // Wczesniej kazde niepowodzenie konczylo sie identycznym
                // "Item not in network: X" - gracz nie mogl odroznic braku
                // skladnika od braku receptury, maszyny bez pradu czy planu,
                // ktory nie zmiescil sie w budzecie. Zgloszenie "GUI pokazuje,
                // ze moge, a nie moge zrobic" bylo wtedy nierozwiazywalne.
                sendCraftError(serverPlayer, pkt, pulled);
                resyncInventories(serverPlayer);
                return;
            }

            ItemStack extracted = pulled.stack();
            // Liczba "ile moge jeszcze zrobic" maleje o to, co wlasnie zeszlo
            // (-1 dla jednej sztuki, -64 dla stacka), a gracz dostaje swieza
            // migawke od razu - inaczej cyferka w GUI zostawala stara.
            if (serverPlayer.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                var net = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(serverLevel)
                        .getNetworkForTerminal(serverLevel, pkt.terminalPos());
                if (net != null) {
                    net.noteCrafted(extracted.getItem(), extracted.getCount());
                    PacketDistributor.sendToPlayer(serverPlayer,
                            new SyncCraftableCountsPKT(pkt.terminalPos(),
                                    net.getCraftableMemo(), false));
                }
            }

            ItemStack leftover = ItemHandlerHelper.insertItemStacked(
                    new PlayerMainInvWrapper(serverPlayer.getInventory()), extracted, false);
            if (!leftover.isEmpty()) {
                // Return leftover back to terminal if inventory was partially full
                terminalBE.pushStack(leftover);
                serverPlayer.displayClientMessage(Component.translatable("craftingveloce.message.inventoryFull")
                        .withStyle(net.minecraft.ChatFormatting.GOLD), true);
            }

            serverPlayer.level().playSound(null, serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(),
                    SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F, 1.0F);
            resyncInventories(serverPlayer);
        });
    }
}
