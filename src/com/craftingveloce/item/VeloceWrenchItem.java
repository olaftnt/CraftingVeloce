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
