package com.craftingveloce.block.entity;

import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.network.SyncTerminalCountsPKT;
import com.craftingveloce.rs.RefinedStorageHelper;
import com.tom.storagemod.block.AbstractStorageTerminalBlock;
import com.tom.storagemod.block.AbstractStorageTerminalBlock.TerminalPos;
import com.tom.storagemod.block.entity.StorageTerminalBlockEntity;
import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.IInventoryAccess.IInventoryChangeTracker;
import com.tom.storagemod.inventory.InventoryCableNetwork;
import com.tom.storagemod.inventory.NetworkInventory;
import com.tom.storagemod.inventory.StoredItemStack;
import com.craftingveloce.network.pipe.ConnectedEndpointInfo;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Set;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VeloceTomTerminalBlockEntity extends StorageTerminalBlockEntity {

    private static Field itemCacheField;
    private final List<WeakReference<ServerPlayer>> activeWatchingPlayers = new ArrayList<>();

    static {
        try {
            itemCacheField = StorageTerminalBlockEntity.class.getDeclaredField("itemCache");
            itemCacheField.setAccessible(true);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public VeloceTomTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.VELOCE_TOM_TERMINAL_BE.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            InventoryCableNetwork.getNetwork(level).markNodeInvalid(worldPosition);
        }
    }

    public Direction getConnectionDirection() {
        BlockState st = getBlockState();
        Direction facing = st.getValue(AbstractStorageTerminalBlock.FACING);
        TerminalPos p = st.getValue(AbstractStorageTerminalBlock.TERMINAL_POS);
        if (p == TerminalPos.UP) return Direction.UP;
        if (p == TerminalPos.DOWN) return Direction.DOWN;
        return facing;
    }

    public void onPlayerOpenTerminal(ServerPlayer player) {
        setPlayerWatching(player, true);
        syncCountsToPlayer(player);
    }

    /**
     * Dodaje/usuwa gracza z listy odbiorcow odswiezen.
     *
     * <p>Usuwanie przy zamknieciu GUI jest wazne: wczesniej wpis znikal tylko
     * przy rozlaczeniu albo odejsciu dalej niz 64 klocki, wiec gracz stojacy
     * obok terminala dostawal pelna mape sieci co sekunde bez konca - mimo ze
     * nic nie ogladal.
     */
    public void setPlayerWatching(ServerPlayer player, boolean watching) {
        activeWatchingPlayers.removeIf(ref -> ref.get() == null || ref.get() == player);
        if (watching) {
            activeWatchingPlayers.add(new WeakReference<>(player));
        }
    }

    /**
     * Liczy "ile da sie dorobic" dla podanych itemow.
     *
     * <p><b>Uwaga wydajnosciowa.</b> Wczesniej liczone byly WSZYSTKIE wlaczone
     * itemy (przy opt-out ok. 850), co sekunde, dla kazdego otwartego terminala.
     * Kazde obliczenie rekurencyjnie kopiuje mape stocku na kazdym poziomie,
     * wiec koszt rosnie lawinowo i serwer sie zadlawial - dokladnie to widac
     * w logu, gdzie Server thread przestawal odpowiadac po otwarciu terminala.
     *
     * <p>Teraz liczymy tylko to, o co poprosi klient, i tylko na zadanie.
     */
    /** Gotowe liczby craftowalnosci z cache sieci (bez liczenia). */
    private Map<Item, Long> craftableSnapshot() {
        // Liczby "+N" nie sa juz nigdzie utrzymywane w tle. Klient zamawia je
        // dla widocznej strony (RequestCraftableCountsPKT) i dostaje swieze.
        return Map.of();
    }

    /**
     * Zwraca gotowe liczby "ile da sie dorobic" z cache sieci.
     *
     * <p><b>Nic tu nie liczymy.</b> Cache jest utrzymywany w tle przez
     * {@link com.craftingveloce.crafting.VeloceCraftingCache}, wiec odczyt jest
     * natychmiastowy. Wczesniej liczenie tu, na zadanie, dla wszystkich itemow,
     * zadlawialo serwer przy duzej liczbie receptur.
     *
     * <p>Jesli czegos nie ma w cache (nowy item, trwa jeszcze pierwszy skan),
     * po prostu nie ma go w wyniku - GUI pokaze zero zamiast czekac.
     */
    /**
     * Liczy NATYCHMIAST "ile da sie dorobic" dla podanych itemow.
     *
     * <p>Wywolywane gdy klient otwiera terminal albo zmienia strone - gracz
     * ma zobaczyc aktualne liczby od razu, a nie po sekundzie. Liczymy cala
     * partie z jednym wspoldzielonym budzetem czasowym (25 ms), wiec ~45
     * widocznych itemow oblicza sie w kilka milisekund.
     *
     * <p>To NIE jest to samo co tlo: tlo systematycznie przelicza cala siec,
     * a to odpowiada na konkretne zapytanie widoczne na ekranie.
     */
    public com.craftingveloce.crafting.VeloceAutoCrafter.BatchResult
            computeCraftableCounts(Collection<Item> items) {
        if (!(level instanceof ServerLevel sl) || items == null || items.isEmpty()) {
            return new com.craftingveloce.crafting.VeloceAutoCrafter.BatchResult(Map.of(), true);
        }
        VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                .getNetworkForTerminal(sl, worldPosition);
        if (net == null) {
            // Siec jeszcze nie gotowa - NIE mowimy "nic sie nie da zrobic",
            // bo klient skasowalby wtedy poprawne liczby.
            return new com.craftingveloce.crafting.VeloceAutoCrafter.BatchResult(Map.of(), false);
        }
        Set<Item> enabled = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getAllEnabledItems(sl, net);
        if (enabled.isEmpty()) {
            // Brak craftera w sieci = faktycznie nic nie da sie zrobic.
            return new com.craftingveloce.crafting.VeloceAutoCrafter.BatchResult(Map.of(), true);
        }
        Map<Item, ResourceLocation> preferred = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getPreferredRecipes(sl, net);

        // Ktos patrzy na terminal - obudz cache w tle (patrz punkt 0c w
        // VeloceCraftingCache). Bez tego cache spalby, bo domyslnie liczy
        // wylacznie wtedy, gdy gracz ma otwarte GUI.
        long start = System.nanoTime();
        var result = com.craftingveloce.crafting.VeloceAutoCrafter
                .countCraftableBatchResult(sl, net, items, enabled, preferred,
                        com.craftingveloce.crafting.VeloceAutoCrafter.DEFAULT_ESTIMATE_BUDGET_NS);

        com.craftingveloce.util.VeloceLog.Craft.detail(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "instant craftable count for %d item(s) -> %d result(s) in %d ms (complete=%s)",
                items.size(), result.counts().size(),
                (System.nanoTime() - start) / 1_000_000L, result.complete());
        return result;
    }

    /**
     * Napędza tło cache craftowalnosci dla tej sieci.
     *
     * <p>Wolane z ticku: cache sam wykrywa zmiany stocku i przelicza tylko
     * dotkniete lancuchy. Dzieki temu liczby sa gotowe zanim gracz otworzy GUI.
     */
    /**
     * Utrzymuje siec przy zyciu (force-load chunkow).
     *
     * <p><b>Liczenie craftowalnosci zostalo usuniete.</b> Ten cache liczyl
     * kiedys w tle, w porcjach, "na zapas" - i to bylo zrodlem zamulania,
     * bo nie sposob zgadnac, co gracz za chwile otworzy. Teraz liczby "+N"
     * powstaja WYLACZNIE na zadanie klienta, dla itemow widocznych na ekranie
     * ({@link #computeCraftableCounts}), a nie ma czego trzymac na zapas.
     *
     * <p>Cache zostaje, bo ma druga, niezbedna funkcje: trzyma wymuszone
     * chunki z blokami sieci. Bez tego ekstraktory i craftery przestalyby
     * pracowac, gdy gracz odejdzie od bazy.
     */
    private void tickCraftingCache(ServerLevel sl, VelocePipeNetwork net) {
        com.craftingveloce.crafting.VeloceCraftingCache.get(net).tickIdle(sl);
    }

    /**
     * Krotka migawka stocku sieci - bez liczenia craftowalnosci.
     * Tanie (sam odczyt cache endpointow), wystarcza do zielonych liczb.
     */
    public Map<Item, Long> snapshotStock() {
        if (!(level instanceof ServerLevel sl)) {
            return Map.of();
        }
        VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                .getNetworkForTerminal(sl, worldPosition);
        return net == null ? Map.of() : net.getAllItemCounts(sl);
    }

    public void syncCountsToAllWatchers() {
        if (level == null || level.isClientSide || activeWatchingPlayers.isEmpty()) return;
        // Tylko stock - liczenie craftowalnosci dla wszystkich itemow co sekunde
        // zadlawialo serwer (patrz computeCraftableCounts). Zolta liczba "+N"
        // jest doliczana osobno, na zadanie, tylko dla widocznych itemow.
        Map<Item, Long> counts = getAllStoredItemCounts();
        // BEZ liczb craftowalnosci z cache.
        //
        // Bylo tu doklejanie gotowej migawki z tla. Teraz tlo nic nie liczy:
        // liczby "+N" powstaja WYLACZNIE na zadanie klienta, dla itemow
        // widocznych na ekranie. Doklejanie starej migawki tylko mieszalo
        // swieze odpowiedzi z nieaktualnymi.
        Map<Item, Long> craftable = Map.of();
        Iterator<WeakReference<ServerPlayer>> it = activeWatchingPlayers.iterator();
        while (it.hasNext()) {
            ServerPlayer sp = it.next().get();
            if (sp == null || sp.hasDisconnected() || sp.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) > 64.0) {
                it.remove();
            } else {
                PacketDistributor.sendToPlayer(sp, new SyncTerminalCountsPKT(counts, craftable));
            }
        }
    }

    public void syncCountsToPlayer(ServerPlayer player) {
        if (level == null || level.isClientSide) return;
        Map<Item, Long> counts = getAllStoredItemCounts();
        PacketDistributor.sendToPlayer(player, new SyncTerminalCountsPKT(counts));
    }

    /**
     * Collects all stored item counts from Refined Storage + Tom's Storage / Chests + Veloce Pipe Network.
     */
    public Map<Item, Long> getAllStoredItemCounts() {
        Map<Item, Long> counts = new HashMap<>();
        if (level == null || level.isClientSide || !(level instanceof ServerLevel sl)) return counts;

        // 1. Veloce Pipe Network (authoritative source when connected to pipe network)
        VelocePipeNetworkManager manager = VelocePipeNetworkManager.get(sl);
        VelocePipeNetwork net = manager.getNetworkForTerminal(sl, worldPosition);
        if (net != null) {
            return net.getAllItemCounts(sl);
        }

        // 2. Fallback: Direct RS counts (if terminal is placed directly touching an RS block without pipes)
        Direction connDir = getConnectionDirection();
        BlockPos targetPos = worldPosition.relative(connDir);
        if (RefinedStorageHelper.hasRSNetwork(level, targetPos, connDir.getOpposite())) {
            Map<Item, Long> rsCounts = RefinedStorageHelper.getRSItemCounts(level, targetPos, connDir.getOpposite());
            for (Map.Entry<Item, Long> entry : rsCounts.entrySet()) {
                if (entry.getValue() > 0) {
                    counts.merge(entry.getKey(), entry.getValue(), Long::sum);
                }
            }
            return counts;
        }

        // 3. Fallback: Tom's Storage counts (only if terminal is directly connected to a Tom's Storage cable)
        //
        // Bez getStacks(): ponizej sami pytamy tracker o zmiany i sami ciagniemy
        // stosy (streamWrappedStacks). getStacks() dokladaloby tylko przebudowe
        // mapy itemow Toma, ktorej nie czytamy - a ta sciezka leci co sekunde
        // w syncCountsToAllWatchers.
        IInventoryAccess access = getTomAccess();
        if (access != null) {
            IInventoryChangeTracker tracker = access.tracker();
            if (tracker != null) {
                tracker.getChangeTracker(level);
                try {
                    tracker.streamWrappedStacks(false).forEach(storedStack -> {
                        if (storedStack != null && !storedStack.getStack().isEmpty() && storedStack.getQuantity() > 0) {
                            counts.merge(storedStack.getStack().getItem(), storedStack.getQuantity(), Long::sum);
                        }
                    });
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }

        return counts;
    }

    private IInventoryAccess getTomAccess() {
        if (itemCacheField != null) {
            try {
                NetworkInventory cache = (NetworkInventory) itemCacheField.get(this);
                if (cache != null) {
                    return cache.getAccess(level, worldPosition);
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    @Override
    public void updateServer() {
        // NIE wolamy tu getStacks().
        //
        // getStacks() ustawia u Toma flage updateItems, przez co Tom przy KAZDEJ
        // zmianie zawartosci sieci przebudowywal cala mape itemow terminala:
        // pelny skan wszystkich inwentarzy + alokacja TerminalItemStack per stos
        // + grupowanie i scalanie. To mapa, ktora nasz mod CZYTA NIGDZIE - nasze
        // liczby pochodza z VelocePipeNetwork.getAllItemCounts.
        //
        // Tom sam nie ustawia tej flagi - robi to jego menu (StorageTerminalMenu)
        // przy odpytywaniu. My otwieramy wlasny ekran, wiec ta praca byla
        // wykonywana wylacznie dla nas i wylacznie na marne.
        //
        // Z tego samego powodu nie polegamy na slotCount/freeCount/beaconLevel.
        super.updateServer();

        // Cache craftowalnosci pracuje w tle: wykrywa zmiany stocku i przelicza
        // tylko dotkniete lancuchy. Otwarcie GUI nie czeka na liczenie.
        if (level instanceof ServerLevel sl && level.getGameTime() % 5 == 0) {
            VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                    .getNetworkForTerminal(sl, worldPosition);
            if (net != null) {
                tickCraftingCache(sl, net);
            }
        }

        // Periodically refresh active viewers
        if (level != null && !activeWatchingPlayers.isEmpty() && level.getGameTime() % 20 == 0) {
            syncCountsToAllWatchers();
        }
    }

    /**
     * Prints full debug information about the connected network and resources to the player.
     */
    public void printDebugInfo(ServerPlayer player) {
        if (level == null || level.isClientSide || !(level instanceof ServerLevel sl)) return;

        Direction connDir = getConnectionDirection();
        BlockPos targetPos = worldPosition.relative(connDir);

        player.sendSystemMessage(Component.literal("§6=== [CraftingVeloce Debug] ==="));
        player.sendSystemMessage(Component.literal("§7Terminal Pos: §f[" + worldPosition.getX() + ", " + worldPosition.getY() + ", " + worldPosition.getZ() + "]"));

        // 1. Check Veloce Pipe Network
        VelocePipeNetworkManager manager = VelocePipeNetworkManager.get(sl);
        VelocePipeNetwork net = manager.getNetworkForTerminal(sl, worldPosition);
        if (net != null) {
            player.sendSystemMessage(Component.literal("§a[Pipe Network Found] §7ID: §e" + net.getId()));
            player.sendSystemMessage(Component.literal("  §7Pipes Count: §f" + net.getPipes().size() + "§7, Terminals: §f" + net.getTerminals().size()));
            player.sendSystemMessage(Component.literal("  §bTracked Chunks (" + net.getTrackedChunks().size() + "):"));
            for (ChunkPos cp : net.getTrackedChunks()) {
                boolean loaded = sl.isLoaded(cp.getWorldPosition());
                player.sendSystemMessage(Component.literal("    §7Chunk [" + cp.x + ", " + cp.z + "]: " + (loaded ? "§a[LOADED]" : "§c[UNLOADED]")));
            }
            player.sendSystemMessage(Component.literal("  §bConnected Endpoints (" + net.getEndpoints().size() + "):"));
            for (Map.Entry<BlockPos, ConnectedEndpointInfo> entry : net.getEndpoints().entrySet()) {
                BlockPos epPos = entry.getKey();
                ConnectedEndpointInfo ep = entry.getValue();
                boolean loaded = sl.isLoaded(epPos);
                player.sendSystemMessage(Component.literal("    §e" + ep.getType() + " §7at [" + epPos.getX() + ", " + epPos.getY() + ", " + epPos.getZ() + "] " + (loaded ? "§a[LOADED]" : "§c[UNLOADED]") + " §7(cached types: §f" + ep.getCachedCounts().size() + "§7)"));
            }
            Map<Item, Long> netCounts = net.getAllItemCounts(sl);
            player.sendSystemMessage(Component.literal("  §6Total Network Resources (" + netCounts.size() + " types):"));
            for (Map.Entry<Item, Long> itemEntry : netCounts.entrySet()) {
                player.sendSystemMessage(Component.literal("    §e" + itemEntry.getKey().getDescription().getString() + " §7x§a" + itemEntry.getValue()));
            }
        } else {
            player.sendSystemMessage(Component.literal("§c[No Veloce Pipe Network connected to this terminal]"));
        }

        // 2. Check Refined Storage
        boolean hasRS = RefinedStorageHelper.hasRSNetwork(level, targetPos, connDir.getOpposite());
        player.sendSystemMessage(Component.literal("§7Refined Storage Network Detected: " + (hasRS ? "§aYES" : "§cNO")));
        if (hasRS) {
            Map<String, Long> rsItems = RefinedStorageHelper.queryRSNetwork(level, targetPos, connDir.getOpposite());
            player.sendSystemMessage(Component.literal("§b--- Direct Refined Storage Contents (" + rsItems.size() + " types) ---"));
            for (Map.Entry<String, Long> entry : rsItems.entrySet()) {
                player.sendSystemMessage(Component.literal("  §e" + entry.getKey() + " §7x§a" + entry.getValue()));
            }
        }

        // 3. Check Tom's Storage
        player.sendSystemMessage(Component.literal("§b--- Tom's Storage / Inventory Access ---"));
        getStacks();
        IInventoryAccess access = getTomAccess();
        if (access == null) {
            player.sendSystemMessage(Component.literal("§cNo direct inventory access found via Tom's Storage!"));
        } else {
            player.sendSystemMessage(Component.literal("§7Slots: " + access.getSlotCount() + ", Free: " + access.getFreeSlotCount()));
        }
        player.sendSystemMessage(Component.literal("§6=============================="));
    }

    /**
     * Extracts an item from the connected network (Refined Storage or Tom's Storage / chests or Pipe Network).
     *
     * <p>Jesli itemu nie ma w sieci, probuje go <b>auto-wycraftowac</b> - patrz
     * {@link #craftItemFromNetwork}. Dzieki temu gracz moze wyciagnac deski,
     * majac wlaczone auto-craftowanie dla desek, nawet jesli nikt ich nie
     * wyprodukowal wczesniej: craftowanie jest natychmiastowe i nie przechodzi
     * przez zaden fizyczny blok posredni.
     */
    public ItemStack extractItemFromConnectedNetwork(ItemStack requested, int count) {
        return extractItemFromConnectedNetwork(requested, count, true);
    }

    /**
     * @param allowCrafting czy wolno dotworzyc item auto-craftingiem, gdy brak go w sieci
     */
    public ItemStack extractItemFromConnectedNetwork(ItemStack requested, int count, boolean allowCrafting) {
        if (level == null || level.isClientSide || !(level instanceof ServerLevel sl) || requested.isEmpty() || count <= 0) {
            return ItemStack.EMPTY;
        }

        // 1. Veloce Pipe Network extraction (handles live and on-demand unloaded chunk ticketing)
        VelocePipeNetworkManager manager = VelocePipeNetworkManager.get(sl);
        VelocePipeNetwork net = manager.getNetworkForTerminal(sl, worldPosition);
        com.craftingveloce.util.VeloceLog.Craft.attempt(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "player requested %sx %s from terminal at %s",
                count, requested.getItem(), worldPosition);
        if (net != null) {
            com.craftingveloce.util.VeloceLog.Network.detail(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "network found: %d endpoint(s) for terminal at %s",
                    net.getEndpoints().size(), worldPosition);
            // Gracz wlasnie cos wyciagnal - przyspiesz odswiezanie liczb.

            ItemStack extracted = net.extractItem(sl, requested.getItem(), count);
            if (!extracted.isEmpty()) {
                com.craftingveloce.util.VeloceLog.Craft.success(
                        com.craftingveloce.util.VeloceLog.Side.SERVER,
                        "took %sx %s from stock", extracted.getCount(), extracted.getItem());
                syncCountsToAllWatchers();
                return extracted;
            }
            com.craftingveloce.util.VeloceLog.Craft.why(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "%s not in stock, trying auto-crafting", requested.getItem());
            // 1b. Nie ma w sieci - sprobuj auto-craftingu (jesli wlaczony dla tego itemu).
            if (allowCrafting) {
                ItemStack crafted = craftItemFromNetwork(sl, net, requested, count);
                if (!crafted.isEmpty()) {
                    // Craftowanie zmienilo stock. Klient sam poprosi o nowe
                    // liczby dla widocznej strony po dostaniu nowego stocku -
                    // nie ma tu czego uniewazniac, bo nic nie jest cache'owane.
                    syncCountsToAllWatchers();
                    return crafted;
                }
            }
            return ItemStack.EMPTY;
        }

        Direction connDir = getConnectionDirection();
        BlockPos targetPos = worldPosition.relative(connDir);

        // 2. Try Refined Storage first
        if (RefinedStorageHelper.hasRSNetwork(level, targetPos, connDir.getOpposite())) {
            ItemStack rsExtracted = RefinedStorageHelper.extractItem(level, targetPos, connDir.getOpposite(), requested, count);
            if (!rsExtracted.isEmpty()) {
                syncCountsToAllWatchers();
                return rsExtracted;
            }
        }

        // 3. Try Tom's Storage / connected chests
        try {
            StoredItemStack pulled = pullStack(new StoredItemStack(requested), count);
            if (pulled != null && !pulled.getActualStack().isEmpty()) {
                syncCountsToAllWatchers();
                return pulled.getActualStack();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }

        return ItemStack.EMPTY;
    }

    /**
     * Wklada itemy gracza do sieci (slot "strzalki" w GUI terminala).
     *
     * <p><b>Serwer sam czyta stan gracza i sam zabiera itemy.</b> To jest
     * kluczowe dla poprawnosci: poprzednia wersja dostawala stos w pakiecie
     * i wrzucala go do sieci, nie zabierajac niczego graczowi - item
     * istnial jednoczesnie w beczce i w ekwipunku (duplikacja). Teraz
     * wkladamy DOKLADNIE tyle, ile udalo sie zabrac, i zabieramy DOKLADNIE
     * tyle, ile udalo sie wlozyc.
     *
     * <p><b>Czego nie ruszamy:</b> bufory crafterow (pamiec robocza planera,
     * nie magazyn) i ekstraktory (tylko wydaja). Patrz
     * {@link VelocePipeNetwork#insertIntoStorage}.
     *
     * @param mode {@link com.craftingveloce.network.TerminalStoreItemPKT#MODE_CURSOR},
     *             {@code MODE_INVENTORY} (bez hotbara) albo {@code MODE_EVERYTHING}
     */
    public void storeFromPlayer(ServerPlayer player, int mode) {
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                .getNetworkForTerminal(sl, worldPosition);
        if (net == null) {
            return;
        }

        int moved = 0;
        if (mode == com.craftingveloce.network.TerminalStoreItemPKT.MODE_CURSOR) {
            // inventoryMenu, a nie containerMenu: sync kursora idzie wlasnie
            // przez to menu (tak samo jak w TerminalPullItemPKT.resyncInventories).
            // Ekran terminala nie otwiera menu po stronie serwera, wiec
            // containerMenu to wlasnie inventoryMenu - ale nie zgadujemy.
            moved += storeStack(sl, net, player.inventoryMenu);
        } else {
            boolean includeHotbar = mode == com.craftingveloce.network.TerminalStoreItemPKT.MODE_EVERYTHING;
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                // Sloty 0..8 to hotbar - zwykly shift je pomija.
                if (!includeHotbar && i < 9) {
                    continue;
                }
                ItemStack st = inv.getItem(i);
                if (st.isEmpty()) {
                    continue;
                }
                ItemStack leftover = net.insertIntoStorage(sl, st.copy());
                int stored = st.getCount() - leftover.getCount();
                if (stored > 0) {
                    st.shrink(stored);
                    if (st.isEmpty()) {
                        inv.setItem(i, ItemStack.EMPTY);
                    }
                    moved += stored;
                }
            }
        }

        if (moved <= 0) {
            return;
        }
        // Odsylamy zmiany, zeby kursor i ekwipunek zgadzaly sie u klienta.
        com.craftingveloce.network.TerminalPullItemPKT.resyncInventories(player);
        syncCountsToAllWatchers();
    }

    /**
     * Zabiera stos z kursora gracza i wklada go do sieci.
     *
     * @return ile sztuk udalo sie przeniesc
     */
    private int storeStack(ServerLevel sl, VelocePipeNetwork net,
                           net.minecraft.world.inventory.AbstractContainerMenu menu) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return 0;
        }
        ItemStack leftover = net.insertIntoStorage(sl, carried.copy());
        int stored = carried.getCount() - leftover.getCount();
        if (stored <= 0) {
            return 0;
        }
        carried.shrink(stored);
        if (carried.isEmpty()) {
            menu.setCarried(ItemStack.EMPTY);
        }
        return stored;
    }

    /**
     * Probuje auto-wycraftowac item i natychmiast go oddac.
     *
     * <p>Warunki:
     * <ol>
     *   <li>W sieci musi byc auto-crafter, ktory ma <b>wlaczona</b> recepture
     *       dla tego itemu (patrz {@code VeloceCraftingTableBlockEntity}).
     *       Jesli nie ma zadnego wlaczonego craftera dla itemu - nie craftujemy.</li>
     *   <li>Craftowanie jest natychmiastowe: silnik pobiera skladniki z sieci
     *       i wklada wynik. Nic nie idzie przez fizyczny blok posredni.</li>
     * </ol>
     *
     * @return wycraftowany stack albo {@link ItemStack#EMPTY}
     */
    private ItemStack craftItemFromNetwork(ServerLevel sl, VelocePipeNetwork net,
                                           ItemStack requested, int count) {
        Item item = requested.getItem();

        // Craftujemy tylko to, co ma wlaczony auto-crafting w jakims crafterze sieci.
        if (com.craftingveloce.crafting.VeloceCraftingRegistry
                .findEnabledCrafter(sl, net, item) == null) {
            return ItemStack.EMPTY;
        }

        var enabled = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getAllEnabledItems(sl, net);
        var preferred = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getPreferredRecipes(sl, net);

        var buffers = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getBuffers(sl, net);
        var ctx = new com.craftingveloce.crafting.VeloceAutoCrafter.Context(
                sl, net, enabled, preferred, null, buffers);

        var result = com.craftingveloce.crafting.VeloceAutoCrafter
                .ensureAvailable(sl, net, item, count, ctx);
        if (!result.success()) {
            return ItemStack.EMPTY;
        }

        // Wynik craftowania trafia najpierw do bufora craftera (pamiec podreczna),
        // a dopiero potem do endpointow sieci. Bufor NIE jest endpointem, wiec
        // net.extractItem() go nie widzi - dlatego najpierw probujemy wyciagnac
        // wlasnie z buforow, i tylko jako fallback z sieci.
        ItemStack fromBuffer = extractFromBuffers(buffers, item, count);
        if (!fromBuffer.isEmpty()) {
            return fromBuffer;
        }
        return net.extractItem(sl, item, count);
    }

    /**
     * Wyciaga item z buforow auto-crafterow.
     *
     * <p>Bufor nie jest endpointem sieci, wiec standardowa ekstrakcja go pomija.
     * Bez tego itemy wycraftowane na poczekaniu zostawalyby w bloku craftera
     * zamiast trafic do gracza.
     */
    private static ItemStack extractFromBuffers(
            java.util.List<com.craftingveloce.inventory.VeloceCraftingBuffer> buffers,
            Item item, int count) {
        ItemStack result = ItemStack.EMPTY;
        int remaining = count;
        for (var buf : buffers) {
            if (remaining <= 0) {
                break;
            }
            for (int slot = 0; slot < buf.getContainerSize() && remaining > 0; slot++) {
                ItemStack inSlot = buf.getItem(slot);
                if (inSlot.isEmpty() || inSlot.getItem() != item) {
                    continue;
                }
                int take = Math.min(remaining, inSlot.getCount());
                ItemStack taken = buf.removeItem(slot, take);
                if (taken.isEmpty()) {
                    continue;
                }
                if (result.isEmpty()) {
                    result = taken.copy();
                } else {
                    result.grow(taken.getCount());
                }
                remaining -= taken.getCount();
            }
        }
        return result;
    }
}
