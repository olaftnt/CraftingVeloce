package com.craftingveloce.client.gui;

import com.craftingveloce.inventory.VeloceCrafterStorageMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Ekran magazynu auto-craftera: siatka ze scrollbarem.
 *
 * <p>Zwykly ekran kontenera (jak skrzynia), a nie creative inventory - dlatego
 * nie ma tu slotow armor, offhand ani craftingu 2x2. Nic nie "wskakuje" na
 * slot glowy, bo taki slot po prostu nie istnieje.
 *
 * <p>Scroll dziala kolkiem myszy i przeciaganiem suwaka po prawej.
 */
public class VeloceCrafterStorageScreen extends AbstractContainerScreen<VeloceCrafterStorageMenu> {

    private static final int SLOT_SIZE = 18;
    private static final int GRID_LEFT = 8;
    private static final int GRID_TOP = 18;

    private static final int SCROLLBAR_X = 8 + 9 * SLOT_SIZE + 4;
    private static final int SCROLLBAR_WIDTH = 12;
    private static final int SCROLLBAR_TOP = GRID_TOP;
    private static final int SCROLLBAR_HEIGHT = VeloceCrafterStorageMenu.VISIBLE_ROWS * SLOT_SIZE;

    private boolean draggingScrollbar = false;

    public VeloceCrafterStorageScreen(VeloceCrafterStorageMenu menu, Inventory inventory,
                                      Component title) {
        super(menu, inventory, title);
        this.imageWidth = 8 + 9 * SLOT_SIZE + SCROLLBAR_WIDTH + 8;
        this.imageHeight = GRID_TOP + VeloceCrafterStorageMenu.VISIBLE_ROWS * SLOT_SIZE
                + 14 + 3 * SLOT_SIZE + 6 + SLOT_SIZE + 8;
        this.inventoryLabelY = GRID_TOP + VeloceCrafterStorageMenu.VISIBLE_ROWS * SLOT_SIZE + 8;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        // Tlo okna.
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, 0xFFC6C6C6);
        // Ramka.
        graphics.fill(x, y, x + this.imageWidth, y + 1, 0xFF000000);
        graphics.fill(x, y + this.imageHeight - 1, x + this.imageWidth, y + this.imageHeight, 0xFF000000);
        graphics.fill(x, y, x + 1, y + this.imageHeight, 0xFF000000);
        graphics.fill(x + this.imageWidth - 1, y, x + this.imageWidth, y + this.imageHeight, 0xFF000000);

        // Tla slotow siatki.
        for (int row = 0; row < VeloceCrafterStorageMenu.VISIBLE_ROWS; row++) {
            for (int col = 0; col < 9; col++) {
                int sx = x + GRID_LEFT + col * SLOT_SIZE;
                int sy = y + GRID_TOP + row * SLOT_SIZE;
                graphics.fill(sx, sy, sx + 16, sy + 16, 0xFF8B8B8B);
                graphics.fill(sx, sy, sx + 16, sy + 1, 0xFF373737);
                graphics.fill(sx, sy, sx + 1, sy + 16, 0xFF373737);
                graphics.fill(sx + 15, sy, sx + 16, sy + 16, 0xFFFFFFFF);
                graphics.fill(sx, sy + 15, sx + 16, sy + 16, 0xFFFFFFFF);
            }
        }

        renderScrollbar(graphics);
    }

    private void renderScrollbar(GuiGraphics graphics) {
        int max = this.menu.maxScrollRow();
        int x = this.leftPos + SCROLLBAR_X;
        int y = this.topPos + SCROLLBAR_TOP;

        // Tor.
        graphics.fill(x, y, x + SCROLLBAR_WIDTH, y + SCROLLBAR_HEIGHT, 0xFF555555);
        graphics.fill(x, y, x + 1, y + SCROLLBAR_HEIGHT, 0xFF373737);

        if (max <= 0) {
            // Wszystko widoczne - suwak na calej wysokosci.
            graphics.fill(x + 1, y + 1, x + SCROLLBAR_WIDTH - 1, y + SCROLLBAR_HEIGHT - 1,
                    0xFF8B8B8B);
            return;
        }

        int thumbHeight = Math.max(16, SCROLLBAR_HEIGHT * VeloceCrafterStorageMenu.VISIBLE_ROWS
                / (VeloceCrafterStorageMenu.VISIBLE_ROWS + max));
        int travel = SCROLLBAR_HEIGHT - thumbHeight;
        int thumbY = y + (travel * this.menu.getScrollRow()) / max;

        graphics.fill(x + 1, thumbY, x + SCROLLBAR_WIDTH - 1, thumbY + thumbHeight, 0xFFC6C6C6);
        graphics.fill(x + 1, thumbY, x + SCROLLBAR_WIDTH - 1, thumbY + 1, 0xFFFFFFFF);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBg(graphics, partialTick, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            int delta = scrollY > 0 ? -1 : 1;
            this.menu.setScrollRow(this.menu.getScrollRow() + delta);
            // Odswiez zawartosc widocznych slotow.
            this.menu.slotsChanged(null);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = this.leftPos + SCROLLBAR_X;
        int y = this.topPos + SCROLLBAR_TOP;
        if (button == 0 && mouseX >= x && mouseX < x + SCROLLBAR_WIDTH
                && mouseY >= y && mouseY < y + SCROLLBAR_HEIGHT) {
            draggingScrollbar = true;
            updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        if (draggingScrollbar) {
            updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void updateScrollFromMouse(double mouseY) {
        int max = this.menu.maxScrollRow();
        if (max <= 0) {
            return;
        }
        double rel = (mouseY - (this.topPos + SCROLLBAR_TOP)) / (double) SCROLLBAR_HEIGHT;
        int row = (int) Math.round(rel * max);
        this.menu.setScrollRow(row);
        this.menu.slotsChanged(null);
    }
}
