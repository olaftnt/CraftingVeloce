package com.craftingveloce.compat.jei;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.network.chat.Component;

/**
 * JEI category: "Integrale conversion" - the frame plus an item becomes a module.
 *
 * <p><b>Why a category of our own and not just a catalyst.</b> A catalyst only puts an
 * icon next to somebody else's recipe, so the RULE - that a machine is made by
 * right-clicking the frame, and never in a crafting table - had nowhere to be read. A
 * player who looks for a crusher in the crafting recipes finds nothing and concludes the
 * mod is broken. Making it a real, browsable category is the difference between "this
 * cannot be made" and "this is made differently".
 *
 * <p><b>The rows come from the conversion table.</b> Everything here is built from
 * {@code VeloceIntegraleConversions.all()} - the same table the block consults when the
 * player right-clicks. A second, hand-written list would be the classic way for the
 * shown recipes and the working ones to drift apart, and the drift shows up only as a
 * player complaint.
 *
 * <p><b>Only installed mods appear.</b> The compat modules add their rows from inside
 * their {@code isPresent()} gate, so a table entry for a mod that is not there does not
 * exist to be shown.
 */
public final class IntegraleConversionCategory
        implements IRecipeCategory<IntegraleConversionRecipe> {

    public static final RecipeType<IntegraleConversionRecipe> TYPE =
            RecipeType.create(VeloceJeiPlugin.MOD_ID, "integrale_conversion",
                    IntegraleConversionRecipe.class);

    /** Slot positions inside the category's own little grid. */
    private static final int INPUT_X = 20;
    private static final int INPUT_Y = 20;
    private static final int RESULT_X = 76;
    private static final int RESULT_Y = 20;

    private final IDrawable icon;

    public IntegraleConversionCategory(IGuiHelper guiHelper) {
        // The frame is the icon: it is the one thing every row has in common, and it is
        // what the player has to be holding.
        this.icon = guiHelper.createDrawableItemStack(
                new net.minecraft.world.item.ItemStack(
                        com.craftingveloce.init.VeloceRegistry.VELOCE_INTEGRALE_ITEM.get()));
    }

    @Override
    public RecipeType<IntegraleConversionRecipe> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("craftingveloce.jei.integrale_conversion");
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public int getWidth() {
        return 120;
    }

    @Override
    public int getHeight() {
        return 54;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, IntegraleConversionRecipe recipe,
                          IFocusGroup focuses) {
        builder.addInputSlot(INPUT_X, INPUT_Y)
                .addItemStack(recipe.input());
        builder.addOutputSlot(RESULT_X, RESULT_Y)
                .addItemStack(recipe.result());
    }

    /**
     * One line under the slots: the rule in words, because the slots alone say "these
     * two things are related" and not WHAT to do - and the whole point of the category
     * is that the action is a right-click on the frame, not a crafting recipe.
     */
    @Override
    public void draw(IntegraleConversionRecipe recipe, IRecipeSlotsView slots,
                     net.minecraft.client.gui.GuiGraphics graphics, double mouseX, double mouseY) {
        graphics.drawString(net.minecraft.client.Minecraft.getInstance().font,
                Component.translatable("craftingveloce.jei.integrale_conversion.howto"),
                2, 44, 0x808080, false);
    }
}
