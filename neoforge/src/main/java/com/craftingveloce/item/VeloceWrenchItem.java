package com.craftingveloce.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public class VeloceWrenchItem extends Item {

    public static final Component WRENCH_TOOLTIP = Component.literal("Shift-Right-Click on pipe connections to toggle connect / extract / disconnect.")
            .withStyle(ChatFormatting.GRAY);

    public static final TagKey<Item> C_WRENCH = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "tools/wrench"));
    public static final TagKey<Item> C_WRENCHES = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "wrenches"));

    public VeloceWrenchItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(WRENCH_TOOLTIP);
        super.appendHoverText(stack, context, tooltip, flag);
    }

    @Override
    public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
        net.minecraft.world.level.Level level = context.getLevel();
        net.minecraft.core.BlockPos pos = context.getClickedPos();
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        Player player = context.getPlayer();

        if (state.getBlock() instanceof com.craftingveloce.block.VelocePipeBlock pipe) {
            net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                    context.getClickLocation(), context.getClickedFace(), pos, context.isInside()
            );
            net.minecraft.world.ItemInteractionResult res = pipe.onWrenchClicked(state, level, pos, player, context.getHand(), hit);
            if (res.result().consumesAction()) {
                return res.result();
            }
        }
        return super.useOn(context);
    }

    public static boolean isWrench(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getItem() instanceof VeloceWrenchItem
                || stack.is(C_WRENCH)
                || stack.is(C_WRENCHES);
    }

    public static boolean isHoldingWrench(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (isWrench(stack)) {
                return true;
            }
        }
        return false;
    }
}
