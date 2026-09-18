package com.craftingveloce.client.gui;

import com.craftingveloce.crafting.VeloceCraftErrors;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reasons for failed attempts from the terminal - shown in the item tooltip.
 *
 * <p><b>The problem this solves.</b> The server reported the reason on the ACTION BAR,
 * which is not visible in the terminal GUI - the player clicked an item and nothing
 * visible happened. Now the reason goes into the tooltip of EXACTLY the item that the
 * player tried to craft ("Cannot craft: no furnace in the network", "Cannot craft: missing
 * iron ingot"...), so the answer is where the player is looking.
 *
 * <p><b>The key is the ITEM, not the stack.</b> Just like the network counters and how
 * the terminal list is grouped - otherwise the same item with different NBT would not
 * find its message.
 *
 * <p><b>Entries expire</b> ({@link #LIFETIME_MS}): a reason from half an hour ago has
 * almost always stopped applying, and a tooltip that lies is worse than no tooltip.
 */
public final class VeloceCraftErrorHints {

    /** How long we keep a reason before we consider it outdated. */
    private static final long LIFETIME_MS = 30_000L;

    /** One remembered reason together with the time it was stored. */
    private record Hint(String reason, String detail, String hint, long stamp) {
    }

    private final Map<Item, Hint> hints = new HashMap<>();

    /** Stores the reason of a failed attempt for this item. */
    public void record(ItemStack stack, String reason, String detail, String hint) {
        if (stack.isEmpty()) {
            return;
        }
        hints.put(stack.getItem(), new Hint(reason, detail,
                hint == null ? "" : hint, Util.getMillis()));
    }

    /**
     * Appends the reason if this item failed recently - and, under it, where else it
     * could have been made.
     *
     * <p><b>Why the second line exists.</b> When a recipe has several routes, blaming
     * one of them is wrong: a player with a crafting table and no logs was told there
     * was no Create saw, and went off to build a machine that would not have helped.
     * The reason now comes from the route that was actually tried, and the machines
     * appear underneath as OPTIONS. Entries are built from the installed modules only,
     * so a player without Create never sees a saw mentioned.
     *
     * <p>We remove an outdated entry along the way - this is the only place that
     * knows the reason is still needed.
     */
    public void appendTo(List<Component> tooltip, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        Hint hint = hints.get(stack.getItem());
        if (hint == null) {
            return;
        }
        if (Util.getMillis() - hint.stamp() > LIFETIME_MS) {
            hints.remove(stack.getItem());
            return;
        }
        tooltip.add(VeloceCraftErrors.message(hint.reason(), hint.detail(),
                hint.hint(), stack.getHoverName().getString()).withStyle(ChatFormatting.RED));
    }

    /** Clears the memory - called when the screen is closed. */
    public void clear() {
        hints.clear();
    }
}
