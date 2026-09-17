package com.craftingveloce.util;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.resources.ResourceLocation;
import java.util.HashMap;
import java.util.Map;

public class VelocePotionMapper {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("craftingveloce-potion");

    private static final Map<String, Item> POTION_TO_PROXY = new HashMap<>();
    private static final Map<Item, String> PROXY_TO_POTION = new HashMap<>();

    public static void registerProxy(String potionId, Item proxyItem) {
        POTION_TO_PROXY.put(potionId, proxyItem);
        PROXY_TO_POTION.put(proxyItem, potionId);
        LOG.debug("[VELOCE-DEBUG] proxy registered: potion '{}' <-> item {}", potionId, proxyItem);
    }

    public static Item getProxy(ItemStack stack) {
        if (stack.getItem() != Items.POTION) return stack.getItem();
        var potionOpt = stack.getOrDefault(net.minecraft.core.component.DataComponents.POTION_CONTENTS, PotionContents.EMPTY).potion();
        if (potionOpt.isPresent()) {
            String id = potionOpt.get().unwrapKey().map(k -> k.location().getPath()).orElse("");
            return POTION_TO_PROXY.getOrDefault(id, stack.getItem());
        }
        return stack.getItem();
    }
    
    public static Item getProxy(String path) {
        return POTION_TO_PROXY.getOrDefault(path, Items.POTION);
    }

    public static boolean isProxy(Item item) {
        return PROXY_TO_POTION.containsKey(item);
    }

    public static ItemStack toRealPotion(Item proxyItem, int count) {
        String id = PROXY_TO_POTION.get(proxyItem);
        if (id == null) return new ItemStack(proxyItem, count);
        var potionOpt = net.minecraft.core.registries.BuiltInRegistries.POTION.getOptional(ResourceLocation.parse("minecraft:" + id));
        if (potionOpt.isEmpty()) {
            // A registered proxy whose potion id does not exist in the registry: the
            // player would silently receive a plain, effect-less potion.
            LOG.warn("[VELOCE-DEBUG] proxy {} maps to unknown potion 'minecraft:{}' - "
                    + "returning a plain potion", proxyItem, id);
            return new ItemStack(Items.POTION, count);
        }
        var holder = net.minecraft.core.registries.BuiltInRegistries.POTION.wrapAsHolder(potionOpt.get());
        ItemStack stack = PotionContents.createItemStack(Items.POTION, holder);
        stack.setCount(count);
        LOG.debug("[VELOCE-DEBUG] proxy conversion: {} x{} -> real potion minecraft:{}", proxyItem, count, id);
        return stack;
    }
    
    public static ItemStack toRealPotion(ItemStack proxyStack) {
        if (isProxy(proxyStack.getItem())) {
            return toRealPotion(proxyStack.getItem(), proxyStack.getCount());
        }
        return proxyStack;
    }
}
