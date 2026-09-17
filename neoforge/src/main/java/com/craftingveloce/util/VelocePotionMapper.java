package com.craftingveloce.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.HashMap;
import java.util.Map;

/**
 * Translates between real potions and the ordinary items that stand in for them.
 *
 * <p><b>Why proxies exist at all.</b> Vanilla brewing has no {@code RecipeType}, so
 * the network, the planner and the storage - all of which work on plain items -
 * cannot see a potion's identity, which lives in the {@code PotionContents}
 * component. Every potion STATE therefore gets its own ordinary item, and
 * {@link #toRealPotion} turns it back into a real potion at the moment it is handed
 * to a player or dropped into a container.
 *
 * <p><b>Why the container is part of the key.</b> Brewing turns a potion into a
 * SPLASH potion and then into a LINGERING one, keeping the contents. Keyed on the
 * potion alone, "Potion of Healing", "Splash Potion of Healing" and "Lingering
 * Potion of Healing" would all resolve to one proxy: the network could hold only
 * one of them and would hand out whichever it happened to find. The key is
 * therefore {@code <container>_<potion>} - {@code healing}, {@code
 * splash_healing}, {@code lingering_healing} - with the plain potion's prefix
 * empty so the existing keys keep their names.
 */
public class VelocePotionMapper {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("craftingveloce-potion");

    private static final Map<String, Item> POTION_TO_PROXY = new HashMap<>();
    private static final Map<Item, String> PROXY_TO_POTION = new HashMap<>();

    public static void registerProxy(String potionId, Item proxyItem) {
        POTION_TO_PROXY.put(potionId, proxyItem);
        PROXY_TO_POTION.put(proxyItem, potionId);
        // Logged on the mod's own gated DETAIL channel, not raw slf4j DEBUG: the log
        // config filters DEBUG regardless of this mod's debugEnabled, so a DEBUG-only
        // line can never be surfaced by the setting the player is told to flip.
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "[VELOCE-DEBUG] proxy registered: potion '%s' <-> item %s", potionId, proxyItem);
    }

    /** Container prefix of a potion state, or empty when the item is not a potion container. */
    public static String containerPrefix(Item item) {
        if (item == Items.POTION) return "";
        if (item == Items.SPLASH_POTION) return "splash_";
        if (item == Items.LINGERING_POTION) return "lingering_";
        return null;
    }

    /** Potion path of a stack ("night_vision"), or null when it carries no potion. */
    public static String potionPath(ItemStack stack) {
        return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                .potion()
                .flatMap(holder -> holder.unwrapKey().map(key -> key.location().getPath()))
                .orElse(null);
    }

    /**
     * The proxy-table key of a potion state: {@code healing}, {@code splash_healing},
     * {@code lingering_healing}, {@code long_healing}, ...
     *
     * @return the key, or null when the stack is not a potion container at all
     */
    public static String proxyKey(ItemStack stack) {
        String prefix = containerPrefix(stack.getItem());
        if (prefix == null) {
            return null;
        }
        String potion = potionPath(stack);
        return potion == null ? null : prefix + potion;
    }

    /**
     * The proxy item for this stack, or null when no proxy is registered for it.
     *
     * <p>Null is a real answer, not a failure: another mod can add a potion we have
     * never heard of, and a caller that would otherwise plan a recipe has to know
     * that it cannot represent the result rather than silently using
     * {@code minecraft:potion} for every such potion at once.
     */
    public static Item proxyOrNull(ItemStack stack) {
        String key = proxyKey(stack);
        return key == null ? null : POTION_TO_PROXY.get(key);
    }

    public static Item getProxy(ItemStack stack) {
        Item proxy = proxyOrNull(stack);
        return proxy != null ? proxy : stack.getItem();
    }

    public static Item getProxy(String path) {
        return POTION_TO_PROXY.getOrDefault(path, Items.POTION);
    }

    /** How many potion states have a proxy - for the startup log. */
    public static int proxyKeyCount() {
        return POTION_TO_PROXY.size();
    }

    /** Whether a proxy is already registered for this key. */
    public static boolean hasProxy(String key) {
        return POTION_TO_PROXY.containsKey(key);
    }

    public static boolean isProxy(Item item) {
        return PROXY_TO_POTION.containsKey(item);
    }

    /** The proxy key this item stands for, or null when it is not a proxy. */
    public static String proxyKeyOf(Item item) {
        return PROXY_TO_POTION.get(item);
    }

    public static ItemStack toRealPotion(Item proxyItem, int count) {
        String key = PROXY_TO_POTION.get(proxyItem);
        if (key == null) return new ItemStack(proxyItem, count);

        // The key carries the container, because the same potion exists as a drink,
        // a splash and a lingering variant and all three must come back as themselves.
        Item container = Items.POTION;
        String potionPath = key;
        if (key.startsWith("splash_")) {
            container = Items.SPLASH_POTION;
            potionPath = key.substring("splash_".length());
        } else if (key.startsWith("lingering_")) {
            container = Items.LINGERING_POTION;
            potionPath = key.substring("lingering_".length());
        }

        var potionOpt = BuiltInRegistries.POTION.getOptional(ResourceLocation.parse("minecraft:" + potionPath));
        if (potionOpt.isEmpty()) {
            // A registered proxy whose potion id does not exist in the registry: the
            // player would silently receive a plain, effect-less potion.
            LOG.warn("[VELOCE-DEBUG] proxy {} maps to unknown potion 'minecraft:{}' - "
                    + "returning a plain potion", proxyItem, potionPath);
            return new ItemStack(container, count);
        }
        var holder = BuiltInRegistries.POTION.wrapAsHolder(potionOpt.get());
        ItemStack stack = PotionContents.createItemStack(container, holder);
        stack.setCount(count);
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "[VELOCE-DEBUG] proxy conversion: %s x%s -> real potion %s minecraft:%s",
                proxyItem, count, container, potionPath);
        return stack;
    }

    public static ItemStack toRealPotion(ItemStack proxyStack) {
        if (isProxy(proxyStack.getItem())) {
            return toRealPotion(proxyStack.getItem(), proxyStack.getCount());
        }
        return proxyStack;
    }
}
