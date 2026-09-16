package com.craftingveloce.crafting;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Jak w ogole zrobic ten item" - receptury ze WSZYSTKICH zrodel.
 *
 * <p><b>Czym to sie rozni od {@link VeloceModuleRecipes}.</b> Tamto odpowiada
 * na pytanie PLANERA: "co moge zrobic TERAZ z tego, co mam w sieci" - wiec
 * przepuszcza tylko moduly z maszyna i pradem. To jest pytanie CZLOWIEKA
 * (i narzedzi, np. komendy {@code /cv getitems}): "jak sie to robi" - wiec
 * receptura musi byc widoczna takze wtedy, gdy gracz nie ma jeszcze zadnej
 * maszyny.
 *
 * <p><b>Kolejnosc ma znaczenie dla komunikatow:</b> najpierw receptury bez
 * infrastruktury (crafting table, stonecutter, smithing), potem rodziny
 * modulow (Create/Alchemistry/Mekanism), a na koncu piec - bo dla itemu
 * z receptura craftingowa informacja "mozna tez w piecu" jest tylko dodatkiem.
 *
 * <p><b>Bez duplikatow.</b> Ta sama receptura moze byc widziana z dwoch stron
 * (np. indeks i modul), wiec kluczujemy po identyfikatorze receptury.
 */
public final class VeloceRecipeFinder {

    private VeloceRecipeFinder() {
    }

    /** Wszystkie receptury wytwarzajace dany item, w kolejnosci zrodel. */
    public static List<ProcessingEntry> all(ServerLevel level, Item item) {
        Map<ResourceLocation, ProcessingEntry> unique = new LinkedHashMap<>();
        add(unique, VeloceRecipeRegistry.getRecipesFor(level, item));
        for (VeloceProcessingModule module : VeloceProcessingRegistry.all()) {
            add(unique, module.recipesAnywhere(level, item));
        }
        add(unique, VeloceRecipeRegistry.getFurnaceRecipesFor(level, item));
        return new ArrayList<>(unique.values());
    }

    /**
     * Czy receptura pochodzi z rodziny modulu (Create/Alchemistry/Mekanism)?
     *
     * <p>Do komunikatow: gracz ma wiedziec, ze do tej receptury potrzebna jest
     * maszyna modulu, a nie zwykly crafting table.
     */
    public static boolean isModuleRecipe(ProcessingEntry recipe) {
        Set<RecipeType<?>> furnace = VeloceRecipeFamilies.FURNACE;
        return !furnace.contains(recipe.type())
                && !VeloceRecipeFamilies.isFree(recipe.type())
                && VeloceRecipeFamilies.isKnown(recipe.type());
    }

    /** Czytelna nazwa typu receptury, np. {@code "create:mechanical_crafting"}. */
    public static String typeName(RecipeType<?> type) {
        ResourceLocation id = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        return id == null ? String.valueOf(type) : id.toString();
    }

    private static void add(Map<ResourceLocation, ProcessingEntry> unique,
                            List<ProcessingEntry> recipes) {
        if (recipes == null) {
            return;
        }
        for (ProcessingEntry recipe : recipes) {
            unique.putIfAbsent(recipe.id(), recipe);
        }
    }
}
