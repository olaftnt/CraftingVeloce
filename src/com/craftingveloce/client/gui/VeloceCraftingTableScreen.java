package com.craftingveloce.client.gui;

import com.craftingveloce.network.CraftingTableToggleItemPKT;
import com.craftingveloce.network.CraftingTableCycleRecipePKT;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto-crafter GUI (Veloce Crafting Table).
 *
 * <p>Rules:
 * <ul>
 *   <li><b>Shows only craftable items.</b> Items without a recipe are
 *       <b>removed from the list</b>, so they leave no empty gaps - the rest
 *       slide into their place.</li>
 *   <li><b>Left click</b> toggles auto-crafting on/off (red = off,
 *       green = on).</li>
 *   <li><b>Right click</b> cycles the recipe if the item has more than one;
 *       if it has only one, nothing happens.</li>
 *   <li><b>Tooltip</b> shows the ingredients of every recipe by name.</li>
 * </ul>
 */
public class VeloceCraftingTableScreen extends VeloceCreativeScreen {

    private final BlockPos tablePos;
    /** Items DISABLED (opt-out model - everything is enabled by default). */
    private Set<Item> disabledItems;
    private Map<Item, ResourceLocation> preferredRecipes;

    /**
     * Client-side recipe index.
     *
     * <p><b>Static, because building it is expensive.</b> It used to be an
     * ordinary screen field, and the screen is created anew on EVERY GUI open -
     * so the index (thousands of recipes, each with resolved ingredients) was
     * built from scratch every time, on the client thread. On a large modpack
     * that is exactly the "the window froze" moment when opening the crafter.
     *
     * <p>We key it by the RecipeManager, so changing the world invalidates the
     * cache on its own.
     */
    private static Map<Item, List<ClientRecipe>> craftableItems = null;
    private static Object craftableItemsKey = null;

    /**
     * Contents of the crafter buffer - shown in the "Survival Inventory" tab
     * instead of the regular inventory. Items can be taken out of it, but not
     * put in.
     */
    private List<ItemStack> bufferContents;

    /** Recipe as seen on the client side (for tooltips and cycling). */
    private record ClientRecipe(ResourceLocation id, ItemStack result, List<List<ItemStack>> options) {
        int resultCount() {
            return Math.max(1, result.getCount());
        }
    }

    public VeloceCraftingTableScreen(LocalPlayer player, FeatureFlagSet enabledFeatures,
                                     boolean displayOperatorCreativeTab, BlockPos tablePos,
                                     Set<Item> disabledItems,
                                     Map<Item, ResourceLocation> preferredRecipes,
                                     List<ItemStack> bufferContents) {
        super(player, enabledFeatures, displayOperatorCreativeTab);
        this.tablePos = tablePos;
        this.disabledItems = new HashSet<>(disabledItems);
        this.preferredRecipes = new HashMap<>(preferredRecipes);
        this.bufferContents = new ArrayList<>(bufferContents);
    }

    /** Updates the buffer contents (after an item has been taken out). */
    public void updateBuffer(List<ItemStack> contents) {
        this.bufferContents = new ArrayList<>(contents);
    }

    public List<ItemStack> getBufferContents() {
        return bufferContents;
    }

    /**
     * Replaces the set of items with auto-crafting disabled (opt-out model).
     *
     * <p>The name says what you are passing. The method used to be called
     * {@code updateEnabledItems} while assigning to {@code disabledItems} -
     * asking for reversed semantics on the next change.
     */
    public void updateDisabledItems(Set<Item> items, Map<Item, ResourceLocation> prefs) {
        this.disabledItems = new HashSet<>(items);
        this.preferredRecipes = new HashMap<>(prefs);
    }

    // ------------------------------------------------------------------
    // Contents filtering
    // ------------------------------------------------------------------

    /**
     * We let through only items with a recipe that can be executed without
     * energy/fuel. The rest is removed from the list, so it leaves no gaps.
     */
    @Override
    protected boolean acceptItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        List<ClientRecipe> recipes = getCraftableItems().get(stack.getItem());
        return recipes != null && !recipes.isEmpty();
    }

    /**
     * Builds the index of craftable items from the recipes available on the
     * client side. We use only "infrastructure-free" types - exactly like the
     * server-side registry.
     */
    private Map<Item, List<ClientRecipe>> getCraftableItems() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return Collections.emptyMap();
        }
        Object key = mc.level.getRecipeManager();
        if (craftableItems != null && craftableItemsKey == key) {
            return craftableItems;
        }
        Map<Item, List<ClientRecipe>> out = new LinkedHashMap<>();
        var registries = mc.level.registryAccess();

        // RECIPE TYPES FROM A SINGLE PLACE. This method used to have its own,
        // third copy of the list (CRAFTING/STONECUTTING/SMITHING) alongside
        // VeloceRecipeRegistry and VeloceRecipeGraph - exactly the kind of
        // duplicate that has drifted apart several times in this project.
        // Now we take the families from VeloceRecipeFamilies, so a module
        // registered by compat/* shows up here without any client code change.
        for (RecipeType<?> type : com.craftingveloce.crafting.VeloceRecipeFamilies.withoutHeat()) {
            collectType(mc, type, registries, out);
        }

        craftableItems = out;
        craftableItemsKey = key;
        return craftableItems;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void collectType(Minecraft mc, RecipeType<?> type,
                             net.minecraft.core.HolderLookup.Provider registries,
                             Map<Item, List<ClientRecipe>> out) {
        List<net.minecraft.world.item.crafting.RecipeHolder<?>> holders = new ArrayList<>();
        try {
            holders.addAll(mc.level.getRecipeManager().getAllRecipesFor((RecipeType) type));
        } catch (Throwable t) {
            return;
        }
        for (var holder : holders) {
            var recipe = holder.value();
            // Filter "special" for VANILLA ONLY - mod recipes often use
            // this flag for ordinary recipes (Mekanism: all of its own),
            // so rejecting on isSpecial() alone cut whole mods out of the GUI.
            // The same rule as in the server index (single source of truth).
            if (com.craftingveloce.crafting.VeloceRecipeRegistry.isVanillaSpecial(recipe)) {
                continue;
            }
            ItemStack result;
            try {
                result = recipe.getResultItem(registries);
            } catch (Throwable t) {
                continue;
            }
            if (result.isEmpty()) {
                continue;
            }
            NonNullList<Ingredient> ings = recipe.getIngredients();
            List<List<ItemStack>> options = new ArrayList<>();
            boolean any = false;
            for (Ingredient ing : ings) {
                ItemStack[] items = ing.getItems();
                if (items.length == 0) {
                    continue;
                }
                any = true;
                // No s.copy(): these stacks are only read (tooltip, matching),
                // and getItems() returns an array managed by the Ingredient
                // itself. Copying every stack together with its components, for
                // every option of every ingredient, was the most expensive part
                // of the build here.
                options.add(java.util.Arrays.asList(items));
            }
            if (!any) {
                continue;
            }
            out.computeIfAbsent(result.getItem(), k -> new ArrayList<>())
                    .add(new ClientRecipe(holder.id(), result.copy(), options));
        }
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    @Override
    protected void renderSlot(GuiGraphics graphics, Slot slot) {
        if (isPlayerInventorySlot(slot) || isTrashSlot(slot)) {
            return;
        }
        super.renderSlot(graphics, slot);

        if (!slot.hasItem()) {
            return;
        }

        Item item = slot.getItem().getItem();
        List<ClientRecipe> recipes = getCraftableItems().get(item);
        boolean craftable = recipes != null && !recipes.isEmpty();

        int overlayColor;
        if (!craftable) {
            // Black background = item the crafter will not make.
            // (acceptItem removes those anyway, but if one showed up - it reads
            // clearly.)
            overlayColor = 0xAA000000;
        } else if (!disabledItems.contains(item)) {
            overlayColor = 0x7700AA00;   // green: auto-crafting ON
        } else {
            overlayColor = 0x77AA0000;   // red: recipe exists, but OFF
        }

        RenderSystem.disableDepthTest();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 200);
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, overlayColor);
        graphics.pose().popPose();
        RenderSystem.enableDepthTest();

    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        drawHotbarCover(graphics, 0xFFC6C6C6);
        renderRecipeTooltip(graphics, mouseX, mouseY);
    }

    // ------------------------------------------------------------------
    /**
     * Hides the "Survival Inventory" tab in the crafter.
     *
     * <p>In vanilla this tab opens the player inventory with armour, offhand
     * and 2x2 crafting - exactly what our GUI already shows (the crafter grid
     * plus the item list). The effect was confusing: a mismatched screen
     * appeared and there was no sensible way to use it.
     *
     * <p>Instead the tab is simply absent - just like the administrative tabs.
     */
    @Override
    protected boolean acceptTab(CreativeModeTab tab) {
        if (tab.getType() == CreativeModeTab.Type.INVENTORY) {
            return false;
        }
        return super.acceptTab(tab);
    }

    // ------------------------------------------------------------------
    // Inventory tab - HIDDEN
    // ------------------------------------------------------------------

    // History of attempts (so the mistakes are not repeated):
    //   1. Swapping slots in the vanilla screen -> items on the head slot,
    //      broken layout, because we were fighting the vanilla layout.
    //   2. A custom container screen with a scrollbar -> it opened by itself
    //      when entering the crafter and could not be closed (white block).
    //   3. Now: the tab is simply HIDDEN by acceptTab below.
    //
    // TODO (to be done later, the proper way):
    //   Copy the creative inventory screen into the mod as our own class
    //   and modify it directly. Then the inventory tab can show the crafter
    //   buffer without fighting vanilla - we have full control over the layout.
    //   Do not do this by swapping slots or by adding a second screen.

    // ------------------------------------------------------------------
    // Tooltip
    // ------------------------------------------------------------------

    /**
     * We override the vanilla tooltip to get rid of the lines that the creative
     * inventory adds in the CATEGORY and SEARCH tabs: category names,
     * raw item tags and the list of tabs containing the item.
     */
    @Override
    public List<Component> getTooltipFromContainerItem(ItemStack stack) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return super.getTooltipFromContainerItem(stack);
        }
        return stack.getTooltipLines(
                net.minecraft.world.item.Item.TooltipContext.of(this.minecraft.level),
                this.minecraft.player,
                this.minecraft.options.advancedItemTooltips
                        ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                        : net.minecraft.world.item.TooltipFlag.Default.NORMAL
        );
    }

    /** Tooltip: every recipe + its ingredients by name. */
    private void renderRecipeTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        Slot slot = getSlotUnderMouse();
        if (slot == null || isPlayerInventorySlot(slot) || !slot.hasItem()) {
            return;
        }
        Item item = slot.getItem().getItem();
        List<ClientRecipe> recipes = getCraftableItems().get(item);
        if (recipes == null || recipes.isEmpty()) {
            return;
        }

        List<Component> lines = new ArrayList<>();
        lines.add(slot.getItem().getHoverName());

        ResourceLocation pref = preferredRecipes.get(item);
        for (ClientRecipe r : recipes) {
            boolean active = pref != null ? pref.equals(r.id()) : recipes.indexOf(r) == 0;
            String marker = active ? "▶ " : "  ";
            String color = active ? "§a" : "§7";
            lines.add(Component.literal(color + marker + r.resultCount() + "x "
                    + r.result().getHoverName().getString()));

            String ing = describeIngredients(r);
            if (!ing.isEmpty()) {
                lines.add(Component.literal("§8     ").append(
                        Component.translatable("gui.craftingveloce.crafter.ingredients", ing)
                                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY)));
            }
        }

        lines.add(Component.empty());
        if (recipes.size() > 1) {
            lines.add(Component.translatable("gui.craftingveloce.crafter.recipe.cycle")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        lines.add(Component.translatable(disabledItems.contains(item)
                ? "gui.craftingveloce.crafter.toggle.on"
                : "gui.craftingveloce.crafter.toggle.off")
                .withStyle(net.minecraft.ChatFormatting.GRAY));

        graphics.renderTooltip(this.font, lines, java.util.Optional.empty(), mouseX, mouseY);
    }

    /**
     * Recipe ingredients as "4x Stone" - repetitions of the same item are
     * summed up.
     *
     * <p>A grid recipe returns a separate Ingredient for each slot, so
     * stone bricks (4x stone in a square shape) used to produce
     * "1x Stone, 1x Stone, 1x Stone, 1x Stone". We count the occurrences and
     * show a single entry with the total.
     */
    private static String describeIngredients(ClientRecipe r) {
        // Item name -> how many pieces. LinkedHashMap, so the order is stable.
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (List<ItemStack> options : r.options()) {
            if (options.isEmpty()) {
                continue;
            }
            String name = options.get(0).getHoverName().getString();
            counts.merge(name, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append("§8, §7");
            }
            sb.append(e.getValue()).append("x ").append(e.getKey());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Toggling a whole category (right click on the tab icon)
    // ------------------------------------------------------------------

    /** In the crafter only items with a recipe can be toggled. */
    @Override
    protected boolean isToggleable(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        List<ClientRecipe> recipes = getCraftableItems().get(stack.getItem());
        return recipes != null && !recipes.isEmpty();
    }

    /**
     * The crafter remembers its tab PER BLOCK.
     *
     * <p>Every crafter has its own remembered position, independent of
     * terminals, controllers and the regular creative inventory.
     */
    @Override
    protected Object viewStateKey() {
        return tablePos;
    }

    @Override
    protected boolean isToggledOn(Item item) {
        return !disabledItems.contains(item);
    }

    @Override
    protected void applyToggle(Item item) {
        if (disabledItems.contains(item)) {
            disabledItems.remove(item);
        } else {
            disabledItems.add(item);
        }
        PacketDistributor.sendToServer(new CraftingTableToggleItemPKT(tablePos, item));
    }

    // ------------------------------------------------------------------
    // Interaction
    // ------------------------------------------------------------------

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        if (this.minecraft == null || this.minecraft.player == null) return;

        if (slot == null || isPlayerInventorySlot(slot) || isTrashSlot(slot)) return;

        ItemStack item = slot.getItem();
        if (item.isEmpty()) return;

        Item clickedItem = item.getItem();
        List<ClientRecipe> recipes = getCraftableItems().get(clickedItem);
        if (recipes == null || recipes.isEmpty()) return;

        // Right click = change the recipe (when there is more than one).
        if (mouseButton == 1) {
            cycleRecipe(clickedItem, recipes);
            return;
        }

        // Left click = toggle auto-crafting on/off.
        boolean turningOff = !disabledItems.contains(clickedItem);
        if (disabledItems.contains(clickedItem)) {
            disabledItems.remove(clickedItem);
        } else {
            disabledItems.add(clickedItem);
        }
        com.craftingveloce.util.VeloceLog.Gui.success(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "player turned auto-crafting %s for %s",
                turningOff ? "OFF" : "ON", clickedItem);
        PacketDistributor.sendToServer(new CraftingTableToggleItemPKT(tablePos, clickedItem));
    }

    /**
     * Cycles the active recipe to the next one. When the item has only one
     * recipe, it does nothing (there is nothing to switch to).
     */
    private void cycleRecipe(Item item, List<ClientRecipe> recipes) {
        if (recipes == null || recipes.size() <= 1) {
            return;
        }
        List<ResourceLocation> ids = new ArrayList<>(recipes.size());
        for (ClientRecipe r : recipes) {
            ids.add(r.id());
        }
        ResourceLocation current = preferredRecipes.get(item);
        int idx = current == null ? -1 : ids.indexOf(current);
        // Double modulo, so the result is non-negative also for idx = -1
        // (an item with no selected recipe). The expression is already of type
        // int.
        int next = ((idx + 1) % ids.size() + ids.size()) % ids.size();
        ResourceLocation chosen = ids.get(next);
        preferredRecipes.put(item, chosen);
        PacketDistributor.sendToServer(
                new CraftingTableCycleRecipePKT(tablePos, item, chosen));
    }

    /**
     * Shift+scroll - an additional way to change the recipe.
     * In the creative inventory scroll is often captured by the list, so the
     * primary method is right click.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && net.minecraft.client.gui.screens.Screen.hasShiftDown()
                && this.minecraft != null && this.minecraft.player != null) {
            Slot slot = getSlotUnderMouse();
            if (slot != null && !isPlayerInventorySlot(slot) && slot.hasItem()) {
                Item item = slot.getItem().getItem();
                List<ClientRecipe> recipes = getCraftableItems().get(item);
                if (recipes != null && recipes.size() > 1) {
                    cycleRecipe(item, recipes);
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
