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
        return message(reason, detail, "", itemName);
    }

    /**
     * The message, with the "available in" line under it when there is one.
     *
     * <p>{@code hint} names the machines that could ALSO make the item. It is gold and
     * on its own line on purpose: it is not a reason for the failure, it is an option
     * the player may not have known about. Reading it as a cause is exactly the bug
     * this replaced.
     */
    public static MutableComponent message(String reason, String detail, String hint,
                                           String itemName) {
        MutableComponent out;
        if (!reason.isEmpty()) {
            if (!detail.isEmpty()) {
                out = Component.translatable(detailKey(reason), asArgument(detail));
            } else {
                out = Component.translatable(reason);
            }
        } else {
            out = Component.translatable("craftingveloce.message.itemNotInNetwork", itemName);
        }
        if (hint != null && !hint.isEmpty()) {
            out.append(Component.literal("\n")).append(
                    Component.translatable("craftingveloce.craft.hint.availableIn",
                            asArgument(hint)).withStyle(net.minecraft.ChatFormatting.GOLD));
        }
        return out;
    }

    /**
     * The detail as a component: translation keys get translated, anything else is literal.
     *
     * <p><b>Why this exists.</b> The server names machines and missing ingredients by their
     * description ids ({@code block.craftingveloce.brewing_stand},
     * {@code item.minecraft.nether_wart}), because that is the only form the CLIENT can
     * turn into the player's language. A detail is allowed to be a comma-separated list -
     * a recipe can miss several ingredients at once - so each part is decided on its own.
     */
    private static MutableComponent asArgument(String detail) {
        MutableComponent out = Component.empty();
        String[] parts = detail.split(", ");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(asPart(parts[i]));
        }
        return out;
    }

    /**
     * One part of the detail: a count in front of a key, a key, or plain text.
     *
     * <p>The count form exists because a recipe can want the same ingredient more than
     * once - a door needs six planks - and the player was shown "oak planks oak planks
     * oak planks..." rather than "6x oak planks". The count is the SERVER's text (it is
     * a number), so only the name behind it needs translating, and that is why the
     * prefix is stripped here rather than being part of the key.
     */
    private static MutableComponent asPart(String value) {
        java.util.regex.Matcher count = COUNTED.matcher(value);
        if (count.matches()) {
            return Component.literal(count.group(1)).append(Component.translatable(count.group(2)));
        }
        return looksLikeKey(value) ? Component.translatable(value) : Component.literal(value);
    }

    /** "6x item.minecraft.oak_planks" -> "6x " + the key. */
    private static final java.util.regex.Pattern COUNTED =
            java.util.regex.Pattern.compile("(\\d+x )(.+)");

    private static boolean looksLikeKey(String value) {
        return value.startsWith("block.") || value.startsWith("item.")
                || value.startsWith("entity.");
    }
}
