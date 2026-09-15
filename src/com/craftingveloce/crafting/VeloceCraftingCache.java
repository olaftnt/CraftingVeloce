package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cache craftowalnosci per siec, utrzymywany przyrostowo i z budzetem czasu.
 *
 * <p><b>Dlaczego tak.</b> Liczenie "ile da sie dorobic" jest rekurencyjne i drogie.
 * Przy paczce z 3k itemow i 15k receptur zadne podejscie "przeliczmy to
 * regularnie" sie nie obroni - ani na zadanie, ani co sekunde, ani nawet
 * rozlozone na ticki, jesli budzet nie jest ograniczony czasowo.
 *
 * <p><b>Zasady, ktorych przestrzega ta klasa:</b>
 * <ol>
 *   <li><b>Budzet czasowy, nie liczba itemow.</b> Na tick przeznaczamy okreslony
 *       czas (np. 2 ms). Wolny serwer przeliczy wiecej, obciazony mniej - ale
 *       tick nigdy nie przekroczy budzetu. Liczenie "N itemow na tick" jest
 *       zawodne, bo jeden item moze byc wielokrotnie drozszy od innego.</li>
 *   <li><b>Zmiany wykrywamy tanio.</b> Nie skanujemy calej sieci co tick.
 *       Endpointy same zglaszaja zmiany, a pelny skan jest rzadki i porcjowany.</li>
 *   <li><b>Reakcja na swiat.</b> Podlaczenie/odlaczenie inventory, zaladowanie
 *       i rozladowanie chunka - wszystko uniewaznia wlasciwe itemy.</li>
 * </ol>
 */
public final class VeloceCraftingCache {

    // --- Budzet pracy -------------------------------------------------

    /** Maksymalny czas pracy na jeden tick, w nanosekundach (2 ms). */
    private static final long TICK_BUDGET_NS = 2_000_000L;

    /** Twardy limit pojedynczego przeliczenia - jeden item nie moze zamulic ticku. */
    private static final int MAX_ITEMS_PER_TICK = 64;

    /** Bezpiecznik na dlugosc lancucha "w gore". */
    private static final int MAX_CHAIN = 512;

    /** Bezpiecznik na pelny skan. */
    private static final int MAX_FULL_SCAN = 500;

    /**
     * Ile tickow po starcie swiata czekamy z pierwszym skanem.
     *
     * <p>Kluczowe: ladowanie save'a to najbardziej obciazony moment w cyklu
     * zycia serwera (generowanie chunkow, wczytywanie encji, swiatlo). Jesli
     * zaczniemy wtedy liczyc craftowalnosc, dojdziemy do zawieszenia - co juz
     * sie raz stalo przy 2118 zakolejkowanych itemach.
     */
    private static final int STARTUP_GRACE_TICKS = 200;   // 10 s

    /**
     * Maksymalny rozmiar kolejki. Jesli rosnie ponad to, przestajemy dodawac -
     * lepiej miec niepelny cache niz zawieszony serwer.
     */
    private static final int MAX_QUEUE = 2000;

    /**
     * Co ile tickow wolno zrobic pelny skan stocku (weryfikacja).
     * Miedzy skanami polegamy na taniej detekcji zmian.
     */
    private static final int FULL_STOCK_SCAN_INTERVAL = 200;   // 10 s

    // --- Rejestry -----------------------------------------------------

    private static final Map<UUID, VeloceCraftingCache> CACHES = new HashMap<>();

    private final VelocePipeNetwork network;

    /** Gotowe liczby: ile da sie dorobic danego itemu. */
    private final Map<Item, Long> craftable = new HashMap<>();

    /** Kolejka itemow do przeliczenia. */
    private final Deque<Item> pending = new ArrayDeque<>();

    private final Set<Item> queued = new HashSet<>();

    private boolean fullScanDone = false;

    /** Migawka stocku do wykrywania zmian. */
    private final Map<Item, Long> lastStock = new HashMap<>();

    /** Czy minol okres karencji po starcie swiata. */
    private boolean startupGracePassed = false;

    /** Ostatni pelny skan stocku (gameTime). */
    private long lastFullStockScan = Long.MIN_VALUE;

    /** Czy trzeba przerwac biezaca prace - np. gdy siec zniknela. */

    // --- Statystyki ---------------------------------------------------

    private int totalComputed = 0;
    private int lastBatchSize = 0;
    private long lastTickNanos = 0;
    private int scanCount = 0;

    private VeloceCraftingCache(VelocePipeNetwork network) {
        this.network = network;
    }

    public static VeloceCraftingCache get(VelocePipeNetwork network) {
        return CACHES.computeIfAbsent(network.getId(), id -> new VeloceCraftingCache(network));
    }

    public static void drop(UUID networkId) {
        CACHES.remove(networkId);
    }

    public static void clearAll() {
        CACHES.clear();
    }

    // ------------------------------------------------------------------
    // Odczyt (GUI) - zawsze natychmiastowy, nic nie liczy
    // ------------------------------------------------------------------

    /** Gotowe liczby dla podanych itemow. */
    public Map<Item, Long> lookup(Set<Item> items) {
        Map<Item, Long> out = new HashMap<>();
        for (Item it : items) {
            Long v = craftable.get(it);
            if (v != null && v > 0) {
                out.put(it, v);
            }
        }
        return out;
    }

    /** Wszystkie gotowe liczby. */
    public Map<Item, Long> snapshot() {
        return new HashMap<>(craftable);
    }

    public boolean isFullScanDone() {
        return fullScanDone;
    }

    public int computedCount() {
        return totalComputed;
    }

    public int pendingCount() {
        return pending.size();
    }

    public int lastBatchSize() {
        return lastBatchSize;
    }

    public long lastTickMillis() {
        return lastTickNanos / 1_000_000L;
    }

    public int scanCount() {
        return scanCount;
    }

    // ------------------------------------------------------------------
    // Praca w tle - z budzetem czasowym
    // ------------------------------------------------------------------

    /**
     * Jeden krok pracy. Wolane z ticku, ale robi co najwyzej
     * {@link #TICK_BUDGET_NS} nanosekund roboty.
     */
    public void tick(ServerLevel level, Set<Item> enabledItems,
                     Map<Item, ResourceLocation> preferred) {
        long start = System.nanoTime();
        lastBatchSize = 0;

        // 0. Karencja startowa. Ladowanie save'a to najgorszy moment na
        //    jakakolwiek prace - czekamy, az serwer sie ustabilizuje.
        if (!startupGracePassed) {
            if (level.getGameTime() < STARTUP_GRACE_TICKS) {
                return;
            }
            startupGracePassed = true;
            VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                    "crafting cache: startup grace passed, beginning background scan");
        }

        // 1. Rzadki pelny skan stocku - wylapuje zmiany, ktorych nie zgloszono
        //    (np. rura innego moda przesunela itemy).
        long now = level.getGameTime();
        if (now - lastFullStockScan >= FULL_STOCK_SCAN_INTERVAL) {
            lastFullStockScan = now;
            detectStockChanges(level);
            if (System.nanoTime() - start > TICK_BUDGET_NS) {
                lastTickNanos = System.nanoTime() - start;
                return;
            }
        }

        // 1b. Utrzymuj chunki z blokami sieci (rzadko - co sekunde).
        if (now % 20 == 0) {
            maintainForcedChunks(level);
        }

        // 2. Pierwszy skan: TYLKO itemy faktycznie obecne w sieci.
        //    Reszta (ktorej nikt nie ma) jest dolaczana pozniej, leniwie.
        if (!fullScanDone && pending.isEmpty()) {
            queueInitialScan(enabledItems);
        }

        // 3. Przeliczaj porcje, dopoki starcza budzetu.
        while (!pending.isEmpty() && lastBatchSize < MAX_ITEMS_PER_TICK) {
            if (System.nanoTime() - start > TICK_BUDGET_NS) {
                break;   // budzet wyczerpany - reszta w nastepnym ticku
            }
            Item item = pending.poll();
            queued.remove(item);
            if (!enabledItems.contains(item)) {
                craftable.remove(item);
                continue;
            }
            long n = VeloceAutoCrafter.countCraftableNow(
                    level, network, item, enabledItems, preferred);
            if (n > 0) {
                craftable.put(item, n);
            } else {
                craftable.remove(item);
            }
            lastBatchSize++;
            totalComputed++;
        }

        if (pending.isEmpty() && !fullScanDone) {
            fullScanDone = true;
            VeloceLog.Craft.success(VeloceLog.Side.SERVER,
                    "crafting cache: initial full scan done (%d items computed, %d ms last tick)",
                    totalComputed, lastTickMillis());
        }

        lastTickNanos = System.nanoTime() - start;
    }

    /**
     * Wykrywa zmiany stocku i kolejkuje tylko dotkniety lancuch.
     *
     * <p>To jedyne miejsce, ktore czyta caly stock sieci. Wolane rzadko
     * (raz na {@link #FULL_STOCK_SCAN_INTERVAL} tickow), nie co tick.
     */
    private void detectStockChanges(ServerLevel level) {
        Map<Item, Long> stock = network.getAllItemCounts(level);
        scanCount++;

        if (stock.isEmpty() && lastStock.isEmpty()) {
            return;
        }
        Set<Item> changed = new HashSet<>();
        for (Map.Entry<Item, Long> e : stock.entrySet()) {
            Long prev = lastStock.get(e.getKey());
            if (prev == null || !prev.equals(e.getValue())) {
                changed.add(e.getKey());
            }
        }
        for (Item prev : lastStock.keySet()) {
            if (!stock.containsKey(prev)) {
                changed.add(prev);
            }
        }
        lastStock.clear();
        lastStock.putAll(stock);

        if (changed.isEmpty()) {
            return;
        }
        queueChain(level, changed);
    }

    /** Kolejkuje dotkniete itemy i ich lancuch "w gore". */
    private void queueChain(ServerLevel level, Set<Item> changed) {
        Set<Item> affected = VeloceRecipeGraph.get(level).affectedBy(changed, MAX_CHAIN);
        int added = 0;
        for (Item it : affected) {
            if (pending.size() >= MAX_QUEUE) {
                VeloceLog.Craft.why(VeloceLog.Side.SERVER,
                        "crafting cache: queue full (%d), skipping rest of chain", MAX_QUEUE);
                break;
            }
            if (queued.add(it)) {
                pending.add(it);
                added++;
            }
        }
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "crafting cache: %d changed -> %d queued (%d new)",
                changed.size(), affected.size(), added);
    }

    /** Kolejkuje wstepny skan: najpierw itemy obecne w sieci, potem resztę. */
    private void queueInitialScan(Set<Item> enabled) {
        // Priorytet 1: itemy, ktore faktycznie sa w sieci - to one sa widoczne
        // w GUI i to dla nich liczby maja sens od razu.
        int n = 0;
        for (var e : lastStock.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            if (n++ >= MAX_FULL_SCAN) {
                break;
            }
            if (queued.add(e.getKey())) {
                pending.add(e.getKey());
            }
        }
        int fromStock = n;

        // Priorytet 2: reszta wlaczonych itemow, w ramach limitu kolejki.
        for (Item it : enabled) {
            if (pending.size() >= MAX_QUEUE) {
                break;
            }
            if (queued.add(it)) {
                pending.add(it);
            }
        }

        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "crafting cache: initial scan queued %d item(s) (%d from stock, cap %d)",
                pending.size(), fromStock, MAX_QUEUE);
    }

    // ------------------------------------------------------------------
    // Force-load chunkow z blokami sieci
    // ------------------------------------------------------------------

    /**
     * Maksymalna liczba chunkow, ktore force-loadujemy dla jednej sieci.
     *
     * <p>Bezpiecznik: rozlegla siec (setki rur) nie moze wymusic zaladowania
     * calej mapy. Powyzej limitu ladujemy tylko chunki z wezlami
     * (terminale, craftery, extractory) - to one musza dzialac.
     */
    private static final int MAX_FORCED_CHUNKS = 256;

    /** Chunki, ktore ta siec trzyma zaladowane. */
    private final Set<Long> forcedChunks = new HashSet<>();

    private boolean forceLoadInitialized = false;

    /**
     * Utrzymuje chunki z blokami sieci w stanie zaladowanym.
     *
     * <p>Dzieki temu crafter moze pracowac, a terminal zbierac dane, nawet gdy
     * gracz jest daleko. Bez tego siec "gubi" zawartosc po wyjsciu z zasiegu
     * symulacji i liczby w GUI sa nieaktualne.
     *
     * <p>Chunki sa zwalniane, gdy siec przestaje istniec (patrz {@link #release}).
     */
    private void maintainForcedChunks(ServerLevel level) {
        Set<BlockPos> toLoad = new HashSet<>();

        // Priorytet: wezly sieci (terminal, crafter, extractor).
        int nodes = 0;
        for (BlockPos p : network.getTerminals()) {
            toLoad.add(p);
            nodes++;
        }
        // Potem rury - o ile mieścimy sie w limicie.
        for (BlockPos p : network.getPipes()) {
            if (toLoad.size() >= MAX_FORCED_CHUNKS) {
                break;
            }
            toLoad.add(p);
        }

        Set<Long> wanted = new HashSet<>();
        for (BlockPos p : toLoad) {
            wanted.add(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4));
        }

        // Zwalniamy to, czego juz nie chcemy.
        for (long key : new HashSet<>(forcedChunks)) {
            if (!wanted.contains(key)) {
                level.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), false);
                forcedChunks.remove(key);
            }
        }
        // Ladujemy to, czego brakuje.
        int added = 0;
        for (long key : wanted) {
            if (forcedChunks.add(key)) {
                level.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), true);
                added++;
            }
        }
        if (!forceLoadInitialized) {
            forceLoadInitialized = true;
            VeloceLog.Network.success(VeloceLog.Side.SERVER,
                    "force-loaded %d chunk(s) for network (%d node(s), %d pipe(s))",
                    forcedChunks.size(), nodes, network.getPipes().size());
        } else if (added > 0) {
            VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                    "force-loaded %d more chunk(s), total %d", added, forcedChunks.size());
        }
    }

    /** Zwalnia wszystkie chunki trzymane przez te siec. */
    public void release(ServerLevel level) {
        for (long key : forcedChunks) {
            level.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), false);
        }
        VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                "released %d forced chunk(s) for network", forcedChunks.size());
        forcedChunks.clear();
    }

    // ------------------------------------------------------------------
    // Reakcja na zdarzenia swiata
    // ------------------------------------------------------------------

    /** Inventory podlaczone/odlaczone albo chunk zaladowany/rozladowany. */
    public void onEndpointChanged(ServerLevel level) {
        // Nie wiemy co sie zmienilo - trzeba na nowo odczytac stock.
        // Robimy to leniwie: najblizszy pelny skan to zalapie, a dodatkowo
        // przeliczamy wszystko, bo sklad mogl sie zmienic globalnie.
        lastFullStockScan = Long.MIN_VALUE;
        queueAll(enabledFallback());
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "crafting cache: endpoint set changed - scheduling refresh");
    }

    /** Item zmienil sie lokalnie (np. po craftowaniu). */
    public void invalidateChain(ServerLevel level, Set<Item> changed) {
        queueChain(level, changed);
    }

    /** Wymus przeliczenie jednego itemu. */
    public void invalidate(Item item) {
        if (queued.add(item)) {
            pending.add(item);
        }
    }

    /** Kolejkuje wszystko, co znamy. */
    private void queueAll(Set<Item> items) {
        for (Item it : items) {
            if (queued.add(it)) {
                pending.add(it);
            }
        }
    }

    private Set<Item> enabledFallback() {
        return new HashSet<>(craftable.keySet());
    }
}
