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
        if (previous != null && previous.equals(this.networkCounts)) {
            return;
        }
        requestVisibleCounts(true);
        // We do NOT overwrite the whole craftability map.
        //
        // The background (cache) sends its own snapshot, which during a rescan
        // is empty or incomplete. Overwriting used to erase the numbers supplied
        // by the immediate response - and they never came back. So we only merge
        // in what arrived; precise clearing is done by the immediate path.
        this.craftable.putAll(craftable);
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
    public void setCraftError(BlockPos pos, ItemStack stack, String reason, String detail) {
        if (pos != null && pos.equals(this.terminalPos)) {
            this.craftErrors.record(stack, reason, detail);
        }
    }

    @Override
    protected void init() {
        super.init();
        // Immediately after opening: request the numbers for whatever is visible.
        craftable.resetRequestState();
        requestVisibleCounts(true);
    }

    @Override
    public void containerTick() {
        // IMPORTANT: we call the base - otherwise tab detection and item
        // filtering from VeloceCreativeScreen do not work.
        super.containerTick();
        // Live: when the screen contents change (tab, scroll), request the
        // numbers for the new page.
        requestVisibleCounts(false);
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
        ensureGridItems();
        this.craftable.request(terminalPos, this.menu.slots, this::isPlayerSlot, force);
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
