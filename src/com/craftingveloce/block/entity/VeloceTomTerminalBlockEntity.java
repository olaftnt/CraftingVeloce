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
        activeWatchingPlayers.removeIf(ref -> ref.get() == null || ref.get() == player);
        activeWatchingPlayers.add(new WeakReference<>(player));
        syncCountsToPlayer(player);
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
        if (!(level instanceof ServerLevel sl)) {
            return Map.of();
        }
        VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                .getNetworkForTerminal(sl, worldPosition);
        if (net == null) {
            return Map.of();
        }
        return com.craftingveloce.crafting.VeloceCraftingCache.get(net).snapshot();
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
    public Map<Item, Long> computeCraftableCounts(Collection<Item> items) {
        if (!(level instanceof ServerLevel sl) || items == null || items.isEmpty()) {
            return Map.of();
        }
        VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                .getNetworkForTerminal(sl, worldPosition);
        if (net == null) {
            return Map.of();
        }
        Set<Item> enabled = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getAllEnabledItems(sl, net);
        if (enabled.isEmpty()) {
            return Map.of();
        }
        Map<Item, ResourceLocation> preferred = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getPreferredRecipes(sl, net);

        long start = System.nanoTime();
        Map<Item, Long> out = com.craftingveloce.crafting.VeloceAutoCrafter
                .countCraftableBatch(sl, net, items, enabled, preferred,
                        com.craftingveloce.crafting.VeloceAutoCrafter.DEFAULT_ESTIMATE_BUDGET_NS);

        com.craftingveloce.util.VeloceLog.Craft.detail(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "instant craftable count for %d item(s) -> %d result(s) in %d ms",
                items.size(), out.size(), (System.nanoTime() - start) / 1_000_000L);
        return out;
    }

    /**
     * Napędza tło cache craftowalnosci dla tej sieci.
     *
     * <p>Wolane z ticku: cache sam wykrywa zmiany stocku i przelicza tylko
     * dotkniete lancuchy. Dzieki temu liczby sa gotowe zanim gracz otworzy GUI.
     */
    private void tickCraftingCache(ServerLevel sl, VelocePipeNetwork net) {
        var enabled = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getAllEnabledItems(sl, net);
        var preferred = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getPreferredRecipes(sl, net);
        com.craftingveloce.crafting.VeloceCraftingCache.get(net)
                .tick(sl, enabled, preferred);
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
        // Dokladamy gotowe liczby craftowalnosci z cache - sa juz przeliczone
        // w tle, wiec GUI dostaje je od razu przy otwarciu, bez czekania.
        Map<Item, Long> craftable = craftableSnapshot();
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
        getStacks();
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
        // Keep the item cache alive and actively polling network
        getStacks();
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
            com.craftingveloce.crafting.VeloceCraftingCache.get(net).markBusy(sl);

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
                    // Craftowanie zmienilo stock: przelicz TYLKO lancuch
                    // dotknietych itemow, nie wszystko.
                    com.craftingveloce.crafting.VeloceCraftingCache.get(net)
                            .invalidateChain(sl, java.util.Set.of(requested.getItem()));
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
