package com.craftingveloce.crafting;

import com.craftingveloce.block.VeloceCaseContents;
import com.craftingveloce.init.VeloceRegistry;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Rozkladanie obudowy z powrotem na czesci - receptura w stole craftingu.
 *
 * <p><b>Co robi.</b> Gracz wklada przedmiot maszyny z obudowy (np. Mechanical
 * Crafter Module z zapisana liczba crafterow w NBT) i dostaje:
 * <ul>
 *   <li><b>pusta obudowe</b> ({@code veloce_integrale}) jako wynik,</li>
 *   <li><b>tyle klockow bazowych, ile bylo w srodku</b> - jako przedmioty
 *       zwrocone do siatki ({@link #getRemainingItems}).</li>
 * </ul>
 *
 * <p>Gracz: "gdy wloze ten przedmiot do Crafting Table, receptura powinna mi
 * zwrocic sam mechanical crafter w liczbie odpowiadajacej temu, ile ich tam
 * bylo wsadzonych, plus sam ten bazowy klocek".
 *
 * <p><b>Dlaczego przez {@code getRemainingItems}.</b> Waniliowa receptura ma
 * JEDEN wynik, a my chcemy dwa rozne przedmioty (obudowa + klocki bazowe).
 * Zwracanie czesci do siatki to mechanika wanilii (tak dziala np. wiadro po
 * mleku), wiec nie potrzebujemy wlasnego typu receptury ani ekranu.
 */
public class VeloceCaseDisassemblyRecipe extends CustomRecipe {

    public VeloceCaseDisassemblyRecipe() {
        super(CraftingBookCategory.MISC);
    }

    /** Wystarczy JEDEN przedmiot maszyny z obudowy i nic wiecej. */
    @Override
    public boolean matches(CraftingInput input, Level level) {
        return singleMachine(input) != null;
    }

    /** Wynik: pusta obudowa. Czesci wracaja przez {@link #getRemainingItems}. */
    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return singleMachine(input) == null
                ? ItemStack.EMPTY
                : new ItemStack(VeloceRegistry.VELOCE_INTEGRALE_ITEM.get());
    }

    /**
     * Zwraca klocki bazowe do siatki - po jednym na kazdy wklikany element.
     *
     * <p>Liczbe bierzemy z NBT przedmiotu (zapisywanego przy zbiciu maszyny).
     * Brak zapisu (np. maszyna ze stala zawartoscia) znaczy jeden klocek
     * bazowy. Duze liczby dzielimy na stosy, bo pole siatki nie pomiesci 81.
     */
    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        NonNullList<ItemStack> remaining = NonNullList.withSize(input.size(), ItemStack.EMPTY);
        ItemStack machine = singleMachine(input);
        if (machine == null) {
            return remaining;
        }
        Block base = baseBlock(machine);
        if (base == null) {
            return remaining;
        }
        int slot = 0;
        while (slot < input.size() && input.getItem(slot) != machine) {
            slot++;
        }
        int left = Math.max(1, partsOf(machine));
        int perStack = Math.max(1, new ItemStack(base.asItem()).getMaxStackSize());
        int target = Math.min(slot, remaining.size() - 1);
        while (left > 0 && target < remaining.size()) {
            int take = Math.min(perStack, left);
            remaining.set(target, new ItemStack(base.asItem(), take));
            left -= take;
            target++;
        }
        return remaining;
    }

    /** Receptura dziala w kazdym rozmiarze siatki (uzywa jednej pozycji). */
    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return VeloceRecipes.CASE_DISASSEMBLY.get();
    }

    /** Waniliowy stol craftingu (nie potrzebujemy wlasnego typu receptury). */
    @Override
    public RecipeType<?> getType() {
        return RecipeType.CRAFTING;
    }

    /** Jedyna niepusta pozycja siatki, o ile jest nasza maszyna z obudowa. */
    private static ItemStack singleMachine(CraftingInput input) {
        ItemStack found = null;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (found != null) {
                return null;   // wiecej niz jeden przedmiot - to nie nasza receptura
            }
            found = stack;
        }
        return found != null && baseBlock(found) != null ? found : null;
    }

    /** Blok bazowy tej maszyny (to, co wracalo do siatki), albo null. */
    private static Block baseBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        return VeloceCaseContents.contentFor(blockItem.getBlock());
    }

    /** Ile elementow zapisano w NBT przedmiotu (0 = brak zapisu). */
    public static int partsOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        return data == null ? 0 : data.copyTag().getInt("VeloceParts");
    }
}
