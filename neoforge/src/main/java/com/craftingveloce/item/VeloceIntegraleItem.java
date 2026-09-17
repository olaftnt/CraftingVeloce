package com.craftingveloce.item;

import com.craftingveloce.block.VeloceIntegraleConversions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * Veloce Integrale casing item - with a tooltip showing what turns into what.
 *
 * <p><b>Why the tooltip.</b> The casing is a shell: a right click with the
 * matching vanilla block (crafting table, dispenser, observer, crafting table,
 * furnace) converts it into our machine. Without that list the player has no way
 * of finding this out - and the table itself lives in ONE place
 * ({@link VeloceIntegraleConversions}), so the tooltip cannot drift away from
 * it: it is generated from it.
 *
 * <p>We take the names from the registry ({@code Block.getName()}), so
 * translations work on their own and there is no need to spell them out a second
 * time.
 */
public class VeloceIntegraleItem extends BlockItem {

    /** Header of the conversion list. */
    public static final String CONVERT_KEY = "gui.craftingveloce.integrale.convert";

    public VeloceIntegraleItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(CONVERT_KEY).withStyle(ChatFormatting.GRAY));
        for (VeloceIntegraleConversions.Conversion conversion : VeloceIntegraleConversions.all()) {
            // The key is the ID (a block from another mod may not exist yet at
            // the moment the entry is registered), so we resolve the name only
            // here.
            Block input = BuiltInRegistries.BLOCK.get(conversion.inputId());
            if (input == net.minecraft.world.level.block.Blocks.AIR) {
                continue;
            }
            tooltip.add(Component.literal(" ")
                    .append(input.getName())
                    .append(Component.literal(" -> "))
                    .append(conversion.resultBlock().getName())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
