package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.util.VeloceLog;
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

/**
 * Cache craftowalnosci per siec, utrzymywany przyrostowo.
 *
 * <p><b>Po co to jest.</b> Liczenie "ile da sie dorobic" dla kazdego itemu jest
 * drogie: rekurencyjna symulacja receptur. Przy 3k itemow i 15k receptur
 * liczenie wszystkiego cyklicznie zabija serwer (co juz sie raz stalo).
 *
 * <p><b>Jak dziala.</b> Liczby zmieniaja sie wylacznie wtedy, gdy zmieni sie
 * stock sieci. Wiec:
 * <ol>
 *   <li>Przy pierwszym uzyciu siec robi <b>jeden</b> pelny skan, porcjowany
 *       po kilka itemow na tick, zeby nie zamulic serwera.</li>
 *   <li>Po kazdej zmianie stocku (gracz wyciagnal, crafter zrobil, rura
 *       przesunela) oznaczamy jako brudne <b>tylko</b> itemy z lancucha
 *       "w gore" - patrz {@link VeloceRecipeGraph#affectedBy}.</li>
 *   <li>Brudne itemy sa przeliczane w tle, w malych porcjach.</li>
 * </ol>
 *
 * <p>Efekt: gracz otwiera terminal i liczby sa <b>juz gotowe</b> w cache,
 * a koszt po skraftowaniu jest proporcjonalny do dlugosci lancucha, nie do
 * liczby wszystkich receptur.
 */
public final class VeloceCraftingCache {

    /** Ile itemow przeliczamy na jeden tick (porcjowanie). */
    private static final int ITEMS_PER_TICK = 4;

    /** Bezpiecznik na dlugosc lancucha - patologiczny graf nie zamknie serwera. */
    private static final int MAX_CHAIN = 512;

    /** Bezpiecznik na pelny skan. */
    private static final int MAX_FULL_SCAN = 4000;

    /** Cache per siec (identyfikowanej po UUID). */
    private static final Map<java.util.UUID, VeloceCraftingCache> CACHES = new HashMap<>();

    private final VelocePipeNetwork network;

    /** Gotowe liczby: ile da sie dorobic danego itemu. */
    private final Map<Item, Long> craftable = new HashMap<>();

    /** Kolejka itemow do przeliczenia. */
    private final Deque<Item> pending = new ArrayDeque<>();

    /** Zbior w kolejce - zeby nie dodawac duplikatow. */
    private final Set<Item> queued = new HashSet<>();

    /** Czy pelny skan zostal juz wykonany. */
    private boolean fullScanDone = false;

    /** Migawka stocku z momentu ostatniego liczenia (do wykrywania zmian). */
    private final Map<Item, Long> lastStock = new HashMap<>();

    /** Statystyki do diagnostyki. */
    private int totalComputed = 0;
    private int lastBatchSize = 0;

    private VeloceCraftingCache(VelocePipeNetwork network) {
        this.network = network;
    }

    /** Pobiera (lub tworzy) cache dla sieci. */
    public static VeloceCraftingCache get(VelocePipeNetwork network) {
        return CACHES.computeIfAbsent(network.getId(), id -> new VeloceCraftingCache(network));
    }

    /** Usuwa cache dla sieci (przy jej likwidacji). */
    public static void drop(java.util.UUID networkId) {
        CACHES.remove(networkId);
    }

    /** Czysci wszystkie cache (zmiana swiata). */
    public static void clearAll() {
        CACHES.clear();
    }

    // ------------------------------------------------------------------
    // Odczyt - to wola GUI
    // ------------------------------------------------------------------

    /** Gotowe liczby dla podanych itemow. Nic nie liczy - tylko czyta cache. */
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

    /** Liczby dla wszystkich znanych itemow (do pelnego widoku). */
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

    // ------------------------------------------------------------------
    // Praca w tle
    // ------------------------------------------------------------------

    /**
     * Jeden krok pracy. Wolane z ticku terminala/craftera w sieci.
     *
     * <p>Robota jest ograniczona: najpierw wykrycie zmian stocku, potem mala
     * porcja przeliczen. Dzieki temu tick nigdy nie trwa dlugo.
     */
    public void tick(ServerLevel level, Set<Item> enabledItems,
                     Map<Item, ResourceLocation> preferred) {
        // 1. Wykryj zmiany stocku i dorzuc dotkniete itemy do kolejki.
        detectStockChanges(level);

        // 2. Pierwszy pelny skan - kolejkujemy wszystko, ale to jednorazowe.
        if (!fullScanDone && pending.isEmpty()) {
            queueFullScan(enabledItems);
        }

        // 3. Przelicz mala porcje.
        lastBatchSize = 0;
        while (lastBatchSize < ITEMS_PER_TICK && !pending.isEmpty()) {
            Item item = pending.poll();
            queued.remove(item);
            if (!enabledItems.contains(item)) {
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
                    "crafting cache: initial full scan done, %d item(s) computed",
                    totalComputed);
        }
    }

    /**
     * Wykrywa, ktore itemy zmienily ilosc w sieci, i kolejkuje ich lancuch
     * "w gore" - tylko to, co realnie moze sie zmienic.
     */
    private void detectStockChanges(ServerLevel level) {
        Map<Item, Long> stock = network.getAllItemCounts(level);
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
        // Itemy ktore zniknely ze stocku tez sa zmiana.
        for (Item prev : lastStock.keySet()) {
            if (!stock.containsKey(prev)) {
                changed.add(prev);
            }
        }
        if (changed.isEmpty()) {
            return;
        }

        // Lancuch w gore: dotkniete + wszystko, co z nich powstaje.
        Set<Item> affected = VeloceRecipeGraph.get(level).affectedBy(changed, MAX_CHAIN);
        for (Item it : affected) {
            if (queued.add(it)) {
                pending.add(it);
            }
        }
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "crafting cache: %d item(s) changed -> %d in affected chain queued",
                changed.size(), affected.size());

        lastStock.clear();
        lastStock.putAll(stock);
    }

    /** Kolejkuje wszystkie wlaczane/lub craftowalne itemy (jednorazowo). */
    private void queueFullScan(Set<Item> enabled) {
        int n = 0;
        for (Item it : enabled) {
            if (n++ >= MAX_FULL_SCAN) {
                break;
            }
            if (queued.add(it)) {
                pending.add(it);
            }
        }
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "crafting cache: queued %d item(s) for initial scan", pending.size());
    }

    /** Wymusza przeliczenie danego itemu (np. po zmianie receptury). */
    public void invalidate(Item item) {
        if (queued.add(item)) {
            pending.add(item);
        }
    }

    /** Wymusza przeliczenie lancucha w gore dla danych itemow. */
    public void invalidateChain(ServerLevel level, Set<Item> changed) {
        Set<Item> affected = VeloceRecipeGraph.get(level).affectedBy(changed, MAX_CHAIN);
        for (Item it : affected) {
            if (queued.add(it)) {
                pending.add(it);
            }
        }
    }
}
