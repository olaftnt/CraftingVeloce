package com.craftingveloce.client;

import com.craftingveloce.client.gui.VeloceTerminalScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.Map;

public class ClientTerminalHelper {

    public static void openTerminalScreen(BlockPos pos) {
        com.craftingveloce.util.VeloceLog.Gui.attempt(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "opening terminal screen at %s", pos);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new VeloceTerminalScreen(mc.player, mc.player.connection.enabledFeatures(), true, pos));
        }
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

    public static void openFilterPickerScreen(BlockPos pos, int filterIndex) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new com.craftingveloce.client.gui.VeloceFilterPickerScreen(
                    mc.player, mc.player.connection.enabledFeatures(), true, pos, filterIndex));
        }
    }

    public static void handleSyncExtractorFilters(BlockPos pos, java.util.List<net.minecraft.world.item.ItemStack> filters) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof com.craftingveloce.client.gui.VeloceExtractorScreen screen) {
            screen.updateFilters(filters);
        }
    }

    public static net.minecraft.world.phys.HitResult getClientHitResult() {
        return Minecraft.getInstance().hitResult;
    }

    /**
     * @param disabledItems itemy z WYLACZONYM auto-craftingiem. Model jest
     *                      opt-out, wiec to jest zbior wyjatkow - wczesniej
     *                      parametr nazywal sie {@code enabledItems} i log
     *                      wypisywal "enabled=0" przy zerze WYLACZONYCH, co
     *                      czytalo sie dokladnie odwrotnie niz bylo.
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

    /** @param disabledItems itemy z wylaczonym auto-craftingiem (model opt-out). */
    public static void updateCraftingTableState(BlockPos pos, java.util.Set<Item> disabledItems,
                                                java.util.Map<Item, net.minecraft.resources.ResourceLocation> preferredRecipes) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof com.craftingveloce.client.gui.VeloceCraftingTableScreen screen) {
            screen.updateDisabledItems(disabledItems, preferredRecipes);
        }
    }

    /** Odpowiedz serwera z liczbami "ile da sie dorobic". */
    public static void handleCraftableCounts(Map<Item, Long> counts, boolean complete) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof VeloceTerminalScreen screen) {
            screen.updateCraftableCounts(counts, complete);
        }
    }

    /** Otwiera GUI Veloce Controller z obrazem sieci. */
    public static void openControllerScreen(BlockPos pos,
                                            java.util.Map<Item, Long> stock,
                                            java.util.Set<Item> craftable,
                                            java.util.Set<Item> craftingEnabled,
                                            java.util.Map<Item, Integer> hotbar) {
        Minecraft mc = Minecraft.getInstance();
        com.craftingveloce.util.VeloceLog.Gui.attempt(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "opening controller screen at %s (stock=%d, craftable=%d, enabled=%d, hotbar=%d)",
                pos, stock.size(), craftable.size(), craftingEnabled.size(), hotbar.size());
        if (mc.player != null) {
            mc.setScreen(new com.craftingveloce.client.gui.VeloceControllerScreen(
                    mc.player, mc.player.connection.enabledFeatures(), true,
                    pos, stock, craftable, craftingEnabled, hotbar));
            com.craftingveloce.util.VeloceLog.Gui.success(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "controller screen opened");
        }
    }
}
