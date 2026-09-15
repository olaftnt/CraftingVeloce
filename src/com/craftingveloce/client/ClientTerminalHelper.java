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

    /**
     * Ekran, z ktorego otwarto wybor filtra - tu wracamy po wybraniu itemu.
     *
     * <p><b>Dlaczego pamietamy EKRAN, a nie pytamy o typ menu.</b> Poprzednia
     * wersja czytala {@code mc.player.containerMenu.getType()} i szukala ekranu
     * w rejestrze. To WYWALALO GRE: ekran wyboru filtra dziedziczy po ekranie
     * creative, a ten podmienia menu gracza na waniliowe {@code ItemPickerMenu},
     * ktore ma typ {@code null}. {@code getType()} rzuca wtedy
     * {@code UnsupportedOperationException("Unable to construct this menu by
     * type")} - crash przy KAZDYM wybraniu itemu w filtrze.
     *
     * <p>Zapamietanie ekranu-hosta jest prostsze i niczego nie zaklada o menu:
     * wracamy dokladnie tam, skad przyszliśmy.
     */
    @Nullable
    private static Screen filterPickerReturnScreen;

    public static void openFilterPickerScreen(BlockPos pos, int filterIndex) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            filterPickerReturnScreen = mc.screen;   // zapamietaj, gdzie wrocic
            mc.setScreen(new com.craftingveloce.client.gui.VeloceFilterPickerScreen(
                    mc.player, mc.player.connection.enabledFeatures(), true, pos, filterIndex));
        }
    }

    /**
     * Wraca z wyboru filtra do GUI, z ktorego picker zostal otwarty.
     *
     * <p>Nie zgadujemy po typie menu (patrz {@link #filterPickerReturnScreen}) -
     * przywracamy zapamietany ekran. Dzieki temu obsluguje kazdy blok z filtrami
     * bez listy warunkow i bez pytania o typ menu.
     */
    public static void reopenFilterHostScreen(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        Screen back = filterPickerReturnScreen;
        filterPickerReturnScreen = null;

        if (back == null) {
            // Nie wiemy, skad otwarto picker (np. po przeladowaniu zasobow).
            // Lepiej nie otworzyc NICZEGO, niz otworzyc ekran przypadkowego
            // bloku - tak wlasnie kiedys konczylo sie ekstraktorem dla czujnika.
            com.craftingveloce.util.VeloceLog.Gui.detail(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "filter picked, but no host screen remembered - staying in the world");
            return;
        }

        // OSŁONA: to dzieje sie na ekranie gracza, wiec wyjatek tutaj wywalilby
        // CALA gre (dokladnie tak sie stalo). Nawet gdyby przywracanie ekranu
        // kiedys sie zepsulo, gracz ma wrocic do swiata, a nie do pulpitu.
        try {
            mc.setScreen(back);
        } catch (Throwable t) {
            com.craftingveloce.util.VeloceLog.Gui.error(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT, t,
                    "nie udalo sie wrocic z wyboru filtra do ekranu %s - zostaje w swiecie",
                    back.getClass().getSimpleName());
            mc.setScreen(null);
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
    public static void handleCraftableCounts(BlockPos pos, Map<Item, Long> counts,
                                             boolean complete) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof VeloceTerminalScreen screen) {
            screen.updateCraftableCounts(counts, complete);
        } else if (mc.screen instanceof com.craftingveloce.client.gui.VeloceControllerScreen screen) {
            // Ta sama odpowiedz obsluguje kontroler - pyta o DOKLADNIE te same
            // liczby, wiec korzysta z tego samego pakietu i tej samej sciezki.
            screen.updateCraftableCounts(counts, complete);
        }
    }

    /** Otwiera GUI Veloce Controller z obrazem sieci. */
    public static void openControllerScreen(BlockPos pos,
                                            java.util.Map<Item, Long> stock,
                                            java.util.Set<Item> craftable,
                                            java.util.Set<Item> craftingEnabled,
                                            java.util.Set<Item> furnaceCraftable,
                                            boolean furnaceInNetwork,
                                            boolean furnacePowered,
                                            java.util.Set<Item> furnacePreferred) {
        Minecraft mc = Minecraft.getInstance();
        com.craftingveloce.util.VeloceLog.Gui.attempt(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "opening controller screen at %s (stock=%d, craftable=%d, enabled=%d, "
                        + "furnace=%d inNetwork=%s powered=%s, preferFurnace=%d)",
                pos, stock.size(), craftable.size(), craftingEnabled.size(),
                furnaceCraftable.size(), furnaceInNetwork, furnacePowered, furnacePreferred.size());
        if (mc.player != null) {
            mc.setScreen(new com.craftingveloce.client.gui.VeloceControllerScreen(
                    mc.player, mc.player.connection.enabledFeatures(), true,
                    pos, stock, craftable, craftingEnabled,
                    furnaceCraftable, furnaceInNetwork, furnacePowered, furnacePreferred));
            com.craftingveloce.util.VeloceLog.Gui.success(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "controller screen opened");
        }
    }
}
