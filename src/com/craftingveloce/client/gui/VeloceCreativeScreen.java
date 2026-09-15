package com.craftingveloce.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.client.gui.CreativeTabsScreenPage;
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

        // KOLEJNOSC JEST KRYTYCZNA. Vanilla {@code selectTab(CreativeModeTab)}
        // (dla zakladki INVENTORY) robi {@code menu.slots.clear()} i odbudowuje
        // sloty od zera - czyli KASUJE nasze ukrywanie slotow gracza.
        //
        // BUG, ktory tu byl: suppressPlayerSlots() latalo PRZED
        // restoreViewState(), a to wlasnie restoreViewState wybiera zakladke
        // (przez selectTab). Efekt: nasze ukrywanie bylo natychmiast kasowane,
        // a sloty ekwipunku gracza wracaly do GUI.
        //
        // Teraz: najpierw zakladka, potem ukrywanie.
        restoreViewState();
        disableVanillaTrashSlot();
        suppressPlayerSlots();
        applyItemFilter();
        // Dopiero teraz, gdy zakladka i fraza sa ustawione - inaczej lista
        // bywa pusta przy pierwszym otwarciu (zakladka INVENTORY).
        VeloceTerminalViewState.refreshContents(this);
        applyItemFilter();
    }

    /**
     * Klucz, pod ktorym zapamietujemy widok tego ekranu.
     *
     * <p>Domyslnie {@code null} = nie pamietamy nic. Terminal zwraca pozycje
     * swojego bloku, dzieki czemu KAZDY terminal ma wlasna zakladke, wlasna
     * fraze wyszukiwania i wlasne przewiniecie - niezaleznie od creative
     * inventory i od siebie nawzajem.
     */
    protected Object viewStateKey() {
        return null;
    }

    /**
     * Czy ten ekran pamieta swoja zakladke.
     *
     * <p>Domyslnie tak, per {@link #viewStateKey()}. Ekran wyboru filtra
     * zwraca {@code false}, bo ma zawsze otwierac sie na pierwszej zakladce.
     */
    protected boolean rememberTab() {
        return true;
    }

    /**
     * Zakladka, na ktorej ekran otwiera sie bez zapamietanej.
     *
     * <p>{@code null} = pierwsza dostepna (lewy gorny rog).
     */
    @Nullable
    protected CreativeModeTab defaultTab() {
        return null;
    }

    /**
     * Zakladka, ktora byla wybrana ZANIM ten ekran sie otworzyl.
     *
     * <p>Vanilla trzyma wybrana zakladke w polu {@code private static
     * selectedTab}, wspolnym dla calego klienta. Nasz ekran ja zmienia, wiec
     * po zamknieciu trzeba przywrocic poprzednia - inaczej zwykle creative
     * inventory otwieraloby sie na zakladce z terminala.
     */
    @Nullable
    private CreativeModeTab tabBeforeOpen;
    private boolean tabBeforeOpenCaptured;

    /**
     * Czy zapamietany widok zostal juz zastosowany w tym otwarciu ekranu.
     *
     * <p><b>Po co.</b> {@code init()} leci nie tylko przy otwarciu, ale tez przy
     * kazdym {@code rebuildWidgets()} (np. po kliknieciu filtra w kontrolerze)
     * i przy zmianie rozmiaru okna. Bez tej blokady kazde takie zdarzenie
     * PRZYWRACALO widok zapisany przy ostatnim ZAMKNIECIU ekranu.
     *
     * <p>Objaw byl konkretny: gracz zmienial zakladke (albo przewijal, albo
     * wpisywal fraze), klikal przycisk filtra - i widok przeskakiwal z powrotem
     * do miejsca sprzed zamkniecia. To samo przy kazdym resize okna.
     */
    private boolean viewStateApplied;

    /** Przywraca zapamietany widok (zakladka, fraza, przewiniecie). */
    private void restoreViewState() {
        Object key = viewStateKey();

        // Zapamietujemy zakladke creative TYLKO raz, przy pierwszym init().
        // init() leci takze przy rebuildWidgets (np. po kliknieciu filtra),
        // a wtedy "poprzednia" bylaby juz nasza zakladka.
        if (!tabBeforeOpenCaptured) {
            tabBeforeOpenCaptured = true;
            tabBeforeOpen = VeloceTerminalViewState.currentTab();
        }

        // Widok stosujemy TYLKO raz na otwarcie ekranu - patrz viewStateApplied.
        if (viewStateApplied) {
            return;
        }
        viewStateApplied = true;

        // Ekran bez wlasnej pamieci (key == null) i jednoczesnie pamietajacy
        // zakladke nic nie zmienia - to zwykly creative, ma zostac jak jest.
        if (key == null && rememberTab()) {
            return;
        }

        CreativeModeTab tab;
        if (key != null && rememberTab()) {
            tab = VeloceTerminalViewState.findTab(VeloceTerminalViewState.savedTab(key));
            // Brak zapamietanej zakladki (pierwsze otwarcie) albo zapamietana
            // juz nie istnieje - schodzimy na domyslna.
            //
            // BUG, ktory tu byl: przy braku zapisanej zakladki findTab() zwracalo
            // null i CALE ustawianie bylo pomijane. Ekran zostawal wiec na
            // wspolnej, statycznej zakladce vanilla - a jesli byla to INVENTORY
            // (Survival Inventory), crafter pokazywal zakladke, ktora sam ukrywa
            // i ktorej nie pozwala kliknac.
            if (tab == null) {
                tab = defaultTab() != null ? defaultTab() : firstAcceptedTab();
            }
        } else {
            // Nie pamietamy - zawsze pierwsza dostepna (lewy gorny rog).
            // To dotyczy takze pickera filtra, ktory nie ma klucza, a ma
            // zawsze otwierac sie od poczatku listy.
            tab = defaultTab() != null ? defaultTab() : firstAcceptedTab();
        }
        // Zakladki, ktorych u nas nie ma (np. ukryte), nie przywracamy.
        if (tab != null && acceptTab(tab)) {
            VeloceTerminalViewState.applyTab(this, tab);
            // ...i przewin na STRONE, na ktorej ta zakladka lezy - inaczej
            // przy duzej liczbie zakladek wybrana jest niewidoczna.
            restoreTabPage(tab);
        }

        // Fraza i przewiniecie tylko dla ekranow z wlasnym kluczem.
        if (key == null) {
            return;
        }
        VeloceTerminalViewState.applySearch(this, VeloceTerminalViewState.savedSearch(key));
        Float scroll = VeloceTerminalViewState.savedScroll(key);
        if (scroll != null) {
            VeloceTerminalViewState.applyScroll(this, scroll);
        }
    }

    /** Zapisuje widok, zeby nastepne otwarcie wrocilo w to samo miejsce. */
    private void saveViewState() {
        Object key = viewStateKey();
        if (key == null || !rememberTab()) {
            return;
        }
        VeloceTerminalViewState.save(key,
                VeloceTerminalViewState.currentTab(),
                VeloceTerminalViewState.currentSearch(this),
                VeloceTerminalViewState.currentScroll(this));
    }

    /**
     * Przywraca zakladke, ktora byla wybrana przed otwarciem tego ekranu.
     *
     * <p>Bez tego zwykle creative inventory otwieraloby sie na zakladce
     * z terminala (albo craftera, kontrolera...), bo vanilla trzyma ja
     * w polu statycznym wspolnym dla calego klienta. Kazdy nasz ekran ma
     * wlasny zapamietany stan, a creative ma swoj - i te stany musza byc
     * calkowicie od siebie niezalezne.
     */
    private void restoreCreativeTab() {
        if (tabBeforeOpen == null || !acceptTab(tabBeforeOpen)) {
            return;
        }
        VeloceTerminalViewState.applyTab(this, tabBeforeOpen);
        restoreTabPage(tabBeforeOpen);
    }

    /**
     * Ustawia STRONE zakladek tak, zeby zawierala podana zakladke.
     *
     * <p><b>Problem.</b> Gdy zakladek jest duzo, NeoForge dzieli je na strony
     * z przyciskami &lt; &gt;. Strona jest wybierana TYLKO w {@code init()},
     * i to na podstawie zakladki, ktora byla wybrana W TYM MOMENCIE:
     * <pre>
     *   this.currentPage = pages.stream()
     *           .filter(page -&gt; page.getVisibleTabs().contains(selectedTab))
     *           .findFirst().orElse(this.currentPage);
     * </pre>
     * (odczytane z bajtkodu patchowanej klasy NeoForge).
     *
     * <p>A nasze {@code restoreViewState()} przywraca zapamietana zakladke
     * przez {@code selectTab} DOPIERO PO {@code super.init()} - a
     * {@code selectTab} strony NIE zmienia. Efekt: {@code currentPage}
     * zostawala na stronie starej zakladki, wiec przywrocona zakladka byla
     * na innej stronie i nie bylo jej widac. Uzytkownik widzial poprawnie
     * zapamietana zakladke, ale nie widzial jej na ekranie.
     *
     * <p><b>Rozwiazanie.</b> Po wyborze zakladki odtwarzamy te sama logike,
     * ktorej uzywa NeoForge: znajdujemy strone zawierajaca te zakladke
     * i ustawiamy ja jako biezaca. Nie zapisujemy numeru strony osobno, bo
     * numer strony nie jest stabilny (zmienia sie, gdy dojdzie mod z nowymi
     * zakladkami) - a zakladka jest. Dzieki temu zawsze trafiamy na te sama
     * strone co przed zamknieciem, i zawsze widac wybrana zakladke.
     */
    protected void restoreTabPage(CreativeModeTab tab) {
        if (tab == null) {
            return;
        }
        try {
            for (CreativeTabsScreenPage page : tabPages()) {
                if (page.getVisibleTabs().contains(tab)) {
                    if (getCurrentPage() != page) {
                        setCurrentPage(page);
                    }
                    return;
                }
            }
        } catch (Throwable t) {
            if (!tabPageFailureLogged) {
                tabPageFailureLogged = true;
                com.craftingveloce.util.VeloceLog.Gui.failure(
                        com.craftingveloce.util.VeloceLog.Side.CLIENT,
                        "nie moge ustawic strony zakladek: %s", t);
            }
        }
    }

    /** Czy juz logowalismy awarie obslugi stron zakladek. */
    private boolean tabPageFailureLogged;

    /**
     * Lista stron zakladek.
     *
     * <p>Pole jest prywatne i NeoForge nie daje publicznego dostepu do CALEJ
     * listy (tylko {@code getCurrentPage()}), wiec czytamy je refleksja.
     * Sam typ strony jest dostepny w kompilacji, wiec dalej pracujemy na nim
     * normalnie, bez refleksji.
     */
    private static java.lang.reflect.Field tabPagesField;
    private static boolean tabPagesResolveTried;

    @SuppressWarnings("unchecked")
    private java.util.List<CreativeTabsScreenPage> tabPages() {
        if (!tabPagesResolveTried) {
            tabPagesResolveTried = true;
            try {
                tabPagesField = CreativeModeInventoryScreen.class.getDeclaredField("pages");
                tabPagesField.setAccessible(true);
            } catch (Throwable t) {
                tabPagesField = null;
            }
        }
        if (tabPagesField == null) {
            return java.util.List.of();
        }
        Object raw = null;
        try {
            raw = tabPagesField.get(this);
        } catch (Throwable ignored) {
            return java.util.List.of();
        }
        return raw instanceof java.util.List<?> list
                ? (java.util.List<CreativeTabsScreenPage>) list
                : java.util.List.of();
    }

    /** Pierwsza zakladka, ktora u nas przechodzi filtr (lewy gorny rog). */
    @Nullable
    protected CreativeModeTab firstAcceptedTab() {
        for (CreativeModeTab tab : net.minecraft.world.item.CreativeModeTabs.tabs()) {
            if (acceptTab(tab)) {
                return tab;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Slot "odkladania do sieci" na miejscu vanillaowego kosza
    // ------------------------------------------------------------------

    /**
     * Wylacza vanillaowy tooltip "Destroy Item".
     *
     * <p><b>Dlaczego w ogole.</b> Vanilla trzyma slot kosza w prywatnym polu
     * {@code destroyItemSlot} i w swoim {@code render()} rysuje na jego
     * podstawie wlasny tooltip - ZUPELNIE z pominięciem
     * {@code getTooltipFromContainerItem}, wiec nadpisanie samego tooltipa nic
     * nie dawalo (potwierdzone w bajtkocie):
     * <pre>
     *   if (destroyItemSlot != null
     *           &amp;&amp; selectedTab.getType() == Type.INVENTORY
     *           &amp;&amp; isHovering(...)) {
     *       renderTooltip(font, TRASH_SLOT_TOOLTIP, mouseX, mouseY);
     *   }
     * </pre>
     * Ustawienie pola na {@code null} wylacza caly ten blok.
     *
     * <p><b>DLACZEGO ZERUJEMY TO CO KLATKE.</b> To byl prawdziwy powod, dla
     * ktorego "Destroy Item" nie znikal mimo zerowania w {@code init()}.
     * Pole jest przypisywane w metodzie <b>{@code selectTab(CreativeModeTab)}</b>
     * (nie w {@code init()}): gdy wybrana zakladka jest typu INVENTORY, vanilla
     * tworzy tam NOWY slot kosza i dodaje go do menu.
     *
     * <p>A nasz {@code restoreViewState()} wola {@code selectTab} przy KAZDYM
     * otwarciu ekranu (zeby przywrocic zapamietana zakladke). Czyli kolejnosc
     * byla taka:
     * <pre>
     *   super.init()            - vanilla tworzy destroyItemSlot
     *   disableVanillaTrashSlot - my zerujemy pole
     *   restoreViewState()      - selectTab OD TWARZA destroyItemSlot
     * </pre>
     * i tooltip wracal. Zerowanie przed kazdym {@code super.render()} zamyka
     * te luke niezaleznie od tego, co i kiedy odtworzy pole.
     *
     * <p><b>Dlaczego po TYPIE, a nie po nazwie.</b> Refleksja po nazwie jest
     * krucha (w srodowisku produkcyjnym nazwy moga byc zmapowane inaczej),
     * a jej awaria byla wczesniej polykana po cichu. Szukamy wiec jedynego
     * NIEstatycznego pola typu {@code Slot} - {@code originalSlots} to lista,
     * wiec nie pasuje.
     */
    private static java.lang.reflect.Field trashSlotField;
    private static boolean trashSlotResolveTried;
    private boolean trashSlotMissingLogged;

    protected void disableVanillaTrashSlot() {
        if (!trashSlotResolveTried) {
            trashSlotResolveTried = true;
            try {
                for (java.lang.reflect.Field f : CreativeModeInventoryScreen.class.getDeclaredFields()) {
                    if (f.getType() != net.minecraft.world.inventory.Slot.class) {
                        continue;
                    }
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    f.setAccessible(true);
                    trashSlotField = f;
                    break;
                }
            } catch (Throwable t) {
                trashSlotField = null;
            }
        }
        if (trashSlotField == null) {
            if (!trashSlotMissingLogged) {
                trashSlotMissingLogged = true;
                com.craftingveloce.util.VeloceLog.Gui.failure(
                        com.craftingveloce.util.VeloceLog.Side.CLIENT,
                        "nie znaleziono pola slotu kosza - vanilla 'Destroy Item' "
                                + "moze byc widoczny");
            }
            return;
        }
        try {
            // Sprawdzamy najpierw, bo set() na tym samym obiekcie co klatke
            // to niepotrzebna praca; pole i tak czesto jest juz null.
            if (trashSlotField.get(this) != null) {
                trashSlotField.set(this, null);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Czy patrzymy na zakladke Survival Inventory.
     *
     * <p>Tylko tam vanilla pokazuje swoj kosz (warunek w jej {@code render()}:
     * {@code selectedTab.getType() == Type.INVENTORY}), wiec tylko tam ma sens
     * nasz slot odkladania. W pozostalych zakladkach tego miejsca po prostu
     * nie ma i rysowanie tam strzalki wygladalo jak blad.
     */
    protected boolean isSurvivalInventoryTab() {
        CreativeModeTab tab = VeloceTerminalViewState.currentTab();
        return tab != null && tab.getType() == CreativeModeTab.Type.INVENTORY;
    }

    /**
     * Slot "odkladania" jest na miejscu vanillaowego kosza (173, 112).
     *
     * <p>Numeru nie bierzemy z sufitu: to pozycja, ktora vanilla sama ustawia
     * dla {@code destroyItemSlot}, potwierdzona w bajtkodzie.
     */
    protected boolean isStoreSlot(Slot slot) {
        return slot != null && slot.x == 173 && slot.y == 112;
    }

    /**
     * Rysuje strzalke "do sieci" na slocie odkladania.
     *
     * <p>Rysujemy to w {@code render()} PO {@code super}, a nie w
     * {@code renderSlot}, zeby miec pewnosc, ze zaslonimy krzyzyk wypalony
     * w teksturze creative inventory.
     */
    private void drawStoreSlotIcon(GuiGraphics graphics) {
        if (!isSurvivalInventoryTab()) {
            return;   // poza Survival Inventory to miejsce nie istnieje
        }
        int x = this.leftPos + 173;
        int y = this.topPos + 112;

        // Tlo slotu - zaslania krzyzyk wypalony w teksturze creative inventory.
        graphics.fill(x, y, x + 16, y + 16, 0xFFC6C6C6);
        graphics.fill(x, y, x + 16, y + 1, 0xFF8B8B8B);
        graphics.fill(x, y, x + 1, y + 16, 0xFF8B8B8B);

        drawArrowRight(graphics, x + 3, y + 4);
    }

    /**
     * Strzalka w prawo: "odloz to do sieci".
     *
     * <p>Rysowana jako plaska figura (plaszcz + trojkatny gro), zeby byla
     * czytelna w 16-pikselowym slocie. Poprzednia wersja skladala gro
     * z pojedynczych pikseli po przekatnej, co wygladalo jak przypadkowe
     * kleksy.
     */
    private static void drawArrowRight(GuiGraphics graphics, int x, int y) {
        int color = 0xFF2E7D32;      // ciemna zielen - "siec"
        int shadow = 0xFF1B5E20;

        // Plaszcz strzalki: 2 px wysokosci.
        graphics.fill(x, y + 3, x + 5, y + 5, color);
        graphics.fill(x, y + 5, x + 5, y + 6, shadow);

        // Gro: trojkat o podstawie 7 px, zwężajacy sie do czubka w prawo.
        for (int i = 0; i < 4; i++) {
            int half = 3 - i;
            graphics.fill(x + 4 + i, y + 4 - half, x + 5 + i, y + 5 + half, color);
        }
        // Cien pod grotem - tylko dolna krawedz, dla glebi.
        for (int i = 0; i < 4; i++) {
            int half = 3 - i;
            graphics.fill(x + 4 + i, y + 4 + half, x + 5 + i, y + 5 + half, shadow);
        }
    }

    /** Tooltip slotu odkladania - zamiast vanillaowego "Destroy Item". */
    private void drawStoreSlotTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isSurvivalInventoryTab()) {
            return;
        }
        if (!this.isHovering(173, 112, 16, 16, mouseX, mouseY)) {
            return;
        }
        // DWIE linie jako osobne wpisy - to gwarantuje, ze tooltip jest waski
        // i ze NIE zamieni sie w jedna dluga linie.
        java.util.List<net.minecraft.util.FormattedCharSequence> lines = java.util.List.of(
                Component.translatable("gui.craftingveloce.terminal.storeSlot").getVisualOrderText(),
                Component.translatable("gui.craftingveloce.terminal.storeHint")
                        .withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText());

        // WLASNY POSITIONER: zawsze po PRAWEJ stronie kursora.
        //
        // BUG, ktory to naprawia: domyslny positioner Minecrafta decyduje
        // o stronie na podstawie tego, czy tooltip sie MIESCI. Gdy sie nie
        // miescil, ODWRACAL go na lewo od myszki. Vanilla "Destroy Item" byl
        // krotki i miescil sie po prawej, a nasz dluzszy tooltip ladowal po
        // lewej - stad wrazenie dwoch roznych tooltipow.
        //
        // Teraz strona jest ustalona: +12 px w prawo od kursora (tak samo jak
        // robi to vanilla dla krotkich tooltipow). Gdy zabraknie miejsca przy
        // krawedzi ekranu, tooltip zostaje DOciagniety do krawedzi - ale
        // NADAL po prawej stronie kursora, nigdy po lewej.
        graphics.renderTooltip(this.font, lines, TOOLTIP_RIGHT_OF_CURSOR, mouseX, mouseY);
    }

    /**
     * Positioner tooltipa: zawsze na prawo od kursora.
     *
     * <p>Kolejnosc argumentow pochodzi z interfejsu:
     * {@code (screenWidth, screenHeight, mouseX, mouseY, tooltipWidth, tooltipHeight)}.
     */
    private static final net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner
            TOOLTIP_RIGHT_OF_CURSOR = (screenWidth, screenHeight, mouseX, mouseY,
                                       tooltipWidth, tooltipHeight) -> {
        // 12 px w prawo i 12 px w gore - dokladnie jak vanilla.
        int x = mouseX + 12;
        int y = mouseY - 12;

        // Przy krawedzi ekranu dosuwamy do brzegu, ale NIE przenosimy na lewa
        // strone kursora. To jest cala roznica wobec domyslnego positionera.
        if (x + tooltipWidth > screenWidth - 4) {
            x = Math.max(4, screenWidth - tooltipWidth - 4);
        }
        if (y + tooltipHeight > screenHeight - 4) {
            y = Math.max(4, screenHeight - tooltipHeight - 4);
        }
        return new org.joml.Vector2i(x, y);
    };

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // PRZED super.render(): to wlasnie tam vanilla sprawdza destroyItemSlot
        // i rysuje "Destroy Item". Pole bywa odtworzone przez selectTab, wiec
        // zerujemy je tuz przed rysowaniem.
        disableVanillaTrashSlot();

        super.render(graphics, mouseX, mouseY, partialTick);

        // Po super, wiec na wierzchu wszystkiego co narysowala vanilla.
        drawStoreSlotIcon(graphics);
        drawStoreSlotTooltip(graphics, mouseX, mouseY);
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

        // ZMIANA ZAKLADKI PRZEZ GRACZA.
        //
        // Gracz moze kliknac inna zakladke, a vanilla robi to samo co przy
        // otwarciu: {@code selectTab} czysci {@code menu.slots} i odbudowuje je
        // od zera. To kasuje i nasze ukrywanie slotow gracza, i wylaczenie
        // kosza. Wykrywamy wiec zmiane zakladki i nakladamy nasze poprawki
        // od nowa.
        CreativeModeTab current = VeloceTerminalViewState.currentTab();
        if (current != lastSeenTab) {
            lastSeenTab = current;
            suppressPlayerSlots();
            disableVanillaTrashSlot();
        }

        applyItemFilter();
    }

    /** Zakladka widziana w poprzednim ticku - do wykrywania zmian. */
    @Nullable
    private CreativeModeTab lastSeenTab;

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

    /**
     * Zbiera sloty zbroi i tarczy z menu ekwipunku gracza.
     *
     * <p>Creatiive inventory ich nie ma, ale zakladka Survival Inventory
     * pokazuje prawdziwe menu gracza - i tam one sa. Trzeba je odroznic od
     * zwyklych slotow ekwipunku, bo inaczej zostana ukryte.
     */
    protected java.util.Set<Slot> collectArmorAndShieldSlots() {
        java.util.Set<Slot> out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (this.minecraft == null || this.minecraft.player == null) {
            return out;
        }
        var inv = this.minecraft.player.inventoryMenu;
        if (inv == null) {
            return out;
        }
        int size = inv.slots.size();
        var startF = reflectStaticInt(
                "net.minecraft.world.inventory.InventoryMenu", "ARMOR_SLOT_START");
        var countF = reflectStaticInt(
                "net.minecraft.world.inventory.InventoryMenu", "ARMOR_SLOT_COUNT");
        Integer shield = reflectStaticInt(
                "net.minecraft.world.inventory.InventoryMenu", "SHIELD_SLOT");
        if (startF == null || countF == null) {
            return out;
        }
        for (int i = startF; i < startF + countF && i < size; i++) {
            out.add(inv.slots.get(i));
        }
        if (shield != null && shield >= 0 && shield < size) {
            out.add(inv.slots.get(shield));
        }
        return out;
    }

    /** Czyta statyczna stala int z klasy vanilla (null gdy sie nie uda). */
    @Nullable
    private static Integer reflectStaticInt(String className, String fieldName) {
        try {
            var f = Class.forName(className).getField(fieldName);
            return f.getInt(null);
        } catch (Throwable t) {
            return null;
        }
    }

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

    /**
     * Czy zostawic hotbar gracza dzialajacy.
     *
     * <p>Domyslnie wylaczamy sloty gracza (te GUI sa "fake creative" i nie
     * sluza do przenoszenia itemow). Terminal tego nie chce - gracz musi moc
     * korzystac z hotbara, np. odkladac wyciagniete itemy. Podklasy
     * nadpisuja te metode zwracajac true.
     */
    protected boolean keepPlayerHotbar() {
        return false;
    }

    /** Zastepuje sloty gracza nieaktywnymi slotami poza ekranem. */
    protected void suppressPlayerSlots() {
        if (this.menu == null || this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        // ===== REGRESJA, ktora to naprawia =====
        //
        // Na zakladce SURVIVAL INVENTORY ekwipunek gracza to CALA ZAWARTOSC
        // tego ekranu. Ukrycie go daje PUSTE GUI - zadnych slotow, nic nie
        // da sie kliknac. Dokladnie to sie stalo, gdy suppression zaczela
        // dzialac PO wyborze zakladki: wczesniej vanilla selectTab odbudowywala
        // sloty i przypadkiem je "odslaniala".
        //
        // Teraz regula jest jawna: ukrywamy sloty gracza TYLKO na zakladkach
        // z siatka itemow (tam zaslaniaja nasz uklad), a na Survival
        // Inventory zostawiamy je w spokoju.
        if (isSurvivalInventoryTab()) {
            return;
        }
        // Sloty zbroi i tarczy w zakladce Survival Inventory.
        //
        // BUG: te sloty maja kontener gracza, wiec isPlayerInventorySlot()
        // kwalifikowalo je do ukrycia - i znikaly (zostawaly tylko zwykle
        // sloty ekwipunku). Uzytkownik zglaszal brak slotu na tarcze.
        //
        // Rozpoznajemy je po POZYCJI w menu gracza (ARMOR_SLOT_START..COUNT),
        // bo te stale sa stabilne, a sam slot nie ma wlasnego typu.
        java.util.Set<Slot> armorAndShield = collectArmorAndShieldSlots();

        for (int i = 0; i < this.menu.slots.size(); i++) {
            Slot s = this.menu.slots.get(i);

            if (armorAndShield.contains(s)) {
                continue;   // zbroja i tarcza zostaja widoczne
            }

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
        boolean typing = isTypingInTextField();

        // GRACZ PISZE W WYSZUKIWARCE - klawisze naleza do POLA, a nie do ekranu.
        //
        // BUG, ktory to naprawia (zgloszenie gracza): "E" (klawisz ekwipunku)
        // zamykalo GUI w trakcie pisania. Wersja posrednia zdejmowala fokus,
        // wiec pierwsze E cicho przerywalo pisanie, a drugie zamykalo okno -
        // gracz widzial dokladnie to samo: "pisze i E zamyka inventory".
        //
        // Vanilla creative robi to tak (bajtkod CreativeModeInventoryScreen):
        //     if (searchBox.keyPressed(...)) { ...; return true; }
        //     if (searchBox.isFocused() && searchBox.isVisible() && key != ESC)
        //         return true;
        // czyli: pole obsluguje to, co chce, a CALA reszta (w tym E) jest
        // pochlaniana bez zamykania okna. Fokus zostaje, wiec pisanie trwa,
        // a litera "e" trafia do pola przez charTyped - tak jak w wanilii.
        if (typing) {
            boolean isEscape = keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
            boolean isInventoryKey = this.minecraft != null && this.minecraft.options != null
                    && this.minecraft.options.keyInventory != null
                    && this.minecraft.options.keyInventory.matches(keyCode, scanCode);

            // ESC ZAWSZE ZAMYKA - takze gdy pole tekstowe jest aktywne.
            //
            // Tak robi wanilia: w jej galezi dla wyszukiwarki kazdy klawisz jest
            // pochlaniany POZA Escape (`... && keyCode != 256`), wiec Esc leci
            // do bazy i zamyka ekran. Wczesniej nasza galaz "pisze" zdejmowala
            // tylko fokus, wiec gracz musial nacisnac Esc dwa razy - a po
            // naprawie wykrywania pisania (searchBox.isFocused) Esc przestal
            // zamykac ZUPELNIE, bo do tej galezi trafial czesciej. Blokujemy
            // wiec wylacznie klawisz ekwipunku (E), a Esc zostaje wyjsciem.
            if (isEscape) {
                this.onClose();
                return true;
            }
            if (isEscape || isInventoryKey) {
                // Log dokladnie tego przypadku: bez niego nie da sie odroznic
                // "poprawka nie dziala" od "gracz testuje stary JAR".
                com.craftingveloce.util.VeloceLog.Gui.detail(
                        com.craftingveloce.util.VeloceLog.Side.CLIENT,
                        "pole tekstowe aktywne: klawisz %d zostaje w polu (okno sie nie zamyka)",
                        keyCode);
            }
            if (isInventoryKey && this.minecraft != null && this.minecraft.options != null) {
                // KLUCZOWE: samo POCHLONIECIE klawisza przez ekran NIE WYSTARCZA.
                //
                // Minecraft.handleKeybinds() co tick robi
                //     while (options.keyInventory.consumeClick()) { ...otworz ekwipunek... }
                // i NIE patrzy przy tym na ekran (sprawdzone w bajtkodzie: nie ma
                // tam zadnego testu `screen == null`). Wiec nacisniete "E"
                // zostawalo w kolejce klawisza i po chwili otwieralo ekwipunek -
                // gracz widzial to jako "E zamyka mi GUI, kiedy pisze".
                // Konsumujemy wiec ten klik, zeby handleKeybinds nie mial czego
                // obsluzyc. Dokladnie takiego efektu oczekuje gracz: "E nie
                // zamyka, kiedy pole tekstowe jest aktywne".
                this.minecraft.options.keyInventory.consumeClick();
            }
            // 1) Pole tekstowe samo wie, co zrobic z backspace, strzalkami,
            //    Ctrl+A czy wklejaniem. Bierzemy fokus ekranu ALBO waniliowa
            //    wyszukiwarke (patrz isTypingInTextField).
            net.minecraft.client.gui.components.EditBox box =
                    VeloceTerminalViewState.focusedTextBox(this, this.getFocused());
            if (box != null && box.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            // 2) Reszta - w tym klawisz ekwipunku - jest pochlaniana, zeby nie
            //    zamknela okna. (Esc obslugujemy wyzej.)
            return true;
        }

        // Esc zamyka (gdy nie piszemy).
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        // Klawisz ekwipunku (domyslnie E) tez zamyka.
        if (this.minecraft != null && this.minecraft.options != null
                && this.minecraft.options.keyInventory != null
                && this.minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * Tooltip itemu BEZ linii kategorii i tagow, ktore dokleja creative.
     *
     * <p><b>BUG, ktory to naprawia (zgloszenie gracza).</b> W zakladkach
     * CATEGORY i SEARCH wanilia dokleja do tooltipa nazwe kategorii
     * ("Building Blocks") oraz tagi. W kontrolerze dochodzil do tego drugi,
     * wlasny tooltip - i napisy nachodzily na siebie, przykrywajac liczby przy
     * ikonach. Gracz nie chce tam kategorii: "wszędzie indziej jest ukryte".
     *
     * <p>Zwracamy wiec CZYSTY tooltip itemu (nazwa, opis, atrybuty). Trzymamy
     * to w klasie bazowej, zeby terminal, kontroler, crafter i selektor filtra
     * mialy DOKLADNIE to samo - a nie cztery wlasne wersje, ktore sie rozjada.
     */
    @Override
    public List<net.minecraft.network.chat.Component> getTooltipFromContainerItem(
            net.minecraft.world.item.ItemStack stack) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return super.getTooltipFromContainerItem(stack);
        }
        return stack.getTooltipLines(
                net.minecraft.world.item.Item.TooltipContext.of(this.minecraft.level),
                this.minecraft.player,
                this.minecraft.options.advancedItemTooltips
                        ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                        : net.minecraft.world.item.TooltipFlag.Default.NORMAL);
    }

    /**
     * Czy gracz wlasnie pisze w polu tekstowym (wyszukiwarka).
     *
     * <p>Po to, zeby klawisze skrotow (E, Esc, Enter) nie zabieraly znakow
     * ani nie zamykaly okna w trakcie pisania.
     */
    protected boolean isTypingInTextField() {
        try {
            // 1) Widget skupiony przez ekran (nasze wlasne pola, np. prog sensora).
            if (this.getFocused() instanceof net.minecraft.client.gui.components.EditBox) {
                return true;
            }
            // 2) WYSZUKIWARKA WANILI - pytamy SAM WIDGET, nie ekran.
            //
            // BUG, ktory to naprawia (zgloszenie gracza powtarzane dwa razy):
            // sprawdzalismy tylko fokus EKRANU, a waniliowa wyszukiwarka
            // creative zyje wlasnym zyciem. Gdy ekran nie wskazywal na nia jako
            // na skupiony widget, ten test wychodzil FALSE, wiec "E" (klawisz
            // ekwipunku) zamykalo GUI w trakcie pisania. Vanilla pyta wprost
            // o searchBox.isFocused() (CreativeModeInventoryScreen.keyPressed)
            // i to jest jedyne wiarygodne zrodlo tej informacji.
            return VeloceTerminalViewState.isSearchBoxFocused(this);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void removed() {
        // Najpierw zapisujemy swoj widok, potem oddajemy creative jego wlasny.
        saveViewState();
        restoreCreativeTab();
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
