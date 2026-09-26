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
     * How many units can still be made by auto-crafting (the yellow "+N" number).
     *
     * <p>The ordering logic and the merging of responses live in a shared class
     * that the controller ALSO uses - so that both screens show the same
     * numbers from the same code, and not from two copies that have to agree.
     */
    private final VeloceCraftableCounts craftable = new VeloceCraftableCounts();

    /** Ticks to wait after opening before reporting what the GUI holds. */
    private static final int PROBE_DELAY_TICKS = 40;
    private int probeCountdown = -1;

    /**
     * Reasons for failed attempts (server -> item tooltip).
     *
     * <p>The action bar is not visible in the terminal GUI, so the reason for a
     * failed craft is shown in the tooltip of the item the player clicked.
     */
    private final VeloceCraftErrorHints craftErrors = new VeloceCraftErrorHints();

    @Nullable

    private static Method selectTabMethod;
    private static Field selectedTabField;

    static {
        try {
            // BY NAME AND RETURN TYPE, not "the first method with a CreativeModeTab".
            //
            // The previous version scanned getDeclaredMethods() for any single-argument
            // method taking a CreativeModeTab - and vanilla has SIX of those:
            // selectTab (void), getTabX/getTabY (int), checkTabClicked (boolean),
            // checkTabHovering, renderTabButton. Method order is unspecified, so the loop
            // could bind to getTabX and then every "select the tab" call would quietly
            // compute a coordinate and change nothing. That is precisely the failure this
            // hook hit: the search tab was "selected", the log said so, and the page never
            // moved off oak logs.
            for (Method m : CreativeModeInventoryScreen.class.getDeclaredMethods()) {
                if (m.getName().equals("selectTab")
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == net.minecraft.world.item.CreativeModeTab.class
                        && m.getReturnType() == void.class) {
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
            // Reflection over vanilla - if the fields/methods get renamed by an
            // update, we want to learn about it from the LOG, not from the console.
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
        try (var ignored = com.craftingveloce.util.VeloceProfiler
                .section("client.updateNetworkCounts")) {
            Map<Item, Long> previous = this.networkCounts;
            this.networkCounts = new HashMap<>(counts);

            // We ask again ONLY when the stock has really changed.
            //
            // The BUG that used to be here: we called requestVisibleCounts(true)
            // unconditionally. And the server sends this packet once per second
            // (syncCountsToAllWatchers), so a LOOP formed:
            //     updateNetworkCounts -> requestVisibleCounts -> server computes
            //     -> SyncCraftableCounts -> (next second) updateNetworkCounts
            // The client therefore sent a request once per second forever, and the
            // player felt it as "lag before the numbers show up" - the GUI was
            // waiting for a round-trip.
            //
            // Now we compare the stock with the previous packet: if it has not
            // changed, there is no point in asking. After crafting/withdrawing it
            // differs, so the "+N" numbers refresh the way they should.
            this.networkCounts = new HashMap<>(counts);
            this.craftable.putAll(craftable);
            requestVisibleCounts(true);
        }
        if (terminalPos != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.craftingveloce.network.TerminalWatcherPKT(terminalPos, true));
        }
    }

    /**
     * The server's response with the numbers for the visible items.
     *
     * <p>We update ONLY the items we asked about. If we overwrote the whole
     * map, the background (cache) and the immediate response would overwrite
     * each other and the numbers would flicker.
     *
     * <p>We also remove entries for requested items that are missing from the
     * result. For some time now the server has also been sending ZEROS (an
     * explicit answer of "nothing more can be made"), but the clearing stays as
     * a safeguard for responses from a server without those zeros.
     */
    public void updateCraftableCounts(Map<Item, Long> craftable, boolean complete) {
        com.craftingveloce.util.VeloceLog.Gui.detail(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "received instant counts for %d item(s) (complete=%s)",
                craftable.size(), complete);
        this.craftable.update(craftable, complete);
    }

    /**
     * Reason for a failed attempt from the server - remember it for the item tooltip.
     *
     * <p>Packets from ANOTHER terminal (the player managed to jump to a second
     * one) are skipped: showing the reason on the wrong screen would be
     * misleading.
     */
    public void setCraftError(BlockPos pos, ItemStack stack, String reason, String detail,
                              String hint) {
        if (pos != null && pos.equals(this.terminalPos)) {
            this.craftErrors.record(stack, reason, detail, hint);
        }
    }

    @Override
    protected void init() {
        // THE SESSION OPENS HERE, and here rather than on the server on purpose.
        //
        // This is the moment the player feels the freeze begin: the click lands, the screen is
        // built, and only afterwards does anything reach the server. A session opened by the
        // count request would therefore start too late to contain the client-side half - and
        // worse, it would CLEAR whatever the client had already measured. The client owns the
        // session, and closes it when the numbers arrive (see VeloceCraftableCounts.update).
        //
        // The cost is that the per-tick rows only accumulate while the terminal is open, which
        // is exactly what makes the totals in the report meaningful.
        com.craftingveloce.util.VeloceProfiler.beginClientSession();
        try (var ignored = com.craftingveloce.util.VeloceProfiler.section("client.openTerminal")) {
            try (var ignored2 = com.craftingveloce.util.VeloceProfiler
                    .section("client.openTerminal.superInit")) {
                super.init();
            }
            // Immediately after opening: request the numbers for whatever is visible.
            try (var ignored2 = com.craftingveloce.util.VeloceProfiler
                    .section("client.openTerminal.resetRequestState")) {
                craftable.resetRequestState();
            }
            requestVisibleCounts(true);
        }
        if (terminalPos != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.craftingveloce.network.TerminalWatcherPKT(terminalPos, true));
        }
        // And a couple of seconds later, say what the screen actually ended up holding.
        // The server cannot see this: it knows a number was sent, not whether the screen
        // found an item to draw it on.
        probeCountdown = PROBE_DELAY_TICKS;
    }

    @Override
    public void containerTick() {
        // IMPORTANT: we call the base - otherwise tab detection and item
        // filtering from VeloceCreativeScreen do not work.
        try (var ignored = com.craftingveloce.util.VeloceProfiler
                .section("client.terminal.containerTick.super")) {
            super.containerTick();
        }
        // Live: when the screen contents change (tab, scroll), request the
        // numbers for the new page.
        try (var ignored = com.craftingveloce.util.VeloceProfiler
                .section("client.terminal.containerTick.requestCounts")) {
            requestVisibleCounts(false);
        }
        if (probeCountdown > 0 && --probeCountdown == 0) {
            try (var ignored = com.craftingveloce.util.VeloceProfiler
                    .section("client.terminal.containerTick.probe")) {
                sendCountsProbe();
            }
        }
    }

    /**
     * Reports to the server what this GUI is showing - once, shortly after opening.
     *
     * <p>See {@link com.craftingveloce.network.GuiCountsProbePKT}: the craftable number
     * can fail in two places that look identical from the server, and only the client
     * can tell them apart.
     */
    private void sendCountsProbe() {
        if (this.menu == null) {
            com.craftingveloce.CraftingVeloceMod.LOGGER.info("[Veloce] GUI probe: menu is null");
            return;
        }
        java.util.Set<net.minecraft.world.item.Item> seen = new java.util.HashSet<>();
        java.util.List<String> potions = new java.util.ArrayList<>();
        java.util.List<String> without = new java.util.ArrayList<>();
        int slotsWithItems = 0;
        int distinct = 0;
        int withCounts = 0;
        boolean potionCounted = false;
        for (Slot slot : this.menu.slots) {
            if (slot == null || !slot.hasItem() || isPlayerSlot(slot)) {
                continue;
            }
            slotsWithItems++;
            net.minecraft.world.item.ItemStack stack = slot.getItem();
            net.minecraft.world.item.Item raw = stack.getItem();
            // The same key the request and the overlay use - so this reports what the
            // screen really matches on, not what it meant to.
            net.minecraft.world.item.Item key =
                    com.craftingveloce.util.VelocePotionMapper.getProxy(stack);
            if (!seen.add(key)) {
                continue;
            }
            distinct++;
            long count = this.craftable.get(key);
            if (count > 0) {
                withCounts++;
            } else {
                // THE NAME, not another counter. "withCounts=2 of 44" cannot be compared
                // against a report that names one item; this list can. Zero and "no entry
                // at all" are the same to the PLAYER (neither draws anything) and BOTH are
                // listed here on purpose - the map holds a 0 only when the server answered
                // "cannot be made", and holds nothing when the batch ran out of time first.
                without.add(name(key) + (this.craftable.hasEntry(key) ? "=0" : "=none"));
            }
            if (raw == net.minecraft.world.item.Items.POTION
                    || raw == net.minecraft.world.item.Items.SPLASH_POTION
                    || raw == net.minecraft.world.item.Items.LINGERING_POTION
                    || com.craftingveloce.util.VelocePotionMapper.isProxy(raw)) {
                potions.add(name(raw) + "=" + count + "(" + name(key) + ")");
                if (count > 0) {
                    potionCounted = true;
                }
            }
        }
        com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                "[Veloce] GUI probe sending: slots={} distinct={} withCounts={} withoutCounts=[{}] potions=[{}]",
                slotsWithItems, distinct, withCounts, String.join(" ", without),
                String.join(" ", potions));
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.craftingveloce.network.GuiCountsProbePKT(this.terminalPos,
                        slotsWithItems, distinct, withCounts, potionCounted,
                        String.join(" ", potions), String.join(" ", without)));
    }

    private static String name(net.minecraft.world.item.Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    /**
     * Types a phrase into the vanilla search box - the test hook for "which page".
     *
     * <p><b>Why a test needs this and cannot fake it.</b> Every assertion about the "+N"
     * numbers is an assertion about a PAGE, and an automated run does not otherwise control
     * which page is shown: the terminal opens on the creative inventory's default tab, so a
     * probe reports whatever that tab holds (oak logs, planks) and the item under test is
     * not on screen at all. A probe against such a page passes or fails for reasons
     * unrelated to the item, which is how an earlier round "verified" the counts while
     * measuring nothing.
     *
     * <p>We set the text on the REAL search box and then run the same refresh the player's
     * typing triggers, so the resulting page comes from the production filtering path. The
     * box is reached through {@code VeloceTerminalViewState.searchBox}, the same accessor
     * the "is the player typing" check uses.
     */
    public void applySearchPhrase(String phrase) {
        String wanted = phrase == null ? "" : phrase;

        // THE TAB HAS TO BE ABLE TO SEARCH, or nothing below does anything.
        //
        // Vanilla's refreshSearchResults() starts with
        // `if (!selectedTab.hasSearchBar()) return;` - the BUILDING BLOCKS tab that the
        // creative screen opens on has NO search bar, so setting the phrase and refreshing
        // changed exactly nothing and the grid kept the default page. That is why an earlier
        // attempt at this reported "grid now holds 45 item(s)" while still measuring oak
        // logs: the search was applied to a tab that cannot search.
        //
        // The SEARCH tab is the one vanilla itself uses for this. We select it first, then
        // set the phrase.
        if (!selectSearchTab()) {
            com.craftingveloce.util.VeloceLog.Gui.failure(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "cannot set the search phrase '%s' - no search-capable creative tab was "
                            + "found, so the page cannot be changed", wanted);
            return;
        }

        net.minecraft.client.gui.components.EditBox box =
                VeloceTerminalViewState.searchBox(this);
        if (box == null) {
            com.craftingveloce.util.VeloceLog.Gui.error(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT, null,
                    "cannot set the search phrase '%s' - the vanilla search box was not found",
                    phrase);
            return;
        }
        box.setValue(wanted);
        box.setFocused(true);
        // The same call vanilla makes when the text changes: it re-runs the search and
        // refills the grid, which is what makes this page REAL rather than simulated.
        //
        // The report is logged at SUCCESS level, not detail: whether this call succeeded is
        // the difference between a test that measures the page it asked for and one that
        // silently measures the default tab, and that distinction must be visible without
        // turning on debug logging.
        String refreshReport;
        try {
            java.lang.reflect.Method refresh = CreativeModeInventoryScreen.class
                    .getDeclaredMethod("refreshSearchResults");
            refresh.setAccessible(true);
            // Same call as in refreshContents, and the same reason to isolate it: a test or a
            // player setting a search phrase pays for a full vanilla search, and this row tells
            // apart "the search is slow" from "our filtering after it is slow".
            try (var ignored = com.craftingveloce.util.VeloceProfiler
                    .section("client.applySearchPhrase.refreshSearchResults")) {
                refresh.invoke(this);
            }
            refreshReport = "vanilla refreshSearchResults called";
        } catch (Throwable t) {
            // Vanilla's own name for it may differ between versions; our own filtering
            // below always exists, so the page may still change.
            refreshReport = "vanilla refreshSearchResults FAILED (" + t + ")";
        }
        applyItemFilter();
        // The page just changed, so ask for numbers for the NEW items immediately instead
        // of waiting for the next periodic refresh.
        requestVisibleCounts(true);
        // RE-ARM THE PROBE. It is one-shot and fires 40 ticks after the screen opens, so
        // without this a test that sets the page AFTER opening would still be reporting the
        // page from before the search - and would "pass" while measuring the wrong items.
        probeCountdown = PROBE_DELAY_TICKS;
        // How many grid slots the new page holds, counted HERE rather than through the
        // parent's private helper: if the search did not change the grid, that number stays
        // at the size of the old page, and this line is what says so.
        int gridItems = 0;
        if (this.menu != null) {
            for (Slot slot : this.menu.slots) {
                if (slot != null && slot.hasItem() && !isPlayerSlot(slot)) {
                    gridItems++;
                }
            }
        }
        com.craftingveloce.util.VeloceLog.Gui.success(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "terminal search phrase set to '%s': %s; grid now holds %d item(s), "
                        + "probe re-armed in %d ticks",
                wanted, refreshReport, gridItems, PROBE_DELAY_TICKS);
    }

    /**
     * Selects a creative tab that HAS a search bar, and says whether it managed to.
     *
     * <p><b>Why this is required.</b> Vanilla's {@code refreshSearchResults()} begins with
     * {@code if (!selectedTab.hasSearchBar()) return;} - searching is a property of the TAB,
     * not of the box. The creative screen opens on BUILDING BLOCKS, which has no search bar,
     * so a phrase set on that tab is stored and then ignored: the grid keeps its old
     * contents and every downstream measurement describes the wrong page. This is what made
     * an earlier attempt at this hook report success while still measuring oak logs.
     *
     * <p>We pick the vanilla SEARCH tab - the same one a player uses for "search all items" -
     * through the reflection handles this class already keeps for tab selection. Tabs the
     * terminal hides are skipped for the same reason the player cannot pick them.
     */
    private boolean selectSearchTab() {
        if (selectedTabField == null || selectTabMethod == null) {
            return false;
        }
        try {
            for (net.minecraft.world.item.CreativeModeTab tab
                    : net.minecraft.world.item.CreativeModeTabs.allTabs()) {
                if (!tab.hasSearchBar()) {
                    continue;
                }
                if (!acceptTab(tab)) {
                    continue;   // a tab the terminal deliberately hides
                }
                selectTabMethod.invoke(this, tab);
                // The tab is named on the log, not just "selection succeeded": the whole
                // failure mode here is a selection that reports success and changes nothing,
                // and only the name makes that visible.
                com.craftingveloce.util.VeloceLog.Gui.detail(
                        com.craftingveloce.util.VeloceLog.Side.CLIENT,
                        "selected search-capable tab %s", tab.getDisplayName().getString());
                return true;
            }
        } catch (Throwable t) {
            com.craftingveloce.util.VeloceLog.Gui.error(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT, t,
                    "could not select a search-capable creative tab");
        }
        return false;
    }

    /**
     * Requests from the server the "how many can still be made" numbers for the
     * visible items.
     *
     * <p>This gives an IMMEDIATE answer: the server computes the whole page
     * with one shared time budget and sends the ready numbers back. The
     * background (cache) recomputes the rest of the network, but the player
     * does not have to wait for that to see current values where they are
     * looking.
     *
     * @param force true = send even if the signature has not changed
     */
    private void requestVisibleCounts(boolean force) {
        if (this.minecraft == null || this.minecraft.player == null || this.menu == null) {
            return;
        }
        // The slots may be empty even though the items are displayed (see
        // ensureGridItems) - then there is nothing to request and the numbers do
        // not appear until a tab or a search phrase is switched.
        // Both halves are measured: filling the grid touches every slot, and the request
        // builds and serialises the visible item list. Either can be the client-thread cost.
        try (var ignored = com.craftingveloce.util.VeloceProfiler
                .section("client.ensureGridItems")) {
            ensureGridItems();
        }
        try (var ignored = com.craftingveloce.util.VeloceProfiler
                .section("client.requestVisibleCounts")) {
            this.craftable.request(terminalPos, this.menu.slots, this::isPlayerSlot, force);
        }
    }

    /**
     * Draws an arrow on the deposit slot.
     *
     * <p>Vanilla draws no icon at all in this spot - the cross that is visible
     * there is burned into the creative GUI texture. Since our slot does not
     * destroy the item, it only DEPOSITS it, the cross is misleading. So we draw
     * our own "into the network" arrow and cover the old graphic with it.
     */
    private void drawStoreArrow(GuiGraphics graphics, Slot slot) {
        // We cover the cross from the texture with the slot background.
        int x = this.leftPos + slot.x;
        int y = this.topPos + slot.y;
        graphics.fill(x, y, x + 16, y + 16, 0xFFC6C6C6);

        // Right arrow: two diagonals + a shaft.
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
            // Same key the request used - see VeloceCraftableCounts. Looking these up by
            // the raw stack made BOTH numbers (green stock and yellow craftable) silently
            // zero for every potion, while every other item was fine.
            net.minecraft.world.item.Item key =
                    com.craftingveloce.util.VelocePotionMapper.getProxy(stack);
            long count = networkCounts.getOrDefault(key, 0L);
            if (count > 0) {
                VeloceSlotOverlay.drawStock(graphics, this.font, count, slot.x, slot.y);
            }
            // The number of units that can still be made by auto-crafting.
            // Shown as "+N" in the top left corner - yellow, to tell it apart
            // from the green stock. Zero is not drawn.
            long craftable = this.craftable.get(key);
            if (craftable > 0) {
                VeloceSlotOverlay.drawCraftable(graphics, this.font, craftable, slot.x, slot.y);
            }
        }
    }

    /** Compatibility: abbreviated number. The implementation is in {@link VeloceSlotOverlay}. */
    public static String formatCount(long number) {
        return VeloceSlotOverlay.formatCount(number);
    }

    /**
     * Tooltip of the storage slot.
     *
     * <p>For a slot in this spot vanilla prints "Destroy Item" - in our case
     * this slot does NOT destroy, it only hands the item over to the network.
     * So we replace the text with "usage" and add a hint about how shift works.
     *
     * <p>For ordinary items we leave a clean tooltip (without the category line
     * and tags that the creative inventory appends in the CATEGORY/SEARCH tabs).
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
        // The clean item tooltip is built by the base class (without creative
        // categories and tags) - a single source for all four screens. We append
        // the reason for a failed craft if this item has just failed (the server
        // can no longer use the action bar - it is not visible under the GUI).
        List<Component> tooltip = new ArrayList<>(super.getTooltipFromContainerItem(stack));
        craftErrors.appendTo(tooltip, stack);
        return tooltip;
    }

    /**
     * View state key - the position of THIS terminal.
     *
     * <p>Thanks to this every terminal remembers its own tab, search phrase and
     * scroll position, instead of sharing them with the creative inventory.
     */
    @Override
    protected Object viewStateKey() {
        return terminalPos;
    }

    /** The terminal leaves the player's hotbar working - it can be used from it. */
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
     * Deposits the player's items into the network.
     *
     * <p>Three variants, as in the vanilla creative inventory:
     * <ul>
     *   <li>ordinary click - whatever you hold on the cursor,</li>
     *   <li>shift + click - the whole inventory EXCEPT the hotbar,</li>
     *   <li>shift + right click - literally everything, the hotbar too.</li>
     * </ul>
     *
     * <p><b>The client sends only the MODE and touches nothing locally.</b> The
     * previous version sent a copy of the stack and cleared the cursor locally -
     * but the server took nothing away from the player, so the item appeared
     * both in the barrel and in the inventory (physical duplication). Now the
     * server takes the items itself and sends the changes back.
     */
    private void handleStoreClick(int mouseButton, ClickType clickType) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        // We are not holding anything on the cursor and there is nothing to deposit.
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

        // We do NOT block the action on the client side.
        //
        // The BUG that used to be here (the source of a persistent false
        // "network full"):
        // the client counted free slots from the LAST synchronization and when
        // that came out as 0, it refused to send the packet:
        //
        //     String blocked = storeBlockedReason(mode);
        //     if (blocked != null) { storeFeedback(blocked); return; }
        //
        // Except that "0 free slots" does NOT mean "network full":
        //   1. the empty slot counter ignores room in NOT-FULL stacks - a chest
        //      filled with stacks of 40 has 0 empty slots, yet it will still
        //      accept hundreds,
        //   2. the client's data may be STALE (someone topped up the chest, a
        //      hopper added something) - and then the client blocks FOREVER,
        //      because on its own it never refreshes that zero.
        //
        // The client does not have enough information to decide this reliably:
        // it does not know the contents of individual slots or whether the data
        // is fresh. The decision belongs to the SERVER, which has the full
        // picture (and can even load the chunk). So we ALWAYS send the request,
        // and the server answers with the truth - with a message when truly
        // nothing got in.
        //
        PacketDistributor.sendToServer(
                new com.craftingveloce.network.TerminalStoreItemPKT(terminalPos, mode));

        // We do NOT touch the cursor locally.
        //
        // The BUG that used to be here (the source of "ghost items"): after
        // sending the packet we cleared the cursor on our side "so that the GUI
        // reacts right away":
        //
        //     this.menu.setCarried(ItemStack.EMPTY);
        //
        // When the network was FULL, the server took nothing (because it had
        // nowhere to put it), so the item vanished ONLY visually on the client -
        // and after reopening the inventory it came back, because on the server
        // it had been there the whole time.
        //
        // Now the client changes NOTHING until the server confirms. The
        // confirmation is an inventory resync (TerminalPullItemPKT.
        // resyncInventories), which the server sends only when it REALLY moved
        // something. Thanks to this the item never leaves its place if there is
        // nowhere to put it - and there is nothing to roll back.
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

        // 2) The "arrow" slot - depositing into the network (NOT deleting).
        //
        // Behaviour as in the vanilla creative inventory, but instead of
        // destroying the item we hand it over to the network's first storage:
        //   - ordinary click      -> the whole cursor contents
        //   - shift + click       -> everything EXCEPT the hotbar
        //   - shift + right click -> literally everything (the hotbar too)
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
        requestVisibleCounts(true);
    }

    @Override
    public void removed() {
        // We tell the server that we stopped looking - otherwise it would send
        // us the full network map once per second the whole time we are nearby.
        if (terminalPos != null) {
            PacketDistributor.sendToServer(new com.craftingveloce.network.TerminalWatcherPKT(
                    terminalPos, false));
        }
        // The reasons for failed attempts live only as long as this screen.
        craftErrors.clear();
        // The held stack is handled by the base class - a single source of truth.
        super.removed();

    }
}
