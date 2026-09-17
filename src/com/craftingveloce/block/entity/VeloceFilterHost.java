package com.craftingveloce.block.entity;

import net.minecraft.world.item.ItemStack;

/**
 * A block that has a list of FILTERS selected by the player.
 *
 * <p>Two blocks in the mod have filters and both are selected the same way -
 * through the item picker screen: the extractor (what it should pull from the
 * network) and the Velocity Furnace (which fuel it should draw). Without a
 * shared interface each of them would need its own "set filter" packet, its own
 * path for opening the picker and its own screen reopening - that is, three
 * copies of the same logic that would sooner or later drift apart, just as the
 * network node list and the furnace recipe type list drifted apart.
 *
 * <p>The filters are NOT items in the world - they are only a choice of "which
 * item". That is why their slots are ghosts and cannot be filled by dragging.
 */
public interface VeloceFilterHost {

    /** How many filters this block has. */
    int filterCount();

    /** The filter with the given number (empty stack = no filter). */
    ItemStack getFilterAt(int index);

    /** Sets the filter; an empty stack clears the filter. */
    void setFilterAt(int index, ItemStack stack);
}
