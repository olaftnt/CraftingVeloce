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
 * <p>Tooltip pokazuje TYLKO to, co jest potrzebne: nazwe, tempo (minuta
 * i godzina) oraz - dla itemow z receptura pieca - preferencje "crafting czy
 * piec". Stocku w nim NIE ma (liczba jest na ikonie), nie ma powodow braku
 * dostepnosci ani komunikatow o stanie, ktory dziala - gracz kazal je usunac.
 *
 * <p>Tooltip budujemy w {@link #getTooltipFromContainerItem(ItemStack)}, czyli
 * podmieniamy liste linii wanilii, zamiast rysowac wlasny obok - inaczej
 * powstawaly DWA tooltipy naraz, a waniliowy dokleja na zakladce SEARCH nazwe
 * kategorii, ktora nachodzila na liczby.
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
    /**
     * Stock sieci - ODSWIEZANY co sekunde pakietem tempa.
     *
     * <p>Nie jest finalny od czasu, gdy kontroler dostaje swiezy stock razem
     * z tempem (patrz SyncControllerFlowPKT): przy otwartym GUI gracz moze
     * wyjac item ze skrzynki i liczba ma sie zmienic od razu.
     */
    private Map<Item, Long> stock;
    /**
     * Itemy, ktore crafter REALNIE zrobi (auto-crafting wlaczony) - zielone tlo.
     *
     * <p>To nie to samo co "ma recepture": {@code craftable} (receptura jest,
     * ale crafter moze miec ja wylaczona) nie jest juz potrzebne, bo gracz
     * kazal usunac teksty o powodach braku dostepnosci.
     */
    private final Set<Item> craftingEnabled;
    /** Itemy z receptura PIECA (smelting / blasting / smoking). */
    private final Set<Item> furnaceCraftable;
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

    // ---------- przeplyw (staly przyrost / ubytek) ----------
    /**
     * Tempo w sztukach na SEKUNDE - TYLKO dla itemow o stalym trendzie.
     *
     * <p>Serwer sam decyduje, co jest trendem (jednokierunkowy ruch, ktory
     * powtorzyl sie co najmniej dwa razy) i przysyla wylacznie takie itemy.
     * Brak wpisu = "stoi albo sie szarpie" = w tooltipie nie ma zadnej linii
     * tempa. Jedna liczba, dwie skale (na minute i na godzine) liczy klient.
     */
    private Map<Item, Float> flowRate = new HashMap<>();

    /**
     * Szerokosc guzika filtra.
     *
     * <p>Stala, bo pilnuje jej build (validate_filter_labels): etykieta musi
     * sie zmiescic. Poprzednie napisy ("Show all", "Not available") wychodzily
     * za przycisk i nachodzily na sasiada.
     */
    private static final int FILTER_BUTTON_W = 52;

    /** Co ile tickow dopytujemy serwer o swieze tempo. */
    private static final int FLOW_REQUEST_INTERVAL_TICKS = 20;
    /** Ponizej tego tempa (szt./s) nic nie pokazujemy - ten sam prog co w trackerze. */
    private static final float FLOW_MIN = 0.01f;
    private int flowRequestCooldown;

    public VeloceControllerScreen(LocalPlayer player, FeatureFlagSet enabledFeatures,
                                  boolean displayOperatorCreativeTab, BlockPos controllerPos,
                                  Map<Item, Long> stock,
                                  Set<Item> craftingEnabled, Set<Item> furnaceCraftable,
                                  boolean furnacePowered,
                                  Set<Item> furnacePreferred) {
        super(player, enabledFeatures, displayOperatorCreativeTab);
        this.controllerPos = controllerPos;
        this.stock = new HashMap<>(stock);
        this.craftingEnabled = new HashSet<>(craftingEnabled);
        this.furnaceCraftable = new HashSet<>(furnaceCraftable);
        this.furnacePowered = furnacePowered;
        this.furnacePreferred.addAll(furnacePreferred);
    }

    // ---------- klasyfikacja itemu ----------

    private boolean hasStock(Item item) {
        return stock.getOrDefault(item, 0L) > 0;
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

    /**
     * Czy item da sie uzyskac: jest na stocku, crafter go zrobi albo piec przepali.
     *
     * <p><b>JEDNO zrodlo prawdy o dostepnosci.</b> Filtr i kolor ikony pytaja
     * o to samo, wiec nie moga sie rozjechac. Kolejnosc jest ta sama w obu
     * miejscach: stock, potem crafter, potem piec.
     *
     * <p>Sprawdzamy {@code craftingEnabled}, a nie samo "ma recepture":
     * item moze miec recepture, ktorej crafter nie wykonuje.
     */
    private boolean isAvailable(Item item) {
        return hasStock(item) || craftingEnabled.contains(item) || furnaceCanSmelt(item);
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

    /**
     * Kolor tla ikony wg stanu dostepnosci.
     *
     * <p><b>Zielony = crafter to zrobi, zolty = piec to przepali.</b> Wczesniej
     * crafter mial zolty, a piec pomaranczowy; gracz chcial zielony dla
     * craftowania (tak jak w innych modach), a zolty zostawil dla "da sie
     * inaczej", czyli wlasnie dla pieca. Niebieski (stock) i czerwony (nie ma
     * i nie da sie zrobic) zostaja bez zmian.
     *
     * <p>Kolejnosc jest ta sama co w {@link #isAvailable}: stock, crafter, piec.
     */
    private int colorFor(Item item) {
        if (hasStock(item)) {
            return 0x770000AA;  // niebieski: na stocku
        }
        if (craftingEnabled.contains(item)) {
            return 0x7700AA00;  // zielony: crafter to zrobi
        }
        if (furnaceCanSmelt(item)) {
            return 0x77AAAA00;  // zolty: piec to przepali (inna droga)
        }
        return 0x77AA0000;      // czerwony: nie ma i nie da sie zrobic
    }

    /**
     * Odswieza tempo przeplywu i ZMIANE stocku - BEZ przebudowy ekranu.
     *
     * <p>To jest powod, dla ktorego przeplyw ma osobny pakiet: gdyby serwer
     * co sekunde przysylal pelny obraz sieci i kazal tworzyc ekran od nowa,
     * gracz tracilby przy kazdym odswiezeniu wybrany filtr, pozycje przewijania
     * i wpisane wyszukiwanie.
     *
     * <p><b>Stock przychodzi jako roznica, nie calosc</b> (patrz
     * {@code VeloceStockDeltas}): w duzej sieci pelny obraz co sekunde to
     * tysiace wpisow bez zmiany. Dlatego scalamy zmiany i usuwamy znikniete
     * itemy, a pelny zrzut (raz na minute) po prostu zastepuje mape.
     *
     * @param pos     pozycja kontrolera, ktorego dotyczy pakiet - ignorujemy
     *                pakiety dla innego kontrolera, zeby nie podmieszac danych
     * @param changed wpisy nowe albo o zmienionej liczbie (wartosci bezwzgledne)
     * @param removed itemy, ktorych w sieci juz nie ma
     * @param full    czy to pelny zrzut (zastap stock, nie scalaj)
     */
    public void updateFlow(net.minecraft.core.BlockPos pos,
                           Map<Item, Long> changed,
                           Set<Item> removed,
                           Map<Item, Float> rates,
                           boolean full) {
        if (!controllerPos.equals(pos)) {
            return;
        }
        if (full) {
            this.stock = new HashMap<>(changed);
        } else {
            this.stock.putAll(changed);
            for (Item item : removed) {
                this.stock.remove(item);
            }
        }
        this.flowRate = new HashMap<>(rates);
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
        // Jak w terminalu: puste sloty = brak zamowienia = brak liczb.
        ensureGridItems();
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
        int w = FILTER_BUTTON_W;
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

    }

    /**
     * Tooltip ikony: nazwa, tempo (minuta i godzina) i preferencja "crafting
     * czy piec" - dla itemow z receptura pieca.
     *
     * <p><b>BUG, ktory to naprawia (zgloszenie gracza).</b> Kontroler rysowal
     * WLASNY tooltip, a waniliowy rysowal sie obok - dwa naraz. Waniliowy na
     * zakladce SEARCH dokleja jeszcze nazwe kategorii ("Building Blocks"),
     * wiec napisy nachodzily na siebie, a kategoria przykrywala liczby.
     * Terminal robil to dobrze od poczatku: nadpisuje te metode, wiec rysuje
     * sie DOKLADNIE jedna lista linii - bez kategorii i bez tagow.
     *
     * <p><b>Stock zniknal z tooltipa</b> na zyczenie gracza: liczba jest juz
     * narysowana na ikonie (prawy dolny rog), wiec linia "Stock: N" byla
     * powtorzeniem.
     */
    @Override
    public List<Component> getTooltipFromContainerItem(ItemStack stack) {
        if (this.minecraft == null || this.minecraft.player == null || stack.isEmpty()) {
            return super.getTooltipFromContainerItem(stack);
        }
        Slot hovered = getSlotUnderMouse();
        if (hovered != null && isPlayerInventorySlot(hovered)) {
            return super.getTooltipFromContainerItem(stack);   // sloty gracza bez zmian
        }
        List<Component> lines = new ArrayList<>();
        lines.add(stack.getHoverName());
        addFlowLines(lines, stack.getItem());
        addPreferenceLines(lines, stack.getItem());
        return lines;
    }

    /**
     * Jedna linia tempa - tylko dla itemow o STAŁYM trendzie.
     *
     * <p><b>Czego gracz nie chcial.</b> Dwoch linii ("1 min:" i "1 hour:")
     * oraz linii "no change" przy kazdym itemie. Zamiast tego: jedna linia
     * z JEDNA liczba w dwoch skalach, i to tylko wtedy, gdy item naprawde ma
     * staly przyrost albo staly ubytek (serwer przysyla tylko takie itemy -
     * patrz VeloceFlowTracker.steadyRates). Gdy nic stalego sie nie dzieje,
     * nie ma tu zadnej linii.
     */
    private void addFlowLines(List<Component> lines, Item item) {
        Float rate = this.flowRate.get(item);
        if (rate == null || Math.abs(rate) < FLOW_MIN) {
            return;
        }
        // Jedna liczba, dwie skale: na minute i na godzine.
        lines.add(Component.translatable("gui.craftingveloce.controller.flow.rate",
                        Component.literal(signed(rate * 60f)),
                        Component.literal(signed(rate * 3600f)))
                .withStyle(rate > 0f ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    /**
     * Liczba ze znakiem, bez jednostki: "+15", "-19.8", "+1.2K".
     *
     * <p>Znak jest CZESCIA NAPISU, nie tylko kolorem - inaczej gracz
     * nierozrozniajacy barw nie wie, czy zapas rosnie, czy spada. Jednostke
     * ("/min", "/h") dodaje klucz jezykowy.
     *
     * <p>Format samej liczby zyje w {@link com.craftingveloce.util.VeloceFormat}
     * (jedno miejsce dla calego moda): bez zbednego ".0", bez wiodacego zera
     * ponizej jedynki i ze skrotem K/M powyzej tysiaca.
     */
    private static String signed(float rate) {
        return (rate < 0f ? "-" : "+")
                + com.craftingveloce.util.VeloceFormat.rate(Math.abs(rate));
    }

    /**
     * Czy item da sie zrobic OBIEMA drogami: crafterem i piecem.
     *
     * <p><b>BUG, ktory to naprawia (zgloszenie gracza).</b> Preferencje
     * pokazywalismy kazdemu itemowi z receptura pieca - takze szklu, ktore
     * powstaje WYLACZNIE w piecu i nie ma receptury craftingowej. Gracz widzial
     * wiec wybor "Preference: Crafting / Furnace" dla itemu, ktorego craftingiem
     * nie da sie zrobic, i slusznie pytal, po co ten wybor jest. Preferencja ma
     * sens tylko wtedy, gdy jest miedzy czym wybierac.
     *
     * <p>Sprawdzamy {@code craftingEnabled} (crafter realnie to zrobi), a nie
     * samo "ma recepture" - item z wylaczonym craftingiem tez ma tylko jedna
     * dostepna droge.
     */
    private boolean hasBothPaths(Item item) {
        return craftingEnabled.contains(item) && furnaceCraftable.contains(item);
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
        if (!hasBothPaths(item)) {
            return;   // jest tylko jedna droga - nie ma czego preferowac
        }
        boolean furnace = furnacePreferred.contains(item);
        lines.add(Component.translatable(furnace
                        ? "gui.craftingveloce.controller.prefer.furnace"
                        : "gui.craftingveloce.controller.prefer.crafting")
                .withStyle(ChatFormatting.YELLOW));
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
        if (!hasBothPaths(item)) {
            // Jedna droga (sam crafting albo sam piec) - nie ma miedzy czym
            // wybierac, wiec prawy klik nic nie robi (patrz hasBothPaths).
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
