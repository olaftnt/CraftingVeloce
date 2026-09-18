package com.craftingveloce.client;


import com.craftingveloce.client.gui.VeloceTerminalScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.screens.Screen;
import javax.annotation.Nullable;
import net.minecraft.world.item.Item;

import java.util.Map;

public class ClientTerminalHelper {

    public static void openTerminalScreen(BlockPos pos) {
        com.craftingveloce.util.VeloceLog.Gui.attempt(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "opening terminal screen at %s", pos);
        Minecraft mc = Minecraft.getInstance();
        com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                "[Veloce] openTerminalScreen at {}: player={}", pos, mc.player != null);
        if (mc.player != null) {
            mc.setScreen(new VeloceTerminalScreen(mc.player, mc.player.connection.enabledFeatures(), true, pos));
            com.craftingveloce.CraftingVeloceMod.LOGGER.info(
                    "[Veloce] terminal screen set: {}", mc.screen);
        }
    }

    /**
     * Clears the remembered terminal views.
     *
     * <p>Block positions make no sense in another world (and in a new one they
     * could accidentally point at a different terminal), so when leaving the
     * world we start from a clean slate.
     */
    public static void clearSavedTerminalViews() {
        com.craftingveloce.client.gui.VeloceTerminalViewState.clearAll();
    }

    public static void handleSyncCounts(Map<Item, Long> itemCounts) {
        handleSyncCounts(itemCounts, Map.of());
    }

    public static void handleSyncCounts(Map<Item, Long> itemCounts,
                                        Map<Item, Long> craftableCounts) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof VeloceTerminalScreen screen) {
            screen.updateNetworkCounts(itemCounts, craftableCounts);
        } else if (mc.screen instanceof com.craftingveloce.client.gui.VeloceFilterPickerScreen screen) {
            screen.updateNetworkCounts(itemCounts);
        }
    }

    /**
     * The screen from which the filter picker was opened - this is where we
     * return after an item is picked.
     *
     * <p><b>Why we remember the SCREEN instead of asking for the menu type.</b>
     * The previous version read {@code mc.player.containerMenu.getType()} and
     * looked the screen up in the registry. That CRASHED THE GAME: the filter
     * picker screen extends the creative screen, and that one replaces the
     * player's menu with the vanilla {@code ItemPickerMenu}, which has a
     * {@code null} type. {@code getType()} then throws
     * {@code UnsupportedOperationException("Unable to construct this menu by
     * type")} - a crash on EVERY item picked in a filter.
     *
     * <p>Remembering the host screen is simpler and assumes nothing about the
     * menu: we go back exactly where we came from.
     */
    @Nullable
    private static Screen filterPickerReturnScreen;

    public static void openFilterPickerScreen(BlockPos pos, int filterIndex) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            filterPickerReturnScreen = mc.screen;   // remember where to go back
            mc.setScreen(new com.craftingveloce.client.gui.VeloceFilterPickerScreen(
                    mc.player, mc.player.connection.enabledFeatures(), true, pos, filterIndex));
        }
    }

    /**
     * Returns from the filter picker to the GUI from which the picker was opened.
     *
     * <p>We do not guess by menu type (see {@link #filterPickerReturnScreen}) -
     * we restore the remembered screen. Thanks to that it handles every block
     * with filters without a list of conditions and without asking for the menu
     * type.
     *
     * @return {@code true} when some screen has been restored. {@code false}
     *         means "there is nothing to return to" - then the caller must close
     *         the screen itself (otherwise Esc would do NOTHING and the player
     *         would be stuck in the picker)
     */
    public static boolean reopenFilterHostScreen(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        Screen back = filterPickerReturnScreen;
        filterPickerReturnScreen = null;

        // THE MENU TOGETHER WITH THE SCREEN - see handBackMenu. This must happen
        // BEFORE setScreen, because after the return the screen immediately
        // draws its slots and accepts clicks.
        handBackMenu(mc, back);

        if (back == null) {
            // We do not know where the picker was opened from (e.g. after a
            // resource reload). Better to open NOTHING than to open the screen of
            // a random block - that is exactly how it once ended with the
            // extractor screen for a sensor.
            com.craftingveloce.util.VeloceLog.Gui.detail(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "filter picked, but no host screen remembered - staying in the world");
            return false;
        }

        // GUARD: this happens on the player's screen, so an exception here would
        // take down the WHOLE game (that is exactly what happened). Even if
        // restoring the screen ever broke, the player must return to the world,
        // not to the desktop.
        try {
            mc.setScreen(back);
            return true;
        } catch (Throwable t) {
            com.craftingveloce.util.VeloceLog.Gui.error(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT, t,
                    "failed to return from the filter picker to screen %s - staying in the world",
                    back.getClass().getSimpleName());
            mc.setScreen(null);
            return false;
        }
    }

    /**
     * Hands the player back the menu of the screen we are returning to.
     *
     * <p><b>The BUG this fixes (reported by a player).</b> The filter picker
     * extends the vanilla creative inventory screen, and that one does
     * {@code player.containerMenu = <its own ItemPickerMenu>} IN ITS CONSTRUCTOR
     * and NEVER gives it back - in vanilla there is no one to give it back to:
     * underneath there is the world, not a block GUI.
     *
     * <p>After returning to the extractor/furnace/sensor the screen drew itself
     * with its own menu, but {@code MultiPlayerGameMode.handleInventoryMouseClick}
     * compares the container id with {@code player.containerMenu} and ends up
     * logging "Ignoring click in mismatching container". The effect for the
     * player: they see their slots, but cannot move a single item until they
     * close and reopen the GUI from scratch (and the filters still work, because
     * they go through their own packets).
     *
     * <p>When we are not returning to any screen, we give back the backpack -
     * that is exactly the state the game is in after closing a window.
     */
    private static void handBackMenu(Minecraft mc, @Nullable Screen back) {
        if (mc.player == null || back == null) {
            if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                mc.player.containerMenu = mc.player.inventoryMenu;
            }
            return;
        }
        if (!(back instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> host)) {
            return;
        }
        if (mc.player.containerMenu != host.getMenu()) {
            com.craftingveloce.util.VeloceLog.Gui.detail(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "restoring the menu of screen %s to the player (containerMenu != screen menu)",
                    back.getClass().getSimpleName());
            mc.player.containerMenu = host.getMenu();
        }
    }

    public static void handleSyncExtractorFilters(BlockPos pos,
                                                  java.util.List<net.minecraft.world.item.ItemStack> filters,
                                                  java.util.List<Boolean> allowCrafting) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof com.craftingveloce.client.gui.VeloceExtractorScreen screen) {
            screen.updateFilters(filters, allowCrafting);
        }
    }

    public static net.minecraft.world.phys.HitResult getClientHitResult() {
        return Minecraft.getInstance().hitResult;
    }

    /**
     * @param disabledItems items with auto-crafting DISABLED. The model is
     *                      opt-out, so this is the set of exceptions - the
     *                      parameter used to be called {@code enabledItems} and
     *                      the log printed "enabled=0" when zero were DISABLED,
     *                      which read exactly the opposite of what it was.
     */
    public static void openCraftingTableScreen(BlockPos pos, java.util.Set<Item> disabledItems,
                                               java.util.Map<Item, net.minecraft.resources.ResourceLocation> preferredRecipes,
                                               java.util.List<net.minecraft.world.item.ItemStack> bufferContents) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            com.craftingveloce.util.VeloceLog.Gui.attempt(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "opening crafter screen at %s (disabled=%d, buffer=%d stacks)",
                    pos, disabledItems.size(), bufferContents.size());
            mc.setScreen(new com.craftingveloce.client.gui.VeloceCraftingTableScreen(
                    mc.player, mc.player.connection.enabledFeatures(), true, pos,
                    disabledItems, preferredRecipes, bufferContents));
            com.craftingveloce.util.VeloceLog.Gui.success(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "crafter screen opened");
        }
    }

    /** @param disabledItems items with auto-crafting disabled (opt-out model). */
    public static void updateCraftingTableState(BlockPos pos, java.util.Set<Item> disabledItems,
                                                java.util.Map<Item, net.minecraft.resources.ResourceLocation> preferredRecipes) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof com.craftingveloce.client.gui.VeloceCraftingTableScreen screen) {
            screen.updateDisabledItems(disabledItems, preferredRecipes);
        }
    }

    /**
     * The reason for a failed terminal craft attempt - it ends up in the
     * tooltip of that item.
     *
     * <p>The server sends only the reason (a translation key) and the detail;
     * the screen assembles the message so that it is in the player's language.
     * The packet may concern ANOTHER terminal (the player has a second one
     * open) - the screen then ignores it.
     */
    public static void handleCraftError(BlockPos pos, net.minecraft.world.item.ItemStack stack,
                                        String reason, String detail, String hint) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof VeloceTerminalScreen screen) {
            screen.setCraftError(pos, stack, reason, detail, hint);
        }
    }

    /** The server's response with the "how many can still be crafted" numbers. */
    public static void handleCraftableCounts(BlockPos pos, Map<Item, Long> counts,
                                             Map<Item, String> madeBy, boolean complete) {        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof VeloceTerminalScreen screen) {
            screen.updateCraftableCounts(counts, complete);
        } else if (mc.screen instanceof com.craftingveloce.client.gui.VeloceControllerScreen screen) {
            // The same response is handled by the controller - it asks for
            // EXACTLY the same numbers, so it uses the same packet and the same
            // path.
            // The controller alone takes the maker list: it is the screen that shows items it
            // cannot make as a red icon, and the tooltip there has room for the explanation.
            screen.updateCraftableCounts(counts, madeBy, complete);
        }
    }

    /** Opens the Veloce Controller GUI with the network overview. */
    public static void openControllerScreen(BlockPos pos,
                                            java.util.Map<Item, Long> stock,
                                            java.util.Set<Item> craftingEnabled,
                                            java.util.Set<Item> furnaceCraftable,
                                            boolean furnacePowered,
                                            java.util.Set<Item> furnacePreferred) {
        Minecraft mc = Minecraft.getInstance();
        com.craftingveloce.util.VeloceLog.Gui.attempt(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "opening controller screen at %s (stock=%d, enabled=%d, "
                        + "furnace=%d powered=%s, preferFurnace=%d)",
                pos, stock.size(), craftingEnabled.size(),
                furnaceCraftable.size(), furnacePowered, furnacePreferred.size());
        if (mc.player != null) {
            mc.setScreen(new com.craftingveloce.client.gui.VeloceControllerScreen(
                    mc.player, mc.player.connection.enabledFeatures(), true,
                    pos, stock, craftingEnabled,
                    furnaceCraftable, furnacePowered, furnacePreferred));
            com.craftingveloce.util.VeloceLog.Gui.success(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "controller screen opened");
        }
    }
}
