package com.craftingveloce.crafting;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The player's carried items, seen as a crafting source.
 *
 * <p><b>Why this exists now and did not before.</b> {@link VeloceAutoCrafter.ItemInventory}
 * was written with every branch in place and a note saying it would not be wired up without
 * the owner's decision, because doing so changes behaviour: crafting starts eating items out
 * of the player's inventory. The owner has asked for exactly that, with one condition - the
 * NETWORK is used first, and the player's inventory only covers what the network could not
 * supply. So the priority in the planner was inverted at the same time as this was wired in;
 * wiring this up alone would have taken items out of the player's pockets while a chest full
 * of the same item stood next to the terminal.
 *
 * <p><b>Carried means main inventory and offhand, NOT armour.</b> The inventory object also
 * holds the four armour slots, and counting those would let a recipe be paid for with the
 * helmet off the player's head. Armour is worn, not carried, and nobody expects a machine to
 * take it.
 *
 * <p><b>Built only when there is a player.</b> A machine has none: the extractor crafts on a
 * timer with nobody present, so it passes {@code null} and behaves exactly as before. Only
 * the terminal, which is always used by somebody, passes an inventory.
 */
public final class VelocePlayerInventory implements VeloceAutoCrafter.ItemInventory {

    /**
     * The main inventory slots - hotbar plus backpack.
     *
     * <p>Vanilla lays the inventory out as 0-35 main, 36-39 armour, 40 offhand, and this
     * walks the first group before the last, so the hotbar and backpack are spent before the
     * item the player is holding in the other hand.
     */
    private static final int MAIN_SLOTS = 36;

    private final ServerPlayer player;

    public VelocePlayerInventory(ServerPlayer player) {
        this.player = player;
    }

    @Override
    public int count(Item item) {
        int total = 0;
        for (ItemStack stack : carried()) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @Override
    public ItemStack extract(Item item, int max) {
        int taken = 0;
        for (ItemStack stack : carried()) {
            if (taken >= max) {
                break;
            }
            if (!stack.is(item)) {
                continue;
            }
            int n = Math.min(max - taken, stack.getCount());
            stack.shrink(n);
            taken += n;
        }
        if (taken <= 0) {
            return ItemStack.EMPTY;
        }
        // The inventory is what the client renders and what the server compares against, so
        // a change made here has to be pushed to the player - otherwise the items vanish
        // from the crafting plan but stay visible in the hotbar until something else
        // refreshes it.
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        return new ItemStack(item, taken);
    }

    @Override
    public Set<Item> allItems() {
        Set<Item> items = new LinkedHashSet<>();
        for (ItemStack stack : carried()) {
            if (!stack.isEmpty()) {
                items.add(stack.getItem());
            }
        }
        return items;
    }

    /**
     * The carried stacks, main inventory first and then the offhand.
     *
     * <p>Returns the live {@link ItemStack} objects, not copies, because {@link #extract}
     * has to shrink them in place - the whole point is that the items leave the player.
     */
    private java.util.List<ItemStack> carried() {
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>(MAIN_SLOTS + 1);
        for (int i = 0; i < MAIN_SLOTS; i++) {
            stacks.add(player.getInventory().getItem(i));
        }
        stacks.add(player.getOffhandItem());
        return stacks;
    }
}
