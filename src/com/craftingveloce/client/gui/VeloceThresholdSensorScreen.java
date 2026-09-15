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
 * GUI Veloce Threshold Sensor.
 *
 * <p><b>Jeden wiersz, wysrodkowany:</b> slot itemu, pole liczby, "+", "-"
 * i guzik trybu. Wszystkie wspolrzedne pochodza z
 * {@link VeloceThresholdSensorMenu} - ekran nie ma wlasnej kopii ukladu.
 *
 * <p><b>Zero napisow w GUI.</b> Gracz nie chcial tekstow ("Inventory",
 * "In network", "Output: ON") ani kwadratu w teksturze, ktory byl dla nich
 * miejscem. Nie ma ich - stan wyjscia widac po diodzie na bloku, a tryb po
 * ikonie guzika.
 *
 * <p><b>Guzik trybu to pochodnia redstone.</b> Zapalona = "co najmniej tyle"
 * (sygnal wprost), zgaszona = "ponizej" (odwrotka). Klik przelacza tryb, czyli
 * odwraca sygnal - standardowa odwrotka redstone.
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

    /** Ikony trybu: waniliowe tekstury bloku pochodni (16x16). */
    private static final ResourceLocation TORCH_LIT =
            ResourceLocation.withDefaultNamespace("textures/block/redstone_torch.png");
    private static final ResourceLocation TORCH_OFF =
            ResourceLocation.withDefaultNamespace("textures/block/redstone_torch_off.png");

    private EditBox thresholdField;
    private ModeButton modeButton;

    /**
     * Tryb wybrany w GUI - to, co widzi gracz i co dopiero poleci na serwer.
     *
     * <p>MUSI byc osobny od {@link #sentHighMode}. Poprzednia wersja uzywala
     * jednego pola i do porownania "czy sie zmienilo", i jako nowej wartosci -
     * wiec porownanie bylo tautologia (patrz {@link #sendConfig}).
     */
    private boolean uiHighMode;

    /** Co FAKTYCZNIE poszlo na serwer - zeby nie spamowac pakietami. */
    private long sentThreshold = Long.MIN_VALUE;
    private boolean sentHighMode;

    /** Lustro filtra po stronie klienta (blok entity jest zrodlem prawdy). */
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
        // Tylko cyfry - pole jest liczbowe, wiec nie ma po co wpuszczac liter.
        this.thresholdField.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        // Podpowiedz pola: naglowek + jedno zdanie, co ta liczba znaczy.
        this.thresholdField.setTooltip(Tooltip.create(
                Component.translatable("gui.craftingveloce.sensor.threshold")
                        .append(Component.literal("\n"))
                        .append(Component.translatable("gui.craftingveloce.sensor.threshold.tip")
                                .withStyle(ChatFormatting.DARK_GRAY))));
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

        // Guzik trybu na koncu wiersza - ikona zamiast napisu.
        this.modeButton = addRenderableWidget(new ModeButton(
                this.leftPos + VeloceThresholdSensorMenu.MODE_X,
                this.topPos + VeloceThresholdSensorMenu.ROW_Y));
    }

    /**
     * Guzik trybu: pochodnia zamiast napisu "When below".
     *
     * <p>Kolor ikony NIE zmienia sie przy kliknieciu "na chwile" - pochodnia
     * pokazuje TRYB, a nie chwilowy stan wyjscia. Inaczej gracz nie wiedzialby,
     * jaki tryb jest ustawiony, gdy warunek akurat nie jest spelniony.
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
            // Ikona 16x16 wysrodkowana w guziku 20x20 (po 2 px z kazdej strony).
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
     * Podpowiedz guzika "+" / "-".
     *
     * <p>Krotko - sam znak i jedynka. Dlugie zdanie ("Change the amount by one,
     * type a bigger value") gracz kazal usunac: guzik jest maly, a tooltip ma
     * dopowiedziec, a nie tlumaczyc obsluge.
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

    /** Progu o jeden - liczone od tego, co gracz WIDZI w polu. */
    private void step(int delta) {
        long next = Math.max(VeloceThresholdSensorBlockEntity.MIN_THRESHOLD,
                currentFieldValue() + delta);
        this.thresholdField.setValue(Long.toString(next));
        sendConfig();
    }

    /**
     * Wysyla prog i tryb, ale TYLKO gdy ktorys naprawde sie zmienil.
     *
     * <p><b>BUG, ktory to naprawia.</b> Poprzednia wersja brala "nowy tryb"
     * z tego samego pola, z ktorym sie porownywala:
     * <pre>
     *   if (threshold == sentThreshold &amp;&amp; sentHighMode == currentHighMode()) return;
     *   // a currentHighMode() zwracalo po prostu sentHighMode
     * </pre>
     * czyli drugi warunek byl ZAWSZE prawdziwy. Przy niezmienionym progu
     * funkcja wychodzila wiec wczesniej i NIC nie wysylala - a ze przycisk
     * trybu zmienial tylko swoja etykiete, wygladalo to na dzialajace.
     * Tryb odwrotny nie docieral do serwera ani razu.
     *
     * <p>Teraz porownujemy stan GUI z tym, co NAPRAWDE poszlo.
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
     * Prog, ktory gracz ma teraz przed oczami.
     *
     * <p>Bierzemy pole, a nie wartosc z serwera - inaczej wpisanie 500 i
     * wcisniecie "+" daloby (stara wartosc z serwera)+1, czyli cicho zgubilo
     * to, co gracz wlasnie wpisal. Puste albo bzdurne pole schodzi do wartosci
     * z serwera.
     */
    private long currentFieldValue() {
        String text = this.thresholdField == null ? "" : this.thresholdField.getValue().trim();
        if (!text.isEmpty()) {
            try {
                return Math.max(VeloceThresholdSensorBlockEntity.MIN_THRESHOLD,
                        Long.parseLong(text));
            } catch (NumberFormatException ignored) {
                // pole przyjmuje tylko cyfry, wiec to praktycznie nie wystapi
            }
        }
        return this.menu.getThreshold();
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
        // Tryb z serwera przyjmujemy TYLKO gdy nie mamy wlasnej, jeszcze
        // niepotwierdzonej zmiany - inaczej nadpisanie cofneloby klik gracza
        // (serwer odpowiada z opoznieniem jednego ticku).
        boolean serverHigh = this.menu.getMode() == VeloceThresholdSensorBlockEntity.Mode.HIGH;
        if (this.sentHighMode == this.uiHighMode && serverHigh != this.uiHighMode) {
            this.uiHighMode = serverHigh;
            this.sentHighMode = serverHigh;
            // Ikona czyta tryb przy rysowaniu, ale podpowiedz jest budowana
            // raz - trzeba ja odswiezyc, bo inaczej opisywalaby stary tryb.
            if (this.modeButton != null) {
                this.modeButton.setTooltip(modeTooltip());
            }
        }
        // Filtr mogl zostac zmieniony wspolnym pakietem - odswiezamy lustro.
        this.clientFilter = this.menu.getFilter();
    }

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

    /**
     * Bez napisu "Inventory".
     *
     * <p>Gracz nie chcial zadnych tekstow w tym GUI - zostaje sam tytul
     * (nazwa bloku), zeby bylo wiadomo, co sie otworzylo.
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
     * Podpowiedz slotu itemu - dwa stany, bez instrukcji-obrazka.
     *
     * <p>Tak samo jak w ekstraktorze: pusty slot to samo "Empty filter".
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
