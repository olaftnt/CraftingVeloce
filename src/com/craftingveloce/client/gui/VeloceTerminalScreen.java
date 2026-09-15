package com.craftingveloce.client.gui;

import com.craftingveloce.network.TerminalPullItemPKT;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class VeloceTerminalScreen extends VeloceCreativeScreen {

    private final BlockPos terminalPos;
    private Map<Item, Long> networkCounts = new HashMap<>();

    /**
     * Ile sztuk da sie dorobic auto-craftingiem (zolta liczba "+N").
     *
     * <p>Logika zamawiania i sklejania odpowiedzi siedzi we wspolnej klasie,
     * ktorej uzywa TAKZE kontroler - zeby oba ekrany pokazywaly te same
     * liczby z tego samego kodu, a nie z dwoch kopii, ktore maja sie zgadzac.
     */
    private final VeloceCraftableCounts craftable = new VeloceCraftableCounts();

    @Nullable

    private static Method selectTabMethod;
    private static Field selectedTabField;

    static {
        try {
            for (Method m : CreativeModeInventoryScreen.class.getDeclaredMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == net.minecraft.world.item.CreativeModeTab.class) {
                    m.setAccessible(true);
                    selectTabMethod = m;
                    break;
                }
            }
            for (Field f : CreativeModeInventoryScreen.class.getDeclaredFields()) {
                if (f.getType() == net.minecraft.world.item.CreativeModeTab.class) {
                    f.setAccessible(true);
                    selectedTabField = f;
                    break;
                }
            }
        } catch (Throwable t) {
            // Refleksja nad vanilla - jesli pola/metody zmienia nazwy po
            // aktualizacji, chcemy o tym wiedziec z LOGA, a nie z konsoli.
            com.craftingveloce.util.VeloceLog.Gui.error(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT, t,
                    "could not resolve CreativeModeInventoryScreen fields (tab/page memory disabled)");
        }
    }

    public VeloceTerminalScreen(LocalPlayer player, FeatureFlagSet enabledFeatures, boolean displayOperatorCreativeTab, BlockPos terminalPos) {
        super(player, enabledFeatures, displayOperatorCreativeTab);
        this.terminalPos = terminalPos;
    }

    public void updateNetworkCounts(Map<Item, Long> counts) {
        updateNetworkCounts(counts, Map.of());
    }

    public void updateNetworkCounts(Map<Item, Long> counts, Map<Item, Long> craftable) {
        Map<Item, Long> previous = this.networkCounts;
        this.networkCounts = new HashMap<>(counts);

        // Pytamy ponownie TYLKO gdy stock naprawde sie zmienil.
        //
        // BUG, ktory tu byl: wolalismy requestVisibleCounts(true) bezwarunkowo.
        // A serwer wysyla ten pakiet co sekunde (syncCountsToAllWatchers),
        // wiec powstawala PETLA:
        //     updateNetworkCounts -> requestVisibleCounts -> serwer liczy
        //     -> SyncCraftableCounts -> (nastepna sekunda) updateNetworkCounts
        // Klient wysylal wiec zadanie co sekunde bez konca, a gracz czul to
        // jako "lag zanim pokaza sie liczby" - GUI czekalo na round-trip.
        //
        // Teraz porownujemy stock z poprzednim pakietem: jesli sie nie zmienil,
        // nie ma po co pytac. Po skraftowaniu/wyciagnieciu rozni sie, wiec
        // liczby "+N" odswiezaja sie tak, jak powinny.
        if (previous != null && previous.equals(this.networkCounts)) {
            return;
        }
        requestVisibleCounts(true);
        // NIE nadpisujemy calej mapy craftowalnosci.
        //
        // Tlo (cache) wysyla swoja migawke, ktora podczas ponownego skanu jest
        // pusta albo niepelna. Nadpisanie kasowalo wtedy liczby dostarczone
        // przez natychmiastowa odpowiedz - i nie wracaly. Dokladamy wiec tylko
        // to, co przyszlo; precyzyjne czyszczenie robi natychmiastowa sciezka.
        this.craftable.putAll(craftable);
    }

    /**
     * Odpowiedz serwera z liczbami dla widocznych itemow.
     *
     * <p>Aktualizujemy TYLKO te itemy, o ktore pytalismy. Gdybysmy
     * nadpisali cala mape, tlo (cache) i natychmiastowa odpowiedz
     * nadpisywalyby sie nawzajem i liczby by migotaly.
     *
     * <p>Usuwamy tez wpisy dla pytanych itemow, ktorych nie ma w wyniku.
     * Serwer od pewnego czasu przysyla takze ZERA (konkretna odpowiedz "nie da
     * sie juz nic zrobic"), ale czyszczenie zostaje jako zabezpieczenie dla
     * odpowiedzi z serwera bez tych zer.
     */
    public void updateCraftableCounts(Map<Item, Long> craftable, boolean complete) {
        com.craftingveloce.util.VeloceLog.Gui.detail(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "received instant counts for %d item(s) (complete=%s)",
                craftable.size(), complete);
        this.craftable.update(craftable, complete);
    }

    @Override
    protected void init() {
        super.init();
        // Natychmiast po otwarciu: zamow liczby dla tego, co widac.
        craftable.resetRequestState();
        requestVisibleCounts(true);
    }

    @Override
    public void containerTick() {
        // WAZNE: wolamy baze - inaczej nie dziala wykrywanie zakladek ani
        // filtrowanie itemow z VeloceCreativeScreen.
        super.containerTick();
        // Na zywo: gdy zmieni sie zawartosc ekranu (zakladka, przewiniecie),
        // zamow liczby dla nowej strony.
        requestVisibleCounts(false);
    }

    /**
     * Zamawia na serwerze liczby "ile da sie dorobic" dla widocznych itemow.
     *
     * <p>To daje odpowiedz NATYCHMIAST: serwer liczy cala strone jednym
     * wspoldzielonym budzetem czasowym i odsyla gotowe liczby. Tlo (cache)
     * przelicza reszte sieci, ale gracz nie musi na to czekac, zeby zobaczyc
     * aktualne wartosci tam, gdzie patrzy.
     *
     * @param force true = wyslij nawet jesli sygnatura sie nie zmienila
     */
    private void requestVisibleCounts(boolean force) {
        if (this.minecraft == null || this.minecraft.player == null || this.menu == null) {
            return;
        }
        // Sloty moga byc puste, mimo ze itemy sa wyswietlone (patrz
        // ensureGridItems) - wtedy nie ma czego zamowic i liczby nie pojawiaja
        // sie, dopoki nie przelaczy sie zakladki albo frazy.
        ensureGridItems();
        this.craftable.request(terminalPos, this.menu.slots, this::isPlayerSlot, force);
    }

    /**
     * Rysuje strzalke na slocie odkladania.
     *
     * <p>Vanilla na tym miejscu nie rysuje zadnej ikonki - krzyzyk, ktory tam
     * widac, jest wypalony w teksturze GUI creative. Skoro nasz slot nie
     * niszczy itemu, tylko go ODKLADA, krzyzyk jest mylacy. Rysujemy wiec
     * wlasna strzalke "do sieci" i zaslaniamy nia stara grafike.
     */
    private void drawStoreArrow(GuiGraphics graphics, Slot slot) {
        // Zaslaniamy krzyzyk z tekstury tlem slotu.
        int x = this.leftPos + slot.x;
        int y = this.topPos + slot.y;
        graphics.fill(x, y, x + 16, y + 16, 0xFFC6C6C6);

        // Strzalka w prawo: dwa skosy + trzon.
        int cx = x + 3;
        int cy = y + 7;
        int color = 0xFF3B6E3B;
        for (int i = 0; i < 5; i++) {
            graphics.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, color);
            graphics.fill(cx + i, cy + 8 - i, cx + i + 1, cy + 9 - i, color);
        }
        graphics.fill(cx + 5, cy + 4, cx + 11, cy + 5, color);
    }

    @Override
    protected void renderSlot(GuiGraphics graphics, Slot slot) {
        if (isStoreSlot(slot)) {
            drawStoreArrow(graphics, slot);
            return;
        }
        super.renderSlot(graphics, slot);

        if (isShopSlot(slot) && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            long count = networkCounts.getOrDefault(stack.getItem(), 0L);
            if (count > 0) {
                VeloceSlotOverlay.drawStock(graphics, this.font, count, slot.x, slot.y);
            }
            // Liczba sztuk, ktore da sie dorobic auto-craftingiem.
            // Pokazywana jako "+N" w lewym gornym rogu - zolta, zeby odroznic
            // od zielonego stocku. Zero nie jest rysowane.
            long craftable = this.craftable.get(stack.getItem());
            if (craftable > 0) {
                VeloceSlotOverlay.drawCraftable(graphics, this.font, craftable, slot.x, slot.y);
            }
        }
    }

    /** Zgodnosc: skrocona liczba. Implementacja jest we {@link VeloceSlotOverlay}. */
    public static String formatCount(long number) {
        return VeloceSlotOverlay.formatCount(number);
    }

    /**
     * Tooltip slotu magazynu.
     *
     * <p>Vanilla dla slotu na tym miejscu wypisuje "Destroy Item" - u nas ten
     * slot NIE niszczy, tylko oddaje item do sieci. Podmieniamy wiec tekst na
     * "usage" i dodajemy podpowiedz, jak dziala shift.
     *
     * <p>Dla zwyklych itemow zostawiamy czysty tooltip (bez linii kategorii
     * i tagow, ktore creative inventory dokleja w zakladkach CATEGORY/SEARCH).
     */
    @Override
    public List<Component> getTooltipFromContainerItem(ItemStack stack) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return super.getTooltipFromContainerItem(stack);
        }
        Slot hovered = getSlotUnderMouse();
        if (hovered != null && isStoreSlot(hovered)) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("gui.craftingveloce.terminal.storeSlot")
                    .withStyle(net.minecraft.ChatFormatting.WHITE));
            lines.add(Component.translatable("gui.craftingveloce.terminal.storeHint")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            lines.add(Component.translatable("gui.craftingveloce.terminal.storeHintShift")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            return lines;
        }
        // Czysty tooltip itemu buduje klasa bazowa (bez kategorii i tagow
        // creative) - jedno zrodlo dla wszystkich czterech ekranow.
        return super.getTooltipFromContainerItem(stack);
    }

    /**
     * Klucz stanu widoku - pozycja TEGO terminala.
     *
     * <p>Dzieki temu kazdy terminal pamieta wlasna zakladke, fraze i
     * przewiniecie, zamiast wspoldzielic je z creative inventory.
     */
    @Override
    protected Object viewStateKey() {
        return terminalPos;
    }

    /** Terminal zostawia hotbar gracza dzialajacy - mozna z niego korzystac. */
    @Override
    protected boolean keepPlayerHotbar() {
        return true;
    }

    private boolean isPlayerSlot(Slot slot) {
        if (slot == null || this.minecraft == null || this.minecraft.player == null) {
            return false;
        }
        if (this.isInventoryOpen() && this.indexOfSlotIn(this.minecraft.player.inventoryMenu, slot) >= 0) {
            return true;
        }
        return slot.container == this.minecraft.player.getInventory();
    }

    private boolean isShopSlot(Slot slot) {
        if (slot == null || this.isPlayerSlot(slot)) {
            return false;
        }
        // Trash / sell slot
        return slot.x != 173 || slot.y != 112;
    }

    /**
     * Odklada itemy gracza do sieci.
     *
     * <p>Trzy warianty, jak w vanilla creative inventory:
     * <ul>
     *   <li>zwykly klik - to, co trzymasz na kursorze,</li>
     *   <li>shift + klik - caly ekwipunek OPROCZ hotbara,</li>
     *   <li>shift + prawy klik - doslownie wszystko, hotbar tez.</li>
     * </ul>
     *
     * <p><b>Klient wysyla tylko TRYB i nic nie rusza u siebie.</b> Poprzednia
     * wersja wysylala kopie stosu i czyscila kursor lokalnie - a serwer nie
     * zabieral niczego graczowi, wiec item powstawal i w beczce, i w ekwipunku
     * (fizyczna duplikacja). Teraz serwer sam zabiera itemy i odsyla zmiany.
     */
    private void handleStoreClick(int mouseButton, ClickType clickType) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        // Nic nie trzymamy na kursorze i nie ma czego odkladac.
        boolean shift = clickType == ClickType.QUICK_MOVE;
        boolean rightClick = mouseButton == 1;

        int mode;
        if (shift) {
            mode = rightClick
                    ? com.craftingveloce.network.TerminalStoreItemPKT.MODE_EVERYTHING
                    : com.craftingveloce.network.TerminalStoreItemPKT.MODE_INVENTORY;
        } else {
            if (this.menu == null || this.menu.getCarried().isEmpty()) {
                return;
            }
            mode = com.craftingveloce.network.TerminalStoreItemPKT.MODE_CURSOR;
        }

        // NIE BLOKujemy akcji po stronie klienta.
        //
        // BUG, ktory tu byl (zrodlo trwalego falszywego "network full"):
        // klient liczyl wolne sloty z OSTATNIEJ synchronizacji i gdy wyszlo 0,
        // odmawial wyslania pakietu:
        //
        //     String blocked = storeBlockedReason(mode);
        //     if (blocked != null) { storeFeedback(blocked); return; }
        //
        // Tyle ze "0 wolnych slotow" NIE znaczy "siec pelna":
        //   1. licznik pustych slotow ignoruje miejsce w NIEDOPELNIONYCH
        //      stosach - skrzynia wypelniona stosami po 40 sztuk ma 0 pustych
        //      slotow, a przyjmie jeszcze setki,
        //   2. dane klienta moga byc NIEAKTUALNE (ktos dopelnil skrzynie,
        //      hopper cos dosypal) - a wtedy klient blokuje NA ZAWSZE, bo
        //      sam z siebie nigdy nie odswiezy tego zero.
        //
        // Klient nie ma dosc informacji, zeby rozstrzygnac to rzetelnie:
        // nie zna zawartosci poszczegolnych slotow ani tego, czy dane sa
        // swieze. Decyzja nalezy do SERWERA, ktory ma pelny obraz (i moze
        // nawet doczytac chunk). Wysylamy wiec zadanie ZAWSZE, a serwer
        // odpowiada prawda - komunikatem, gdy naprawde nic nie weszlo.
        //
        PacketDistributor.sendToServer(
                new com.craftingveloce.network.TerminalStoreItemPKT(terminalPos, mode));

        // NIE ruszamy kursora lokalnie.
        //
        // BUG, ktory tu byl (zrodlo "ghost itemow"): po wyslaniu pakietu
        // czyscilismy kursor u siebie, "zeby GUI zareagowalo od razu":
        //
        //     this.menu.setCarried(ItemStack.EMPTY);
        //
        // Gdy siec byla PELNA, serwer nie zabieral niczego (bo nie mial gdzie
        // wlozyc), wiec item znikal TYLKO wizualnie u klienta - a po ponownym
        // otwarciu ekwipunku wracal, bo na serwerze caly czas byl.
        //
        // Teraz klient nie zmienia NICZEGO, dopoki serwer nie potwierdzi.
        // Potwierdzeniem jest resync ekwipunku (TerminalPullItemPKT.
        // resyncInventories), ktory serwer wysyla tylko gdy NAPRAWDE cos
        // przeniosl. Dzieki temu item nigdy nie opuszcza swojego miejsca,
        // jesli nie ma go gdzie wlozyc - i nie ma czego cofac.
    }

    private void clickViaInventoryMenu(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        if (this.minecraft == null || this.minecraft.player == null || this.minecraft.gameMode == null) {
            return;
        }
        LocalPlayer player = this.minecraft.player;
        AbstractContainerMenu inventoryMenu = player.inventoryMenu;

        int inventorySlotId;
        if (slot == null) {
            inventorySlotId = slotId;
        } else {
            int resolved = this.resolveInventoryMenuIndex(slot);
            if (resolved < 0) {
                return;
            }
            inventorySlotId = resolved;
        }

        if (inventorySlotId != -999 && (inventorySlotId < 0 || inventorySlotId >= inventoryMenu.slots.size())) {
            return;
        }

        AbstractContainerMenu previous = player.containerMenu;
        try {
            player.containerMenu = inventoryMenu;
            this.minecraft.gameMode.handleInventoryMouseClick(
                    inventoryMenu.containerId, inventorySlotId, mouseButton, clickType, player);
        } finally {
            player.containerMenu = previous;
        }
    }

    private int resolveInventoryMenuIndex(Slot slot) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return -1;
        }
        LocalPlayer player = this.minecraft.player;
        AbstractContainerMenu inventoryMenu = player.inventoryMenu;

        if (this.isInventoryOpen()) {
            int byIdentity = this.indexOfSlotIn(inventoryMenu, slot);
            if (byIdentity >= 0) {
                return byIdentity;
            }
            int containerSlot = slot.getContainerSlot();
            return (containerSlot >= 0 && containerSlot < inventoryMenu.slots.size()) ? containerSlot : -1;
        }

        if (slot.container == player.getInventory()) {
            int containerSlot = slot.getContainerSlot();
            if (containerSlot < 0 || containerSlot > 8) {
                return -1;
            }
            return 36 + containerSlot;
        }

        return -1;
    }

    private int indexOfSlotIn(AbstractContainerMenu menu, Slot slot) {
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.slots.get(i) == slot) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }

        // 1) Player inventory slots
        if (this.isPlayerSlot(slot)) {
            this.clickViaInventoryMenu(slot, slotId, mouseButton, clickType);
            return;
        }

        // Clicks outside the window
        if (slot == null) {
            this.clickViaInventoryMenu(null, slotId, mouseButton, clickType);
            return;
        }

        // 2) Slot "strzalki" - odkladanie do sieci (NIE kasowanie).
        //
        // Zachowanie jak w vanilla creative inventory, ale zamiast niszczyc
        // item oddajemy go do pierwszego magazynu sieci:
        //   - zwykly klik          -> cala zawartosc kursora
        //   - shift + klik         -> wszystko OPROCZ hotbara
        //   - shift + prawy klik   -> doslownie wszystko (takze hotbar)
        if (mouseButton >= 0 && isStoreSlot(slot)) {
            handleStoreClick(mouseButton, clickType);
            return;
        }

        // 3) Shop grid slots
        if (!this.isShopSlot(slot)) {
            return;
        }

        AbstractContainerMenu menu = this.menu;
        if (menu != null && !menu.getCarried().isEmpty()) {
            return;
        }

        ItemStack item = slot.getItem();
        if (item.isEmpty()) {
            return;
        }

        int count = 1;
        if (clickType == ClickType.QUICK_MOVE) {
            count = item.getMaxStackSize();
        }

        // Send pull packet to server! Never touch cursor on client to prevent ghost items
        com.craftingveloce.util.VeloceLog.Gui.attempt(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "player clicked %s in terminal (count=%d, click=%s) - sending pull packet",
                item.getItem(), count, clickType);
        PacketDistributor.sendToServer(new TerminalPullItemPKT(terminalPos, item, count));
    }

    @Override
    public void removed() {
        // Mowimy serwerowi, ze przestalismy patrzec - inaczej wysylalby nam
        // pelna mape sieci co sekunde przez caly czas przebywania w poblizu.
        if (terminalPos != null) {
            PacketDistributor.sendToServer(new com.craftingveloce.network.TerminalWatcherPKT(
                    terminalPos, false));
        }
        // Trzymany stos obsluguje klasa bazowa - jedno zrodlo prawdy.
        super.removed();

    }
}
