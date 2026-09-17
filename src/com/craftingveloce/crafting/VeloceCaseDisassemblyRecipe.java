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
 * Disassembling the casing back into parts - a recipe in the crafting table.
 *
 * <p><b>What it does.</b> The player puts in a machine item from the casing
 * (e.g. a Mechanical Crafter Module with the number of crafters saved in NBT)
 * and gets:
 * <ul>
 *   <li><b>an empty casing</b> ({@code veloce_integrale}) as the output,</li>
 *   <li><b>as many base blocks as there were inside</b> - as items returned to
 *       the grid ({@link #getRemainingItems}).</li>
 * </ul>
 *
 * <p>Player: "when I put this item into the Crafting Table, the recipe should
 * give me back just the mechanical crafter in a number matching how many were
 * put in there, plus that base block itself".
 *
 * <p><b>Why via {@code getRemainingItems}.</b> A vanilla recipe has ONE output,
 * and we want two different items (the casing + the base blocks). Returning
 * parts to the grid is vanilla mechanics (that is how, for example, a bucket
 * after milk works), so we need no recipe type or screen of our own.
 */
public class VeloceCaseDisassemblyRecipe extends CustomRecipe {

    public VeloceCaseDisassemblyRecipe() {
        super(CraftingBookCategory.MISC);
    }

    /** ONE machine item from the casing and nothing else is enough. */
    @Override
    public boolean matches(CraftingInput input, Level level) {
        return singleMachine(input) != null;
    }

    /** Output: the empty casing. The parts come back through {@link #getRemainingItems}. */
    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return singleMachine(input) == null
                ? ItemStack.EMPTY
                : new ItemStack(VeloceRegistry.VELOCE_INTEGRALE_ITEM.get());
    }

    /**
     * Returns the base blocks to the grid - one for every element inserted.
     *
     * <p>We take the number from the item's NBT (written when the machine is
     * broken). No entry (e.g. a machine with fixed contents) means one base
     * block. Large numbers we split into stacks, because a grid slot will not
     * hold 81.
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

    /** The recipe works in every grid size (it uses a single position). */
    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return VeloceRecipes.CASE_DISASSEMBLY.get();
    }

    /** The vanilla crafting table (we need no recipe type of our own). */
    @Override
    public RecipeType<?> getType() {
        return RecipeType.CRAFTING;
    }

    /** The single non-empty grid position, provided it is our machine with a casing. */
    private static ItemStack singleMachine(CraftingInput input) {
        ItemStack found = null;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (found != null) {
                return null;   // more than one item - this is not our recipe
            }
            found = stack;
        }
        return found != null && baseBlock(found) != null ? found : null;
    }

    /** The base block of this machine (the thing that was returned to the grid), or null. */
    private static Block baseBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        return VeloceCaseContents.contentFor(blockItem.getBlock());
    }

    /** How many elements are saved in the item's NBT (0 = no entry). */
    public static int partsOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        return data == null ? 0 : data.copyTag().getInt("VeloceParts");
    }
}
