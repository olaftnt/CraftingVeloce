package com.craftingveloce.client.gui;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.inventory.VeloceExtractorMenu;
import com.craftingveloce.network.OpenFilterPKT;
import com.craftingveloce.network.SetFilterPKT;
import com.craftingveloce.network.ExtractorToggleCraftingPKT;
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

    /** Mirrored copy of the auto-crafting flags from the server (per filter slot). */
    private final java.util.List<Boolean> clientAllowCrafting =
            new java.util.ArrayList<>(java.util.Collections.nCopies(9, Boolean.TRUE));

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

    public void updateFilters(List<ItemStack> filters, List<Boolean> allowCrafting) {
        for (int i = 0; i < Math.min(9, filters.size()); i++) {
            clientFilters.set(i, filters.get(i));
        }
        if (allowCrafting != null) {
            for (int i = 0; i < Math.min(9, allowCrafting.size()); i++) {
                clientAllowCrafting.set(i, allowCrafting.get(i));
            }
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
                    graphics.renderFakeItem(filterItem, slotX, slotY);

                    // The colour says whether the extractor may order a
                    // craft for this item: green = yes, red = only what is
                    // already in the network. Without it, a right click
                    // toggled something that was not visible.
                    boolean canCraft = clientAllowCrafting.get(index);
                    int overlay = canCraft ? 0x5500AA00 : 0x55AA0000;
                    RenderSystem.disableDepthTest();
                    graphics.fillGradient(slotX, slotY, slotX + 16, slotY + 16, overlay, overlay);
                    RenderSystem.enableDepthTest();
                }

                // If hovered, render slot highlight or tooltip
                if (isHovering(26 + col * 18, 18 + row * 18, 16, 16, mouseX, mouseY)) {
                    if (!filterItem.isEmpty()) {
                        boolean canCraft = clientAllowCrafting.get(index);
                        java.util.List<Component> lines = new java.util.ArrayList<>();
                        lines.add(filterItem.getHoverName());
                        // The state alone: ON or OFF. The player did not want the
                        // "(network stock only)" note - the icon colour already
                        // says that with OFF the extractor takes only what is in
                        // the network.
                        lines.add(Component.translatable(canCraft
                                        ? "gui.craftingveloce.extractor.craftingOn"
                                        : "gui.craftingveloce.extractor.craftingOff")
                                .withStyle(canCraft
                                        ? net.minecraft.ChatFormatting.GREEN
                                        : net.minecraft.ChatFormatting.RED));
                        // The instructions in TWO lines: left and right click do
                        // two different things, and glued into one sentence they
                        // read like a wall of text.
                        lines.add(Component.translatable("gui.craftingveloce.extractor.leftClick")
                                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
                        lines.add(Component.translatable("gui.craftingveloce.extractor.rightClick")
                                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
                        graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
                    } else {
                        // An empty filter is just "Empty filter" - without
                        // instructions.
                        graphics.renderComponentTooltip(this.font, java.util.List.of(
                                Component.translatable("gui.craftingveloce.extractor.filterEmpty")),
                                mouseX, mouseY);
                    }
                }
            }
        }

        this.renderTooltip(graphics, mouseX, mouseY);
    }

    /**
     * Handles clicks in the 9 filter slots.
     *
     * <p>Established behaviour:
     * <ul>
     *   <li><b>Right click on an OCCUPIED slot</b> - toggles auto-crafting for
     *       this item (whether the extractor should order a craft, or take only
     *       what is already in the network). The item selection itself stays
     *       unchanged.</li>
     *   <li><b>Left click on an OCCUPIED slot</b> - clears the filter.</li>
     *   <li><b>Left click on an EMPTY slot</b> - opens the item picker.</li>
     *   <li><b>Click with an item on the cursor</b> - immediately sets that item
     *       as the filter (without consuming it from the cursor).</li>
     * </ul>
     */
    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        if (slot != null && slot.index < 9 && slot.container != this.minecraft.player.getInventory()) {
            int filterIndex = slot.index;
            ItemStack carried = this.menu.getCarried();
            boolean occupied = !clientFilters.get(filterIndex).isEmpty();

            // The cursor with an item always has priority: move the filter to
            // this item.
            if (!carried.isEmpty()) {
                ItemStack single = carried.copy();
                single.setCount(1);
                clientFilters.set(filterIndex, single);
                PacketDistributor.sendToServer(
                        new SetFilterPKT(this.menu.getPos(), filterIndex, single));
                return;
            }

            if (occupied) {
                if (mouseButton == 1) {
                    // Right click on an occupied slot = toggle auto-crafting.
                    clientAllowCrafting.set(filterIndex, !clientAllowCrafting.get(filterIndex));
                    PacketDistributor.sendToServer(
                            new ExtractorToggleCraftingPKT(this.menu.getPos(), filterIndex));
                } else {
                    // Left click on an occupied slot = clear the filter.
                    clientFilters.set(filterIndex, ItemStack.EMPTY);
                    PacketDistributor.sendToServer(
                            new SetFilterPKT(this.menu.getPos(), filterIndex, ItemStack.EMPTY));
                }
                return;
            }

            // Empty slot + left click = item picker.
            if (mouseButton == 0) {
                PacketDistributor.sendToServer(
                        new OpenFilterPKT(this.menu.getPos(), filterIndex));
            }
            return;
        }

        super.slotClicked(slot, slotId, mouseButton, clickType);
    }
}
