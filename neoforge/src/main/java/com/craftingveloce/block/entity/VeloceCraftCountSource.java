package com.craftingveloce.block.entity;

import com.craftingveloce.crafting.VeloceAutoCrafter;
import net.minecraft.world.item.Item;

import java.util.Collection;

/**
 * A block that can count "how many can still be crafted" for its network.
 *
 * <p><b>Why a shared interface.</b> The terminal counted these values for the
 * visible page, and the controller needs EXACTLY the same thing - the same
 * numbers, from the same logic. Without a shared interface the
 * {@code RequestCraftableCountsPKT} packet would have to know both block types
 * and branch on them, and every further screen showing numbers would add a third
 * branch. That is the same mistake that already once made the network node list
 * and the furnace recipe type list drift apart.
 *
 * <p>The implementation MUST find its own network (the terminal and the
 * controller do it identically) and MUST take care of its own time budget -
 * counting the recipe tree for a whole page is real work on the server thread.
 */
public interface VeloceCraftCountSource {

    /**
     * Craftable numbers for the given items.
     *
     * @param items the items visible on the player's screen
     * @return the numbers + information whether ALL requested items were counted
     *         (an incomplete result must not erase valid numbers on the client)
     */
    VeloceAutoCrafter.BatchResult computeCraftableCounts(Collection<Item> items);
}
