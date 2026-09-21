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
 * The shared base for the Veloce "fake creative" GUIs (terminal, filter picker,
 * crafting table, controller).
 *
 * <p>It gathers in one place what all these screens do the same way, instead of
 * repeating it in every class:
 *
 * <ul>
 *   <li>forcing creative mode for the time the screen is open and restoring it afterwards</li>
 *   <li>disabling the player slots (they are not used anyway) and painting over the hotbar</li>
 *   <li><b>filtering the item list</b> - it lets us remove items we do not want to
 *       show, so they do not leave empty holes in the grid</li>
 *   <li><b>hiding unwanted tabs</b> (e.g. Operator Utilities,
 *       Saved Hotbars) - overridden in subclasses through {@link #tabFilter}</li>
 * </ul>
 */
public abstract class VeloceCreativeScreen extends CreativeModeInventoryScreen {

    /** The field with the current tab's item list (it also contains empty entries). */
    private static Field itemsField;

    /** The items field in SlotWrapper (for recognizing a player slot). */
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
     * The mode that was in effect before our GUIs forced creative, captured BEFORE the
     * screen is constructed - see {@link #prepareCreativeMode()}.
     *
     * <p><b>Why this is static and why it must exist.</b> The mode has to be creative
     * BEFORE vanilla's constructor runs, because that constructor is what builds and
     * CACHES the creative tab parameters, and one of them is
     * {@code player.canUseGameMasterBlocks() && displayOperatorCreativeTab} - i.e. it
     * depends on the game mode. A survival player who opened the terminal therefore
     * cached "no permissions", our {@code init()} switched the mode to creative a
     * moment later, and vanilla's first {@code containerTick} saw the parameters had
     * changed and rebuilt EVERY creative tab of the pack a second time. Measured in a
     * 339-mod pack: 0.9-4.3 s per terminal open, two full rebuilds of every tab, and
     * the probe in {@code probeTabParameters()} named it precisely
     * ({@code permissions=true (false)} at the constructor, {@code (true)} on the first
     * tick). Switching before the constructor means the cache already matches what the
     * tick will compute, so the second rebuild disappears.
     *
     * <p>Static, because the switch happens before there is an instance to record it
     * on; the instance adopts it in {@link #init()} and clears it when the mode is
     * given back in {@code removed()}.
     */
    @Nullable
    private static GameType pendingModeBeforeGui;

    /**
     * Reflection resolved ONCE, not on every refresh.
     *
     * <p>{@code getMethod}/{@code getDeclaredField}/{@code setAccessible} are
     * relatively expensive operations (walking the class hierarchy). Previously
     * they ran 20 times per second for the whole session with the GUI open.
     */
    private static java.lang.reflect.Method scrollToMethod;
    private static java.lang.reflect.Field scrollOffsFieldRef;
    private static boolean reflectionResolved;

    protected VeloceCreativeScreen(LocalPlayer player, FeatureFlagSet enabledFeatures,
                                   boolean displayOperatorCreativeTab) {
        super(player, enabledFeatures, displayOperatorCreativeTab);
        this.operatorTabAllowed = displayOperatorCreativeTab;
        // Everything AFTER the vanilla constructor. The pair to compare with
        // client.openTerminalScreen.construct: if that row is ~1.6 s and this one is 0 ms, the
        // time is vanilla's class loading and nothing of ours, which is exactly what the first
        // run's numbers suggested and what nobody could prove before.
        try (var ignored = com.craftingveloce.util.VeloceProfiler
                .section("client.creativeScreen.ctorOwn")) {
            // The probe's BASELINE is taken here, not on the first tick.
            //
            // Why it matters: vanilla's constructor rebuilds the tabs with exactly these
            // three parameters and caches them. The second, expensive rebuild happens on
            // the first {@code containerTick} - so whatever differs, it differs between
            // "just after the constructor" and "the first tick". Snapshotted on the first
            // tick the probe would compare the tick against itself and report nothing.
            probeTabParameters();
        }
    }

    /**
     * The {@code displayOperatorCreativeTab} this screen was built with.
     *
     * <p>Kept only so the tab-rebuild probe below can ask the SAME question vanilla asks
     * ({@code player.canUseGameMasterBlocks() && displayOperatorCreativeTab}) - see
     * {@link #probeTabParameters()}.
     */
    private final boolean operatorTabAllowed;

    // ------------------------------------------------------------------
    // Filtering the contents - to be overridden in subclasses
    // ------------------------------------------------------------------

    /** What the last probe saw - see {@link #probeTabParameters()}. */
    private static FeatureFlagSet probedFeatures;
    private static boolean probedPermissions;
    private static Object probedRegistries;

    /**
     * Reports WHICH parameter makes vanilla rebuild every creative tab.
     *
     * <p><b>Why this exists.</b> The tabs are being rebuilt again and again while the terminal is
     * open - the log showed it as other mods' "creative tab ... will display N entries" lines
     * appearing every few seconds, and each rebuild costs seconds in a pack this size. Vanilla
     * rebuilds when its three parameters differ from the cached ones
     * ({@code CreativeModeTabs.ItemDisplayParameters.needsUpdate}:
     * {@code features.equals() || permissions != || registries !=}). Which of the three keeps
     * changing was not answerable from the outside, and guessing it has already wasted rounds.
     *
     * <p>It only WRITES when something changed, so a healthy session logs nothing. If it reports
     * nothing and the rebuilds continue, that is an answer too: the parameters are stable and the
     * rebuild is being asked for directly by some other code, which narrows the search to
     * "who calls tryRebuildTabContents".
     */
    private void probeTabParameters() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) {
            return;
        }
        FeatureFlagSet features = mc.player.connection.enabledFeatures();
        boolean permissions = mc.player.canUseGameMasterBlocks() && operatorTabAllowed;
        Object registries = mc.player.level().registryAccess();
        if (probedFeatures == null) {
            probedFeatures = features;
            probedPermissions = permissions;
            probedRegistries = registries;
            return;
        }
        boolean featuresChanged = !probedFeatures.equals(features);
        boolean permissionsChanged = probedPermissions != permissions;
        boolean registriesChanged = probedRegistries != registries;
        if (featuresChanged || permissionsChanged || registriesChanged) {
            com.craftingveloce.CraftingVeloceMod.LOGGER.warn(
                    "[Veloce][TABS] rebuild params changed -> vanilla will rebuild every tab: "
                            + "features={} permissions={} ({}) registries={} ({})",
                    featuresChanged, permissionsChanged, permissions,
                    registriesChanged,
                    Integer.toHexString(System.identityHashCode(registries)));
            probedFeatures = features;
            probedPermissions = permissions;
            probedRegistries = registries;
        }
    }

    /**
     * Whether to show this item in the grid. Returning false <b>removes the item from
     * the list</b>, so the remaining ones shift up and there are no empty holes.
     */
    protected boolean acceptItem(ItemStack stack) {
        // NOT measured here: the base accepts everything, so a section would only add a row
        // saying "free". The cost lives in the overrides, and those measure themselves - see
        // VeloceCraftingTableScreen and VeloceControllerScreen, where acceptItem does a recipe
        // or filter lookup PER ITEM of the whole list.
        return true;
    }

    /**
     * Tabs hidden in all of our GUIs.
     * Operator Utilities and Saved Hotbars serve creative administration,
     * not the Veloce network - the player does not need them here.
     */
    private static final Set<String> HIDDEN_TABS = Set.of(
            "operator", "hotbar", "saved_hotbars", "op_blocks", "op_items"
    );

    /**
     * Whether to show this creative tab. By default we hide the administrative
     * tabs (operator utilities, saved hotbars).
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
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Puts the player into creative BEFORE one of our screens is constructed.
     *
     * <p><b>Call this immediately before {@code new Veloce...Screen(...)}.</b> The mode
     * must already be creative when vanilla's {@code CreativeModeInventoryScreen}
     * constructor runs, because that constructor builds and caches the creative tab
     * parameters - including permissions, which are
     * {@code player.canUseGameMasterBlocks() && displayOperatorCreativeTab} and therefore
     * depend on the game mode. Switching in {@code init()} instead (which is what the code
     * used to do) left the constructor with "no permissions" and the first tick with
     * "permissions", so vanilla rebuilt every creative tab of the pack a second time:
     * measured 0.9-4.3 s per terminal open in a 339-mod pack, twice.
     *
     * <p>The mode is given back in {@code removed()}, exactly as before - this method only
     * moves WHEN the switch happens and remembers what to restore.
     */
    public static void prepareCreativeMode() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameMode == null) {
            return;
        }
        if (pendingModeBeforeGui == null && !mc.gameMode.hasInfiniteItems()) {
            // Recorded once: a second screen opened on top of the first (the filter picker
            // over the terminal) must not overwrite the mode the player really had.
            pendingModeBeforeGui = mc.gameMode.getPlayerMode();
            mc.gameMode.setLocalMode(GameType.CREATIVE);
        }
    }

    @Override
    protected void init() {
        if (this.minecraft == null || this.minecraft.gameMode == null) {
            super.init();
            return;
        }
        if (!this.minecraft.gameMode.hasInfiniteItems()) {
            // The fallback path: a screen built WITHOUT prepareCreativeMode() - vanilla's
            // constructor has already cached the tab parameters by now, so this costs one
            // extra rebuild of every tab on the first tick (see pendingModeBeforeGui).
            if (pendingModeBeforeGui == null) {
                pendingModeBeforeGui = this.minecraft.gameMode.getPlayerMode();
            }
            this.minecraft.gameMode.setLocalMode(GameType.CREATIVE);
        }
        if (pendingModeBeforeGui != null) {
            // Whatever prepared the switch recorded the mode we owe the player back.
            this.modeBeforeOpen = pendingModeBeforeGui;
        }
        super.init();

        // THE ORDER IS CRITICAL. Vanilla {@code selectTab(CreativeModeTab)}
        // (for the INVENTORY tab) does {@code menu.slots.clear()} and rebuilds
        // the slots from scratch - that is, it WIPES our hiding of the player slots.
        //
        // The BUG that was here: suppressPlayerSlots() ran BEFORE
        // restoreViewState(), and it is restoreViewState that selects the tab
        // (through selectTab). The effect: our hiding was immediately wiped,
        // and the player's inventory slots came back into the GUI.
        //
        // Now: first the tab, then the hiding.
        // MEASURED STEP BY STEP, and this is the block the whole profiler was written for.
        //
        // "Opening the terminal freezes the game" is a statement about THIS method and the
        // vanilla work inside it, and the steps fail for completely different reasons: the tab
        // restore re-selects a tab (vanilla clears and rebuilds every slot), the filter walks
        // the whole registered item list, and refreshContents calls vanilla's own search which
        // re-scans that list again. Without splitting them the report says "init: 900 ms" and
        // the next step is still a guess - which is exactly what happened before this existed.
        try (var ignored = com.craftingveloce.util.VeloceProfiler.section("client.init.restoreViewState")) {
            restoreViewState();
        }
        try (var ignored = com.craftingveloce.util.VeloceProfiler.section("client.init.disableTrashSlot")) {
            disableVanillaTrashSlot();
        }
        try (var ignored = com.craftingveloce.util.VeloceProfiler.section("client.init.suppressPlayerSlots")) {
            suppressPlayerSlots();
        }
        try (var ignored = com.craftingveloce.util.VeloceProfiler.section("client.init.applyItemFilter.1")) {
            applyItemFilter();
        }
        // ONLY WHEN THE GRID IS ACTUALLY EMPTY, and that condition is the whole point.
        //
        // The original reason for this call was real: on some openings the list came up EMPTY
        // (the INVENTORY tab) and only a full refresh fixed it. But it was paid on EVERY open,
        // and it is a full vanilla `refreshSearchResults` - measured at 623 ms in a 339-mod pack
        // whenever the restored tab is the SEARCH tab, i.e. 623 ms of the ~680 ms freeze the
        // player felt on opening the terminal.
        //
        // Vanilla's own `selectTab` (reached through restoreViewState -> applyTab) has just
        // refreshed the tab's contents, and applyItemFilter above has already copied them into
        // the grid slots. So the refresh is needed exactly in the case it was written for - the
        // grid came up empty - and it is skipped when the grid is already populated, which keeps
        // the old fix and drops the cost.
        if (!hasGridItems()) {
            try (var ignored = com.craftingveloce.util.VeloceProfiler.section("client.init.refreshContents")) {
                VeloceTerminalViewState.refreshContents(this);
            }
            try (var ignored = com.craftingveloce.util.VeloceProfiler.section("client.init.applyItemFilter.2")) {
                applyItemFilter();
            }
        }
    }

    /**
     * The key under which we remember this screen's view.
     *
     * <p>By default {@code null} = we remember nothing. The terminal returns the
     * position of its block, thanks to which EACH terminal has its own tab, its own
     * search phrase and its own scroll - independently of the creative
     * inventory and of each other.
     */
    protected Object viewStateKey() {
        return null;
    }

    /**
     * Whether this screen remembers its tab.
     *
     * <p>By default yes, per {@link #viewStateKey()}. The filter picker screen
     * returns {@code false}, because it must always open on the first tab.
     */
    protected boolean rememberTab() {
        return true;
    }

    /**
     * The tab on which the screen opens when there is nothing remembered.
     *
     * <p>{@code null} = the first available one (top left corner).
     */
    @Nullable
    protected CreativeModeTab defaultTab() {
        return null;
    }

    /**
     * The tab that was selected BEFORE this screen opened.
     *
     * <p>Vanilla keeps the selected tab in the field {@code private static
     * selectedTab}, shared by the whole client. Our screen changes it, so
     * after closing we have to restore the previous one - otherwise the regular
     * creative inventory would open on the terminal's tab.
     */
    @Nullable
    private CreativeModeTab tabBeforeOpen;
    private boolean tabBeforeOpenCaptured;

    /**
     * Whether the remembered view has already been applied in this opening of the screen.
     *
     * <p><b>Why.</b> {@code init()} runs not only on opening, but also on every
     * {@code rebuildWidgets()} (e.g. after clicking a filter in the controller)
     * and on a window resize. Without this guard, every such event RESTORED the
     * view saved at the last CLOSING of the screen.
     *
     * <p>The symptom was concrete: the player changed the tab (or scrolled, or typed
     * a phrase), clicked the filter button - and the view jumped back to where it
     * was before closing. The same on every window resize.
     */
    private boolean viewStateApplied;

    /** Restores the remembered view (tab, phrase, scroll). */
    private void restoreViewState() {
        Object key = viewStateKey();

        // We remember the creative tab ONLY once, on the first init().
        // init() also runs on rebuildWidgets (e.g. after clicking a filter),
        // and then the "previous" one would already be our tab.
        if (!tabBeforeOpenCaptured) {
            tabBeforeOpenCaptured = true;
            tabBeforeOpen = VeloceTerminalViewState.currentTab();
        }

        // We apply the view ONLY once per opening of the screen - see viewStateApplied.
        if (viewStateApplied) {
            return;
        }
        viewStateApplied = true;

        // A screen with no memory of its own (key == null) that also remembers
        // its tab changes nothing - it is a regular creative and should stay as it is.
        if (key == null && rememberTab()) {
            return;
        }

        CreativeModeTab tab;
        try (var ignored = com.craftingveloce.util.VeloceProfiler
                .section("client.init.tabLookup")) {
        if (key != null && rememberTab()) {
            tab = VeloceTerminalViewState.findTab(VeloceTerminalViewState.savedTab(key));
            // No remembered tab (first opening) or the remembered one no longer
            // exists - we fall back to the default.
            //
            // The BUG that was here: when there was no saved tab, findTab() returned
            // null and the WHOLE tab setup was skipped. The screen therefore stayed on
            // the shared, static vanilla tab - and if that was INVENTORY
            // (Survival Inventory), the crafter showed a tab that it hides
            // itself and does not let you click.
            if (tab == null) {
                tab = defaultTab() != null ? defaultTab() : firstAcceptedTab();
            }
        } else {
            // We do not remember - always the first available one (top left corner).
            // This also applies to the filter picker, which has no key but must
            // always open from the beginning of the list.
            tab = defaultTab() != null ? defaultTab() : firstAcceptedTab();
        }
        }
        // Tabs that we do not have (e.g. hidden ones) are not restored.
        if (tab != null && acceptTab(tab)) {
            try (var ignored = com.craftingveloce.util.VeloceProfiler
                    .section("client.init.applyTab.selectTab")) {
                VeloceTerminalViewState.applyTab(this, tab);
            }
            // ...and scroll to the PAGE that tab is on - otherwise, with a large
            // number of tabs, the selected one is invisible.
            try (var ignored = com.craftingveloce.util.VeloceProfiler
                    .section("client.init.restoreTabPage")) {
                restoreTabPage(tab);
            }
        }

        // The phrase and the scroll only for screens with a key of their own.
        if (key == null) {
            return;
        }
        try (var ignored = com.craftingveloce.util.VeloceProfiler
                .section("client.init.applySearch")) {
            VeloceTerminalViewState.applySearch(this, VeloceTerminalViewState.savedSearch(key));
        }
        Float scroll = VeloceTerminalViewState.savedScroll(key);
        if (scroll != null) {
            try (var ignored = com.craftingveloce.util.VeloceProfiler
                    .section("client.init.applyScroll")) {
                VeloceTerminalViewState.applyScroll(this, scroll);
            }
        }
    }

    /** Saves the view so that the next opening comes back to the same place. */
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
     * Restores the tab that was selected before this screen opened.
     *
     * <p>Without this, the regular creative inventory would open on the terminal's
     * tab (or the crafter's, the controller's...), because vanilla keeps it in a
     * static field shared by the whole client. Each of our screens has its own
     * remembered state and creative has its own - and those states have to be
     * completely independent of each other.
     */
    private void restoreCreativeTab() {
        if (tabBeforeOpen == null || !acceptTab(tabBeforeOpen)) {
            return;
        }
        VeloceTerminalViewState.applyTab(this, tabBeforeOpen);
        restoreTabPage(tabBeforeOpen);
    }

    /**
     * Sets the tab PAGE so that it contains the given tab.
     *
     * <p><b>The problem.</b> When there are many tabs, NeoForge splits them into pages
     * with &lt; &gt; buttons. The page is chosen ONLY in {@code init()},
     * and based on the tab that was selected AT THAT MOMENT:
     * <pre>
     *   this.currentPage = pages.stream()
     *           .filter(page -&gt; page.getVisibleTabs().contains(selectedTab))
     *           .findFirst().orElse(this.currentPage);
     * </pre>
     * (read from the bytecode of the patched NeoForge class).
     *
     * <p>And our {@code restoreViewState()} restores the remembered tab through
     * {@code selectTab} only AFTER {@code super.init()} - and {@code selectTab} does
     * NOT change the page. The effect: {@code currentPage} stayed on the old tab's
     * page, so the restored tab was on a different page and was not visible. The user
     * saw the correctly remembered tab, but did not see it on the screen.
     *
     * <p><b>The solution.</b> After selecting the tab we replay the same logic
     * that NeoForge uses: we find the page containing that tab and make it the
     * current one. We do not store the page number separately, because
     * the page number is not stable (it changes when a mod with new tabs is
     * added) - but the tab is. Thanks to that we always land on the same
     * page as before closing, and the selected tab is always visible.
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
                        "cannot set the tab page: %s", t);
            }
        }
    }

    /** Whether we have already logged a failure of the tab page handling. */
    private boolean tabPageFailureLogged;

    /**
     * The list of tab pages.
     *
     * <p>The field is private and NeoForge gives no public access to the WHOLE
     * list (only {@code getCurrentPage()}), so we read it by reflection.
     * The page type itself is available at compile time, so we keep working on it
     * normally, without reflection.
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

    /** The first tab that passes our filter (top left corner). */
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
    // The "deposit into the network" slot in place of the vanilla trash can
    // ------------------------------------------------------------------

    /**
     * Disables the vanilla "Destroy Item" tooltip.
     *
     * <p><b>Why at all.</b> Vanilla keeps the trash slot in the private field
     * {@code destroyItemSlot} and in its own {@code render()} draws its own tooltip
     * based on it - COMPLETELY bypassing
     * {@code getTooltipFromContainerItem}, so overriding the tooltip alone achieved
     * nothing (confirmed in the bytecode):
     * <pre>
     *   if (destroyItemSlot != null
     *           &amp;&amp; selectedTab.getType() == Type.INVENTORY
     *           &amp;&amp; isHovering(...)) {
     *       renderTooltip(font, TRASH_SLOT_TOOLTIP, mouseX, mouseY);
     *   }
     * </pre>
     * Setting the field to {@code null} disables that whole block.
     *
     * <p><b>WHY WE ZERO IT EVERY FRAME.</b> This was the real reason why
     * "Destroy Item" did not disappear despite zeroing it in {@code init()}.
     * The field is assigned in the method <b>{@code selectTab(CreativeModeTab)}</b>
     * (not in {@code init()}): when the selected tab is of type INVENTORY, vanilla
     * creates a NEW trash slot there and adds it to the menu.
     *
     * <p>And our {@code restoreViewState()} calls {@code selectTab} on EVERY
     * opening of the screen (to restore the remembered tab). So the order
     * was like this:
     * <pre>
     *   super.init()            - vanilla creates destroyItemSlot
     *   disableVanillaTrashSlot - we zero the field
     *   restoreViewState()      - selectTab RE-CREATES destroyItemSlot
     * </pre>
     * and the tooltip came back. Zeroing before every {@code super.render()} closes
     * that gap regardless of what recreates the field and when.
     *
     * <p><b>Why by TYPE and not by name.</b> Reflection by name is
     * fragile (in a production environment names may be remapped differently),
     * and its failure used to be swallowed silently. So we look for the only
     * NON-static field of type {@code Slot} - {@code originalSlots} is a list,
     * so it does not match.
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
                        "the trash slot field was not found - vanilla 'Destroy Item' "
                                + "may be visible");
            }
            return;
        }
        try {
            // We check first, because set() on the same object every frame
            // is unnecessary work; the field is usually already null anyway.
            if (trashSlotField.get(this) != null) {
                trashSlotField.set(this, null);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Whether we are looking at the Survival Inventory tab.
     *
     * <p>Only there does vanilla show its trash can (the condition in its
     * {@code render()}: {@code selectedTab.getType() == Type.INVENTORY}), so only
     * there does our deposit slot make sense. In the other tabs that place simply
     * does not exist and drawing an arrow there looked like a bug.
     */
    protected boolean isSurvivalInventoryTab() {
        CreativeModeTab tab = VeloceTerminalViewState.currentTab();
        return tab != null && tab.getType() == CreativeModeTab.Type.INVENTORY;
    }

    /**
     * The "deposit" slot is in the place of the vanilla trash can (173, 112).
     *
     * <p>We did not make the number up: it is the position that vanilla itself sets
     * for {@code destroyItemSlot}, confirmed in the bytecode.
     */
    protected boolean isStoreSlot(Slot slot) {
        return slot != null && slot.x == 173 && slot.y == 112;
    }

    /**
     * Draws the "into the network" arrow on the deposit slot.
     *
     * <p>We draw this in {@code render()} AFTER {@code super}, not in
     * {@code renderSlot}, to be sure we cover the cross burned into the
     * creative inventory texture.
     */
    private void drawStoreSlotIcon(GuiGraphics graphics) {
        if (!isSurvivalInventoryTab()) {
            return;   // outside Survival Inventory that place does not exist
        }
        int x = this.leftPos + 173;
        int y = this.topPos + 112;

        // Slot background - it covers the cross burned into the creative inventory texture.
        graphics.fill(x, y, x + 16, y + 16, 0xFFC6C6C6);
        graphics.fill(x, y, x + 16, y + 1, 0xFF8B8B8B);
        graphics.fill(x, y, x + 1, y + 16, 0xFF8B8B8B);

        drawArrowRight(graphics, x + 3, y + 4);
    }

    /**
     * An arrow to the right: "deposit this into the network".
     *
     * <p>Drawn as a flat figure (shaft + triangular head) so that it is
     * readable in a 16-pixel slot. The previous version assembled the head
     * from single pixels along the diagonal, which looked like random
     * blobs.
     */
    private static void drawArrowRight(GuiGraphics graphics, int x, int y) {
        int color = 0xFF2E7D32;      // dark green - "network"
        int shadow = 0xFF1B5E20;

        // Arrow shaft: 2 px tall.
        graphics.fill(x, y + 3, x + 5, y + 5, color);
        graphics.fill(x, y + 5, x + 5, y + 6, shadow);

        // Head: a triangle with a 7 px base, narrowing to the tip on the right.
        for (int i = 0; i < 4; i++) {
            int half = 3 - i;
            graphics.fill(x + 4 + i, y + 4 - half, x + 5 + i, y + 5 + half, color);
        }
        // Shadow under the head - only the bottom edge, for depth.
        for (int i = 0; i < 4; i++) {
            int half = 3 - i;
            graphics.fill(x + 4 + i, y + 4 + half, x + 5 + i, y + 5 + half, shadow);
        }
    }

    /** Tooltip of the deposit slot - instead of the vanilla "Destroy Item". */
    private void drawStoreSlotTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isSurvivalInventoryTab()) {
            return;
        }
        if (!this.isHovering(173, 112, 16, 16, mouseX, mouseY)) {
            return;
        }
        // TWO lines as separate entries - this guarantees that the tooltip is narrow
        // and that it does NOT turn into one long line.
        java.util.List<net.minecraft.util.FormattedCharSequence> lines = java.util.List.of(
                Component.translatable("gui.craftingveloce.terminal.storeSlot").getVisualOrderText(),
                Component.translatable("gui.craftingveloce.terminal.storeHint")
                        .withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText());

        // OUR OWN POSITIONER: always on the RIGHT side of the cursor.
        //
        // The BUG this fixes: Minecraft's default positioner decides
        // the side based on whether the tooltip FITS. When it did not
        // fit, it FLIPPED it to the left of the mouse. Vanilla "Destroy Item" was
        // short and fit on the right, while our longer tooltip landed on
        // the left - hence the impression of two different tooltips.
        //
        // Now the side is fixed: +12 px to the right of the cursor (exactly as
        // vanilla does it for short tooltips). When there is not enough room at
        // the screen edge, the tooltip is pulled UP TO the edge - but
        // STILL on the right side of the cursor, never on the left.
        graphics.renderTooltip(this.font, lines, TOOLTIP_RIGHT_OF_CURSOR, mouseX, mouseY);
    }

    /**
     * Tooltip positioner: always to the right of the cursor.
     *
     * <p>The argument order comes from the interface:
     * {@code (screenWidth, screenHeight, mouseX, mouseY, tooltipWidth, tooltipHeight)}.
     */
    private static final net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner
            TOOLTIP_RIGHT_OF_CURSOR = (screenWidth, screenHeight, mouseX, mouseY,
                                       tooltipWidth, tooltipHeight) -> {
        // 12 px right and 12 px up - exactly like vanilla.
        int x = mouseX + 12;
        int y = mouseY - 12;

        // At the screen edge we push it to the border, but we do NOT move it to the
        // left side of the cursor. That is the whole difference from the default
        // positioner.
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
        // BEFORE super.render(): that is exactly where vanilla checks destroyItemSlot
        // and draws "Destroy Item". The field is sometimes recreated by selectTab, so
        // we zero it right before drawing.
        try (var ignored = com.craftingveloce.util.VeloceProfiler
                .section("client.render.disableTrashSlot")) {
            disableVanillaTrashSlot();
        }

        // Per FRAME, so the call count in the report is the frame count and total/calls is the
        // average cost per frame. This is the row that says whether our drawing contributes to a
        // low frame rate at all - the item filtering above can only be judged against it.
        try (var ignored = com.craftingveloce.util.VeloceProfiler
                .section("client.render.vanillaSuper")) {
            super.render(graphics, mouseX, mouseY, partialTick);
        }

        // After super, so on top of everything vanilla drew.
        try (var ignored = com.craftingveloce.util.VeloceProfiler
                .section("client.render.overlays")) {
            drawStoreSlotIcon(graphics);
            drawStoreSlotTooltip(graphics, mouseX, mouseY);
        }
    }

    /**
     * Applies our filter to the menu's item list.
     *
     * <p><b>Why this way and not by overriding the vanilla methods.</b>
     * {@code CreativeModeInventoryScreen.refreshSearchResults()} and
     * {@code refreshCurrentTabContents()} are PRIVATE, so they cannot be
     * intercepted by inheritance. And it is exactly they that do
     * {@code menu.items.clear()} and fill the list with the RAW
     * {@code getDisplayItems()}, wiping everything we filtered out - on a tab
     * change, when typing into the search box and from {@code containerTick}.
     *
     * <p>That is why the filter is applied from {@code containerTick} (called every
     * tick, public) and is based on a COMPARISON WITH THE LIST STATE: if vanilla has
     * just rebuilt it, the list differs from our version and the filter runs again.
     * Thanks to that it works regardless of WHO overwrote it and WHEN.
     */
    @Override
    public void containerTick() {
        // PER TICK, i.e. 20 times a second, which is the number that matters: a step that costs
        // 3 ms here is 60 ms of every second stolen from the frame rate, while the same 3 ms
        // inside init() would be invisible. The report shows both the total and the call count,
        // so "20 call(s)" next to a big total reads immediately as "this repeats".
        probeTabParameters();
        try (var ignored = com.craftingveloce.util.VeloceProfiler
                .section("client.creativeScreen.containerTick.super")) {
            super.containerTick();
        }

        // THE PLAYER CHANGING THE TAB.
        //
        // The player can click another tab, and vanilla does the same as on
        // opening: {@code selectTab} clears {@code menu.slots} and rebuilds them
        // from scratch. That wipes both our hiding of the player slots and the
        // disabling of the trash can. So we detect the tab change and re-apply our
        // fixes from scratch.
        CreativeModeTab current = VeloceTerminalViewState.currentTab();
        if (current != lastSeenTab) {
            lastSeenTab = current;
            try (var ignored = com.craftingveloce.util.VeloceProfiler
                    .section("client.creativeScreen.containerTick.tabChanged")) {
                suppressPlayerSlots();
                disableVanillaTrashSlot();
            }
        }

        try (var ignored = com.craftingveloce.util.VeloceProfiler
                .section("client.creativeScreen.containerTick.applyItemFilter")) {
            applyItemFilter();
        }
    }

    /** The tab seen in the previous tick - for detecting changes. */
    @Nullable
    private CreativeModeTab lastSeenTab;

    /**
     * Removes from the list the items rejected by {@link #acceptItem}.
     *
     * <p>Vanilla builds the grid from half the list, so if we leave the rejected
     * entries in it as empty stacks, holes appear. We compact the list and fill the
     * tail with empty stacks - otherwise vanilla would try to read
     * non-existent indices.
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
            // `kept` is built ONLY when something is actually filtered out - see below. This is
            // not a micro-optimisation, it was measured: the creative list in the reference pack
            // holds 21 344 entries, this method runs on EVERY tick, and allocating a 21 344-entry
            // ArrayList 20 times a second was ~100-170 KB of garbage per tick for nothing at all
            // when the filter rejects nothing (which is the normal case for the terminal, whose
            // acceptItem accepts everything).
            List<ItemStack> kept = null;
            // The unit column is what makes this row readable across packs: "21344 unit(s)"
            // is this pack's registered item count, and a report from a small pack can be
            // compared with it directly. It also separates "slow because it ran once over a
            // huge list" from "cheap but ran twenty times a second".
            com.craftingveloce.util.VeloceProfiler.count(
                    "client.applyItemFilter.scan", total);
            try (var ignored = com.craftingveloce.util.VeloceProfiler
                    .section("client.applyItemFilter.scan")) {
                for (int i = 0; i < total; i++) {
                    Object o = list.get(i);
                    boolean accepted =
                            o instanceof ItemStack st && !st.isEmpty() && acceptItem(st);
                    if (accepted) {
                        if (kept != null) {
                            kept.add((ItemStack) o);
                        }
                    } else if (kept == null) {
                        // FIRST rejection: now the compacted list is needed, and the accepted
                        // prefix has to be copied over before continuing.
                        kept = new ArrayList<>(total);
                        for (int j = 0; j < i; j++) {
                            kept.add((ItemStack) list.get(j));
                        }
                    }
                }
            }

            @SuppressWarnings("unchecked")
            NonNullList<ItemStack> items = (NonNullList<ItemStack>) list;

            // There is NO "fast exit" here and that is deliberate.
            //
            // The BUG that was here: we compared the list contents with the desired
            // result and returned when they were identical. The problem is that
            // vanilla (refreshSearchResults / refreshCurrentTabContents)
            // fills the list with the RAW getDisplayItems() - and those are THE SAME
            // ItemStack instances that themselves pass our filter. When none
            // of them is filtered out, the list after the rebuild looks
            // IDENTICAL to the one before it, so we concluded "nothing changed"
            // and SKIPPED scrollTo.
            //
            // And it is scrollTo that copies the list into the CONTAINER from which the
            // slots read. Without it the CONTAINER kept the data from the PREVIOUS
            // filtering - that is, exactly the symptom reported by the
            // user: the GUI shows an unfiltered list until you
            // move the scrollbar (scrolling calls scrollTo itself).
            //
            // So: the SLOTS ARE ALWAYS REFRESHED below, unconditionally - that part of the old
            // fix is what mattered and it stays.
            //
            // What did NOT need to stay is rewriting (and re-allocating) the list itself when
            // nothing was rejected. `kept == null` means every entry passed the filter, so the
            // list already IS the compacted list, reference for reference and in the same order:
            // the rewrite would write each element onto itself. Measured in the reference pack,
            // that rewrite was 3 ms of client thread on EVERY tick - 60 ms of every second, for
            // a loop that provably changed nothing.
            if (kept != null) {
                final List<ItemStack> compacted = kept;
                try (var ignored = com.craftingveloce.util.VeloceProfiler
                        .section("client.applyItemFilter.writeBack")) {
                    for (int i = 0; i < total; i++) {
                        items.set(i, i < compacted.size() ? compacted.get(i) : ItemStack.EMPTY);
                    }
                }
            }
            // UNCONDITIONAL, and this is the line the old fast-exit bug broke: without scrollTo
            // the CONTAINER keeps the data from the previous filtering and the grid looks
            // unfiltered until the player scrolls.
            try (var ignored = com.craftingveloce.util.VeloceProfiler
                    .section("client.applyItemFilter.refreshSlots")) {
                refreshSlotsFromItems();
            }
        } catch (Throwable ignored) {
            // Reflection may fail on a version change - then we simply
            // do not filter, the GUI still works (with holes).
        }
    }

    /**
     * Copies the item list into the menu slots.
     *
     * <p>Vanilla does the same in {@code ItemPickerMenu.scrollTo(float)}, so
     * we call that method with the current scroll position.
     */
    private void refreshSlotsFromItems() {
        try {
            if (!reflectionResolved) {
                resolveReflection();
            }
            if (scrollToMethod == null) {
                return;
            }
            // scrollTo is vanilla's "copy the list into the container slots". Measured
            // separately from the filter because it is REFLECTION: on a version change it can
            // throw and, before this, that failure was silent and the grid simply looked
            // unfiltered until the player scrolled.
            try (var ignored = com.craftingveloce.util.VeloceProfiler
                    .section("client.applyItemFilter.refreshSlots.scrollTo")) {
                scrollToMethod.invoke(this.menu, currentScrollOffset());
            }
        } catch (Throwable t) {
            // NOT silently. A silent failure here has already cost us one long
            // diagnosis: the filter "came back" after scrolling, and there was not
            // a single trace in the log that scrollTo was not being executed at all.
            if (!slotRefreshFailed) {
                slotRefreshFailed = true;
                com.craftingveloce.util.VeloceLog.Gui.failure(
                        com.craftingveloce.util.VeloceLog.Side.CLIENT,
                        "could not refresh item slots after filtering - list will look "
                                + "unfiltered until scrolled: %s", t);
            }
        }
    }

    /** Whether the slot refresh error has already been reported (once is enough). */
    private static boolean slotRefreshFailed;

    /** Resolves the reflection once per process, not once per tick. */
    private void resolveReflection() {
        reflectionResolved = true;
        // scrollTo LIVES IN ItemPickerMenu, NOT in CreativeModeInventoryScreen.
        //
        // THIS WAS THE REAL REASON why the filter "came back" after scrolling.
        // Previously we looked for the method on the screen class:
        //     CreativeModeInventoryScreen.class.getMethod("scrollTo", float.class)
        // and it is NOT there - it is in the nested class ItemPickerMenu.
        // So getMethod threw NoSuchMethodException, scrollToMethod stayed
        // null, and refreshSlotsFromItems() silently did nothing. The list was
        // filtered, but the slots read from the CONTAINER, which nobody refreshed -
        // until the moment the player moved the scrollbar (scrolling calls scrollTo itself).
        //
        // So we look it up on the MENU class (this.menu), not on the screen class.
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

    /** The current list scroll position (the 'scrollOffs' field in vanilla). */
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
     * Hides the tabs rejected by {@link #acceptTab}.
     *
     * <p>We do this by overriding {@link #renderTabButton} (we do not draw the
     * rejected ones) and {@link #checkTabClicked} (we ignore clicks).
     * It cannot be done by removing them from the list, because
     * {@code CreativeModeTabs.tabs()} returns an unmodifiable list.
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
    // Toggling individual items - to be overridden in subclasses
    // ------------------------------------------------------------------

    /** Whether this item can be toggled here at all. By default: no. */
    protected boolean isToggleable(ItemStack stack) {
        return false;
    }

    /** Whether the item is currently on. */
    protected boolean isToggledOn(net.minecraft.world.item.Item item) {
        return false;
    }

    /** Toggles a single item (also sends a packet to the server). */
    protected void applyToggle(net.minecraft.world.item.Item item) {
    }

    // ------------------------------------------------------------------
    // Player slots
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
     * Collects the armor and shield slots from the player's inventory menu.
     *
     * <p>The creative inventory does not have them, but the Survival Inventory
     * tab shows the real player menu - and they are there. They have to be told
     * apart from the regular inventory slots, otherwise they will be hidden.
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

    /** Reads a static int constant from a vanilla class (null when it fails). */
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
     * A replacement slot for hidden player slots.
     *
     * <p>A separate class (not an anonymous one) so that it can be RECOGNIZED on
     * the next call - otherwise we would wrap it endlessly.
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
     * Whether to leave the player's hotbar working.
     *
     * <p>By default we disable the player slots (these GUIs are "fake creative" and
     * are not meant for moving items). The terminal does not want that - the player
     * has to be able to use the hotbar, e.g. to put away pulled-out items. Subclasses
     * override this method returning true.
     */
    protected boolean keepPlayerHotbar() {
        return false;
    }

    /** Replaces the player slots with inactive slots off-screen. */
    protected void suppressPlayerSlots() {
        if (this.menu == null || this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        // ===== THE REGRESSION THIS FIXES =====
        //
        // On the SURVIVAL INVENTORY tab the player's inventory is the ENTIRE CONTENTS
        // of that screen. Hiding it gives an EMPTY GUI - no slots, nothing
        // can be clicked. That is exactly what happened when the suppression started
        // working AFTER the tab selection: previously vanilla selectTab rebuilt
        // the slots and accidentally "revealed" them again.
        //
        // Now the rule is explicit: we hide the player slots ONLY on tabs
        // with an item grid (there they cover our layout), and on Survival
        // Inventory we leave them alone.
        if (isSurvivalInventoryTab()) {
            return;
        }
        // The armor and shield slots on the Survival Inventory tab.
        //
        // BUG: these slots have the player's container, so isPlayerInventorySlot()
        // qualified them for hiding - and they disappeared (only the regular
        // inventory slots remained). The user reported a missing shield slot.
        //
        // We recognize them by their POSITION in the player menu (ARMOR_SLOT_START..COUNT),
        // because those constants are stable and the slot itself has no type of its own.
        java.util.Set<Slot> armorAndShield = collectArmorAndShieldSlots();

        for (int i = 0; i < this.menu.slots.size(); i++) {
            Slot s = this.menu.slots.get(i);

            if (armorAndShield.contains(s)) {
                continue;   // armor and shield stay visible
            }

            // Already hidden - we do not wrap it a second time.
            //
            // The BUG that was here: the replacement slot kept THE SAME container,
            // so isPlayerInventorySlot() still recognized it as a player slot
            // and on the next init() wrapped it again. And init() runs on
            // EVERY rebuildWidgets() (e.g. after every filter click
            // in the controller), so the chain of wrappers grew without limit.
            if (s instanceof HiddenSlot) {
                continue;
            }
            if (isPlayerInventorySlot(s)) {
                // We leave the hotbar if the subclass wants that.
                if (keepPlayerHotbar() && isHotbarSlot(s)) {
                    continue;
                }
                this.menu.slots.set(i, new HiddenSlot(s.container, s.getContainerSlot()));
            }
        }
    }

    /** Whether the slot belongs to the quick access bar (the player's 9 slots). */
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

    /** Whether this is the trash slot (bottom right corner of the creative inventory). */
    protected static boolean isTrashSlot(Slot slot) {
        return slot != null && slot.x == 173 && slot.y == 112;
    }

    /** Paints over the hotbar strip (it is unused in these GUIs). */
    protected void drawHotbarCover(net.minecraft.client.gui.GuiGraphics graphics, int color) {
        graphics.fill(this.leftPos + 8, this.topPos + 111,
                this.leftPos + 170, this.topPos + 130, color);
    }

    protected static Minecraft mc() {
        return Minecraft.getInstance();
    }

    /**
     * A reliable way out of the screen.
     *
     * <p>These screens pretend to be the creative inventory, so it is easy to end up
     * in a situation where the player has no way out - no reaction to Esc or a stuck
     * state. We force closing on Esc and on the inventory key, regardless of
     * what vanilla does.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean typing = isTypingInTextField();

        // THE PLAYER IS TYPING IN THE SEARCH BOX - the keys belong to the FIELD, not to
        // the screen.
        //
        // The BUG this fixes (player report): "E" (the inventory key)
        // closed the GUI while typing. An intermediate version removed the focus,
        // so the first E silently interrupted typing and the second closed the window -
        // the player saw exactly the same thing: "I type and E closes the inventory".
        //
        // Vanilla creative does it like this (bytecode of CreativeModeInventoryScreen):
        //     if (searchBox.keyPressed(...)) { ...; return true; }
        //     if (searchBox.isFocused() && searchBox.isVisible() && key != ESC)
        //         return true;
        // that is: the field handles what it wants, and ALL the rest (including E) is
        // swallowed without closing the window. The focus stays, so typing continues,
        // and the letter "e" reaches the field through charTyped - just as in vanilla.
        if (typing) {
            boolean isEscape = keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
            boolean isInventoryKey = this.minecraft != null && this.minecraft.options != null
                    && this.minecraft.options.keyInventory != null
                    && this.minecraft.options.keyInventory.matches(keyCode, scanCode);

            // ESC ALWAYS CLOSES - even when the text field is active.
            //
            // That is what vanilla does: in its search box branch every key is
            // swallowed EXCEPT Escape (`... && keyCode != 256`), so Esc goes
            // to the base and closes the screen. Previously our "typing" branch only
            // removed the focus, so the player had to press Esc twice - and after
            // fixing the typing detection (searchBox.isFocused) Esc stopped
            // closing AT ALL, because this branch was hit more often. So we block
            // only the inventory key (E), and Esc remains the way out.
            if (isEscape) {
                this.onClose();
                return true;
            }
            if (isEscape || isInventoryKey) {
                // We log exactly this case: without it, it is impossible to tell
                // "the fix does not work" from "the player is testing an old JAR".
                com.craftingveloce.util.VeloceLog.Gui.detail(
                        com.craftingveloce.util.VeloceLog.Side.CLIENT,
                        "text field active: key %d stays in the field (the window does not close)",
                        keyCode);
            }
            if (isInventoryKey && this.minecraft != null && this.minecraft.options != null) {
                // CRITICAL: merely SWALLOWING the key by the screen is NOT ENOUGH.
                //
                // Minecraft.handleKeybinds() does this every tick
                //     while (options.keyInventory.consumeClick()) { ...open the inventory... }
                // and it does NOT look at the screen while doing so (checked in the
                // bytecode: there is no `screen == null` test there). So the pressed "E"
                // stayed in the key queue and after a moment opened the inventory -
                // the player saw this as "E closes my GUI while I am typing".
                // So we consume that click, so that handleKeybinds has nothing
                // to handle. This is exactly the effect the player expects: "E does not
                // close when the text field is active".
                this.minecraft.options.keyInventory.consumeClick();
            }
            // 1) We hand the key over to the VANILLA branch of this screen.
            //
            //    The BUG this fixes: we called the text field DIRECTLY,
            //    which skipped an important part of the vanilla handling -
            //    CreativeModeInventoryScreen.keyPressed checks after every pressed
            //    key whether the text changed, and then calls
            //    refreshSearchResults(). Without that, BACKSPACE changed the text
            //    but the result list stayed old (player report).
            //    Vanilla also handles the arrows, Ctrl+A and pasting correctly.
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        // Esc closes (when we are not typing).
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        // The inventory key (E by default) also closes.
        if (this.minecraft != null && this.minecraft.options != null
                && this.minecraft.options.keyInventory != null
                && this.minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * Whether there is ANYTHING in the grid slots (besides player slots).
     *
     * <p>We build the number request from the slots, and they are sometimes empty even
     * though the items are DISPLAYED - vanilla draws the grid from the {@code items}
     * list and only {@code scrollTo} fills the slots. In the player's log we saw this
     * literally:
     * <pre>
     *   craftable counts: NOTHING to compute (slots=47, player=4, empty=43)
     * </pre>
     * That is, zero requests and zero numbers until you switch the tab or the
     * phrase. That is why we check this state before requesting and - when needed -
     * fill the slots from the item list.
     */
    protected boolean hasGridItems() {
        if (this.menu == null) {
            return false;
        }
        for (Slot slot : this.menu.slots) {
            if (slot != null && slot.hasItem() && !isPlayerInventorySlot(slot)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Makes sure the grid slots match the displayed list.
     *
     * <p>The same thing a tab or phrase change does ({@link #applyItemFilter()}
     * rewrites the list and calls {@code scrollTo}), only called when we
     * notice that the slots are empty - otherwise the "+N" numbers would have
     * nowhere to come from.
     */
    protected void ensureGridItems() {
        if (hasGridItems()) {
            return;
        }
        applyItemFilter();
        if (hasGridItems()) {
            com.craftingveloce.util.VeloceLog.Gui.detail(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "grid slots were empty - I filled them from the item list");
            return;
        }
        // Still empty - we say EXPLICITLY what is empty: vanilla's item list,
        // or only the slots (e.g. scrolled past the list). Without that the next
        // fix would again be guesswork.
        com.craftingveloce.util.VeloceLog.Gui.detail(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "grid slots STILL empty: slots=%d, with items=%d, in menu=%d, "
                        + "scroll=%s",
                this.menu == null ? -1 : this.menu.slots.size(),
                countGridSlotsWithItems(),
                menuItemCount(),
                currentScrollOffset());
    }

    /** How many grid slots (besides the player's) have an item - for diagnostics. */
    private int countGridSlotsWithItems() {
        if (this.menu == null) {
            return 0;
        }
        int n = 0;
        for (Slot slot : this.menu.slots) {
            if (slot != null && slot.hasItem() && !isPlayerInventorySlot(slot)) {
                n++;
            }
        }
        return n;
    }

    /** How many positions the vanilla item list has - for diagnostics. */
    private int menuItemCount() {
        try {
            if (itemsField == null || this.menu == null) {
                return -1;
            }
            Object raw = itemsField.get(this.menu);
            return raw instanceof java.util.List<?> list ? list.size() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Item tooltip WITHOUT the category line and tags that creative appends.
     *
     * <p><b>The BUG this fixes (player report).</b> In the CATEGORY and SEARCH tabs
     * vanilla appends the category name ("Building Blocks") and tags to the tooltip.
     * In the controller a second, own tooltip was added on top of that - and the texts
     * overlapped, covering the numbers next to the icons. The player does not want the
     * category there: "it is hidden everywhere else".
     *
     * <p>So we return a CLEAN item tooltip (name, description, attributes). We keep
     * this in the base class so that the terminal, controller, crafter and filter
     * picker have EXACTLY the same thing - and not four own versions that drift apart.
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
     * Whether the player is currently typing in a text field (the search box).
     *
     * <p>So that the shortcut keys (E, Esc, Enter) do not steal characters
     * or close the window while typing.
     */
    protected boolean isTypingInTextField() {
        try {
            // 1) A widget focused by the screen (our own fields, e.g. the sensor threshold).
            if (this.getFocused() instanceof net.minecraft.client.gui.components.EditBox) {
                return true;
            }
            // 2) THE VANILLA SEARCH BOX - we ask the WIDGET itself, not the screen.
            //
            // The BUG this fixes (a player report repeated twice):
            // we only checked the SCREEN's focus, and the vanilla creative search box
            // lives its own life. When the screen did not point at it as
            // the focused widget, this test came out FALSE, so "E" (the inventory
            // key) closed the GUI while typing. Vanilla asks directly
            // about searchBox.isFocused() (CreativeModeInventoryScreen.keyPressed)
            // and that is the only reliable source of this information.
            return VeloceTerminalViewState.isSearchBoxFocused(this);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void removed() {
        // First we save our view, then we give creative its own back.
        saveViewState();
        restoreCreativeTab();
        // A stack held on the cursor MUST NOT vanish.
        //
        // There used to be `this.menu.setCarried(ItemStack.EMPTY)` here - that is,
        // the item the player had "on the mouse" at the moment the window was closed
        // simply disappeared. The same was repeated by the overrides in the controller
        // and the filter picker, and the crafter screen inherited it from this class.
        // Only the terminal did it correctly.
        //
        // Now we do it once, here: we try to put it into the inventory, and if it does
        // not fit - we drop it into the world (instead of deleting it).
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
            // The debt is paid, so a screen opened later has to capture the mode again.
            pendingModeBeforeGui = null;
        }
    }
}
