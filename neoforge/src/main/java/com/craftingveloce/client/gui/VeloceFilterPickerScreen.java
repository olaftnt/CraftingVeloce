package com.craftingveloce.client.gui;

import com.craftingveloce.network.SetFilterPKT;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

public class VeloceFilterPickerScreen extends VeloceCreativeScreen {

    private final BlockPos extractorPos;
    private final int filterIndex;

    /**
     * Whether we are picking a filter for the FUEL FURNACE (then only fuel counts).
     *
     * <p>We detect this from the host block, not from a separate packet: the picker
     * is a SINGLE one for the extractor, the furnace and the sensor, so adding a
     * "block kind" to it would mean three places to keep in sync with every new
     * block that has a filter.
     */
    private final boolean fuelOnly;

    private Map<Item, Long> networkCounts = new HashMap<>();

    public void updateNetworkCounts(Map<Item, Long> counts) {
        this.networkCounts = new HashMap<>(counts);
    }

    public VeloceFilterPickerScreen(LocalPlayer player, FeatureFlagSet enabledFeatures, boolean displayOperatorCreativeTab, BlockPos extractorPos, int filterIndex) {
        super(player, enabledFeatures, displayOperatorCreativeTab);
        this.extractorPos = extractorPos;
        this.filterIndex = filterIndex;
        this.fuelOnly = isFuelOnlyHost(player, extractorPos);
    }

    /** Whether the block at this position is a fuel furnace (filter = fuel filter). */
    private static boolean isFuelOnlyHost(LocalPlayer player, BlockPos pos) {
        if (player == null || player.level() == null) {
            return false;
        }
        return player.level().getBlockState(pos).getBlock()
                instanceof com.craftingveloce.block.VeloceVelocityFurnaceBlock;
    }

    /** Whether this stack is acceptable as a filter (in fuel mode: only fuel). */
    private boolean acceptable(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        // fuelOnly: fuel. Shared rule with the furnace screen - see
        // VeloceVelocityFurnaceBlockEntity.isUnusableFuelFilter (with the canary).
        return !fuelOnly
                || !com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity
                        .isUnusableFuelFilter(stack);
    }

    @Override
    protected void init() {
        // Note: we do NOT hide the player slots a second time here.
        //
        // There used to be a custom suppressHotbarSlots() with an anonymous Slot
        // that did exactly the same thing as suppressPlayerSlots() from the base
        // class (and did it after it), and on top of that it no longer recognized
        // an already hidden slot - so on every init() it wrapped the slot in a new
        // wrapper.
        //
        // The SECOND copy of the local mode switch (survival -> creative for the
        // duration of the GUI) disappeared the same way: the base class does the
        // same thing, and its remembered mode was overwritten by that copy AFTER
        // the swap, so "mode before opening" meant "mode already swapped". One
        // place = one source of truth.
        super.init();
    }


    @Override
    public void containerTick() {
        // MUST call super. The base class (VeloceCreativeScreen) maintains the
        // item list filter and the hiding of operator tabs here. This method used
        // to be empty, so after switching tabs in this window the list was no
        // longer filtered - the picker showed items it should not have (and lost
        // consistency with the rest of our GUIs).
        super.containerTick();
    }

    @Override
    protected void renderSlot(GuiGraphics graphics, Slot slot) {
        if (isPlayerInventorySlot(slot)) {
            return;
        }
        super.renderSlot(graphics, slot);
        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();

            // In "fuel only" mode, items that cannot be smelted are marked in RED -
            // the player immediately sees what they cannot pick (clicking such an
            // item does nothing, see slotClicked).
            if (fuelOnly && !acceptable(stack)) {
                RenderSystem.disableDepthTest();
                graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0x77AA0000);
                RenderSystem.enableDepthTest();
            }

            // Draw count overlay if this item is available in the network
            long count = networkCounts.getOrDefault(stack.getItem(), 0L);
            if (count > 0) {
                drawCountOverlay(graphics, this.font, count, slot.x, slot.y);
            }
        }
    }

    private void drawCountOverlay(GuiGraphics graphics, Font font, long count, int x, int y) {
        float scaleFactor = 0.6f;
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();
        String text = VeloceTerminalScreen.formatCount(count);
        graphics.pose().pushPose();
        graphics.pose().scale(scaleFactor, scaleFactor, scaleFactor);
        graphics.pose().translate(0, 0, 450);
        float inverseScale = 1.0f / scaleFactor;
        int textX = (int) (((float) x + 16.0f - font.width(text) * scaleFactor) * inverseScale);
        int textY = (int) (((float) y + 16.0f - 7.0f * scaleFactor) * inverseScale);
        // White = how much is in stock (consistent with the terminal).
        graphics.drawString(font, text, textX, textY, 0xFFFFFF, true);
        graphics.pose().popPose();
        RenderSystem.enableDepthTest();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // The hotbar strip is unused - we paint it over with the shared helper
        // from the base class, so the geometry does not drift from the rest of the GUI.
        drawHotbarCover(graphics, 0xFFC6C6C6);
    }

    /**
     * The filter picker does NOT remember the tab.
     *
     * <p>It must always open on the first tab (top left corner) - the player is
     * looking for a specific item there, not returning to the place from the
     * previous opening.
     */
    @Override
    protected boolean rememberTab() {
        return false;
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

        // Trash slot - we use the shared helper from the base class instead of
        // repeating the same coordinates here (they would drift when the layout changes).
        if (isTrashSlot(slot)) {
            return;
        }

        ItemStack item = slot.getItem();
        if (!item.isEmpty()) {
            // Not acceptable as a filter in this window (in the fuel furnace: this
            // is not fuel). We do NOTHING - the player stays in the picker, the
            // screen does not change, and the filter stays as it was.
            if (!acceptable(item)) {
                return;
            }
            // Selected this item as filter!
            ItemStack filterItem = item.copy();
            filterItem.setCount(1);
            PacketDistributor.sendToServer(new SetFilterPKT(extractorPos, filterIndex, filterItem));

            // GO BACK TO THE EXTRACTOR, not to the game.
            //
            // There used to be this.onClose(), which closed the screen entirely -
            // the player picked an item and landed in the world instead of
            // returning to the block.
            com.craftingveloce.client.ClientTerminalHelper.reopenFilterHostScreen(extractorPos);
        }
    }

    /**
     * Esc (and the inventory key) GOES BACK to the block GUI, not to the world.
     *
     * <p>The picker is part of the block screen: a player who only wanted to undo
     * the choice landed in the world with an unfinished filter - and had to walk
     * back to the block on their own. "Back" must mean "go back there".
     *
     * <p>The same path as after picking an item, so the player menu returns to its
     * place together with the screen (see ClientTerminalHelper.handBackMenu).
     *
     * <p>When there is nothing to go back to (the host was not remembered, e.g.
     * after a resource reload), the ORDINARY close must work - otherwise Esc
     * would do nothing and the player would be trapped in the picker.
     */
    @Override
    public void onClose() {
        if (!com.craftingveloce.client.ClientTerminalHelper.reopenFilterHostScreen(extractorPos)) {
            super.onClose();
        }
    }
}
