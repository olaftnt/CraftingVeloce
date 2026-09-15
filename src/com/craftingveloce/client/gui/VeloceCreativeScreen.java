package com.craftingveloce.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Wspolna baza dla "fake creative" GUI Veloce (terminal, filter picker,
 * crafting table, controller).
 *
 * <p>Zbiera w jednym miejscu to, co wszystkie te ekrany robia tak samo,
 * zamiast powtarzac to w kazdej klasie:
 *
 * <ul>
 *   <li>wymuszenie trybu creative na czas otwarcia i przywrocenie po zamknieciu</li>
 *   <li>wylaczenie slotow gracza (i tak sie ich nie uzywa) i zamalowanie hotbara</li>
 *   <li><b>filtrowanie listy itemow</b> - pozwala usunac itemy, ktorych nie
 *       chcemy pokazywac, zeby nie zostawialy pustych dziur w siatce</li>
 *   <li><b>ukrycie niechcianych zakladek</b> (np. Operator Utilities,
 *       Saved Hotbars) - nadpisywane w podklasach przez {@link #tabFilter}</li>
 * </ul>
 */
public abstract class VeloceCreativeScreen extends CreativeModeInventoryScreen {

    /** Pole z lista itemow biezacej zakladki (zawiera tez puste wpisy). */
    private static Field itemsField;

    /** Pole itemow w SlotWrapper (do rozpoznania slotu gracza). */
    private static Field slotWrapperTargetField;

    static {
        try {
            itemsField = Class.forName(
                            "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$ItemPickerMenu")
                    .getDeclaredField("items");
            itemsField.setAccessible(true);
        } catch (Throwable t) {
            itemsField = null;
        }
        try {
            Class<?> wrapperClass = Class.forName(
                    "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$SlotWrapper");
            for (Field f : wrapperClass.getDeclaredFields()) {
                if (Slot.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    slotWrapperTargetField = f;
                    break;
                }
            }
        } catch (Throwable t) {
            slotWrapperTargetField = null;
        }
    }

    @Nullable
    private GameType modeBeforeOpen;

    /**
     * Refleksja rozwiazana RAZ, a nie przy kazdym odswiezeniu.
     *
     * <p>{@code getMethod}/{@code getDeclaredField}/{@code setAccessible} to
     * wzglednie drogie operacje (przegladanie hierarchii klas). Wczesniej
     * lecialy 20 razy na sekunde przez cala sesje z otwartym GUI.
     */
    private static java.lang.reflect.Method scrollToMethod;
    private static java.lang.reflect.Field scrollOffsFieldRef;
    private static boolean reflectionResolved;


    protected VeloceCreativeScreen(LocalPlayer player, FeatureFlagSet enabledFeatures,
                                   boolean displayOperatorCreativeTab) {
        super(player, enabledFeatures, displayOperatorCreativeTab);
    }

    // ------------------------------------------------------------------
    // Filtrowanie zawartosci - do nadpisania w podklasach
    // ------------------------------------------------------------------

    /**
     * Czy pokazac ten item w siatce. Zwrocenie false <b>usuwa item z listy</b>,
     * wiec pozostale zsuwaja sie i nie ma pustych dziur.
     */
    protected boolean acceptItem(ItemStack stack) {
        return true;
    }

    /**
     * Zakladki ukrywane we wszystkich naszych GUI.
     * Operator Utilities i Saved Hotbars sluza do administracji creative,
     * nie do sieci Veloce - gracz ich tu nie potrzebuje.
     */
    private static final Set<String> HIDDEN_TABS = Set.of(
            "operator", "hotbar", "saved_hotbars", "op_blocks", "op_items"
    );

    /**
     * Czy pokazac te zakladke creative. Domyslnie ukrywamy zakladki
     * administracyjne (operator utilities, saved hotbars).
     */
    protected boolean acceptTab(net.minecraft.world.item.CreativeModeTab tab) {
        try {
            var key = net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            if (key != null && HIDDEN_TABS.contains(key.getPath())) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Cykl zycia
    // ------------------------------------------------------------------

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
        suppressPlayerSlots();
        applyItemFilter();
    }

    /**
     * Nakłada nasz filtr na liste itemow menu.
     *
     * <p><b>Dlaczego tak, a nie przez nadpisanie metod vanilla.</b>
     * {@code CreativeModeInventoryScreen.refreshSearchResults()} i
     * {@code refreshCurrentTabContents()} sa PRYWATNE, wiec nie da sie ich
     * przechwycic dziedziczeniem. A to wlasnie one robia
     * {@code menu.items.clear()} i wypelniaja liste SUROWYMI
     * {@code getDisplayItems()}, kasujac wszystko, co odfiltrowalismy - przy
     * zmianie zakladki, przy wpisywaniu w wyszukiwarke i z {@code containerTick}.
     *
     * <p>Dlatego filtr jest nakladany z {@code containerTick} (wołanym co tick,
     * publicznym) i opiera sie na POROWNANIU ZE STANEM LISTY: jesli vanilla
     * wlasnie ja przebudowala, lista sie rozni od naszej wersji i filtr leci
     * od nowa. Dzieki temu dziala niezaleznie od tego, KTO i KIEDY ja nadpisal.
     */
    @Override
    public void containerTick() {
        super.containerTick();
        applyItemFilter();
    }

    /**
     * Usuwa z listy itemy odrzucone przez {@link #acceptItem}.
     *
     * <p>Vanilla buduje siatke z pol listy, wiec jesli pozostawimy w niej
     * odrzucone wpisy jako puste stosy, powstaną dziury. Kompaktujemy liste,
     * a ogon dopelniamy pustymi stosami - inaczej vanilla probowalby czytac
     * nieistniejace indeksy.
     */
    protected void applyItemFilter() {
        if (itemsField == null || this.menu == null) {
            return;
        }
        try {
            Object raw = itemsField.get(this.menu);
            if (!(raw instanceof NonNullList<?> list)) {
                return;
            }
            int total = list.size();
            List<ItemStack> kept = new ArrayList<>(total);
            for (Object o : list) {
                if (o instanceof ItemStack st && !st.isEmpty() && acceptItem(st)) {
                    kept.add(st);
                }
            }

            @SuppressWarnings("unchecked")
            NonNullList<ItemStack> items = (NonNullList<ItemStack>) list;

            // NIE ma tu "szybkiego wyjscia" i to jest celowe.
            //
            // BUG, ktory tu byl: porownywalismy zawartosc listy z pozadanym
            // wynikiem i wychodzilismy, gdy byly identyczne. Problem w tym, ze
            // vanilla (refreshSearchResults / refreshCurrentTabContents)
            // wypelnia liste SUROWYMI getDisplayItems() - a to sa TE SAME
            // instancje ItemStack, ktore same przechodza nasz filtr. Gdy zadna
            // z nich nie jest odfiltrowana, lista po przebudowie wyglada
            // IDENTYCZNIE jak przed nia, wiec uznawalismy "nic sie nie zmienilo"
            // i POMIJALISMY scrollTo.
            //
            // A to scrollTo przepisuje liste do CONTAINER, z ktorego czytaja
            // sloty. Bez niego CONTAINER zostawal z danymi z POPRZEDNIEGO
            // przefiltrowania - czyli dokladnie objaw zglaszany przez
            // uzytkownika: GUI pokazuje nieodfiltrowana liste, dopoki nie
            // ruszy sie scrollem (scroll wywoluje scrollTo sam).
            //
            // Wiec: zawsze przepisujemy liste i zawsze odswiezamy sloty.
            // Koszt jest znikomy (jedno przejscie po ~1.5 tys. wpisow), a to
            // jedyny sposob, zeby dzialalo niezaleznie od tego, co vanilla
            // zrobila z lista miedzy tickami.
            for (int i = 0; i < total; i++) {
                items.set(i, i < kept.size() ? kept.get(i) : ItemStack.EMPTY);
            }
            refreshSlotsFromItems();
        } catch (Throwable ignored) {
            // Refleksja moze sie nie udac przy zmianie wersji - wtedy po prostu
            // nie filtrujemy, GUI dalej dziala (z dziurami).
        }
    }

    /**
     * Przepisuje liste itemow do slotow menu.
     *
     * <p>Vanilla robi to samo w {@code ItemPickerMenu.scrollTo(float)}, wiec
     * wywolujemy te metode z biezaca pozycja przewijania.
     */
    private void refreshSlotsFromItems() {
        try {
            if (!reflectionResolved) {
                resolveReflection();
            }
            if (scrollToMethod == null) {
                return;
            }
            scrollToMethod.invoke(this.menu, currentScrollOffset());
        } catch (Throwable t) {
            // NIE po cichu. Cicha awaria w tym miejscu kosztowala nas juz raz
            // dluga diagnoze: filtr "wracal" po przewinieciu, a w logu nie bylo
            // ani sladu, ze scrollTo w ogole sie nie wykonuje.
            if (!slotRefreshFailed) {
                slotRefreshFailed = true;
                com.craftingveloce.util.VeloceLog.Gui.failure(
                        com.craftingveloce.util.VeloceLog.Side.CLIENT,
                        "could not refresh item slots after filtering - list will look "
                                + "unfiltered until scrolled: %s", t);
            }
        }
    }

    /** Czy blad odswiezania slotow zostal juz zaraportowany (raz wystarczy). */
    private static boolean slotRefreshFailed;

    /** Rozwiazuje refleksje raz na proces, nie raz na tick. */
    private void resolveReflection() {
        reflectionResolved = true;
        // scrollTo ZYJE W ItemPickerMenu, NIE w CreativeModeInventoryScreen.
        //
        // TO BYL PRAWDZIWY POWOD, dlaczego filtr "wracal" po przewinieciu.
        // Wczesniej szukalismy metody na klasie ekranu:
        //     CreativeModeInventoryScreen.class.getMethod("scrollTo", float.class)
        // a tam jej NIE MA - jest w zagnieżdżonej klasie ItemPickerMenu.
        // getMethod rzucal wiec NoSuchMethodException, scrollToMethod zostawalo
        // null, a refreshSlotsFromItems() po cichu nic nie robilo. Lista byla
        // filtrowana, ale sloty czytaly z CONTAINER, ktory nikt nie odswiezal -
        // az do momentu, gdy gracz ruszył scrollem (scroll sam wola scrollTo).
        //
        // Szukamy wiec po KLASIE MENU (this.menu), a nie po klasie ekranu.
        try {
            scrollToMethod = this.menu.getClass().getMethod("scrollTo", float.class);
            scrollToMethod.setAccessible(true);
        } catch (Throwable t) {
            scrollToMethod = null;
            com.craftingveloce.util.VeloceLog.Gui.failure(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "ItemPickerMenu.scrollTo not found - item filter cannot refresh slots: %s", t);
        }
        try {
            scrollOffsFieldRef = CreativeModeInventoryScreen.class
                    .getDeclaredField("scrollOffs");
            scrollOffsFieldRef.setAccessible(true);
        } catch (Throwable t) {
            scrollOffsFieldRef = null;
        }
    }

    /** Biezaca pozycja przewijania listy (pole 'scrollOffs' w vanilla). */
    private float currentScrollOffset() {
        try {
            if (!reflectionResolved) {
                resolveReflection();
            }
            if (scrollOffsFieldRef == null) {
                return 0.0f;
            }
            Object v = scrollOffsFieldRef.get(this);
            if (v instanceof Float fl) {
                return fl;
            }
        } catch (Throwable ignored) {
        }
        return 0.0f;
    }

    /**
     * Ukrywa zakladki odrzucone przez {@link #acceptTab}.
     *
     * <p>Robimy to przez nadpisanie {@link #renderTabButton} (nie rysujemy
     * odrzuconych) oraz {@link #checkTabClicked} (ignorujemy klikniecia).
     * Nie da sie tego zrobic przez usuniecie z listy, bo
     * {@code CreativeModeTabs.tabs()} zwraca liste niemodyfikowalna.
     */
    @Override
    protected void renderTabButton(net.minecraft.client.gui.GuiGraphics graphics,
                                   net.minecraft.world.item.CreativeModeTab tab) {
        if (!acceptTab(tab)) {
            return;
        }
        super.renderTabButton(graphics, tab);
    }

    @Override
    protected boolean checkTabClicked(net.minecraft.world.item.CreativeModeTab tab,
                                      double mouseX, double mouseY) {
        if (!acceptTab(tab)) {
            return false;
        }
        return super.checkTabClicked(tab, mouseX, mouseY);
    }

    /**
     * Prawy klik na ikonke zakladki = przelacz wszystkie itemy tej kategorii.
     *
     * <p>Jesli w kategorii jest cokolwiek wlaczone - wylaczamy wszystko.
     * Jesli nic nie jest wlaczone - wlaczamy wszystko, co da sie wlaczyc.
     * Dzieki temu jeden gest dziala jako "round robin": kolejne klikniecia
     * przelaczaja cala grupe tam i z powrotem.
     *
     * <p>Działa niezaleznie od tego, gdzie jestes na liscie (takze po
     * przewinieciu dlugiej zakladki moda), bo patrzymy na ikonke zakladki,
     * a nie na widoczne sloty.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && this.minecraft != null && this.minecraft.player != null) {
            net.minecraft.world.item.CreativeModeTab tab = tabUnderMouse(mouseX, mouseY);
            if (tab != null && acceptTab(tab)) {
                toggleWholeTab(tab);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Znajduje zakladke, ktorej ikonka jest pod kursorem. */
    @Nullable
    private net.minecraft.world.item.CreativeModeTab tabUnderMouse(double mouseX, double mouseY) {
        for (net.minecraft.world.item.CreativeModeTab tab
                : net.minecraft.world.item.CreativeModeTabs.tabs()) {
            try {
                if (super.checkTabClicked(tab, mouseX, mouseY)) {
                    return tab;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * Tooltip na ikonce zakladki - podpowiada, ze prawy klik przelacza
     * cala kategorie. Pokazywany tylko gdy podkursorem jest zakladka i gdy
     * w tej zakladce jest w ogole cos do przelaczenia.
     *
     * <p>Wywoływane z {@link #render} podklas. Vanilla nie rysuje zadnego
     * tooltipa dla zakladek, wiec dodajemy go sami.
     */
    protected void renderTabTooltip(net.minecraft.client.gui.GuiGraphics graphics,
                                    int mouseX, int mouseY) {
        if (this.minecraft == null) {
            return;
        }
        net.minecraft.world.item.CreativeModeTab tab = tabUnderMouse(mouseX, mouseY);
        if (tab == null || !acceptTab(tab)) {
            return;
        }
        // Sprawdzamy, czy w zakladce jest cokolwiek, co da sie przelaczyc.
        int toggleable = 0;
        boolean anyOn = false;
        for (ItemStack st : tab.getDisplayItems()) {
            if (st.isEmpty() || !isToggleable(st)) {
                continue;
            }
            toggleable++;
            if (isToggledOn(st.getItem())) {
                anyOn = true;
            }
        }
        if (toggleable == 0) {
            return;
        }

        List<Component> lines = new ArrayList<>();
        lines.add(tab.getDisplayName());
        lines.add(Component.translatable("gui.craftingveloce.category.hint")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        lines.add(Component.translatable(anyOn
                ? "gui.craftingveloce.category.willDisable"
                : "gui.craftingveloce.category.willEnable", toggleable)
                .withStyle(net.minecraft.ChatFormatting.GRAY));

        graphics.renderTooltip(this.font, lines,
                java.util.Optional.empty(), mouseX, mouseY);
    }

    /**
     * Przelacza wszystkie craftowalne itemy z danej zakladki.
     * Podklasy decyduja, co zrobic z pojedynczym itemem.
     */
    private void toggleWholeTab(net.minecraft.world.item.CreativeModeTab tab) {
        List<ItemStack> stacks = new ArrayList<>(tab.getDisplayItems());

        // Zbierz itemy, ktore w ogole kwalifikuja sie do przelaczenia.
        List<net.minecraft.world.item.Item> candidates = new ArrayList<>();
        boolean anyOn = false;
        for (ItemStack st : stacks) {
            if (st.isEmpty() || !isToggleable(st)) {
                continue;
            }
            candidates.add(st.getItem());
            if (isToggledOn(st.getItem())) {
                anyOn = true;
            }
        }
        if (candidates.isEmpty()) {
            return;
        }

        // Round robin: cokolwiek wlaczone -> wylacz wszystko; inaczej wlacz wszystko.
        boolean target = !anyOn;
        for (net.minecraft.world.item.Item it : candidates) {
            if (isToggledOn(it) != target) {
                applyToggle(it);
            }
        }
    }

    // ------------------------------------------------------------------
    // Przelaczanie pojedynczych itemow - do nadpisania w podklasach
    // ------------------------------------------------------------------

    /** Czy ten item w ogole mozna tu przelaczac. Domyslnie: nie. */
    protected boolean isToggleable(ItemStack stack) {
        return false;
    }

    /** Czy item jest aktualnie wlaczony. */
    protected boolean isToggledOn(net.minecraft.world.item.Item item) {
        return false;
    }

    /** Przelacza pojedynczy item (wysyla tez pakiet do serwera). */
    protected void applyToggle(net.minecraft.world.item.Item item) {
    }

    // ------------------------------------------------------------------
    // Sloty gracza
    // ------------------------------------------------------------------

    protected boolean isPlayerInventorySlot(Slot slot) {
        if (slot == null || this.minecraft == null || this.minecraft.player == null) {
            return false;
        }
        if (slot.container == this.minecraft.player.getInventory()) {
            return true;
        }
        if (slotWrapperTargetField != null) {
            try {
                Object target = slotWrapperTargetField.get(slot);
                if (target instanceof Slot ts && ts.container == this.minecraft.player.getInventory()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Zastepuje sloty gracza nieaktywnymi slotami poza ekranem. */
    /**
     * Czy zostawic hotbar gracza dzialajacy.
     *
     * <p>Domyslnie wylaczamy sloty gracza (te GUI sa "fake creative" i nie
     * sluza do przenoszenia itemow). Terminal tego nie chce - gracz musi moc
     * korzystac z hotbara, np. odkladac wyciagniete itemy. Podklasy
     * nadpisuja te metode zwracajac true.
     */
    /**
     * Slot zastepczy dla ukrytych slotow gracza.
     *
     * <p>Osobna klasa (a nie anonimowa) po to, zeby dalo sie go ROZPOZNAC przy
     * kolejnym wywolaniu - inaczej zawijalibysmy go w nieskonczonosc.
     */
    private static final class HiddenSlot extends Slot {
        HiddenSlot(net.minecraft.world.Container container, int index) {
            super(container, index, -10000, -10000);
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public boolean isHighlightable() {
            return false;
        }
    }

    protected boolean keepPlayerHotbar() {
        return false;
    }

    protected void suppressPlayerSlots() {
        if (this.menu == null || this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        for (int i = 0; i < this.menu.slots.size(); i++) {
            Slot s = this.menu.slots.get(i);

            // Juz ukryty - nie zawijamy go drugi raz.
            //
            // BUG, ktory tu byl: zastepczy slot zachowywal TEN SAM container,
            // wiec isPlayerInventorySlot() nadal rozpoznawal go jako slot gracza
            // i przy kolejnym init() zawijal go ponownie. A init() leci przy
            // KAZDYM rebuildWidgets() (np. po kazdym kliknieciu filtra
            // w kontrolerze), wiec lancuch wrapperow rosl bez ograniczen.
            if (s instanceof HiddenSlot) {
                continue;
            }
            if (isPlayerInventorySlot(s)) {
                // Hotbar zostawiamy, jesli podklasa tego chce.
                if (keepPlayerHotbar() && isHotbarSlot(s)) {
                    continue;
                }
                this.menu.slots.set(i, new HiddenSlot(s.container, s.getContainerSlot()));
            }
        }
    }

    /** Czy slot nalezy do paska szybkiego dostepu (9 slotow gracza). */
    protected boolean isHotbarSlot(Slot slot) {
        if (slot == null || this.minecraft == null || this.minecraft.player == null) {
            return false;
        }
        var inv = this.minecraft.player.getInventory();
        if (slot.container == inv) {
            int idx = slot.getContainerSlot();
            return idx >= 0 && idx < 9;
        }
        return false;
    }

    /** Czy to slot kosza (prawy dolny rog creative inventory). */
    protected static boolean isTrashSlot(Slot slot) {
        return slot != null && slot.x == 173 && slot.y == 112;
    }

    /** Zamalowuje pasek hotbara (jest nieuzywany w tych GUI). */
    protected void drawHotbarCover(net.minecraft.client.gui.GuiGraphics graphics, int color) {
        graphics.fill(this.leftPos + 8, this.topPos + 111,
                this.leftPos + 170, this.topPos + 130, color);
    }

    protected static Minecraft mc() {
        return Minecraft.getInstance();
    }

    /**
     * Niezawodne wyjscie z ekranu.
     *
     * <p>Te ekrany udaja creative inventory, wiec latwo o sytuacje, w ktorej
     * gracz nie ma jak wyjsc - brak reagowania na Esc albo zablokowany stan.
     * Wymuszamy zamkniecie po Esc i po klawiszu ekwipunku, niezaleznie od
     * tego, co robi vanilla.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Esc zawsze zamyka.
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        // Klawisz ekwipunku (domyslnie E) tez zamyka - gracz tego oczekuje.
        if (this.minecraft != null && this.minecraft.options != null
                && this.minecraft.options.keyInventory != null
                && this.minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        // Stos trzymany na kursorze NIE MOZE zginac.
        //
        // Wczesniej bylo tu `this.menu.setCarried(ItemStack.EMPTY)` - czyli
        // przedmiot, ktory gracz mial "na myszce" w chwili zamkniecia okna,
        // po prostu znikal. To samo powtarzaly nadpisania w kontrolerze
        // i pickerze filtrow, a ekran craftera dziedziczyl to z tej klasy.
        // Tylko terminal robil to poprawnie.
        //
        // Teraz robimy to raz, tutaj: probujemy wlozyc do ekwipunku, a jak sie
        // nie zmiesci - upuszczamy do swiata (zamiast skasowac).
        if (this.minecraft != null && this.minecraft.player != null && this.menu != null
                && !this.menu.getCarried().isEmpty()) {
            ItemStack carried = this.menu.getCarried();
            this.menu.setCarried(ItemStack.EMPTY);
            if (!this.minecraft.player.getInventory().add(carried)) {
                this.minecraft.player.drop(carried, false);
            }
        }
        super.removed();
        if (this.modeBeforeOpen != null && this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.setLocalMode(this.modeBeforeOpen);
            this.modeBeforeOpen = null;
        }
    }
}
