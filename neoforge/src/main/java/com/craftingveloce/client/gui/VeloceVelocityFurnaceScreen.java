package com.craftingveloce.client.gui;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.inventory.VeloceVelocityFurnaceMenu;
import com.craftingveloce.network.OpenFilterPKT;
import com.craftingveloce.network.SetFilterPKT;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI of the Velocity Furnace.
 *
 * <p><b>Layout (as agreed):</b> a flame like in a vanilla furnace + six filter
 * slots (like in the extractor) for the accepted fuels, plus a real fuel slot
 * holding what the furnace is burning right now.
 *
 * <p><b>The flame shows the HEAT BUFFER</b>, not the "smelting progress". This
 * furnace does not smelt one item at a time - it burns continuously, and every
 * smelting operation for the crafter eats a portion of that buffer right away.
 * The flame height is therefore {@code burnTicksRemaining / burnTicksTotal},
 * which is exactly how much heat is left to distribute.
 *
 * <p>The filters are GHOST slots: they cannot be filled by dragging. They are
 * picked from a list of items (left click on an empty slot), and they are kept
 * by the block entity.
 */
public class VeloceVelocityFurnaceScreen
        extends AbstractContainerScreen<VeloceVelocityFurnaceMenu> {

    private static final ResourceLocation GUI_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            CraftingVeloceMod.MODID, "textures/gui/velocity_furnace.png");

    /** Texture of the vanilla furnace - we take the EXTINGUISHED flame outline from it. */
    private static final ResourceLocation VANILLA_FURNACE = ResourceLocation
            .withDefaultNamespace("textures/gui/container/furnace.png");

    /**
     * The LIT part of the flame - a vanilla sprite (a separate file since 1.20.2).
     *
     * <p><b>The BUG this fixes.</b> Previously I took the flame from the old
     * panel texture at (176, 0). In 1.21 vanilla moved that sprite to
     * {@code textures/gui/sprites/container/furnace/lit_progress.png} and there
     * is NOTHING at (176, 0) in the panel any more - so EMPTINESS was drawn,
     * and the only trace of the flame was the text in the tooltip. Now we take
     * the same route as the vanilla furnace: the sprite
     * {@code minecraft:container/furnace/lit_progress}.
     */
    private static final ResourceLocation LIT_PROGRESS_SPRITE = ResourceLocation
            .withDefaultNamespace("container/furnace/lit_progress");

    /** Extinguished flame outline in the vanilla furnace texture (same as vanilla). */
    private static final int EMPTY_FLAME_U = 56;
    private static final int EMPTY_FLAME_V = 36;

    /** Flame: 14x14 - exactly like in the vanilla furnace. */
    private static final int FLAME_W = 14;
    private static final int FLAME_H = 14;

    private final List<ItemStack> clientFilters =
            new ArrayList<>(java.util.Collections.nCopies(
                    VeloceVelocityFurnaceMenu.FILTER_SLOTS, ItemStack.EMPTY));

    /** Heat buffer from the last update - the flame must not flicker. */
    private long burnRemaining;
    private long burnTotal;

    public VeloceVelocityFurnaceScreen(VeloceVelocityFurnaceMenu menu,
                                       Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        // Dimensions and labels EXACTLY like in the extractor - both screens are
        // supposed to look like one family, not like two different mods.
        this.imageWidth = 212;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
        this.inventoryLabelX = 26;
        this.titleLabelX = 26;
        for (int i = 0; i < clientFilters.size(); i++) {
            clientFilters.set(i, menu.getFilter(i));
        }
        if (menu.getFurnace() != null) {
            this.burnRemaining = menu.getFurnace().getBurnTicksRemaining();
            this.burnTotal = menu.getFurnace().getBurnTicksTotal();
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(GUI_TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
        renderFlame(graphics);
        renderFilterIcons(graphics);
    }

    /**
     * The flame: first the extinguished outline, then the lit part from the BOTTOM.
     *
     * <p>This is exactly how the vanilla furnace draws it - which is why the
     * "from the bottom" path matters: the flame has to go out from the top,
     * because that is what fire looks like.
     */
    private void renderFlame(GuiGraphics graphics) {
        int x = this.leftPos + FLAME_X;
        int y = this.topPos + FLAME_Y;

        // 1. Extinguished outline - from the vanilla furnace texture, exactly the
        //    fragment over which vanilla draws the lit part.
        graphics.blit(VANILLA_FURNACE, x, y, EMPTY_FLAME_U, EMPTY_FLAME_V,
                FLAME_W, FLAME_H);

        // 2. Lit part - the vanilla sprite, clipped FROM THE TOP.
        //
        // The formula is copied from AbstractFurnaceScreen:
        //     lit = floor(progress * 13) + 1
        //     blitSprite(sprite, 14, 14, 0, 14 - lit, x, y + 14 - lit, 14, lit)
        // That is why the flame falls from the top instead of growing from the
        // bottom - that is how fire goes out.
        if (burnTotal > 0 && burnRemaining > 0) {
            int lit = net.minecraft.util.Mth.floor(
                    Math.min(1.0f, (float) burnRemaining / (float) burnTotal) * 13.0F) + 1;
            lit = net.minecraft.util.Mth.clamp(lit, 1, FLAME_H);
            graphics.blitSprite(LIT_PROGRESS_SPRITE, FLAME_W, FLAME_H,
                    0, FLAME_H - lit, x, y + FLAME_H - lit, FLAME_W, lit);
        }
    }

    /** Filter icons - we draw them ourselves, because the slots are ghosts. */
    private void renderFilterIcons(GuiGraphics graphics) {
        for (int i = 0; i < clientFilters.size(); i++) {
            ItemStack filter = clientFilters.get(i);
            if (filter.isEmpty()) {
                continue;
            }
            int sx = this.leftPos + filterX(i);
            int sy = this.topPos + filterY(i);
            graphics.renderFakeItem(filter, sx, sy);
            // NOTE: the purple bars above and below the icon are gone now.
            //
            // The BUG this fixes: I drew them from sx-1 to sx+17, that is one
            // pixel BEYOND the slot - they overlapped the neighbouring cell and
            // looked like an accidental purple underline (exactly what the user
            // reported). The selected item itself is visible enough.
        }
    }

    // Field positions - ONE source, used both for drawing and for clicking.
    private static final int FILTER_X = 63;
    private static final int FILTER_Y = 18;
    // Flame ABOVE the fuel slot - layout: filters 3x2 | (flame / fuel).
    // The position must agree with the menu and with gen_furnace_gui.py (build.py checks it).
    private static final int FLAME_X = 135;
    private static final int FLAME_Y = 19;

    private static int filterX(int index) {
        return FILTER_X + (index % 3) * 18;
    }

    private static int filterY(int index) {
        return FILTER_Y + (index / 3) * 18;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderHeatTooltip(graphics, mouseX, mouseY);
        renderFilterTooltip(graphics, mouseX, mouseY);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    /**
     * On the flame - ONLY how many burn ticks are left.
     *
     * <p>There used to be a description here with the number of smeltings and a
     * hint; the user wanted exactly one number. The flame is a vanilla icon, so
     * the tooltip only has to add what cannot be seen from the icon.
     */
    private void renderHeatTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isHovering(FLAME_X, FLAME_Y, FLAME_W, FLAME_H, mouseX, mouseY)) {
            return;
        }
        // Just the ratio: how many ticks are LEFT out of what that item added.
        // Without any "left to burn" - that is what the user asked for.
        graphics.renderTooltip(this.font,
                Component.translatable("gui.craftingveloce.furnace.ticks",
                        burnRemaining, burnTotal),
                mouseX, mouseY);
    }

    private void renderFilterTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        for (int i = 0; i < clientFilters.size(); i++) {
            if (!isHovering(filterX(i), filterY(i), 16, 16, mouseX, mouseY)) {
                continue;
            }
            ItemStack filter = clientFilters.get(i);
            List<Component> lines = new ArrayList<>();
            if (filter.isEmpty()) {
                // No slot number ("Slot 1/2/3/4"): the player is supposed to know
                // that the slot is EMPTY and that it can be clicked, not to count
                // which one it is.
                lines.add(Component.translatable("gui.craftingveloce.furnace.filterEmpty"));
            } else {
                lines.add(filter.getHoverName());
                lines.add(Component.translatable("gui.craftingveloce.furnace.filterClear")
                        .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            }
            lines.add(Component.translatable("gui.craftingveloce.furnace.filterHint")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
            return;
        }
    }

    /**
     * Clicks on the filters.
     *
     * <p>The behaviour is the same as in the extractor, because it is the same
     * choice:
     * <ul>
     *   <li>cursor with an item -> set that item as the filter (without taking it),</li>
     *   <li>left click on a filled one -> clear the filter,</li>
     *   <li>left click on an empty one -> open the item picker.</li>
     * </ul>
     * A right click is of no use here - the furnace has no per-filter
     * auto-crafting toggle, so we do not pretend it does.
     */
    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        int filterIndex = filterIndexFor(slot);
        if (filterIndex >= 0) {
            ItemStack carried = this.menu.getCarried();
            if (!carried.isEmpty()) {
                // FROM THE CURSOR: a filter only makes sense for fuel. An item
                // that cannot be smelted is NOT accepted - the action simply does
                // not happen (filter unchanged, item stays on the cursor).
                if (com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity
                        .isUnusableFuelFilter(carried)) {
                    return;
                }
                setFilter(filterIndex, carried);
                return;
            }
            if (!clientFilters.get(filterIndex).isEmpty()) {
                setFilter(filterIndex, ItemStack.EMPTY);
                return;
            }
            if (mouseButton == 0) {
                PacketDistributor.sendToServer(
                        new OpenFilterPKT(this.menu.getPos(), filterIndex));
            }
            return;
        }
        super.slotClicked(slot, slotId, mouseButton, clickType);
    }

    /** Filter number for a ghost slot, or -1 when it is not a filter slot. */
    private int filterIndexFor(Slot slot) {
        if (slot == null || slot.container == this.minecraft.player.getInventory()) {
            return -1;
        }
        return slot.index >= 0 && slot.index < VeloceVelocityFurnaceMenu.FILTER_SLOTS
                ? slot.index
                : -1;
    }

    private void setFilter(int index, ItemStack stack) {
        ItemStack single = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        clientFilters.set(index, single);
        PacketDistributor.sendToServer(
                new SetFilterPKT(this.menu.getPos(), index, single));
    }

    /**
     * Refreshes the flame and the filters from the block entity.
     *
     * <p><b>Why without a separate packet.</b> The menu holds the client's block
     * entity, and the furnace sends its state on an ongoing basis in the block
     * update packet - so the screen has the full data AT HAND. A separate
     * "update the flame" packet would be a second, parallel path for the same
     * numbers, that is exactly the thing that drifts apart.
     */
    @Override
    public void containerTick() {
        super.containerTick();
        var be = this.menu.getFurnace();
        if (be == null) {
            return;
        }
        this.burnRemaining = be.getBurnTicksRemaining();
        this.burnTotal = be.getBurnTicksTotal();
        for (int i = 0; i < clientFilters.size(); i++) {
            clientFilters.set(i, be.getFuelFilter(i));
        }
    }
}
