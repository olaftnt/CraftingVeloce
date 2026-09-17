package com.craftingveloce.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Remembered terminal view: tab, search phrase and scroll position.
 *
 * <p><b>Why separately per terminal.</b> Vanilla keeps the selected tab in the field
 * {@code private static CreativeModeTab selectedTab} - that is, ONE field shared by the
 * whole client. The result: the terminal opened on the tab last used in the regular
 * creative inventory, and switching a tab in the terminal also changed it outside it.
 *
 * <p>Here the state is keyed by the terminal block position, so each terminal remembers
 * its own place - independently of the creative inventory and of the other terminals.
 *
 * <p>Entries disappear when leaving the world (see {@link #clearAll()}).
 */
public final class VeloceTerminalViewState {

    private VeloceTerminalViewState() {
    }

    /** Id of the tab selected in the given terminal. */
    private static final Map<Object, ResourceLocation> TAB = new HashMap<>();
    /** The phrase typed into the search box. */
    private static final Map<Object, String> SEARCH = new HashMap<>();
    /** List scroll position (0..1). */
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

    /** The tab with the given id, or null when it is missing (e.g. the mod was removed). */
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
     * Sets the tab in the creative screen.
     *
     * <p>{@code selectTab} is private, so we go through reflection. The method does
     * everything that is needed: it rebuilds the item list, sets the static field
     * and scrolls back to the top.
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

    /** Writes the phrase into the search box and refreshes the results. */
    /**
     * The vanilla search box of this screen (the {@code searchBox} field).
     *
     * <p>ONE place with this reflection: both the phrase restoration (applySearch) and
     * the "is the player currently typing" question (isSearchBoxFocused) use it.
     * Previously each place did the same thing separately.
     */
    @Nullable
    static EditBox searchBox(CreativeModeInventoryScreen screen) {
        if (screen == null) {
            return null;
        }
        try {
            var f = CreativeModeInventoryScreen.class.getDeclaredField("searchBox");
            f.setAccessible(true);
            return f.get(screen) instanceof EditBox box ? box : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether the search box has focus - EXACTLY the way vanilla asks.
     *
     * <p><b>Why a separate question.</b> Checking the SCREEN's focus
     * ({@code Screen.getFocused()}) is not enough: the vanilla creative search box lives
     * its own life, and when the screen did not point at it as the focused widget, our
     * "is the player typing" test came out FALSE - and the E key (inventory) closed the
     * GUI while the player was typing. Vanilla asks directly about
     * {@code searchBox.isFocused()} (CreativeModeInventoryScreen.keyPressed) and that is
     * the only reliable source of this information.
     */
    static boolean isSearchBoxFocused(CreativeModeInventoryScreen screen) {
        EditBox box = searchBox(screen);
        return box != null && box.isFocused();
    }

    /**
     * The text box that should receive the key: the screen focus or the vanilla
     * search box (when that one is active).
     */
    @Nullable
    static EditBox focusedTextBox(CreativeModeInventoryScreen screen,
                                  @Nullable net.minecraft.client.gui.components.events.GuiEventListener screenFocus) {
        if (screenFocus instanceof EditBox box) {
            return box;
        }
        EditBox search = searchBox(screen);
        return search != null && search.isFocused() ? search : null;
    }

    static void applySearch(CreativeModeInventoryScreen screen, String text) {
        if (screen == null || text == null || text.isEmpty()) {
            return;
        }
        try {
            EditBox editBox = searchBox(screen);
            if (editBox != null) {
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

    /** The current phrase from the search box ("" when there is none). */
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

    /** Whether we have already logged a failure to read the tab. */
    private static boolean currentTabFailureLogged;

    /**
     * The {@code selectedTab} field - resolved ONCE.
     *
     * <p>This method is called every tick from {@code containerTick} (detecting the
     * player changing the tab), and {@code getDeclaredField} on every call is an
     * unnecessary cost - field lookup is not free.
     */
    private static java.lang.reflect.Field selectedTabField;
    private static boolean selectedTabResolveTried;

    /** The current tab (may be null when reflection fails). */
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
                        "cannot find the selectedTab field - tab memory "
                                + "and deposit slot visibility will not work");
            }
            return null;
        }
        try {
            Object v = selectedTabField.get(null);
            return v instanceof CreativeModeTab tab ? tab : null;
        } catch (Throwable t) {
            // We do NOT swallow this silently: the deposit slot visibility depends on
            // it (we only draw that slot in Survival Inventory), so a failure here
            // would show up as a "missing arrow" with no trace in the log.
            if (!currentTabFailureLogged) {
                currentTabFailureLogged = true;
                com.craftingveloce.util.VeloceLog.Gui.failure(
                        com.craftingveloce.util.VeloceLog.Side.CLIENT,
                        "cannot read the current creative tab: %s", t);
            }
            return null;
        }
    }

    /** Scroll position from the screen (0 when it could not be read). */
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
     * Sets the list scroll position.
     *
     * <p><b>Important.</b> Writing {@code scrollOffs} alone is NOT enough. Vanilla keeps
     * the items in a static {@code CONTAINER}, and only {@code ItemPickerMenu.scrollTo(float)}
     * copies them into the slots. Without that call the scrollbar would be drawn at the
     * remembered position, but the list would show the items from the top - that is,
     * completely different ones than the scrollbar indicates.
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
     * Forces a rebuild of the current tab's contents.
     *
     * <p><b>Why.</b> On the first opening, the INVENTORY tab (Survival Inventory) is
     * sometimes empty - the item list is not filled in yet and only appears after
     * switching the tab back and forth. Calling the same method that does this on a tab
     * change fills the list right away.
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

    /** Is the client available? (a small safeguard for calls from init). */
    static boolean clientReady() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.player != null;
    }
}
