package com.craftingveloce.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;

import java.util.HashMap;
import java.util.Map;

/**
 * Zapamietany widok terminala: zakladka, fraza wyszukiwania i przewiniecie.
 *
 * <p><b>Dlaczego osobno per terminal.</b> Vanilla trzyma wybrana zakladke
 * w polu {@code private static CreativeModeTab selectedTab} - czyli JEDNYM,
 * wspolnym dla calego klienta. Skutek: terminal otwieral sie na zakladce
 * ostatnio uzywanej w zwyklym creative inventory, a przelaczenie zakladki
 * w terminalu zmienialo ja takze poza nim.
 *
 * <p>Tutaj stan jest kluczowany pozycja bloku terminala, wiec kazdy terminal
 * pamieta swoje wlasne miejsce - niezaleznie od creative inventory i od
 * pozostalych terminali.
 *
 * <p>Wpisy znikaja przy wyjsciu ze swiata (patrz {@link #clearAll()}).
 */
public final class VeloceTerminalViewState {

    private VeloceTerminalViewState() {
    }

    /** Id zakladki wybranej w danym terminalu. */
    private static final Map<Object, ResourceLocation> TAB = new HashMap<>();
    /** Fraza wpisana w wyszukiwarce. */
    private static final Map<Object, String> SEARCH = new HashMap<>();
    /** Pozycja przewiniecia listy (0..1). */
    private static final Map<Object, Float> SCROLL = new HashMap<>();

    static void save(Object key, CreativeModeTab tab, String search, float scroll) {
        if (key == null) {
            return;
        }
        if (tab != null) {
            ResourceLocation id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            if (id != null) {
                TAB.put(key, id);
            }
        }
        SEARCH.put(key, search == null ? "" : search);
        SCROLL.put(key, scroll);
    }

    static ResourceLocation savedTab(Object key) {
        return key == null ? null : TAB.get(key);
    }

    static String savedSearch(Object key) {
        return key == null ? null : SEARCH.get(key);
    }

    static Float savedScroll(Object key) {
        return key == null ? null : SCROLL.get(key);
    }

    public static void clearAll() {
        TAB.clear();
        SEARCH.clear();
        SCROLL.clear();
    }

    /** Zakladka o danym id, albo null gdy jej nie ma (np. mod usuniety). */
    static CreativeModeTab findTab(ResourceLocation id) {
        if (id == null) {
            return null;
        }
        for (CreativeModeTab tab : CreativeModeTabs.tabs()) {
            if (id.equals(BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab))) {
                return tab;
            }
        }
        return null;
    }

    /**
     * Ustawia zakladke w ekranie creative.
     *
     * <p>{@code selectTab} jest prywatne, wiec przez refleksje. Metoda robi
     * wszystko co trzeba: przebudowuje liste itemow, ustawia pole statyczne
     * i przewija na poczatek.
     */
    static void applyTab(CreativeModeInventoryScreen screen, CreativeModeTab tab) {
        if (screen == null || tab == null) {
            return;
        }
        try {
            var m = CreativeModeInventoryScreen.class.getDeclaredMethod(
                    "selectTab", CreativeModeTab.class);
            m.setAccessible(true);
            m.invoke(screen, tab);
        } catch (Throwable t) {
            com.craftingveloce.util.VeloceLog.Gui.detail(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "could not restore tab: %s", t);
        }
    }

    /** Wpisuje fraze do pola wyszukiwania i odswieza wyniki. */
    static void applySearch(CreativeModeInventoryScreen screen, String text) {
        if (screen == null || text == null || text.isEmpty()) {
            return;
        }
        try {
            var f = CreativeModeInventoryScreen.class.getDeclaredField("searchBox");
            f.setAccessible(true);
            Object box = f.get(screen);
            if (box instanceof EditBox editBox) {
                editBox.setValue(text);
                var refresh = CreativeModeInventoryScreen.class
                        .getDeclaredMethod("refreshSearchResults");
                refresh.setAccessible(true);
                refresh.invoke(screen);
            }
        } catch (Throwable t) {
            com.craftingveloce.util.VeloceLog.Gui.detail(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "could not restore search: %s", t);
        }
    }

    /** Biezaca fraza z pola wyszukiwania ("" gdy brak). */
    static String currentSearch(CreativeModeInventoryScreen screen) {
        if (screen == null) {
            return "";
        }
        try {
            var f = CreativeModeInventoryScreen.class.getDeclaredField("searchBox");
            f.setAccessible(true);
            Object box = f.get(screen);
            if (box instanceof EditBox editBox) {
                return editBox.getValue();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** Czy juz logowalismy awarie odczytu zakladki. */
    private static boolean currentTabFailureLogged;

    /**
     * Pole {@code selectedTab} - rozwiazywane RAZ.
     *
     * <p>Ta metoda jest wolana co tick z {@code containerTick} (wykrywanie
     * zmiany zakladki przez gracza), a {@code getDeclaredField} przy kazdym
     * wywolaniu to zbedny koszt - wyszukiwanie pola nie jest darmowe.
     */
    private static java.lang.reflect.Field selectedTabField;
    private static boolean selectedTabResolveTried;

    /** Biezaca zakladka (moze byc null, gdy refleksja zawiedzie). */
    static CreativeModeTab currentTab() {
        if (!selectedTabResolveTried) {
            selectedTabResolveTried = true;
            try {
                selectedTabField = CreativeModeInventoryScreen.class
                        .getDeclaredField("selectedTab");
                selectedTabField.setAccessible(true);
            } catch (Throwable t) {
                selectedTabField = null;
            }
        }
        if (selectedTabField == null) {
            if (!currentTabFailureLogged) {
                currentTabFailureLogged = true;
                com.craftingveloce.util.VeloceLog.Gui.failure(
                        com.craftingveloce.util.VeloceLog.Side.CLIENT,
                        "nie moge znalezc pola selectedTab - pamiec zakladek "
                                + "i widocznosc slotu odkladania nie beda dzialac");
            }
            return null;
        }
        try {
            Object v = selectedTabField.get(null);
            return v instanceof CreativeModeTab tab ? tab : null;
        } catch (Throwable t) {
            // Nie polykamy po cichu: od tego zalezy m.in. widocznosc slotu
            // odkladania (rysujemy go tylko w Survival Inventory), wiec awaria
            // tutaj objawialaby sie "zniknieta strzalka" bez sladu w logu.
            if (!currentTabFailureLogged) {
                currentTabFailureLogged = true;
                com.craftingveloce.util.VeloceLog.Gui.failure(
                        com.craftingveloce.util.VeloceLog.Side.CLIENT,
                        "nie moge odczytac biezacej zakladki creative: %s", t);
            }
            return null;
        }
    }

    /** Pozycja przewiniecia z ekranu (0 gdy nie odczytano). */
    static float currentScroll(CreativeModeInventoryScreen screen) {
        if (screen == null) {
            return 0f;
        }
        try {
            var f = CreativeModeInventoryScreen.class.getDeclaredField("scrollOffs");
            f.setAccessible(true);
            Object v = f.get(screen);
            return v instanceof Float fl ? fl : 0f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    /**
     * Ustawia przewiniecie listy.
     *
     * <p><b>Wazne.</b> Samo wpisanie {@code scrollOffs} NIE wystarcza. Vanilla
     * trzyma itemy w statycznym {@code CONTAINER}, a do slotow przepisuje je
     * dopiero {@code ItemPickerMenu.scrollTo(float)}. Bez tego wywolania
     * pasek przewijania rysowalby sie w zapamietanym miejscu, ale lista
     * pokazywalaby itemy od gory - czyli zupelnie inne, niz wskazuje pasek.
     */
    static void applyScroll(CreativeModeInventoryScreen screen, float scroll) {
        if (screen == null) {
            return;
        }
        try {
            var f = CreativeModeInventoryScreen.class.getDeclaredField("scrollOffs");
            f.setAccessible(true);
            f.set(screen, scroll);

            var menu = screen.getMenu();
            if (menu == null) {
                return;
            }
            var scrollTo = menu.getClass().getMethod("scrollTo", float.class);
            scrollTo.invoke(menu, scroll);
        } catch (Throwable t) {
            com.craftingveloce.util.VeloceLog.Gui.detail(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "could not restore scroll: %s", t);
        }
    }

    /**
     * Wymusza przebudowe zawartosci biezacej zakladki.
     *
     * <p><b>Po co.</b> Przy pierwszym otwarciu zakladka INVENTORY (Survival
     * Inventory) bywa pusta - lista itemow jest wtedy jeszcze nie wypelniona,
     * a pojawia sie dopiero po przelaczeniu zakladki w te i z powrotem.
     * Wywolanie tej samej metody, ktora robi to przy zmianie zakladki,
     * wypelnia liste od razu.
     */
    static void refreshContents(CreativeModeInventoryScreen screen) {
        if (screen == null) {
            return;
        }
        try {
            var refresh = CreativeModeInventoryScreen.class
                    .getDeclaredMethod("refreshSearchResults");
            refresh.setAccessible(true);
            refresh.invoke(screen);
        } catch (Throwable t) {
            com.craftingveloce.util.VeloceLog.Gui.detail(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "could not refresh tab contents: %s", t);
        }
    }

    /** Klient jest dostepny? (male zabezpieczenie dla wywolan z init). */
    static boolean clientReady() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.player != null;
    }
}
