package com.craftingveloce.compat.jei;

import net.minecraft.world.item.ItemStack;

/**
 * One row of the "right-click an Integrale with this" table, as JEI shows it.
 *
 * <p><b>Why a record and not our own {@code Conversion}.</b> JEI's category is generic
 * over the recipe type, and a recipe is whatever the category can draw. Wrapping the
 * two stacks here means the category never touches {@code Supplier<Block>} - which is
 * resolved lazily and could hand back an empty block if a mod's registration order
 * changed - and the two things the player actually needs to see are already stacks.
 */
public record IntegraleConversionRecipe(ItemStack input, ItemStack result) {
}
