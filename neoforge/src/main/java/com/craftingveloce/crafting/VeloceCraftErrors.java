package com.craftingveloce.crafting;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The "why it failed" texts - a single source for every place that shows the
 * reason for a failed craft.
 *
 * <p><b>Why a separate class.</b> The reason ({@code reason}) and the detail
 * ({@code detail}) come from the planner and are TRANSLATION KEYS, not ready
 * text. The same reason is shown today by the item tooltip in the terminal,
 * while previously it was shown by the action bar - if every place glued the
 * message together itself, the keys would drift apart at the first change (and
 * the drift is only visible in game, as the raw string
 * "craftingveloce.craft.error.noFurnace").
 *
 * <p>The client receives just the reason and the detail from the server and
 * translates them ON ITS OWN - thanks to that the text is in the player's
 * language, not the server's.
 */
public final class VeloceCraftErrors {

    private VeloceCraftErrors() {
    }

    /**
     * The message key with the detail for a given planner reason.
     *
     * <p>Not every reason has room for a detail: {@code noBase} and
     * {@code extract} say "X is missing" / "failed to take X", so they have
     * separate variants, while {@code noModule} and {@code moduleUnpowered}
     * already have that room built in (we substitute the module or machine name
     * there).
     */
    public static String detailKey(String reason) {
        return switch (reason) {
            case "craftingveloce.craft.error.noBase" ->
                    "craftingveloce.craft.error.noBaseItem";
            case "craftingveloce.craft.error.extract" ->
                    "craftingveloce.craft.error.extractItem";
            default -> reason;
        };
    }

    /**
     * The error message: first the reason from the server, and only when there
     * is none - the generic "this item is not in the network".
     */
    public static MutableComponent message(String reason, String detail, String itemName) {
        if (!reason.isEmpty()) {
            if (!detail.isEmpty()) {
                return Component.translatable(detailKey(reason), detail);
            }
            return Component.translatable(reason);
        }
        return Component.translatable("craftingveloce.message.itemNotInNetwork", itemName);
    }
}
