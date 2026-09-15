package com.craftingveloce.client.gui;

import com.craftingveloce.network.CraftingTableToggleItemPKT;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VeloceCraftingTableScreen extends CreativeModeInventoryScreen {

    private final BlockPos tablePos;
    private Set<Item> enabledItems;

    @Nullable
    private GameType modeBeforeOpen;

    private static Field slotWrapperTargetField;

    // Cached set of items that have crafting recipes
    private Set<Item> craftableItems = null;

    static {
        try {
            Class<?> wrapperClass = Class.forName("net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$SlotWrapper");
            for (Field f : wrapperClass.getDeclaredFields()) {
                if (Slot.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    slotWrapperTargetField = f;
                    break;
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public VeloceCraftingTableScreen(LocalPlayer player, FeatureFlagSet enabledFeatures, boolean displayOperatorCreativeTab, BlockPos tablePos, Set<Item> enabledItems) {
        super(player, enabledFeatures, displayOperatorCreativeTab);
        this.tablePos = tablePos;
        this.enabledItems = new HashSet<>(enabledItems);
    }

    public void updateEnabledItems(Set<Item> items) {
        this.enabledItems = new HashSet<>(items);
    }

    @Override
    protected void init() {
        if (this.minecraft == null || this.minecraft.gameMode == null) {
            super.init();
            return;
        }
        if (!this.minecraft.gameMode.hasInfiniteItems()) {
            if (this.modeBeforeOpen == null) {
                this.modeBeforeOpen = this.minecraft.gameMode.getPlayerMode();
            }
            this.minecraft.gameMode.setLocalMode(GameType.CREATIVE);
        }
        super.init();
        suppressHotbarSlots();
    }

    private Set<Item> getCraftableItems() {
        if (craftableItems != null) return craftableItems;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return Collections.emptySet();
        craftableItems = new HashSet<>();
        List<net.minecraft.world.item.crafting.RecipeHolder<CraftingRecipe>> recipes =
                mc.level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING);
        for (var holder : recipes) {
            ItemStack result = holder.value().getResultItem(mc.level.registryAccess());
            if (!result.isEmpty()) {
                craftableItems.add(result.getItem());
            }
        }
        return craftableItems;
    }

    private boolean isPlayerInventorySlot(Slot slot) {
        if (slot == null || this.minecraft == null || this.minecraft.player == null) return false;
        if (slot.container == this.minecraft.player.getInventory()) return true;
        if (slotWrapperTargetField != null) {
            try {
                Object target = slotWrapperTargetField.get(slot);
                if (target instanceof Slot ts && ts.container == this.minecraft.player.getInventory()) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private void suppressHotbarSlots() {
        if (this.menu == null || this.minecraft == null || this.minecraft.player == null) return;
        for (int i = 0; i < this.menu.slots.size(); i++) {
            Slot s = this.menu.slots.get(i);
            if (isPlayerInventorySlot(s)) {
                final Slot orig = s;
                this.menu.slots.set(i, new Slot(orig.container, orig.getContainerSlot(), -10000, -10000) {
                    @Override
                    public boolean isActive() { return false; }
                    @Override
                    public boolean isHighlightable() { return false; }
                });
            }
        }
    }

    @Override
    public void containerTick() {
    }

    @Override
    protected void renderSlot(GuiGraphics graphics, Slot slot) {
        if (isPlayerInventorySlot(slot)) {
            return;
        }

        // Skip the trash slot (bottom right corner)
        if (slot.x == 173 && slot.y == 112) {
            return;
        }

        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();
            Item item = stack.getItem();
            Set<Item> craftable = getCraftableItems();

            // Determine color overlay
            int overlayColor;
            if (!craftable.contains(item)) {
                // Purple: no crafting recipe
                overlayColor = 0x77800080;
            } else if (enabledItems.contains(item)) {
                // Green: enabled / ON
                overlayColor = 0x7700AA00;
            } else {
                // Red: has recipe but OFF (default)
                overlayColor = 0x77AA0000;
            }

            // Render the item first
            super.renderSlot(graphics, slot);

            // Then draw the colored overlay on top
            RenderSystem.disableDepthTest();
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 200);
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, overlayColor);
            graphics.pose().popPose();
            RenderSystem.enableDepthTest();
        } else {
            super.renderSlot(graphics, slot);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Blank out the hotbar area
        int x1 = this.leftPos + 8;
        int y1 = this.topPos + 111;
        int x2 = this.leftPos + 170;
        int y2 = this.topPos + 130;
        graphics.fill(x1, y1, x2, y2, 0xFFC6C6C6);
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        if (this.minecraft == null || this.minecraft.player == null) return;

        if (slot == null || isPlayerInventorySlot(slot)) return;

        // Trash slot - ignore
        if (slot.x == 173 && slot.y == 112) return;

        ItemStack item = slot.getItem();
        if (item.isEmpty()) return;

        Item clickedItem = item.getItem();

        // Only allow toggling items that have crafting recipes
        Set<Item> craftable = getCraftableItems();
        if (!craftable.contains(clickedItem)) return;

        // Toggle enabled state
        if (enabledItems.contains(clickedItem)) {
            enabledItems.remove(clickedItem);
        } else {
            enabledItems.add(clickedItem);
        }

        // Send to server
        PacketDistributor.sendToServer(new CraftingTableToggleItemPKT(tablePos, clickedItem));
    }

    @Override
    public void removed() {
        if (this.minecraft != null && this.minecraft.player != null && this.menu != null
                && !this.menu.getCarried().isEmpty()) {
            this.menu.setCarried(ItemStack.EMPTY);
        }
        super.removed();
        if (this.modeBeforeOpen != null && this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.setLocalMode(this.modeBeforeOpen);
            this.modeBeforeOpen = null;
        }
    }
}
