package com.craftingveloce.client;


import com.craftingveloce.client.gui.VeloceTerminalScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.screens.Screen;
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
     * Wraca z wyboru filtra do GUI tego bloku, z ktorego picker zostal otwarty.
     *
     * <p><b>Skad wiemy, do ktorego.</b> Nie trzeba tym nigdzie podrozowac:
     * pytamy ZYWE menu gracza, ktore serwer naprawde ma otwarte. Picker jest
     * otwierany przez {@code setScreen}, wiec menu kontenera caly czas zyje -
     * a jego typ mowi wprost, czy wracamy do ekstraktora, czy do pieca.
     *
     * <p>Dzieki temu jeden pakiet filtrow i jedna sciezka powrotu obsluguja
     * wszystkie bloki z filtrami. Wersja z osobnym "rodzajem bloku" w pakiecie
     * wymagalaby trzech zgodnych zmian przy kazdym nowym bloku.
     */
    @SuppressWarnings("unchecked")
    public static void reopenFilterHostScreen(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        // KLUCZOWE: bierzemy MENU, ktore serwer NAPRAWDE ma otwarte.
        //
        // BUG, ktory tu byl: tworzone bylo zupelnie nowe menu z
        // `inventoryMenu.containerId`, czyli z ID 0. Ale serwer nadal mial
        // otwarte prawdziwe menu bloku (o ID 1..100) - picker byl otwierany
        // przez setScreen, wiec zadne zamkniecie kontenera nie poszlo do
        // serwera. Skutek: ID menu klienta (0) nie zgadzalo sie z ID kontenera
        // na serwerze, wiec KAZDE klikniecie lecialo z ID 0 i bylo ignorowane -
        // gracz wracal z wyboru filtra i nie mogl juz nic wyjac.
        net.minecraft.world.inventory.AbstractContainerMenu open = mc.player.containerMenu;

        // MENU GRACZA to nie jest menu bloku - czyli kontener naprawde zniknal
        // (gracz odszedl, blok zostal zburzony). Wtedy NIE otwieramy niczego:
        // kazdy ekran bylby zgadywaniem, a wczesniej otwieral sie tu ekstraktor
        // nawet dla zupelnie innego bloku.
        if (open == null || open == mc.player.inventoryMenu) {
            return;
        }

        // EKRAN Z REJESTRU, A NIE Z LISTY WARUNKOW.
        //
        // Trzeci raz trafilismy na to samo: nowy blok z filtrami (czujnik progu)
        // nie zostal dopisany do ponizszej listy `instanceof`, wiec po wyborze
        // itemu gracz wracal do GUI EKSTRAKTORA. Zamiast dopisywac czwarty
        // warunek - i czekac na nastepny blok - pytamy rejestr ekranow, ten sam,
        // ktory wypelnia RegisterMenuScreensEvent. Dzieki temu ekran, ktory
        // gdziekolwiek zarejestrowano, jest tu obslugiwany automatycznie.
        Screen restored = screenFor(open, mc.player.getInventory(), titleFor(open));
        if (restored != null) {
            mc.setScreen(restored);
        }
    }

    /**
     * Buduje ekran zarejestrowany dla tego typu menu.
     *
     * <p>Typowane parametry sa tu konieczne: przy surowym
     * {@code ScreenConstructor} javac gubi metode {@code create}, bo jej typ
     * zwracany jest przecieciem klasy i interfejsu. Wersja z {@code T} dziala
     * i nie wymaga zadnych rzutowan na ekran.
     */
    @SuppressWarnings("unchecked")
    private static <T extends net.minecraft.world.inventory.AbstractContainerMenu> Screen screenFor(
            T menu, net.minecraft.world.entity.player.Inventory inv,
            net.minecraft.network.chat.Component title) {
        net.minecraft.world.inventory.MenuType<T> type =
                (net.minecraft.world.inventory.MenuType<T>) menu.getType();
        return net.minecraft.client.gui.screens.MenuScreens.getScreenFactory(type)
                .map(f -> f.create(menu, inv, title))
                .orElse(null);   // menu bez ekranu - nie ma do czego wracac
    }

    /**
     * Tytul okna dla odtwarzanego ekranu.
     *
     * <p>Menu zna juz swoj typ, wiec bierzemy tytul z bloku, ktory to menu
     * otworzyl - a nie ze sztywnej listy nazw.
     */
    private static net.minecraft.network.chat.Component titleFor(
            net.minecraft.world.inventory.AbstractContainerMenu menu) {
        net.minecraft.core.BlockPos pos = posOf(menu);
        if (pos != null && Minecraft.getInstance().level != null) {
            net.minecraft.world.level.block.state.BlockState state =
                    Minecraft.getInstance().level.getBlockState(pos);
            if (!state.isAir()) {
                return state.getBlock().getName();
            }
        }
        return net.minecraft.network.chat.Component.empty();
    }

    /** Pozycja bloku, ktorego dotyczy menu - po znanych typach. */
    private static net.minecraft.core.BlockPos posOf(
            net.minecraft.world.inventory.AbstractContainerMenu menu) {
        if (menu instanceof com.craftingveloce.inventory.VeloceExtractorMenu m) {
            return m.getPos();
        }
        if (menu instanceof com.craftingveloce.inventory.VeloceVelocityFurnaceMenu m) {
            return m.getPos();
        }
        if (menu instanceof com.craftingveloce.inventory.VeloceElectricFurnaceMenu m) {
            return m.getPos();
        }
        if (menu instanceof com.craftingveloce.inventory.VeloceThresholdSensorMenu m) {
            return m.getPos();
        }
        return null;
    }

    private static net.minecraft.network.chat.Component title(String blockName) {
        return net.minecraft.network.chat.Component.translatable(
                "block.craftingveloce." + blockName);
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
                                            java.util.Map<Item, Integer> hotbar) {
        Minecraft mc = Minecraft.getInstance();
        com.craftingveloce.util.VeloceLog.Gui.attempt(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "opening controller screen at %s (stock=%d, craftable=%d, enabled=%d, "
                        + "furnace=%d inNetwork=%s powered=%s, hotbar=%d)",
                pos, stock.size(), craftable.size(), craftingEnabled.size(),
                furnaceCraftable.size(), furnaceInNetwork, furnacePowered, hotbar.size());
        if (mc.player != null) {
            mc.setScreen(new com.craftingveloce.client.gui.VeloceControllerScreen(
                    mc.player, mc.player.connection.enabledFeatures(), true,
                    pos, stock, craftable, craftingEnabled,
                    furnaceCraftable, furnaceInNetwork, furnacePowered, hotbar));
            com.craftingveloce.util.VeloceLog.Gui.success(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "controller screen opened");
        }
    }
}
