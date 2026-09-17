package com.craftingveloce.client.gui;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceThresholdSensorBlockEntity;
import com.craftingveloce.inventory.VeloceThresholdSensorMenu;
import com.craftingveloce.network.OpenFilterPKT;
import com.craftingveloce.network.SensorConfigPKT;
import com.craftingveloce.network.SetFilterPKT;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Veloce Threshold Sensor GUI.
 *
 * <p><b>One row, centred:</b> the item slot, the number field, "+", "-"
 * and the mode button. All coordinates come from
 * {@link VeloceThresholdSensorMenu} - the screen has no copy of the layout of its own.
 *
 * <p><b>Zero text in the GUI.</b> The player did not want texts ("Inventory",
 * "In network", "Output: ON") or a square in the texture that served as space for
 * them. They are gone - the output state is visible from the diode on the block,
 * and the mode from the button icon.
 *
 * <p><b>The mode button is a redstone torch.</b> Lit = "at least this much"
 * (a direct signal), unlit = "below" (the inverse). A click toggles the mode, that is,
 * it inverts the signal - the standard redstone inversion.
 *
 * <p><b>The text field</b> accepts digits only. We save it on confirmation
 * (Enter) or when leaving the field - not on every key press, because otherwise
 * the "6" of "64" would be sent as threshold 6 and the sensor would briefly
 * run on the wrong value.
 */
public class VeloceThresholdSensorScreen
        extends AbstractContainerScreen<VeloceThresholdSensorMenu> {

    private static final ResourceLocation GUI_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            CraftingVeloceMod.MODID, "textures/gui/threshold_sensor.png");

    /** Mode icons: vanilla torch block textures (16x16). */
    private static final ResourceLocation TORCH_LIT =
            ResourceLocation.withDefaultNamespace("textures/block/redstone_torch.png");
    private static final ResourceLocation TORCH_OFF =
            ResourceLocation.withDefaultNamespace("textures/block/redstone_torch_off.png");

    private EditBox thresholdField;
    private ModeButton modeButton;

    /**
     * The mode selected in the GUI - what the player sees and what is about to be
     * sent to the server.
     *
     * <p>It MUST be separate from {@link #sentHighMode}. The previous version used
     * one field both for the "did it change" comparison and as the new value -
     * so the comparison was a tautology (see {@link #sendConfig}).
     */
    private boolean uiHighMode;

    /** What ACTUALLY went to the server - so we do not spam packets. */
    private long sentThreshold = Long.MIN_VALUE;
    private boolean sentHighMode;

    /** The client-side mirror of the filter (the block entity is the source of truth). */
    private ItemStack clientFilter = ItemStack.EMPTY;

    public VeloceThresholdSensorScreen(VeloceThresholdSensorMenu menu,
                                       Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = VeloceThresholdSensorMenu.PANEL_WIDTH;
        this.imageHeight = VeloceThresholdSensorMenu.PANEL_HEIGHT;
        this.titleLabelX = 8;
    }

    @Override
    protected void init() {
        super.init();
        long threshold = this.menu.getThreshold();
        this.uiHighMode = this.menu.getMode() == VeloceThresholdSensorBlockEntity.Mode.HIGH;
        this.sentThreshold = threshold;
        this.sentHighMode = this.uiHighMode;

        this.thresholdField = new EditBox(this.font,
                this.leftPos + VeloceThresholdSensorMenu.FIELD_X,
                this.topPos + VeloceThresholdSensorMenu.ROW_Y,
                VeloceThresholdSensorMenu.FIELD_W, VeloceThresholdSensorMenu.FIELD_H,
                Component.translatable("gui.craftingveloce.sensor.threshold"));
        this.thresholdField.setValue(Long.toString(threshold));
        this.thresholdField.setMaxLength(10);
        // Digits only - the field is numeric, so there is no point letting letters in.
        this.thresholdField.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        // Field hint: just "Amount". The player asked for the sentence
        // "Network count to compare against" to be removed - the label is enough.
        this.thresholdField.setTooltip(Tooltip.create(
                Component.translatable("gui.craftingveloce.sensor.threshold")));
        addRenderableWidget(this.thresholdField);

        addRenderableWidget(Button.builder(Component.literal("+"), b -> step(1))
                .bounds(this.leftPos + VeloceThresholdSensorMenu.STEP_PLUS_X,
                        this.topPos + VeloceThresholdSensorMenu.ROW_Y,
                        VeloceThresholdSensorMenu.BTN_W, VeloceThresholdSensorMenu.ROW_H)
                .tooltip(stepTip("gui.craftingveloce.sensor.step.plus"))
                .build());
        addRenderableWidget(Button.builder(Component.literal("-"), b -> step(-1))
                .bounds(this.leftPos + VeloceThresholdSensorMenu.STEP_MINUS_X,
                        this.topPos + VeloceThresholdSensorMenu.ROW_Y,
                        VeloceThresholdSensorMenu.BTN_W, VeloceThresholdSensorMenu.ROW_H)
                .tooltip(stepTip("gui.craftingveloce.sensor.step.minus"))
                .build());

        // The mode button at the end of the row - an icon instead of a label.
        this.modeButton = addRenderableWidget(new ModeButton(
                this.leftPos + VeloceThresholdSensorMenu.MODE_X,
                this.topPos + VeloceThresholdSensorMenu.ROW_Y));
    }

    /**
     * Mode button: a torch instead of the label "When below".
     *
     * <p>The icon colour does NOT change on a "momentary" click - the torch shows
     * the MODE, not the momentary output state. Otherwise the player would not know
     * which mode is set when the condition happens not to be met.
     */
    private final class ModeButton extends Button {

        ModeButton(int x, int y) {
            super(x, y, VeloceThresholdSensorMenu.BTN_W, VeloceThresholdSensorMenu.ROW_H,
                    Component.empty(), b -> toggleMode(), Button.DEFAULT_NARRATION);
            setTooltip(modeTooltip());
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            // A 16x16 icon centred in a 20x20 button (2 px on each side).
            graphics.blit(torchTexture(), getX() + 2, getY() + 2, 0f, 0f, 16, 16, 16, 16);
        }
    }

    private ResourceLocation torchTexture() {
        return this.uiHighMode ? TORCH_LIT : TORCH_OFF;
    }

    private Tooltip modeTooltip() {
        return Tooltip.create(Component.translatable(this.uiHighMode
                        ? "gui.craftingveloce.sensor.mode.high"
                        : "gui.craftingveloce.sensor.mode.low")
                .append(Component.literal("\n"))
                .append(Component.translatable("gui.craftingveloce.sensor.mode.invert")
                        .withStyle(ChatFormatting.DARK_GRAY)));
    }

    /**
     * Tooltip of the "+" / "-" button.
     *
     * <p>Short - just the sign and one. The long sentence ("Change the amount by one,
     * type a bigger value") was removed at the player's request: the button is small,
     * and a tooltip should add a word, not explain how to operate it.
     */
    private Tooltip stepTip(String key) {
        return Tooltip.create(Component.translatable(key));
    }

    private void toggleMode() {
        this.uiHighMode = !this.uiHighMode;
        if (this.modeButton != null) {
            this.modeButton.setTooltip(modeTooltip());
        }
        sendConfig();
    }

    /** The threshold by one - counted from what the player SEES in the field. */
    private void step(int delta) {
        long next = Math.max(VeloceThresholdSensorBlockEntity.MIN_THRESHOLD,
                currentFieldValue() + delta);
        this.thresholdField.setValue(Long.toString(next));
        sendConfig();
    }

    /**
     * Sends the threshold and the mode, but ONLY when one of them really changed.
     *
     * <p><b>The BUG this fixes.</b> The previous version took the "new mode"
     * from the same field it compared itself against:
     * <pre>
     *   if (threshold == sentThreshold &amp;&amp; sentHighMode == currentHighMode()) return;
     *   // and currentHighMode() simply returned sentHighMode
     * </pre>
     * so the second condition was ALWAYS true. With an unchanged threshold the
     * function therefore returned early and sent NOTHING - and since the mode
     * button only changed its own label, it looked like it worked.
     * The inverted mode never reached the server, not even once.
     *
     * <p>Now we compare the GUI state with what REALLY went out.
     */
    private void sendConfig() {
        long threshold = currentFieldValue();
        boolean high = this.uiHighMode;
        if (threshold == this.sentThreshold && high == this.sentHighMode) {
            return;
        }
        this.sentThreshold = threshold;
        this.sentHighMode = high;
        PacketDistributor.sendToServer(
                new SensorConfigPKT(this.menu.getPos(), threshold, high));
    }

    /**
     * The threshold the player has before their eyes right now.
     *
     * <p>We take the field, not the value from the server - otherwise typing 500 and
     * pressing "+" would give (the old value from the server)+1, which would silently
     * lose what the player had just typed. An empty or nonsense field falls back to
     * the value from the server.
     */
    private long currentFieldValue() {
        String text = this.thresholdField == null ? "" : this.thresholdField.getValue().trim();
        if (!text.isEmpty()) {
            try {
                return Math.max(VeloceThresholdSensorBlockEntity.MIN_THRESHOLD,
                        Long.parseLong(text));
            } catch (NumberFormatException ignored) {
                // the field accepts digits only, so this practically cannot happen
            }
        }
        return this.menu.getThreshold();
    }

    @Override
    public void containerTick() {
        super.containerTick();
        // The server sends its own values late (e.g. after a world save or a change
        // from somewhere else). We update the text field ONLY when the player is not
        // typing in it - otherwise we would overwrite what they are typing right now.
        if (this.thresholdField != null && !this.thresholdField.isFocused()) {
            String current = Long.toString(this.menu.getThreshold());
            if (!current.equals(this.thresholdField.getValue())) {
                this.thresholdField.setValue(current);
                this.sentThreshold = this.menu.getThreshold();
            }
        }
        // We accept the mode from the server ONLY when we have no own, not yet
        // confirmed change - otherwise the overwrite would undo the player's click
        // (the server answers with a one-tick delay).
        boolean serverHigh = this.menu.getMode() == VeloceThresholdSensorBlockEntity.Mode.HIGH;
        if (this.sentHighMode == this.uiHighMode && serverHigh != this.uiHighMode) {
            this.uiHighMode = serverHigh;
            this.sentHighMode = serverHigh;
            // The icon reads the mode while drawing, but the tooltip is built
            // once - it has to be refreshed, otherwise it would describe the old mode.
            if (this.modeButton != null) {
                this.modeButton.setTooltip(modeTooltip());
            }
        }
        // The filter may have been changed by a shared packet - we refresh the mirror.
        this.clientFilter = this.menu.getFilter();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(GUI_TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);

        // The filter icon - the slot is a ghost slot, so we draw it ourselves.
        if (!clientFilter.isEmpty()) {
            graphics.renderFakeItem(clientFilter,
                    this.leftPos + VeloceThresholdSensorMenu.FILTER_SLOT_X,
                    this.topPos + VeloceThresholdSensorMenu.FILTER_SLOT_Y);
        }
    }

    /**
     * Without the "Inventory" label.
     *
     * <p>The player did not want any texts in this GUI - only the title remains
     * (the block name), so it is clear what was opened.
     */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY,
                0x404040, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderFilterTooltip(graphics, mouseX, mouseY);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    /**
     * Tooltip of the item slot - two states, without a picture-instruction.
     *
     * <p>Just as in the extractor: an empty slot is simply "Empty filter".
     */
    private void renderFilterTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isHovering(VeloceThresholdSensorMenu.FILTER_SLOT_X,
                VeloceThresholdSensorMenu.FILTER_SLOT_Y, 16, 16, mouseX, mouseY)) {
            return;
        }
        List<Component> lines;
        if (clientFilter.isEmpty()) {
            lines = List.of(Component.translatable("gui.craftingveloce.sensor.filterEmpty"));
        } else {
            lines = List.of(clientFilter.getHoverName(),
                    Component.translatable("gui.craftingveloce.sensor.filterChange")
                            .withStyle(ChatFormatting.DARK_GRAY));
        }
        graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    /** Click on the filter: a cursor with an item sets the filter, an empty cursor opens the picker. */
    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        if (slot != null && slot.index == 0
                && slot.container != this.minecraft.player.getInventory()) {
            ItemStack carried = this.menu.getCarried();
            if (!carried.isEmpty()) {
                ItemStack single = carried.copyWithCount(1);
                this.clientFilter = single;
                PacketDistributor.sendToServer(
                        new SetFilterPKT(this.menu.getPos(), 0, single));
                return;
            }
            if (!clientFilter.isEmpty()) {
                this.clientFilter = ItemStack.EMPTY;
                PacketDistributor.sendToServer(
                        new SetFilterPKT(this.menu.getPos(), 0, ItemStack.EMPTY));
                return;
            }
            if (mouseButton == 0) {
                PacketDistributor.sendToServer(
                        new OpenFilterPKT(this.menu.getPos(), 0));
            }
            return;
        }
        super.slotClicked(slot, slotId, mouseButton, clickType);
    }

    /** Saves the threshold also when the player leaves the field without Enter. */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean wasFocused = this.thresholdField != null && this.thresholdField.isFocused();
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (wasFocused && this.thresholdField != null && !this.thresholdField.isFocused()) {
            sendConfig();
        }
        return handled;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean fieldFocused = this.thresholdField != null && this.thresholdField.isFocused();

        // Enter confirms the threshold.
        if (fieldFocused && (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                    || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
            sendConfig();
            this.thresholdField.setFocused(false);
            return true;
        }

        // FIELD ACTIVE: the keys belong to the FIELD, not to the screen.
        //
        // Without this, "E" (the inventory key) closed the GUI while a number was
        // being typed - exactly the same bug the player reported for the search
        // boxes in the creative screens. The field handles what it wants
        // (digits, backspace, arrows), and the rest is swallowed.
        if (fieldFocused) {
            // E (the inventory key) left in the key queue would open the inventory
            // anyway: Minecraft.handleKeybinds() checks it every tick without
            // looking at the screen. So we clear that click (see the comment
            // in VeloceCreativeScreen.keyPressed).
            if (this.minecraft != null && this.minecraft.options != null
                    && this.minecraft.options.keyInventory != null
                    && this.minecraft.options.keyInventory.matches(keyCode, scanCode)) {
                com.craftingveloce.util.VeloceLog.Gui.detail(
                        com.craftingveloce.util.VeloceLog.Side.CLIENT,
                        "threshold field active: E stays in the field (the window does not close)");
                this.minecraft.options.keyInventory.consumeClick();
            }
            // Esc ALWAYS closes - even when the field is active (that is how the game works).
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                this.onClose();
                return true;
            }
            if (this.thresholdField.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            return true;   // including E - it does not close the window
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
