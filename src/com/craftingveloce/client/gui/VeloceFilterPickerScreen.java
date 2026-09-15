package com.craftingveloce.client.gui;

import com.craftingveloce.network.ExtractorSetFilterPKT;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

public class VeloceFilterPickerScreen extends CreativeModeInventoryScreen {

    private final BlockPos extractorPos;
    private final int filterIndex;

    @Nullable
    private GameType modeBeforeOpen;

    private static Field slotWrapperTargetField;

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

    public VeloceFilterPickerScreen(LocalPlayer player, FeatureFlagSet enabledFeatures, boolean displayOperatorCreativeTab, BlockPos extractorPos, int filterIndex) {
        super(player, enabledFeatures, displayOperatorCreativeTab);
        this.extractorPos = extractorPos;
        this.filterIndex = filterIndex;
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

    private void suppressHotbarSlots() {
        if (this.menu == null || this.minecraft == null || this.minecraft.player == null) return;
        for (int i = 0; i < this.menu.slots.size(); i++) {
            Slot s = this.menu.slots.get(i);
            if (isPlayerInventorySlot(s)) {
                // Replace with a dummy inactive slot offscreen
                final Slot orig = s;
                this.menu.slots.set(i, new Slot(orig.container, orig.getContainerSlot(), -10000, -10000) {
                    @Override
                    public boolean isActive() {
                        return false;
                    }
                    @Override
                    public boolean isHighlightable() {
                        return false;
                    }
                });
            }
        }
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

    @Override
    public void containerTick() {
    }

    @Override
    protected void renderSlot(GuiGraphics graphics, Slot slot) {
        if (isPlayerInventorySlot(slot)) {
            return;
        }
        super.renderSlot(graphics, slot);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Blank out the bottom hotbar area (x: 8 to 170, y: 111 to 130) with solid GUI gray
        int x1 = this.leftPos + 8;
        int y1 = this.topPos + 111;
        int x2 = this.leftPos + 170;
        int y2 = this.topPos + 130;
        graphics.fill(x1, y1, x2, y2, 0xFFC6C6C6);
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }

        // If clicking a player slot or outside, ignore
        if (slot == null || isPlayerInventorySlot(slot)) {
            return;
        }

        // Trash / sell slot
        if (slot.x == 173 && slot.y == 112) {
            return;
        }

        ItemStack item = slot.getItem();
        if (!item.isEmpty()) {
            // Selected this item as filter!
            ItemStack filterItem = item.copy();
            filterItem.setCount(1);
            PacketDistributor.sendToServer(new ExtractorSetFilterPKT(extractorPos, filterIndex, filterItem));

            // Close filter screen and return to extractor screen
            this.onClose();
        }
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
