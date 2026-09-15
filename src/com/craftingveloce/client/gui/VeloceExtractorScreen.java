package com.craftingveloce.client.gui;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.inventory.VeloceExtractorMenu;
import com.craftingveloce.network.ExtractorOpenFilterPKT;
import com.craftingveloce.network.ExtractorSetFilterPKT;
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

    /** Lustrzane odbicie flag auto-craftingu z serwera (per slot filtra). */
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

                    // Kolor mowi, czy extractor moze dla tego itemu zamawiac
                    // craft: zielony = tak, czerwony = tylko to, co jest w sieci.
                    // Bez tego prawy klik przelaczal cos, czego nie bylo widac.
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
                        lines.add(Component.translatable(canCraft
                                        ? "gui.craftingveloce.extractor.craftingOn"
                                        : "gui.craftingveloce.extractor.craftingOff")
                                .withStyle(canCraft
                                        ? net.minecraft.ChatFormatting.GREEN
                                        : net.minecraft.ChatFormatting.RED));
                        lines.add(Component.translatable("gui.craftingveloce.extractor.filterHelp")
                                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
                        graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
                    } else {
                        graphics.renderComponentTooltip(this.font, java.util.List.of(
                                Component.translatable("gui.craftingveloce.extractor.emptySlot"),
                                Component.translatable("gui.craftingveloce.extractor.filterHelp")
                                        .withStyle(net.minecraft.ChatFormatting.DARK_GRAY)),
                                mouseX, mouseY);
                    }
                }
            }
        }

        this.renderTooltip(graphics, mouseX, mouseY);
    }

    /**
     * Obsluga klikniec w 9 slotow filtra.
     *
     * <p>Ustalone zachowanie:
     * <ul>
     *   <li><b>Prawy klik na ZAJETYM slocie</b> - przelacza auto-crafting dla
     *       tego itemu (czy extractor ma zamawiac craft, czy brac tylko to, co
     *       juz jest w sieci). Sam wybor itemu zostaje bez zmian.</li>
     *   <li><b>Lewy klik na ZAJETYM slocie</b> - kasuje filtr.</li>
     *   <li><b>Lewy klik na PUSTYM slocie</b> - otwiera wybor itemu.</li>
     *   <li><b>Klik z itemem na kursorze</b> - od razu ustawia ten item jako
     *       filtr (bez zuzywania go z kursora).</li>
     * </ul>
     */
    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        if (slot != null && slot.index < 9 && slot.container != this.minecraft.player.getInventory()) {
            int filterIndex = slot.index;
            ItemStack carried = this.menu.getCarried();
            boolean occupied = !clientFilters.get(filterIndex).isEmpty();

            // Kursor z itemem ma zawsze priorytet: przestaw filtr na ten item.
            if (!carried.isEmpty()) {
                ItemStack single = carried.copy();
                single.setCount(1);
                clientFilters.set(filterIndex, single);
                PacketDistributor.sendToServer(
                        new ExtractorSetFilterPKT(this.menu.getPos(), filterIndex, single));
                return;
            }

            if (occupied) {
                if (mouseButton == 1) {
                    // Prawy klik na zajetym = przelacz auto-crafting.
                    clientAllowCrafting.set(filterIndex, !clientAllowCrafting.get(filterIndex));
                    PacketDistributor.sendToServer(
                            new ExtractorToggleCraftingPKT(this.menu.getPos(), filterIndex));
                } else {
                    // Lewy klik na zajetym = skasuj filtr.
                    clientFilters.set(filterIndex, ItemStack.EMPTY);
                    PacketDistributor.sendToServer(
                            new ExtractorSetFilterPKT(this.menu.getPos(), filterIndex, ItemStack.EMPTY));
                }
                return;
            }

            // Pusty slot + lewy klik = wybor itemu.
            if (mouseButton == 0) {
                PacketDistributor.sendToServer(
                        new ExtractorOpenFilterPKT(this.menu.getPos(), filterIndex));
            }
            return;
        }

        super.slotClicked(slot, slotId, mouseButton, clickType);
    }
}
