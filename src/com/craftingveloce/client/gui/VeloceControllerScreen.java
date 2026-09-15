package com.craftingveloce.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GUI Veloce Controller - przeglad sieci z filtrowaniem.
 *
 * <p>Trzy tryby widoku (przyciski na dole ekranu):
 * <ul>
 *   <li><b>SHOW ALL</b> - wszystko, co jest w sieci + wszystko craftowalne</li>
 *   <li><b>AVAILABLE</b> - tylko to, co jest realnie dostepne: jest na stocku
 *       LUB crafter potrafi to zrobic (wlaczony auto-crafting)</li>
 *   <li><b>NOT AVAILABLE</b> - tego crafter nie zrobi i nie ma na stocku;
 *       wlasnie te itemy warto zaplanowac jako maszyny (extractor + skrzynia)</li>
 * </ul>
 *
 * <p>Kolory tla ikony:
 * <ul>
 *   <li>zielony - item jest w hotbarze gracza (pod reka)</li>
 *   <li>niebieski - item jest na stocku w sieci</li>
 *   <li>zolty - itemu nie ma, ale crafter potrafi go zrobic</li>
 *   <li>czerwony - niedostepny (brak stocku i brak craftingu)</li>
 * </ul>
 */
public class VeloceControllerScreen extends VeloceCreativeScreen {

    /** Tryb filtrowania widoku. */
    /**
     * Tryb filtrowania widoku. Kazdy ma klucz tlumaczenia, zeby etykieta
     * byla z jezyka gry, a nie zaszyta w kodzie.
     */
    public enum Filter {
        ALL("gui.craftingveloce.controller.filter.all",
                "gui.craftingveloce.controller.filter.all.tip"),
        AVAILABLE("gui.craftingveloce.controller.filter.available",
                "gui.craftingveloce.controller.filter.available.tip"),
        NOT_AVAILABLE("gui.craftingveloce.controller.filter.notAvailable",
                "gui.craftingveloce.controller.filter.notAvailable.tip");

        /** Krotka etykieta na przycisku (musi sie zmiescic w 52 px). */
        final String key;
        /** Pelne znaczenie filtra - pokazywane w tooltipie przycisku. */
        final String tooltipKey;

        Filter(String key, String tooltipKey) {
            this.key = key;
            this.tooltipKey = tooltipKey;
        }
    }

    private final BlockPos controllerPos;
    private final Map<Item, Long> stock;
    private final Set<Item> craftable;
    private final Set<Item> craftingEnabled;
    /** Itemy z receptura PIECA (smelting / blasting / smoking). */
    private final Set<Item> furnaceCraftable;
    /** Czy w sieci stoi jakikolwiek piec (nawet bez paliwa). */
    private final boolean furnaceInNetwork;
    /** Czy ktorys piec jest zasilony - tylko wtedy receptury pieca sa realne. */
    private final boolean furnacePowered;
    private final Map<Item, Integer> hotbar;

    private Filter filter = Filter.ALL;

    private final List<Button> filterButtons = new ArrayList<>();

    public VeloceControllerScreen(LocalPlayer player, FeatureFlagSet enabledFeatures,
                                  boolean displayOperatorCreativeTab, BlockPos controllerPos,
                                  Map<Item, Long> stock, Set<Item> craftable,
                                  Set<Item> craftingEnabled, Set<Item> furnaceCraftable,
                                  boolean furnaceInNetwork, boolean furnacePowered,
                                  Map<Item, Integer> hotbar) {
        super(player, enabledFeatures, displayOperatorCreativeTab);
        this.controllerPos = controllerPos;
        this.stock = new HashMap<>(stock);
        this.craftable = new HashSet<>(craftable);
        this.craftingEnabled = new HashSet<>(craftingEnabled);
        this.furnaceCraftable = new HashSet<>(furnaceCraftable);
        this.furnaceInNetwork = furnaceInNetwork;
        this.furnacePowered = furnacePowered;
        this.hotbar = new HashMap<>(hotbar);
    }

    // ---------- klasyfikacja itemu ----------

    private boolean hasStock(Item item) {
        return stock.getOrDefault(item, 0L) > 0;
    }

    private boolean canCrafterMake(Item item) {
        return craftingEnabled.contains(item);
    }

    /**
     * Czy item da sie uzyskac w PIECU.
     *
     * <p>Wymagamy DWOCH rzeczy: receptury pieca ORAZ zasilonego pieca w sieci.
     * Samo istnienie receptury nie wystarcza - bez paliwa nic sie nie przepali.
     */
    private boolean canFurnaceMake(Item item) {
        return furnacePowered && furnaceCraftable.contains(item);
    }

    private boolean isAvailable(Item item) {
        // Dostepne = jest na stocku ALBO crafter potrafi to zrobic ALBO piec
        // jest zasilony i ma na to recepture.
        // (Item moze byc craftowalny w ogole, ale jesli auto-crafting jest
        //  wylaczony, to realnie nie jest dostepny - dlatego sprawdzamy
        //  craftingEnabled, a nie samo craftable.)
        return hasStock(item) || canCrafterMake(item) || canFurnaceMake(item);
    }

    private boolean passesFilter(Item item) {
        return switch (filter) {
            case ALL -> true;
            case AVAILABLE -> isAvailable(item);
            case NOT_AVAILABLE -> !isAvailable(item);
        };
    }

    /**
     * Filtr dziala na LISCIE itemow, a nie tylko na rysowaniu ikony.
     *
     * <p><b>Bylo tu realne pomylenie.</b> Filtr sprawdzal sie wylacznie w
     * {@code renderSlot} i w tooltipie, wiec odfiltrowane itemy zostawaly
     * w siatce jako puste miejsca (cala masa dziur), a ich sloty nadal byly
     * KLIKALNE - dalo sie wyciagnac item, ktory wlasnie byl oznaczony jako
     * niedostepny.
     *
     * <p>Teraz {@code applyItemFilter} z klasy bazowej usuwa je z listy i
     * kompaktuje siatke - tak samo, jak robi to ekran craftera.
     */
    @Override
    protected boolean acceptItem(ItemStack stack) {
        return !stack.isEmpty() && passesFilter(stack.getItem());
    }

    /** Kolor tla ikony wg stanu dostepnosci. */
    private int colorFor(Item item) {
        if (hotbar.containsKey(item)) {
            return 0x7700AA00;  // zielony: w hotbarze
        }
        if (hasStock(item)) {
            return 0x770000AA;  // niebieski: na stocku
        }
        if (canCrafterMake(item)) {
            return 0x77AAAA00;  // zolty: crafter to zrobi
        }
        if (canFurnaceMake(item)) {
            return 0x77AA5500;  // pomaranczowy: piec to przepali
        }
        return 0x77AA0000;      // czerwony: niedostepne
    }

    // ---------- lifecycle ----------

    @Override
    protected void init() {
        super.init();   // baza: tryb creative, ukrycie slotow gracza, filtr itemow
        buildFilterButtons();
    }

    /**
     * Przyciski filtrow umieszczone w pasku hotbara (ktory i tak jest pusty).
     *
     * <p><b>Dlaczego etykiety sa KROTKIE.</b> Przycisk ma 52 px szerokosci, a
     * poprzednie napisy ("Show all", "Not available") sie w nim nie miescily -
     * "Not available" to ~78 px, wiec tekst wychodzil za przycisk i nachodzil
     * na sasiedni. Dlatego etykieta jest jednym slowem, a PELNE znaczenie
     * przeniosl sie do tooltipa - nic nie zginelo, tylko przestalo sie
     * rozlewac. Same OPCJE (co filtruja) zostaja bez zmian.
     */
    private void buildFilterButtons() {
        filterButtons.clear();
        int y = this.topPos + 112;
        int w = 52;
        int gap = 2;
        int totalW = Filter.values().length * w + (Filter.values().length - 1) * gap;
        int startX = this.leftPos + (176 - totalW) / 2;

        int i = 0;
        for (Filter f : Filter.values()) {
            final Filter target = f;
            Button b = Button.builder((filter == f ? Component.literal("§a▶ ") : Component.empty())
                            .append(Component.translatable(f.key)), btn -> {
                this.filter = target;
                rebuildWidgets();
            }).bounds(startX + i * (w + gap), y, w, 18)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.translatable(f.tooltipKey)))
                    .build();
            filterButtons.add(b);
            addRenderableWidget(b);
            i++;
        }
    }




    // ---------- render ----------

    @Override
    protected void renderSlot(GuiGraphics graphics, Slot slot) {
        if (isPlayerInventorySlot(slot)) {
            return;
        }
        if (slot.x == 173 && slot.y == 112) {
            return;  // trash slot
        }

        if (!slot.hasItem()) {
            super.renderSlot(graphics, slot);
            return;
        }

        ItemStack stack = slot.getItem();
        Item item = stack.getItem();

        // Filtr: item poza filtrem traktujemy jak pusty slot (bez ikony).
        if (!passesFilter(item)) {
            return;
        }

        super.renderSlot(graphics, slot);

        RenderSystem.disableDepthTest();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 200);
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, colorFor(item));
        graphics.pose().popPose();
        RenderSystem.enableDepthTest();

        // Liczba sztuk na stocku (jesli jest).
        long n = stock.getOrDefault(item, 0L);
        if (n > 0) {
            String txt = VeloceTerminalScreen.formatCount(n);
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 250);
            graphics.drawString(this.font, txt, slot.x + 17 - this.font.width(txt),
                    slot.y + 9, 0xFFFFFF, true);   // bialy = stock (spojnie z terminalem)
            graphics.pose().popPose();
        }
    }


    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Tlo pod przyciskami filtrow (hotbar jest pusty).
        int x1 = this.leftPos + 8;
        int y1 = this.topPos + 111;
        int x2 = this.leftPos + 170;
        int y2 = this.topPos + 130;
        graphics.fill(x1, y1, x2, y2, 0xFFC6C6C6);

        // Przyciski rysujemy po tle, inaczej zostana zamalowane.
        for (Button b : filterButtons) {
            b.render(graphics, mouseX, mouseY, partialTick);
        }

        renderInfoTooltip(graphics, mouseX, mouseY);

        // Podsumowanie trybu w prawym gornym rogu.
        String infoKey = switch (filter) {
            case ALL -> "gui.craftingveloce.controller.state.all";
            case AVAILABLE -> "gui.craftingveloce.controller.state.available";
            case NOT_AVAILABLE -> "gui.craftingveloce.controller.state.notAvailable";
        };
        String info = Component.translatable(infoKey).getString();
        graphics.drawString(this.font, info, this.leftPos + 176 - 8 - this.font.width(info),
                this.topPos + 6, 0xFFFFFF, true);
    }

    private void renderInfoTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        Slot slot = getSlotUnderMouse();
        if (slot == null || isPlayerInventorySlot(slot) || !slot.hasItem()) {
            return;
        }
        Item item = slot.getItem().getItem();
        if (!passesFilter(item)) {
            return;
        }

        List<Component> lines = new ArrayList<>();
        lines.add(slot.getItem().getHoverName());
        addStockLines(lines, item);
        addCraftingLines(lines, item);
        addFurnaceLines(lines, item);
        addUnavailableHint(lines, item);

        graphics.renderTooltip(this.font, lines, java.util.Optional.empty(), mouseX, mouseY);
    }

    /** Stock w sieci + ewentualna informacja, ze item jest w hotbarze. */
    private void addStockLines(List<Component> lines, Item item) {
        long n = stock.getOrDefault(item, 0L);
        lines.add(n > 0
                ? Component.translatable("gui.craftingveloce.controller.stock", n)
                        .withStyle(net.minecraft.ChatFormatting.AQUA)
                : Component.translatable("gui.craftingveloce.controller.stock.none")
                        .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));

        if (hotbar.containsKey(item)) {
            lines.add(Component.translatable("gui.craftingveloce.controller.inHotbar", hotbar.get(item))
                    .withStyle(net.minecraft.ChatFormatting.GREEN));
        }
    }

    /**
     * Trzy stany auto-craftingu: wlaczony / crafter potrafi ale wylaczony /
     * crafter w ogole nie potrafi.
     */
    private void addCraftingLines(List<Component> lines, Item item) {
        if (craftingEnabled.contains(item)) {
            lines.add(Component.translatable("gui.craftingveloce.controller.craftingOn")
                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
        } else if (craftable.contains(item)) {
            lines.add(Component.translatable("gui.craftingveloce.controller.craftingOff")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
        } else {
            lines.add(Component.translatable("gui.craftingveloce.controller.notCraftable")
                    .withStyle(net.minecraft.ChatFormatting.RED));
        }
    }

    /**
     * Informacja o piecu - tylko dla itemow, ktore maja recepture pieca.
     *
     * <p>Trzy rozne komunikaty, bo trzy rozne sytuacje:
     * <ul>
     *   <li>piec zasilony -> "mozna przepalic" (to jest realna dostepnosc),</li>
     *   <li>piec jest, ale stoi -> "receptura jest, brak paliwa" - to jest
     *       podpowiedz, co zrobic, a nie "niedostepne",</li>
     *   <li>pieca nie ma w sieci -> "potrzebny piec" - podpowiedz, ze trzeba
     *       go postawic i podlaczyc.</li>
     * </ul>
     */
    private void addFurnaceLines(List<Component> lines, Item item) {
        if (!furnaceCraftable.contains(item)) {
            return;
        }
        if (furnacePowered) {
            lines.add(Component.translatable("gui.craftingveloce.controller.smeltingOn")
                    .withStyle(net.minecraft.ChatFormatting.GOLD));
        } else if (furnaceInNetwork) {
            lines.add(Component.translatable("gui.craftingveloce.controller.smeltingNoFuel")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        } else {
            lines.add(Component.translatable("gui.craftingveloce.controller.smeltingNoFurnace")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }
    }

    private void addUnavailableHint(List<Component> lines, Item item) {
        if (isAvailable(item)) {
            return;
        }
        lines.add(Component.empty());
        lines.add(Component.translatable("gui.craftingveloce.controller.buildMachine")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        lines.add(Component.translatable("gui.craftingveloce.controller.buildMachine2")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    /**
     * Kontroler pamieta swoja zakladke PER BLOK.
     *
     * <p>Blok jest na razie wstepnie napisany, ale pamiec zakladki dziala
     * tak samo jak w pozostalych ekranach.
     */
    @Override
    protected Object viewStateKey() {
        return controllerPos;
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        // Controller jest tylko do odczytu - nie przenosimy itemow.
    }

    @Override
    public void removed() {
        // Trzymany stos obsluguje teraz klasa bazowa (oddaje do ekwipunku
        // albo upuszcza). Wczesniej tutaj byl skasowany.
        super.removed();

    }
}
