package com.craftingveloce.item;

import com.craftingveloce.block.entity.VeloceTerminalBlockEntity;
import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.network.OpenTerminalScreenPKT;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public class VeloceTabletItem extends Item {

    public record BoundTerminal(BlockPos pos, ResourceLocation dimension) {}

    public VeloceTabletItem(Properties properties) {
        super(properties);
    }

    public static BoundTerminal getBoundTerminal(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(VeloceRegistry.VELOCE_TABLET.get())) {
            return null;
        }
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return null;
        }
        CompoundTag tag = customData.copyTag();
        if (!tag.contains("terminal_x") || !tag.contains("terminal_y") || !tag.contains("terminal_z")) {
            return null;
        }
        BlockPos pos = new BlockPos(tag.getInt("terminal_x"), tag.getInt("terminal_y"), tag.getInt("terminal_z"));
        ResourceLocation dim = tag.contains("terminal_dim") ? ResourceLocation.tryParse(tag.getString("terminal_dim")) : null;
        return new BoundTerminal(pos, dim);
    }

    public static void bindToTerminal(ItemStack stack, BlockPos pos, ResourceLocation dimension) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putInt("terminal_x", pos.getX());
            tag.putInt("terminal_y", pos.getY());
            tag.putInt("terminal_z", pos.getZ());
            if (dimension != null) {
                tag.putString("terminal_dim", dimension.toString());
            }
        });
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return getBoundTerminal(stack) != null || super.isFoil(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        BoundTerminal bound = getBoundTerminal(stack);
        if (bound != null) {
            String dimStr = bound.dimension() != null ? bound.dimension().toString() : "unknown";
            tooltipComponents.add(Component.translatable("tooltip.craftingveloce.tablet_linked",
                    bound.pos().getX(), bound.pos().getY(), bound.pos().getZ(), dimStr)
                    .withStyle(ChatFormatting.AQUA));
        } else {
            tooltipComponents.add(Component.translatable("tooltip.craftingveloce.tablet_unlinked")
                    .withStyle(ChatFormatting.GRAY));
        }
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();
        BlockState state = level.getBlockState(clickedPos);
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();

        if (state.is(VeloceRegistry.VELOCE_TERMINAL.get())) {
            if (!level.isClientSide && player != null) {
                ResourceLocation dim = level.dimension().location();
                bindToTerminal(stack, clickedPos, dim);
                player.displayClientMessage(
                        Component.translatable("craftingveloce.message.tablet_linked",
                                clickedPos.getX(), clickedPos.getY(), clickedPos.getZ())
                                .withStyle(ChatFormatting.GREEN),
                        true
                );
                level.playSound(null, clickedPos, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0f, 1.2f);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return super.useOn(context);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand usedHand) {
        ItemStack stack = player.getItemInHand(usedHand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            openTerminalForTablet(serverPlayer, stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    public static boolean openTerminalForTablet(ServerPlayer player, ItemStack tablet) {
        BoundTerminal bound = getBoundTerminal(tablet);
        if (bound == null) {
            player.displayClientMessage(
                    Component.translatable("craftingveloce.message.tablet_not_linked")
                            .withStyle(ChatFormatting.RED),
                    true
            );
            return false;
        }

        ResourceLocation currentDim = player.serverLevel().dimension().location();
        if (bound.dimension() != null && !bound.dimension().equals(currentDim)) {
            player.displayClientMessage(
                    Component.translatable("craftingveloce.message.tablet_wrong_dimension", bound.dimension().toString())
                            .withStyle(ChatFormatting.RED),
                    true
            );
            return false;
        }

        if (!player.serverLevel().isLoaded(bound.pos())) {
            player.displayClientMessage(
                    Component.translatable("craftingveloce.message.terminal_not_loaded")
                            .withStyle(ChatFormatting.RED),
                    true
            );
            return false;
        }

        BlockEntity be = player.serverLevel().getBlockEntity(bound.pos());
        if (!(be instanceof VeloceTerminalBlockEntity terminalBE)) {
            player.displayClientMessage(
                    Component.translatable("craftingveloce.message.terminalNotFound")
                            .withStyle(ChatFormatting.RED),
                    true
            );
            return false;
        }

        com.craftingveloce.util.VeloceProfiler.beginInteractionSession();
        try (var ignored = com.craftingveloce.util.VeloceProfiler.section("server.terminalOpen.tablet")) {
            terminalBE.onPlayerOpenTerminal(player);
            try (var ignored2 = com.craftingveloce.util.VeloceProfiler.section("server.terminalOpen.sendOpenPacket")) {
                PacketDistributor.sendToPlayer(player, new OpenTerminalScreenPKT(bound.pos()));
            }
        }
        return true;
    }

    public static List<ItemStack> getAllTablets(Player player) {
        List<ItemStack> tablets = new ArrayList<>();
        if (player.getMainHandItem().is(VeloceRegistry.VELOCE_TABLET.get())) {
            tablets.add(player.getMainHandItem());
        }
        if (player.getOffhandItem().is(VeloceRegistry.VELOCE_TABLET.get())) {
            tablets.add(player.getOffhandItem());
        }
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.is(VeloceRegistry.VELOCE_TABLET.get())) {
                if (!tablets.contains(stack)) {
                    tablets.add(stack);
                }
            }
        }
        if (ModList.get().isLoaded("curios")) {
            for (ItemStack stack : com.craftingveloce.compat.curios.VeloceCuriosCompat.getEquippedCurios(player)) {
                if (!stack.isEmpty() && stack.is(VeloceRegistry.VELOCE_TABLET.get())) {
                    if (!tablets.contains(stack)) {
                        tablets.add(stack);
                    }
                }
            }
        }
        return tablets;
    }

    public static boolean hasValidTabletFor(ServerPlayer player, BlockPos terminalPos) {
        ResourceLocation currentDim = player.serverLevel().dimension().location();
        for (ItemStack stack : getAllTablets(player)) {
            BoundTerminal bound = getBoundTerminal(stack);
            if (bound != null && bound.pos().equals(terminalPos)) {
                if (bound.dimension() == null || bound.dimension().equals(currentDim)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void openBestTablet(ServerPlayer player) {
        List<ItemStack> all = getAllTablets(player);
        if (all.isEmpty()) {
            return;
        }

        ItemStack best = null;
        ResourceLocation currentDim = player.serverLevel().dimension().location();
        for (ItemStack tablet : all) {
            BoundTerminal bound = getBoundTerminal(tablet);
            if (bound != null && currentDim.equals(bound.dimension())) {
                if (player.serverLevel().isLoaded(bound.pos())) {
                    BlockEntity be = player.serverLevel().getBlockEntity(bound.pos());
                    if (be instanceof VeloceTerminalBlockEntity) {
                        best = tablet;
                        break;
                    }
                }
            }
        }

        if (best != null) {
            openTerminalForTablet(player, best);
            return;
        }

        ItemStack firstBound = null;
        for (ItemStack tablet : all) {
            if (getBoundTerminal(tablet) != null) {
                firstBound = tablet;
                break;
            }
        }

        if (firstBound != null) {
            openTerminalForTablet(player, firstBound);
        } else {
            player.displayClientMessage(
                    Component.translatable("craftingveloce.message.no_linked_tablet")
                            .withStyle(ChatFormatting.RED),
                    true
            );
        }
    }
}
