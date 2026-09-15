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

    /**
     * Czysci zapamietane widoki terminali.
     *
     * <p>Pozycje blokow nie maja sensu w innym swiecie (a w nowym moga
     * przypadkiem wskazywac inny terminal), wiec przy wyjsciu ze swiata
     * zaczynamy od czystej karty.
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

    public static void openFilterPickerScreen(BlockPos pos, int filterIndex) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new com.craftingveloce.client.gui.VeloceFilterPickerScreen(
                    mc.player, mc.player.connection.enabledFeatures(), true, pos, filterIndex));
        }
    }

    /**
     * Wraca z wyboru filtra do GUI ekstraktora.
     *
     * <p><b>Po co osobna metoda.</b> Picker konczyl sie {@code onClose()}, ktore
     * zamyka ekran DO GRY - gracz wybieral item i ladowal z powrotem w swiecie,
     * zamiast wrocic do ekstraktora. Ekstraktor ma normalne menu, wiec wystarczy
     * je otworzyc z powrotem (tak samo, jak robi to otwarcie pickera).
     *
     * <p>Trace droge robimy przez zwykle menu gracza, a nie przez {@code onClose()},
     * zeby nie odpalac logiki zamykania pickera (przywracania trybu gry itd.).
     */
    public static void reopenExtractorScreen(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        // KLUCZOWE: bierzemy MENU, ktore serwer naprawde ma otwarte.
        //
        // BUG, ktory tu byl: tworzone bylo zupelnie nowe menu z
        // `inventoryMenu.containerId`, czyli z ID 0. Ale serwer nadal mial
        // otwarte prawdziwe menu ekstraktora (o ID 1..100) - picker byl
        // otwierany przez setScreen, wiec zadne zamkniecie kontenera nie
        // poszlo do serwera.
        //
        // Skutek: ID menu klienta (0) nie zgadzalo sie z ID kontenera na
        // serwerze, wiec KAZDE klikniecie - wyciagniecie z outputu, przenoszenie
        // w ekwipunku - lecialo z ID 0 i bylo przez serwer ignorowane. Gracz
        // wracal z wyboru filtra i nie mogl juz wyjac itemow.
        //
        // Jesli serwerowe menu ekstraktora dla tego bloku nadal zyje, uzywamy
        // go (z jego ID). W przeciwnym razie zostaje dotychczasowa sciezka.
        net.minecraft.world.inventory.AbstractContainerMenu open = mc.player.containerMenu;
        if (open instanceof com.craftingveloce.inventory.VeloceExtractorMenu existing
                && existing.getPos().equals(pos)) {
            mc.setScreen(new com.craftingveloce.client.gui.VeloceExtractorScreen(
                    existing,
                    mc.player.getInventory(),
                    net.minecraft.network.chat.Component.translatable("block.craftingveloce.veloce_extractor")));
            return;
        }

        com.craftingveloce.inventory.VeloceExtractorMenu rebuilt =
                new com.craftingveloce.inventory.VeloceExtractorMenu(
                        mc.player.inventoryMenu.containerId, mc.player.getInventory(), pos);
        // Przypisujemy je graczowi - bez tego klient i serwer nie zgadzaja sie
        // co do otwartego kontenera.
        mc.player.containerMenu = rebuilt;
        mc.setScreen(new com.craftingveloce.client.gui.VeloceExtractorScreen(
                rebuilt,
                mc.player.getInventory(),
                net.minecraft.network.chat.Component.translatable("block.craftingveloce.veloce_extractor")));
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
