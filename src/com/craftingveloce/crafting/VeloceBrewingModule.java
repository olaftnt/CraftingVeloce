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
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class VeloceBrewingModule implements VeloceProcessingModule {
    
    private final List<ProcessingEntry> recipes = new ArrayList<>();
    
    public VeloceBrewingModule() {
    }

    private void initRecipes() {
        if (!recipes.isEmpty()) return;
        
        recipes.add(ProcessingEntry.single(
            ResourceLocation.fromNamespaceAndPath("craftingveloce", "brewing_water"),
            waterBottle(),
            NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.GLASS_BOTTLE)),
            VeloceRecipes.BREWING.get()
        ));
        
        recipes.add(ProcessingEntry.single(
            ResourceLocation.fromNamespaceAndPath("craftingveloce", "brewing_awkward"),
            potion("minecraft:awkward"),
            NonNullList.of(Ingredient.EMPTY, Ingredient.of(waterBottle()), Ingredient.of(Items.NETHER_WART)),
            VeloceRecipes.BREWING.get()
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
    }
    
    private ItemStack waterBottle() {
        return potion("minecraft:water");
    }
    
    @SuppressWarnings("unchecked")
    private ItemStack potion(String id) {
        var potionOpt = net.minecraft.core.registries.BuiltInRegistries.POTION.getOptional(ResourceLocation.parse(id));
        if (potionOpt.isEmpty()) return new ItemStack(Items.POTION);
        
        var holder = net.minecraft.core.registries.BuiltInRegistries.POTION.wrapAsHolder(potionOpt.get());
        return PotionContents.createItemStack(Items.POTION, holder);
    }
    
    private void addMix(String id, String from, Item ingredient, String to) {
        recipes.add(ProcessingEntry.single(
            ResourceLocation.fromNamespaceAndPath("craftingveloce", id),
            potion(to),
            NonNullList.of(Ingredient.EMPTY, Ingredient.of(potion(from)), Ingredient.of(ingredient)),
            VeloceRecipes.BREWING.get()
        ));
    }
    
    @Override
    public String id() { return "brewing"; }
    
    @Override
    public Set<RecipeType<?>> recipeTypes() { return Set.of(VeloceRecipes.BREWING.get()); }
    
    @Override
    public Set<Item> producible(ServerLevel level, VelocePipeNetwork network) {
        initRecipes();
        return recipes.stream().map(r -> r.primaryResult().getItem()).collect(Collectors.toSet());
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
        if (!powered(level, network)) return List.of();
        initRecipes();
        return recipes.stream().filter(r -> r.primaryResult().getItem() == item).toList();
    }
    
    @Override
    public List<ProcessingEntry> recipesAnywhere(ServerLevel level, Item item) {
        initRecipes();
        return recipes.stream().filter(r -> r.primaryResult().getItem() == item).toList();
    }
}
