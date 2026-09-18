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
 * Veloce Controller GUI - network overview with filtering.
 *
 * <p>Three view modes (buttons at the bottom of the screen):
 * <ul>
 *   <li><b>SHOW ALL</b> - everything that is in the network + everything craftable</li>
 *   <li><b>AVAILABLE</b> - only what is really available: it is in stock,
 *       OR the crafter will make it, OR a powered furnace will smelt it</li>
 *   <li><b>NOT AVAILABLE</b> - the crafter will not make it, the furnace will not smelt it
 *       and it is not in stock; these are exactly the items worth planning as machines
 *       (extractor + chest)</li>
 * </ul>
 *
 * <p>Icon background colours:
 * <ul>
 *   <li>blue - the item is in stock in the network</li>
 *   <li>yellow - the item is missing, but the crafter can make it</li>
 *   <li>orange - the item is missing, but a powered furnace has a recipe for it</li>
 *   <li>red - unavailable (no stock, no crafting and no smelting)</li>
 * </ul>
 *
 * <p>The tooltip shows ONLY what is needed: the name, the rate (per minute and per
 * hour) and - for items with a furnace recipe - the "crafting or furnace" preference.
 * Stock is NOT in it (the number is on the icon), nor are reasons for unavailability
 * or messages about a state that works - the player asked for those to be removed.
 *
 * <p>We build the tooltip in {@link #getTooltipFromContainerItem(ItemStack)}, that is,
 * we replace vanilla's line list instead of drawing our own next to it - otherwise
 * TWO tooltips appeared at once, and the vanilla one appends the category name on the
 * SEARCH tab, which overlapped the numbers.
 */
public class VeloceControllerScreen extends VeloceCreativeScreen {

    /**
     * View filtering mode. Each one has a translation key so that the label
     * comes from the game language rather than being hardcoded.
     */
    public enum Filter {
        ALL("gui.craftingveloce.controller.filter.all",
                "gui.craftingveloce.controller.filter.all.tip"),
        AVAILABLE("gui.craftingveloce.controller.filter.available",
                "gui.craftingveloce.controller.filter.available.tip"),
        NOT_AVAILABLE("gui.craftingveloce.controller.filter.notAvailable",
                "gui.craftingveloce.controller.filter.notAvailable.tip");

        /** Short label on the button (it must fit within 52 px). */
        final String key;
        /** The full meaning of the filter - shown in the button tooltip. */
        final String tooltipKey;

        Filter(String key, String tooltipKey) {
            this.key = key;
            this.tooltipKey = tooltipKey;
        }
    }

    private final BlockPos controllerPos;
    /**
     * Network stock - REFRESHED every second by the rate packet.
     *
     * <p>It has not been final since the controller started receiving fresh stock
     * together with the rate (see SyncControllerFlowPKT): with the GUI open the player
     * can take an item out of a chest and the number has to change immediately.
     */
    private Map<Item, Long> stock;
    /**
     * Items that the crafter REALLY makes (auto-crafting enabled) - green background.
     *
     * <p>This is not the same as "has a recipe": {@code craftable} (the recipe exists,
     * but the crafter may have it disabled) is no longer needed, because the player
     * asked for the texts about reasons for unavailability to be removed.
     */
    private final Set<Item> craftingEnabled;
    /** Items with a FURNACE recipe (smelting / blasting / smoking). */
    private final Set<Item> furnaceCraftable;
    /** Whether any furnace is powered - only then are furnace recipes real. */
    private final boolean furnacePowered;
    /**
     * Items for which the player prefers SMELTING over crafting.
     *
     * <p>Kept on the network side (see ControllerPreferKindPKT), here a copy for
     * drawing the hint. A change goes locally right away (so the click responds
     * immediately), and the server is the single source of truth on the next
     * opening of the GUI.
     */
    private final Set<Item> furnacePreferred = new HashSet<>();

    private Filter filter = Filter.ALL;

    /**
     * How many more can be made - the same shared logic and the same drawing code as
     * in the terminal. The controller had its own, simplified version that drew the
     * numbers without scaling the font - and that is why they were either invisible
     * or spilled outside the icon.
     */
    private final VeloceCraftableCounts craftableCounts = new VeloceCraftableCounts();

    private final List<Button> filterButtons = new ArrayList<>();

    // ---------- flow (steady gain / loss) ----------
    /**
     * Rate in items per SECOND - ONLY for items with a steady trend.
     *
     * <p>The server decides by itself what counts as a trend (one-directional movement
     * that repeated at least twice) and sends only such items. A missing entry =
     * "standing still or jumping around" = there is no rate line in the tooltip.
     * One number, two scales (per minute and per hour) are computed by the client.
     */
    private Map<Item, Float> flowRate = new HashMap<>();

    /**
     * Item -&gt; "create, mekanism": which mods could make it.
     *
     * <p>Sent by the SERVER because the answer needs a {@code ServerLevel}
     * ({@code recipesAnywhere(ServerLevel, Item)}), which this screen does not have. Used
     * only for items shown as RED - the tooltip then says where the item could have been
     * made instead of leaving the player with a colour and no way forward.
     */
    private Map<Item, String> madeByMods = new HashMap<>();

    /**
     * Width of the filter button.
     *
     * <p>Constant, because the build enforces it (validate_filter_labels): the label
     * has to fit. The previous labels ("Show all", "Not available") ran outside the
     * button and overlapped the neighbour.
     */
    private static final int FILTER_BUTTON_W = 52;

    /** How often we ask the server for a fresh rate. */
    private static final int FLOW_REQUEST_INTERVAL_TICKS = 20;
    /** Below this rate (items/s) we show nothing - the same threshold as in the tracker. */
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

    // ---------- item classification ----------

    private boolean hasStock(Item item) {
        return stock.getOrDefault(item, 0L) > 0;
    }

    /**
     * Can the item be obtained in a FURNACE.
     *
     * <p>We require TWO things: a furnace recipe AND a powered furnace in the network.
     * The mere existence of a recipe is not enough - without fuel nothing will smelt.
     */
    private boolean furnaceCanSmelt(Item item) {
        return furnacePowered && furnaceCraftable.contains(item);
    }

    /**
     * Can the item be obtained: it is in stock, the crafter will make it or the furnace
     * will smelt it.
     *
     * <p><b>The ONE source of truth about availability.</b> The filter and the icon
     * colour ask about the same thing, so they cannot drift apart. The order is the
     * same in both places: stock, then crafter, then furnace.
     *
     * <p>We check {@code craftingEnabled}, not just "has a recipe": an item may have a
     * recipe that the crafter does not execute.
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
     * The filter works on the item LIST, not just on drawing the icon.
     *
     * <p><b>There used to be a real mix-up here.</b> The filter was checked only in
     * {@code renderSlot} and in the tooltip, so filtered-out items remained in the grid
     * as empty slots (a whole mass of holes), and their slots were still CLICKABLE -
     * you could pull out an item that had just been marked as unavailable.
     *
     * <p>Now {@code applyItemFilter} from the base class removes them from the list and
     * compacts the grid - exactly as the crafter screen does.
     */
    @Override
    protected boolean acceptItem(ItemStack stack) {
        return !stack.isEmpty() && passesFilter(stack.getItem());
    }

    /**
     * Icon background colour by availability state.
     *
     * <p><b>Green = the crafter will make it, yellow = the furnace will smelt it.</b>
     * Previously the crafter was yellow and the furnace orange; the player wanted green
     * for crafting (as in other mods) and left yellow for "can be obtained another way",
     * that is, for the furnace. Blue (stock) and red (missing and cannot be made) stay
     * unchanged.
     *
     * <p>The order is the same as in {@link #isAvailable}: stock, crafter, furnace.
     */
    private int colorFor(Item item) {
        if (hasStock(item)) {
            return 0x770000AA;  // blue: in stock
        }
        if (craftingEnabled.contains(item)) {
            return 0x7700AA00;  // green: the crafter will make it
        }
        if (furnaceCanSmelt(item)) {
            return 0x77AAAA00;  // yellow: the furnace will smelt it (another path)
        }
        return 0x77AA0000;      // red: missing and cannot be made
    }

    /**
     * Refreshes the flow rate and the stock CHANGE - WITHOUT rebuilding the screen.
     *
     * <p>This is the reason the flow has its own packet: if the server sent a full
     * network snapshot every second and told us to recreate the screen from scratch,
     * the player would lose the selected filter, the scroll position and the typed
     * search on every refresh.
     *
     * <p><b>Stock arrives as a delta, not as a whole</b> (see
     * {@code VeloceStockDeltas}): in a large network a full snapshot every second is
     * thousands of entries without a change. That is why we merge the changes and remove
     * the items that disappeared, while a full dump (once a minute) simply replaces
     * the map.
     *
     * @param pos     position of the controller the packet concerns - we ignore
     *                packets for another controller so as not to mix up the data
     * @param changed new entries or entries with a changed count (absolute values)
     * @param removed items that are no longer in the network
     * @param full    whether this is a full dump (replace stock, do not merge)
     */
    public void updateFlow(net.minecraft.core.BlockPos pos,
                           Map<Item, Long> changed,
                           Set<Item> removed,
                           Map<Item, Float> rates,
                           Map<Item, String> madeBy,
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
        // Kept, not replaced: the server only sends the entries its delta mentions, and an
        // answer that was sent earlier is still the same answer - an item's set of possible
        // makers depends on which mods are installed, not on the network.
        this.madeByMods.putAll(madeBy);
    }

    /**
     * Asks the server for a fresh rate as long as the screen is open.
     *
     * <p>A request, not a subscription: the server does not have to remember who is
     * watching, so it is not left with a stale state when the client leaves the game.
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
        // We request the "to be made" numbers at the same rhythm - like the terminal.
        requestVisibleCounts(false);
    }

    // ---------- lifecycle ----------

    @Override
    protected void init() {
        super.init();   // base: creative mode, hiding player slots, item filter
        buildFilterButtons();
        craftableCounts.resetRequestState();
        requestVisibleCounts(true);
    }

    /** Requests the "to be made" numbers for the visible page - like the terminal. */
    private void requestVisibleCounts(boolean force) {
        if (this.minecraft == null || this.minecraft.player == null || this.menu == null) {
            return;
        }
        // As in the terminal: empty slots = no request = no numbers.
        ensureGridItems();
        craftableCounts.request(controllerPos, this.menu.slots,
                this::isPlayerInventorySlot, force);
    }

    /** The server sends the computed numbers - as in the terminal. */
    public void updateCraftableCounts(Map<Item, Long> counts, boolean complete) {
        craftableCounts.update(counts, complete);
    }

    /**
     * Filter buttons placed in the hotbar strip (which is empty anyway).
     *
     * <p><b>Why the labels are SHORT.</b> The button is 52 px wide, and the previous
     * labels ("Show all", "Not available") did not fit in it - "Not available" is
     * ~78 px, so the text ran outside the button and overlapped the neighbouring one.
     * That is why the label is a single word and the FULL meaning moved into the
     * tooltip - nothing was lost, it just stopped spilling over. The OPTIONS themselves
     * (what they filter) stay unchanged.
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

        // Filter: an item outside the filter is treated like an empty slot (no icon).
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

        // Two numbers - EXACTLY as in the terminal, with the same code:
        //   white = how much is in stock (bottom right corner),
        //   yellow = how many more can be made, e.g. "+12" (top left corner).
        VeloceSlotOverlay.drawStock(graphics, this.font,
                stock.getOrDefault(item, 0L), slot.x, slot.y);
        VeloceSlotOverlay.drawCraftable(graphics, this.font,
                craftableCounts.get(item), slot.x, slot.y);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Background under the filter buttons (the hotbar is empty).
        int x1 = this.leftPos + 8;
        int y1 = this.topPos + 111;
        int x2 = this.leftPos + 170;
        int y2 = this.topPos + 130;
        graphics.fill(x1, y1, x2, y2, 0xFFC6C6C6);

        // We draw the buttons after the background, otherwise they get painted over.
        for (Button b : filterButtons) {
            b.render(graphics, mouseX, mouseY, partialTick);
        }

    }

    /**
     * Icon tooltip: the name, the rate (per minute and per hour) and the "crafting or
     * furnace" preference - for items with a furnace recipe.
     *
     * <p><b>The BUG this fixes (player report).</b> The controller drew its OWN tooltip
     * while the vanilla one was drawn next to it - two at once. The vanilla one on the
     * SEARCH tab additionally appends the category name ("Building Blocks"), so the
     * texts overlapped and the category covered the numbers. The terminal did this
     * correctly from the start: it overrides this method, so EXACTLY one line list is
     * drawn - without the category and without tags.
     *
     * <p><b>Stock disappeared from the tooltip</b> at the player's request: the number
     * is already drawn on the icon (bottom right corner), so the "Stock: N" line was
     * a repetition.
     */
    @Override
    public List<Component> getTooltipFromContainerItem(ItemStack stack) {
        if (this.minecraft == null || this.minecraft.player == null || stack.isEmpty()) {
            return super.getTooltipFromContainerItem(stack);
        }
        Slot hovered = getSlotUnderMouse();
        if (hovered != null && isPlayerInventorySlot(hovered)) {
            return super.getTooltipFromContainerItem(stack);   // player slots unchanged
        }
        List<Component> lines = new ArrayList<>();
        lines.add(stack.getHoverName());
        addFlowLines(lines, stack.getItem());
        addPreferenceLines(lines, stack.getItem());
        addMakerLine(lines, stack.getItem());
        return lines;
    }

    /**
     * One rate line - only for items with a STEADY trend.
     *
     * <p><b>What the player did not want.</b> Two lines ("1 min:" and "1 hour:")
     * plus a "no change" line for every item. Instead: one line with ONE number in two
     * scales, and only when the item really has a steady gain or a steady loss (the
     * server sends only such items - see VeloceFlowTracker.steadyRates). When nothing
     * steady is happening, there is no line here at all.
     */
    /**
     * "Also available in: create, mekanism" - for an item shown as RED.
     *
     * <p>Red means the network cannot supply it and nothing here can make it. Saying which
     * MODS have a recipe for it is the difference between "you cannot have this" and "you
     * cannot have this YET - go and look at Create". Nothing is added for an item that is
     * available, because there the line would be noise.
     */
    private void addMakerLine(List<Component> lines, Item item) {
        if (isAvailable(item)) {
            return;
        }
        String mods = madeByMods.get(item);
        if (mods == null || mods.isEmpty()) {
            return;
        }
        lines.add(Component.translatable("craftingveloce.craft.hint.availableIn", mods)
                .withStyle(ChatFormatting.GOLD));
    }

    private void addFlowLines(List<Component> lines, Item item) {
        Float rate = this.flowRate.get(item);
        if (rate == null || Math.abs(rate) < FLOW_MIN) {
            return;
        }
        // One number, two scales: per minute and per hour.
        lines.add(Component.translatable("gui.craftingveloce.controller.flow.rate",
                        Component.literal(signed(rate * 60f)),
                        Component.literal(signed(rate * 3600f)))
                .withStyle(rate > 0f ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    /**
     * A signed number, without a unit: "+15", "-19.8", "+1.2K".
     *
     * <p>The sign is PART OF THE TEXT, not just the colour - otherwise a player who
     * cannot distinguish colours does not know whether the supply is growing or
     * falling. The unit ("/min", "/h") is added by the language key.
     *
     * <p>The number format itself lives in {@link com.craftingveloce.util.VeloceFormat}
     * (one place for the whole mod): without a redundant ".0", without a leading zero
     * below one, and with a K/M abbreviation above a thousand.
     */
    private static String signed(float rate) {
        return (rate < 0f ? "-" : "+")
                + com.craftingveloce.util.VeloceFormat.rate(Math.abs(rate));
    }

    /**
     * Can the item be made BOTH ways: with the crafter and with the furnace.
     *
     * <p><b>The BUG this fixes (player report).</b> We showed the preference for every
     * item with a furnace recipe - including glass, which is produced ONLY in a furnace
     * and has no crafting recipe. The player therefore saw the choice "Preference:
     * Crafting / Furnace" for an item that cannot be made by crafting, and rightly asked
     * why that choice was there. The preference only makes sense when there is something
     * to choose between.
     *
     * <p>We check {@code craftingEnabled} (the crafter really makes it), not just "has a
     * recipe" - an item with crafting disabled also has only one available path.
     */
    private boolean hasBothPaths(Item item) {
        return craftingEnabled.contains(item) && furnaceCraftable.contains(item);
    }

    /**
     * The "crafting or furnace" preference - only for items that CAN be smelted.
     *
     * <p>We show it explicitly ("Preference: Crafting" / "Preference: Furnace"), because
     * an item may have both paths at once and the player has to see which one is first.
     * A right click toggles it.
     *
     * <p><b>One colour in both states</b> - the player wanted this: the line should read
     * like a SETTING, not like an alarm. The "right-click" hint disappeared, because a
     * right click is the only sensible click on this icon.
     */
    private void addPreferenceLines(List<Component> lines, Item item) {
        if (!hasBothPaths(item)) {
            return;   // there is only one path - there is nothing to prefer
        }
        boolean furnace = furnacePreferred.contains(item);
        lines.add(Component.translatable(furnace
                        ? "gui.craftingveloce.controller.prefer.furnace"
                        : "gui.craftingveloce.controller.prefer.crafting")
                .withStyle(ChatFormatting.YELLOW));
    }

    /**
     * The controller remembers its tab PER BLOCK.
     *
     * <p>The block is only a preliminary draft for now, but tab memory works
     * the same way as in the other screens.
     */
    @Override
    protected Object viewStateKey() {
        return controllerPos;
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        // The controller is read-only - we do not move items.
        // The RIGHT button, however, toggles the PREFERENCE "crafting or furnace",
        // just as a right click in the crafter selects a recipe.
        if (mouseButton != 1 || slot == null || !slot.hasItem()) {
            return;
        }
        if (isPlayerInventorySlot(slot)) {
            return;
        }
        Item item = slot.getItem().getItem();
        if (!hasBothPaths(item)) {
            // One path (crafting only or furnace only) - there is nothing to choose
            // between, so a right click does nothing (see hasBothPaths).
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
                "preference for %s: %s", item,
                preferFurnace ? "FURNACE first" : "CRAFTING first");
    }

    @Override
    public void removed() {
        // The held stack is now handled by the base class (it returns it to the
        // inventory or drops it). Previously it was deleted here.
        super.removed();

    }
}
