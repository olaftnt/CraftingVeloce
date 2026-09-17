package com.craftingveloce.crafting;

import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Brewing as a network processing module.
 *
 * <p><b>Proxy rule (why every result and every input here is a PROXY item).</b>
 * Vanilla brewing has no {@code RecipeType}, so the network cannot reason about
 * potions by their {@code PotionContents} component. We therefore describe every
 * potion STATE as its own ordinary item - a proxy registered in
 * {@code VelocePotionMapper}. The network then plans over plain item ids, and
 * {@code VelocePotionMapper.toRealPotion} turns the proxy back into a real
 * potion at the moment it is extracted into a player inventory or a container.
 *
 * <p><b>The bug this fixes.</b> The results used to be MIXED: the awkward recipe
 * produced a real {@code minecraft:potion} stack while every other mix produced a
 * proxy. Two consequences, both reported as "crafting potions is completely
 * broken":
 * <ol>
 *   <li>the chain broke at the first step - the next mix requires the awkward
 *       PROXY as its input, but the awkward recipe produced a real potion, so
 *       {@code hasMix} could never match and no downstream potion was craftable;</li>
 *   <li>{@code producible()} then advertised the generic {@code minecraft:potion}
 *       item as craftable, i.e. the terminal offered an item that no recipe
 *       actually outputs.</li>
 * </ol>
 * Keeping inputs and outputs in the SAME domain (all proxies) is what makes the
 * plan and the execution agree.
 */
public class VeloceBrewingModule implements VeloceProcessingModule {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("craftingveloce-brewing");

    private final List<ProcessingEntry> recipes = new ArrayList<>();

    public VeloceBrewingModule() {
    }

    private void initRecipes() {
        if (!recipes.isEmpty()) return;
        
        recipes.add(ProcessingEntry.single(
            ResourceLocation.fromNamespaceAndPath("craftingveloce", "brewing_water"),
            waterBottle(),
            NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.GLASS_BOTTLE)),
            VeloceRecipes.getBrewing()
        ));
        
        recipes.add(ProcessingEntry.single(
            ResourceLocation.fromNamespaceAndPath("craftingveloce", "brewing_awkward"),
            proxy("awkward"),
            NonNullList.of(Ingredient.EMPTY, Ingredient.of(waterBottle()), Ingredient.of(Items.NETHER_WART)),
            VeloceRecipes.getBrewing()
        ));
        
        addMix("brewing_healing", "minecraft:awkward", Items.GLISTERING_MELON_SLICE, "minecraft:healing");
        addMix("brewing_fire_resistance", "minecraft:awkward", Items.MAGMA_CREAM, "minecraft:fire_resistance");
        addMix("brewing_regeneration", "minecraft:awkward", Items.GHAST_TEAR, "minecraft:regeneration");
        addMix("brewing_strength", "minecraft:awkward", Items.BLAZE_POWDER, "minecraft:strength");
        addMix("brewing_swiftness", "minecraft:awkward", Items.SUGAR, "minecraft:swiftness");
        addMix("brewing_night_vision", "minecraft:awkward", Items.GOLDEN_CARROT, "minecraft:night_vision");
        addMix("brewing_invisibility", "minecraft:night_vision", Items.FERMENTED_SPIDER_EYE, "minecraft:invisibility");
        addMix("brewing_water_breathing", "minecraft:awkward", Items.PUFFERFISH, "minecraft:water_breathing");
        addMix("brewing_leaping", "minecraft:awkward", Items.RABBIT_FOOT, "minecraft:leaping");
        addMix("brewing_slowness", "minecraft:swiftness", Items.FERMENTED_SPIDER_EYE, "minecraft:slowness");
        addMix("brewing_weakness", "minecraft:water", Items.FERMENTED_SPIDER_EYE, "minecraft:weakness");
        addMix("brewing_poison", "minecraft:awkward", Items.SPIDER_EYE, "minecraft:poison");
        addMix("brewing_harming", "minecraft:poison", Items.FERMENTED_SPIDER_EYE, "minecraft:harming");

        // The four base potions that were missing. The models for all of them
        // already existed (assets/craftingveloce/models/item/potion_*.json), so the
        // items were in the game and appeared in the terminal while nothing could
        // produce them - a player could see a Mundane Potion and not make one.
        //
        //   Mundane      water   + redstone        (vanilla: water + redstone)
        //   Thick        water   + glowstone dust  (vanilla: water + glowstone dust)
        //   Slow Falling awkward + phantom membrane
        //   Turtle Master awkward + turtle shell
        //
        // These are the RECIPES; the "any other ingredient brewed on water yields
        // Mundane" rule of vanilla is a different shape - see the note below.
        addMix("brewing_mundane", "minecraft:water", Items.REDSTONE, "minecraft:mundane");
        addMix("brewing_thick", "minecraft:water", Items.GLOWSTONE_DUST, "minecraft:thick");
        addMix("brewing_slow_falling", "minecraft:awkward", Items.PHANTOM_MEMBRANE, "minecraft:slow_falling");
        addMix("brewing_turtle_master", "minecraft:awkward", Items.TURTLE_HELMET, "minecraft:turtle_master");
    }
    
    /**
     * A recipe result in the PROXY domain - never a real potion.
     *
     * <p>A real potion cannot be a recipe token: its identity lives in the
     * {@code PotionContents} component, while the network, the planner and the
     * {@code ===} comparisons in {@code recipesFor} all work on the item alone.
     */
    private ItemStack proxy(String potionId) {
        Item proxy = com.craftingveloce.util.VelocePotionMapper.getProxy(potionId);
        if (proxy == Items.POTION) {
            // The fallback in getProxy is the plain potion item. Reaching it here
            // means the potion is NOT registered in VelocePotionMapper, and the
            // recipe would silently become a duplicate of every other broken one.
            LOG.warn("[VELOCE-DEBUG] potion '{}' has no registered proxy - "
                    + "falling back to minecraft:potion (recipe chain will not work)", potionId);
        }
        return new ItemStack(proxy);
    }

    /** The water bottle as a PROXY - the network's currency for "water potion". */
    private ItemStack waterBottle() {
        return proxy("water");
    }

    private void addMix(String id, String from, Item ingredient, String to) {
        Item fromProxy = com.craftingveloce.util.VelocePotionMapper.getProxy(from.replace("minecraft:", ""));
        Item toProxy = com.craftingveloce.util.VelocePotionMapper.getProxy(to.replace("minecraft:", ""));
        LOG.debug("[VELOCE-DEBUG] proxy mix {}: {} + {} -> {}", id, fromProxy, ingredient, toProxy);
        recipes.add(ProcessingEntry.single(
            ResourceLocation.fromNamespaceAndPath("craftingveloce", id),
            new net.minecraft.world.item.ItemStack(toProxy),
            NonNullList.of(Ingredient.EMPTY, Ingredient.of(fromProxy), Ingredient.of(ingredient)),
            VeloceRecipes.getBrewing()
        ));
    }

    
    @Override
    public String id() { return "brewing"; }
    
    @Override
    public Set<RecipeType<?>> recipeTypes() { return Set.of(VeloceRecipes.getBrewing()); }
    
    @Override
    public Set<Item> producible(ServerLevel level, VelocePipeNetwork network) {
        initRecipes();
        Set<Item> out = recipes.stream().map(r -> r.primaryResult().getItem()).collect(Collectors.toSet());
        LOG.debug("[VELOCE-DEBUG] brewing producible: {} result(s) from {} recipe(s) -> {}",
                out.size(), recipes.size(), out);
        return out;
    }
    
    @Override
    public boolean available(ServerLevel level, VelocePipeNetwork network) {
        if (network == null) return false;
        for (net.minecraft.core.BlockPos pos : network.getTerminals()) {
            if (level.isLoaded(pos) && level.getBlockState(pos).getBlock() == VeloceRegistry.BREWING_STAND.get()) {
                return true;
            }
        }
        return false;
    }
    
    @Override
    public boolean powered(ServerLevel level, VelocePipeNetwork network) {
        return available(level, network);
    }
    
    @Override
    public List<ProcessingEntry> recipesFor(ServerLevel level, VelocePipeNetwork network, Item item) {
        if (!powered(level, network)) {
            LOG.debug("[VELOCE-DEBUG] brewing match for {}: no powered brewing stand in the network", item);
            return List.of();
        }
        initRecipes();
        List<ProcessingEntry> matched = recipes.stream().filter(r -> r.primaryResult().getItem() == item).toList();
        // The count is the useful part: "no recipe" and "the recipe exists but names a
        // different item" look identical to the player, while they need opposite fixes.
        LOG.debug("[VELOCE-DEBUG] brewing match for {}: {} recipe(s) ({} known, proxy={})",
                item, matched.size(), recipes.size(),
                com.craftingveloce.util.VelocePotionMapper.isProxy(item));
        return matched;
    }
    
    @Override
    public List<ProcessingEntry> recipesAnywhere(ServerLevel level, Item item) {
        initRecipes();
        return recipes.stream().filter(r -> r.primaryResult().getItem() == item).toList();
    }
}
