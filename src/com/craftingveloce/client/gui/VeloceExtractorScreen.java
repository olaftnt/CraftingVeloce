package com.craftingveloce.client.gui;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.inventory.VeloceExtractorMenu;
import com.craftingveloce.network.ExtractorOpenFilterPKT;
import com.craftingveloce.network.ExtractorSetFilterPKT;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

public class VeloceExtractorScreen extends AbstractContainerScreen<VeloceExtractorMenu> {

    private static final ResourceLocation GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(CraftingVeloceMod.MODID, "textures/gui/veloce_extractor.png");

    private final NonNullList<ItemStack> clientFilters = NonNullList.withSize(9, ItemStack.EMPTY);

    public VeloceExtractorScreen(VeloceExtractorMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 212;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
        this.inventoryLabelX = 26;
        this.titleLabelX = 26;

        if (menu.getExtractorBE() != null) {
            for (int i = 0; i < 9; i++) {
                clientFilters.set(i, menu.getExtractorBE().getFilter(i));
            }
        }
    }

    public void updateFilters(List<ItemStack> filters) {
        for (int i = 0; i < Math.min(9, filters.size()); i++) {
            clientFilters.set(i, filters.get(i));
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        graphics.blit(GUI_TEXTURE, x, y, 0, 0, this.imageWidth, this.imageHeight);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Render ghost items on the left 3x3 filter slots
        int left = this.leftPos;
        int top = this.topPos;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int index = col + row * 3;
                ItemStack filterItem = clientFilters.get(index);
                int slotX = left + 26 + col * 18;
                int slotY = top + 18 + row * 18;

                if (!filterItem.isEmpty()) {
                    // Draw semi-transparent / ghost item
                    graphics.renderFakeItem(filterItem, slotX, slotY);
                    // Draw a subtle translucent overlay to emphasize it's a filter
                    RenderSystem.disableDepthTest();
                    graphics.fillGradient(slotX, slotY, slotX + 16, slotY + 16, 0x44AA00FF, 0x44AA00FF);
                    RenderSystem.enableDepthTest();
                }

                // If hovered, render slot highlight or tooltip
                if (isHovering(26 + col * 18, 18 + row * 18, 16, 16, mouseX, mouseY)) {
                    if (!filterItem.isEmpty()) {
                        graphics.renderTooltip(this.font, filterItem, mouseX, mouseY);
                    } else {
                        graphics.renderTooltip(this.font, Component.literal("Filter Slot " + (index + 1)), mouseX, mouseY);
                    }
                }
            }
        }

        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        if (slot != null && slot.index < 9 && slot.container != this.minecraft.player.getInventory()) {
            // It is one of our 9 filter slots!
            int filterIndex = slot.index;
            ItemStack carried = this.menu.getCarried();

            if (mouseButton == 1 && carried.isEmpty()) {
                // Right-click with empty cursor clears filter
                clientFilters.set(filterIndex, ItemStack.EMPTY);
                PacketDistributor.sendToServer(new ExtractorSetFilterPKT(this.menu.getPos(), filterIndex, ItemStack.EMPTY));
                return;
            }

            if (!carried.isEmpty()) {
                // Left or right click with item on cursor sets filter to carried item WITHOUT consuming carried item!
                ItemStack single = carried.copy();
                single.setCount(1);
                clientFilters.set(filterIndex, single);
                PacketDistributor.sendToServer(new ExtractorSetFilterPKT(this.menu.getPos(), filterIndex, single));
                return;
            }

            // Clicked with empty hand: open the terminal/creative filter picker screen!
            PacketDistributor.sendToServer(new ExtractorOpenFilterPKT(this.menu.getPos(), filterIndex));
            return;
        }

        super.slotClicked(slot, slotId, mouseButton, clickType);
    }
}
