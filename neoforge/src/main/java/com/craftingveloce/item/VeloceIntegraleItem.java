package com.craftingveloce.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * Veloce Integrale casing item - with ONE line of tooltip saying what to do with it.
 *
 * <p><b>Why the tooltip is a single sentence and not a list.</b> It used to spell out every
 * conversion the frame accepts - a wall of "X -&gt; Y" lines that grew with every integration
 * and buried the only thing the player actually needs to know, which is what to do. The list
 * is not lost: JEI has a browsable "Integrale Conversion" category built from the same table
 * ({@code com.craftingveloce.block.VeloceIntegraleConversions}), so the information lives where a player looks for a
 * recipe instead of in the space of a hover.
 */
public class VeloceIntegraleItem extends BlockItem {

    /** The one line of tooltip. */
    public static final String CONVERT_KEY = "gui.craftingveloce.integrale.convert";

    public VeloceIntegraleItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(CONVERT_KEY).withStyle(ChatFormatting.GRAY));
    }
}
