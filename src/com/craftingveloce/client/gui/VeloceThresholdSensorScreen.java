package com.craftingveloce.client.gui;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.entity.VeloceThresholdSensorBlockEntity;
import com.craftingveloce.inventory.VeloceThresholdSensorMenu;
import com.craftingveloce.network.OpenFilterPKT;
import com.craftingveloce.network.SensorConfigPKT;
import com.craftingveloce.network.SetFilterPKT;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
 * GUI Veloce Threshold Sensor.
 *
 * <p><b>Uklad:</b> jeden slot filtra, pole na prog, trzy male przyciski
 * i ekwipunek gracza - w tej samej siatce co ekstraktor (sloty od x=26,
 * ekwipunek od y=84).
 *
 * <p><b>Trzy przyciski.</b>
 * <ol>
 *   <li><b>tryb</b> - przelacza warunek miedzy "za malo" a "wystarczy",
 *       czyli wlasnie tryb normalny i odwrotny, o ktory chodzilo,</li>
 *   <li><b>-</b> i <b>+</b> - progu o jeden. Wieksze wartosci wpisuje sie
 *       w pole tekstowe.</li>
 * </ol>
 *
 * <p><b>Pole tekstowe</b> przyjmuje tylko cyfry. Zapisujemy je po zatwierdzeniu
 * (Enter) albo po wyjsciu z pola - a nie przy kazdym wcisnietym klawiszu, bo
 * inaczej "6" z "64" zostaloby wyslane jako prog 6 i sensor przez chwile
 * dzialalby na zlej wartosci.
 */
public class VeloceThresholdSensorScreen
        extends AbstractContainerScreen<VeloceThresholdSensorMenu> {

    private static final ResourceLocation GUI_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            CraftingVeloceMod.MODID, "textures/gui/threshold_sensor.png");

    private static final int FIELD_X = 50;
    private static final int FIELD_Y = 18;
    private static final int FIELD_W = 76;
    private static final int FIELD_H = 16;

    private static final int BUTTON_Y = 44;
    private static final int MODE_W = 84;
    private static final int STEP_W = 22;

    private EditBox thresholdField;
    private Button modeButton;

    /** Ostatnia wartosc wyslana na serwer - zeby nie spamowac pakietami. */
    private long sentThreshold = Long.MIN_VALUE;
    private boolean sentHighMode;

    public VeloceThresholdSensorScreen(VeloceThresholdSensorMenu menu,
                                       Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        // Ta sama siatka i te same wymiary co ekstraktor.
        this.imageWidth = 212;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
        this.inventoryLabelX = 26;
        this.titleLabelX = 26;
    }

    @Override
    protected void init() {
        super.init();
        long threshold = this.menu.getThreshold();
        this.sentThreshold = threshold;
        this.sentHighMode = this.menu.getMode() == VeloceThresholdSensorBlockEntity.Mode.HIGH;

        this.thresholdField = new EditBox(this.font,
                this.leftPos + FIELD_X, this.topPos + FIELD_Y, FIELD_W, FIELD_H,
                Component.translatable("gui.craftingveloce.sensor.threshold"));
        this.thresholdField.setValue(Long.toString(threshold));
        this.thresholdField.setMaxLength(10);
        // Tylko cyfry - pole jest liczbowe, wiec nie ma po co wpuszczac liter.
        this.thresholdField.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        this.thresholdField.setResponder(s -> { /* zapis dopiero po zatwierdzeniu */ });
        addRenderableWidget(this.thresholdField);

        this.modeButton = Button.builder(modeLabel(), b -> toggleMode())
                .bounds(this.leftPos + 26, this.topPos + BUTTON_Y, MODE_W, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.craftingveloce.sensor.mode.tip")))
                .build();
        addRenderableWidget(this.modeButton);

        addRenderableWidget(Button.builder(Component.literal("-"), b -> step(-1))
                .bounds(this.leftPos + 26 + MODE_W + 4, this.topPos + BUTTON_Y, STEP_W, 18)
                .tooltip(stepTip())
                .build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> step(1))
                .bounds(this.leftPos + 26 + MODE_W + 4 + STEP_W + 4, this.topPos + BUTTON_Y,
                        STEP_W, 18)
                .tooltip(stepTip())
                .build());
    }

    private net.minecraft.client.gui.components.Tooltip stepTip() {
        return net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("gui.craftingveloce.sensor.step.tip"));
    }

    private Component modeLabel() {
        return Component.translatable(this.sentHighMode
                ? "gui.craftingveloce.sensor.mode.high"
                : "gui.craftingveloce.sensor.mode.low");
    }

    private void toggleMode() {
        this.sentHighMode = !this.sentHighMode;
        if (this.modeButton != null) {
            this.modeButton.setMessage(modeLabel());
        }
        sendConfig();
    }

    private void step(int delta) {
        long next = Math.max(0L, this.menu.getThreshold() + delta);
        this.thresholdField.setValue(Long.toString(next));
        sendConfig();
    }

    /** Wysyla prog i tryb na serwer - tylko gdy naprawde sie zmienily. */
    private void sendConfig() {
        long threshold = parseThreshold();
        if (threshold == sentThreshold && sentHighMode == currentHighMode()) {
            return;
        }
        sentThreshold = threshold;
        PacketDistributor.sendToServer(
                new SensorConfigPKT(this.menu.getPos(), threshold, currentHighMode()));
    }

    private boolean currentHighMode() {
        return this.sentHighMode;
    }

    /** Wartosc z pola; puste albo bzdurne traktujemy jako 0. */
    private long parseThreshold() {
        String text = this.thresholdField == null ? "" : this.thresholdField.getValue().trim();
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(text));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public void containerTick() {
        super.containerTick();
        // Serwer dosyla swoje wartosci (np. po zapisie swiata albo zmianie
        // z innego miejsca). Pole tekstowe aktualizujemy TYLKO gdy gracz w nim
        // nie pisze - inaczej nadpisywalibysmy to, co wlasnie wpisuje.
        if (this.thresholdField != null && !this.thresholdField.isFocused()) {
            String current = Long.toString(this.menu.getThreshold());
            if (!current.equals(this.thresholdField.getValue())) {
                this.thresholdField.setValue(current);
                this.sentThreshold = this.menu.getThreshold();
            }
        }
        boolean high = this.menu.getMode() == VeloceThresholdSensorBlockEntity.Mode.HIGH;
        if (high != this.sentHighMode && this.modeButton != null) {
            this.sentHighMode = high;
            this.modeButton.setMessage(modeLabel());
        }
        // Filtr mogl zostac zmieniony wspolnym pakietem - odswiezamy lustro.
        this.clientFilter = this.menu.getFilter();
    }

    /** Lustro filtra po stronie klienta (blok entity jest zrodlem prawdy). */
    private ItemStack clientFilter = ItemStack.EMPTY;

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(GUI_TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);

        // Ikona filtra - slot jest widmem, wiec rysujemy go sami.
        if (!clientFilter.isEmpty()) {
            graphics.renderFakeItem(clientFilter,
                    this.leftPos + VeloceThresholdSensorMenu.FILTER_SLOT_X,
                    this.topPos + VeloceThresholdSensorMenu.FILTER_SLOT_Y);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderStatus(graphics);
        renderFilterTooltip(graphics, mouseX, mouseY);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    /**
     * Stan czujnika pod przyciskami.
     *
     * <p>Pokazujemy TRZY rzeczy, bo bez kazdej z nich gracz nie wie, dlaczego
     * prad jest albo go nie ma: ile jest w sieci, jaki jest prog i czy warunek
     * jest spelniony.
     */
    private void renderStatus(GuiGraphics graphics) {
        int x = this.leftPos + 26;
        int y = this.topPos + 70;

        long count = this.menu.getLastCount();
        String countText = count < 0
                ? Component.translatable("gui.craftingveloce.sensor.count.unknown").getString()
                : Component.translatable("gui.craftingveloce.sensor.count", count).getString();
        graphics.drawString(this.font, countText, x, y, 0x404040, false);

        if (this.menu.getFilter().isEmpty()) {
            graphics.drawString(this.font,
                    Component.translatable("gui.craftingveloce.sensor.noFilter").getString(),
                    x, y + 10, 0x8B0000, false);
            return;
        }
        boolean met = this.menu.conditionMet();
        Component state = Component.translatable(met
                ? "gui.craftingveloce.sensor.output.on"
                : "gui.craftingveloce.sensor.output.off");
        graphics.drawString(this.font, state.getString(), x, y + 10,
                met ? 0x006400 : 0x404040, false);
    }

    private void renderFilterTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isHovering(VeloceThresholdSensorMenu.FILTER_SLOT_X,
                VeloceThresholdSensorMenu.FILTER_SLOT_Y, 16, 16, mouseX, mouseY)) {
            return;
        }
        List<Component> lines = new ArrayList<>();
        if (clientFilter.isEmpty()) {
            lines.add(Component.translatable("gui.craftingveloce.sensor.filterEmpty"));
        } else {
            lines.add(clientFilter.getHoverName());
            lines.add(Component.translatable("gui.craftingveloce.sensor.filterChange")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }
        lines.add(Component.translatable("gui.craftingveloce.sensor.filterHint")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    /** Klik w filtr: kursor z itemem ustawia filtr, pusty kursor otwiera wybor. */
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

    /** Zapisuje prog takze wtedy, gdy gracz wyjdzie z pola bez Entera. */
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
        // Enter zatwierdza prog.
        if (this.thresholdField != null && this.thresholdField.isFocused()
                && (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                    || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
            sendConfig();
            this.thresholdField.setFocused(false);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
