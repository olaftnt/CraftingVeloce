package com.craftingveloce.client.gui;

import com.craftingveloce.crafting.VeloceFlowTracker;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;
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
 *   <li><b>AVAILABLE</b> - tylko to, co jest realnie dostepne: jest na stocku,
 *       LUB crafter to zrobi, LUB przepali to zasilony piec</li>
 *   <li><b>NOT AVAILABLE</b> - tego crafter nie zrobi, piec nie przepali
 *       i nie ma na stocku; wlasnie te itemy warto zaplanowac jako maszyny
 *       (extractor + skrzynia)</li>
 * </ul>
 *
 * <p>Kolory tla ikony:
 * <ul>
 *   <li>niebieski - item jest na stocku w sieci</li>
 *   <li>zolty - itemu nie ma, ale crafter potrafi go zrobic</li>
 *   <li>pomaranczowy - itemu nie ma, ale zasilony piec ma na to recepture</li>
 *   <li>czerwony - niedostepny (brak stocku, craftingu i przepalania)</li>
 * </ul>
 *
 * <p>Tooltip pokazuje TYLKO to, co jest potrzebne: nazwe, stock, tempo
 * (minuta i godzina), powod braku dostepnosci - i preferencje "crafting czy
 * piec". Komunikaty o stanie, ktory DZIALA ("auto-crafting wlaczony",
 * "przepalanie wlaczone"), zostaly usuniete na zyczenie gracza.
 */
public class VeloceControllerScreen extends VeloceCreativeScreen {

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
    /**
     * Itemy, dla ktorych gracz woli PRZEPALANIE od craftingu.
     *
     * <p>Trzymane po stronie sieci (patrz ControllerPreferKindPKT), tutaj kopia
     * do rysowania podpowiedzi. Zmiana idzie od razu lokalnie (zeby klik
     * odpowiadal natychmiast), a serwer jest jedynym zrodlem prawdy przy
     * kolejnym otwarciu GUI.
     */
    private final Set<Item> furnacePreferred = new HashSet<>();

    private Filter filter = Filter.ALL;

    /**
     * Ile sztuk da sie jeszcze dorobic - ta sama wspolna logika i ten sam
     * kod rysujacy co w terminalu. Kontroler mial wlasna, uproszczona wersje,
     * ktora rysowala liczby bez skalowania czcionki - i dlatego ich nie bylo
     * widac albo wychodzily poza ikonke.
     */
    private final VeloceCraftableCounts craftableCounts = new VeloceCraftableCounts();

    private final List<Button> filterButtons = new ArrayList<>();

    // ---------- przeplyw (ile na sekunde przybywa / ubywa) ----------
    /**
     * Ruch w sztukach na sekunde - tylko dla itemow, ktore REALNIE sie ruszaja.
     *
     * <p>Netto, a osobno zysk i strata: sama roznica koncow jest zerowa, gdy
     * gracz wklada i wyciaga to samo, a wlasnie tak gracz sprawdza, czy
     * pomiar dziala (patrz {@link VeloceFlowTracker.Movement}).
     */
    private Map<Item, VeloceFlowTracker.Movement> flowPerMinute = new HashMap<>();
    private Map<Item, VeloceFlowTracker.Movement> flowPerHour = new HashMap<>();
    /** Co ile tickow dopytujemy serwer o swieze tempo. */
    private static final int FLOW_REQUEST_INTERVAL_TICKS = 20;
    /**
     * Ponizej tego tempa (w sztukach na sekunde) uznajemy, ze nic sie nie
     * dzieje - ten sam prog co w trackerze, zeby klient nie pokazywal ruchu,
     * ktorego serwer nie wyslal.
     */
    private static final float CHURN_MIN = 0.01f;
    private int flowRequestCooldown;

    public VeloceControllerScreen(LocalPlayer player, FeatureFlagSet enabledFeatures,
                                  boolean displayOperatorCreativeTab, BlockPos controllerPos,
                                  Map<Item, Long> stock, Set<Item> craftable,
                                  Set<Item> craftingEnabled, Set<Item> furnaceCraftable,
                                  boolean furnaceInNetwork, boolean furnacePowered,
                                  Set<Item> furnacePreferred) {
        super(player, enabledFeatures, displayOperatorCreativeTab);
        this.controllerPos = controllerPos;
        this.stock = new HashMap<>(stock);
        this.craftable = new HashSet<>(craftable);
        this.craftingEnabled = new HashSet<>(craftingEnabled);
        this.furnaceCraftable = new HashSet<>(furnaceCraftable);
        this.furnaceInNetwork = furnaceInNetwork;
        this.furnacePowered = furnacePowered;
        this.furnacePreferred.addAll(furnacePreferred);
    }

    // ---------- klasyfikacja itemu ----------

    private boolean hasStock(Item item) {
        return stock.getOrDefault(item, 0L) > 0;
    }

    /**
     * Powod, dla ktorego itemu NIE da sie uzyskac - albo {@code null}, gdy da
     * sie go uzyskac (jest na stocku, crafter go zrobi albo piec przepali).
     *
     * <p><b>Jedno zrodlo prawdy o dostepnosci.</b> Filtr, kolor ikony i
     * podpowiedz w tooltipie pytaja o to samo, wiec nie moga sie rozjechac.
     *
     * <p>Sprawdzamy {@code craftingEnabled}, a nie samo {@code craftable}:
     * item moze miec recepture, ktorej crafter nie wykonuje. Piec liczy sie
     * tylko ZASILONY - bez paliwa nic sie nie przepali.
     */
    private Blocker blocker(Item item) {
        if (hasStock(item) || craftingEnabled.contains(item)) {
            return null;
        }
        if (furnaceCanSmelt(item)) {
            return null;
        }
        if (craftable.contains(item)) {
            return new Blocker("gui.craftingveloce.controller.craftingOff", ChatFormatting.GRAY);
        }
        if (furnaceCraftable.contains(item)) {
            return furnaceInNetwork
                    ? new Blocker("gui.craftingveloce.controller.smeltingNoFuel", ChatFormatting.DARK_GRAY)
                    : new Blocker("gui.craftingveloce.controller.smeltingNoFurnace", ChatFormatting.DARK_GRAY);
        }
        return new Blocker("gui.craftingveloce.controller.notCraftable", ChatFormatting.RED);
    }

    /**
     * Czy item da sie uzyskac w PIECU.
     *
     * <p>Wymagamy DWOCH rzeczy: receptury pieca ORAZ zasilonego pieca w sieci.
     * Samo istnienie receptury nie wystarcza - bez paliwa nic sie nie przepali.
     */
    private boolean furnaceCanSmelt(Item item) {
        return furnacePowered && furnaceCraftable.contains(item);
    }

    private boolean isAvailable(Item item) {
        return blocker(item) == null;
    }

    /** Powod braku dostepnosci i kolor, w jakim go pokazac. */
    private record Blocker(String key, ChatFormatting color) {
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
        // Kolor "in hotbar" usuniety razem z informacja o hotbarze - gracz
        // nie chcial, zeby kontroler pokazywal, co ma pod reka.
        if (hasStock(item)) {
            return 0x770000AA;  // niebieski: na stocku
        }
        if (craftingEnabled.contains(item)) {
            return 0x77AAAA00;  // zolty: crafter to zrobi
        }
        if (furnaceCanSmelt(item)) {
            return 0x77AA5500;  // pomaranczowy: piec to przepali
        }
        return 0x77AA0000;      // czerwony: niedostepne
    }

    /**
     * Odswieza samo tempo przeplywu - BEZ przebudowy ekranu.
     *
     * <p>To jest powod, dla ktorego przeplyw ma osobny pakiet: gdyby serwer
     * co sekunde przysylal pelny obraz sieci i kazal tworzyc ekran od nowa,
     * gracz tracilby przy kazdym odswiezeniu wybrany filtr, pozycje przewijania
     * i wpisane wyszukiwanie.
     *
     * @param pos pozycja kontrolera, ktorego dotyczy pakiet - ignorujemy pakiety
     *            dla innego kontrolera, zeby nie podmieszac danych
     */
    public void updateFlow(net.minecraft.core.BlockPos pos,
                           Map<Item, VeloceFlowTracker.Movement> perMinute,
                           Map<Item, VeloceFlowTracker.Movement> perHour) {
        if (!controllerPos.equals(pos)) {
            return;
        }
        this.flowPerMinute = new HashMap<>(perMinute);
        this.flowPerHour = new HashMap<>(perHour);
    }

    /**
     * Dopytuje serwer o swieze tempo, dopoki ekran jest otwarty.
     *
     * <p>Zapytanie, a nie subskrypcja: serwer nie musi pamietac, kto patrzy,
     * wiec nie zostaje z nieaktualnym stanem, gdy klient wyjdzie z gry.
     */
    @Override
    public void containerTick() {
        super.containerTick();
        if (--flowRequestCooldown > 0) {
            return;
        }
        flowRequestCooldown = FLOW_REQUEST_INTERVAL_TICKS;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.craftingveloce.network.ControllerFlowRequestPKT(controllerPos));
        // Liczby "do dorobienia" zamawiamy tym samym rytmem - jak terminal.
        requestVisibleCounts(false);
    }

    // ---------- lifecycle ----------

    @Override
    protected void init() {
        super.init();   // baza: tryb creative, ukrycie slotow gracza, filtr itemow
        buildFilterButtons();
        craftableCounts.resetRequestState();
        requestVisibleCounts(true);
    }

    /** Zamawia liczby "do dorobienia" dla widocznej strony - jak terminal. */
    private void requestVisibleCounts(boolean force) {
        if (this.minecraft == null || this.minecraft.player == null || this.menu == null) {
            return;
        }
        craftableCounts.request(controllerPos, this.menu.slots,
                this::isPlayerInventorySlot, force);
    }

    /** Serwer przysyla policzone liczby - jak w terminalu. */
    public void updateCraftableCounts(Map<Item, Long> counts, boolean complete) {
        craftableCounts.update(counts, complete);
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

        // Dwie liczby - DOKLADNIE tak jak w terminalu, tym samym kodem:
        //   biala  = ile jest na stanie (prawy dolny rog),
        //   zolta  = ile da sie jeszcze dorobic, np. "+12" (lewy gorny rog).
        VeloceSlotOverlay.drawStock(graphics, this.font,
                stock.getOrDefault(item, 0L), slot.x, slot.y);
        VeloceSlotOverlay.drawCraftable(graphics, this.font,
                craftableCounts.get(item), slot.x, slot.y);
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
        addFlowLines(lines, item);
        addBlockerLines(lines, item);
        addPreferenceLines(lines, item);
        addUnavailableHint(lines, item);

        graphics.renderTooltip(this.font, lines, java.util.Optional.empty(), mouseX, mouseY);
    }

    /**
     * Ile tego itemu na sekunde przybywa albo ubywa.
     *
     * <p>Dwa okna, bo odpowiadaja na dwa rozne pytania: minuta mowi "co sie
     * dzieje TERAZ" (probka co 5 s), a godzina "jaki jest dlugofalowy bilans"
     * (probka co 60 s). Oba LICZA SIE OD RAZU - nie ma stanu "zbieram dane";
     * brak wpisu w mapie znaczy po prostu "nic sie nie ruszylo".
     */
    private void addFlowLines(List<Component> lines, Item item) {
        addFlowLine(lines, item, flowPerMinute,
                "gui.craftingveloce.controller.flow.minute", true);
        addFlowLine(lines, item, flowPerHour,
                "gui.craftingveloce.controller.flow.hour", false);
    }

    /**
     * Jedna linia tempa.
     *
     * <p>Kazdy stan ma INNY tekst, nie tylko inny kolor - gracz nie moze byc
     * zmuszony do rozrozniania zielonego od czerwonego, a znak liczby i tak
     * jest czescia napisu.
     *
     * <p>W oknie minuty dokladamy zysk i strate, ale TYLKO gdy oba sa
     * niezerowe. Wtedy samo netto klamie ("bez zmian"), bo item jest
     * jednoczesnie wkladany i wyciagany - a dokladnie tak gracz sprawdza, czy
     * pomiar w ogole dziala. W oknie godziny tego nie ma: probka raz na
     * minute i tak nie widzi takiego szarpania, wiec bylby to szum bez tresci.
     */
    private void addFlowLine(List<Component> lines, Item item,
                             Map<Item, VeloceFlowTracker.Movement> movements,
                             String windowKey, boolean showChurn) {
        VeloceFlowTracker.Movement m = movements.get(item);
        if (m == null) {
            lines.add(Component.translatable(windowKey,
                            Component.translatable("gui.craftingveloce.controller.flow.steady"))
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        Component net = Component.literal(signed(m.net()));
        if (showChurn && m.gain() >= CHURN_MIN && m.loss() >= CHURN_MIN) {
            net = Component.translatable("gui.craftingveloce.controller.flow.churn", net,
                    Component.literal(signed(m.gain())), Component.literal(signed(-m.loss())));
        }
        lines.add(Component.translatable(windowKey, net).withStyle(colorOf(m.net())));
    }

    /** Kolor liczby netto - jeden dla calego projektu, w jednym miejscu. */
    private static ChatFormatting colorOf(float net) {
        if (net > CHURN_MIN) {
            return ChatFormatting.GREEN;
        }
        return net < -CHURN_MIN ? ChatFormatting.RED : ChatFormatting.DARK_GRAY;
    }

    /**
     * Liczba ze znakiem: "+2.00/s" albo "-19.8/s".
     *
     * <p>Znak jest CZESCIA NAPISU, nie tylko kolorem - inaczej gracz
     * nierozrozniajacy barw nie wie, czy zapas rosnie, czy spada.
     */
    private static String signed(float rate) {
        if (Math.abs(rate) < CHURN_MIN) {
            return "0/s";
        }
        return (rate < 0f ? "-" : "+") + formatRate(Math.abs(rate)) + "/s";
    }

    /** Tempo z dokladnoscia, ktora ma sens: 2 miejsca ponizej 1/s, inaczej 1. */
    private static String formatRate(float rate) {
        return rate < 1f
                ? String.format(java.util.Locale.ROOT, "%.2f", rate)
                : String.format(java.util.Locale.ROOT, "%.1f", rate);
    }

    /**
     * Stock w sieci - ZAWSZE jako liczba.
     *
     * <p>Gracz chcial konkretna liczbe takze przy zerze ("0", a nie "none"):
     * "brak" i "0" to dla niego to samo, a liczba jest jednoznaczna.
     * Informacja "in hotbar" zostala usunieta - nie byla potrzebna.
     */
    private void addStockLines(List<Component> lines, Item item) {
        long n = stock.getOrDefault(item, 0L);
        lines.add(Component.translatable("gui.craftingveloce.controller.stock", n)
                .withStyle(n > 0 ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY));
    }

    /**
     * Dlaczego itemu NIE da sie zrobic - jedna linia i TYLKO gdy cos blokuje.
     *
     * <p>Gracz nie chcial komunikatow o stanie, ktory DZIALA ("auto-crafting
     * wlaczony", "przepalanie wlaczone") - to szum, bo dziala to, co ma
     * dzialac. Zostaje sam powod, gdy jest problem.
     */
    private void addBlockerLines(List<Component> lines, Item item) {
        Blocker blocker = blocker(item);
        if (blocker != null) {
            lines.add(Component.translatable(blocker.key()).withStyle(blocker.color()));
        }
    }

    /**
     * Preferencja "crafting czy piec" - tylko dla itemow, ktore MOZNA przepalic.
     *
     * <p>Pokazujemy ja wprost ("Preference: Crafting" / "Preference: Furnace"),
     * bo item moze miec obie drogi naraz i gracz musi widziec, ktora jest
     * pierwsza. Prawy klik przelacza.
     *
     * <p><b>Jeden kolor w obu stanach</b> - gracz tego chcial: linia ma czytac
     * sie jak USTAWIENIE, a nie jak alarm. Podpowiedz "kliknij prawym"
     * zniknela, bo prawy klik jest jedynym sensownym klikiem na tej ikonie.
     */
    private void addPreferenceLines(List<Component> lines, Item item) {
        if (!furnaceCraftable.contains(item)) {
            return;   // nie ma czego preferowac - jest tylko crafting
        }
        boolean furnace = furnacePreferred.contains(item);
        lines.add(Component.translatable(furnace
                        ? "gui.craftingveloce.controller.prefer.furnace"
                        : "gui.craftingveloce.controller.prefer.crafting")
                .withStyle(ChatFormatting.YELLOW));
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
        // PRAWY klawisz przelacza natomiast PREFERENCJE "crafting czy piec",
        // tak samo jak prawy klik w crafterze wybiera recepture.
        if (mouseButton != 1 || slot == null || !slot.hasItem()) {
            return;
        }
        if (isPlayerInventorySlot(slot)) {
            return;
        }
        Item item = slot.getItem().getItem();
        if (!furnaceCraftable.contains(item)) {
            // Tylko crafting - nie ma miedzy czym wybierac.
            return;
        }
        boolean preferFurnace = !furnacePreferred.contains(item);
        if (preferFurnace) {
            furnacePreferred.add(item);
        } else {
            furnacePreferred.remove(item);
        }
        PacketDistributor.sendToServer(
                new com.craftingveloce.network.ControllerPreferKindPKT(
                        controllerPos, item, preferFurnace));
        com.craftingveloce.util.VeloceLog.Gui.success(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "preferencja dla %s: %s", item,
                preferFurnace ? "FURNACE pierwszy" : "CRAFTING pierwszy");
    }

    @Override
    public void removed() {
        // Trzymany stos obsluguje teraz klasa bazowa (oddaje do ekwipunku
        // albo upuszcza). Wczesniej tutaj byl skasowany.
        super.removed();

    }
}
