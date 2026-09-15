package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Silnik auto-craftowania Veloce.
 *
 * <p><b>Zasada nadrzedna:</b> craftujemy TYLKO to, co gracz swiadomie wlaczyl
 * (auto-crafting ON dla danego itemu). Rekursja schodzi w dol wylacznie po
 * itemach z wlaczonym auto-craftingiem. Skladnik bez wlaczonego auto-craftingu
 * musi po prostu byc na stocku - inaczej cala operacja konczy sie
 * niepowodzeniem i <b>nic</b> nie jest craftowane.
 *
 * <p>Dzieki temu nie powstaja "itemy nie wiadomo skad" (np. patyczki przy
 * wylaczonym craftowaniu patyczkow).
 *
 * <p>Algorytm jest dwufazowy:
 * <ol>
 *   <li><b>Planowanie</b> - czysta symulacja na liczbach, bez ruszania itemow.</li>
 *   <li><b>Wykonanie</b> - dopiero gdy plan sie zgadza, fizycznie pobiera
 *       skladniki i wklada wyniki.</li>
 * </ol>
 * Dzieki rozdzieleniu faz nie ma sytuacji, w ktorej czesc skladnikow zostala
 * juz zuzyta, a craftowanie sie nie udalo.
 */
public final class VeloceAutoCrafter {

    private static final int MAX_DEPTH = 24;
    private static final int MAX_PLAN_STEPS = 8192;

    private VeloceAutoCrafter() {
    }

    /** Wynik operacji. */
    public record CraftResult(boolean success, int produced, String reason) {
        static CraftResult ok(int produced) {
            return new CraftResult(true, produced, "");
        }

        static CraftResult fail(String reason) {
            return new CraftResult(false, 0, reason);
        }
    }

    /**
     * Kontekst operacji: co gracz wlaczyl, jakie receptury preferuje,
     * oraz co jest dostepne.
     */
    public static final class Context {
        final ServerLevel level;
        final VelocePipeNetwork network;
        /** Itemy z wlaczonym auto-craftingiem (tylko te wolno craftowac). */
        final Set<Item> enabledItems;
        /** Preferowane receptury (item -> recipe id). */
        final Map<Item, ResourceLocation> preferred;
        /** Ekwipunek gracza (moze byc null) - ma priorytet przy pobieraniu. */
        @Nullable
        final ItemInventory inventory;

        /**
         * Bufory crafterow (pamiec podreczna). Nadwyzka produkcji trafia tutaj
         * i jest normalnie dostepna dla sieci.
         */
        @Nullable
        final List<com.craftingveloce.inventory.VeloceCraftingBuffer> buffers;

        public Context(ServerLevel level, VelocePipeNetwork network,
                       Set<Item> enabledItems, Map<Item, ResourceLocation> preferred,
                       @Nullable ItemInventory inventory) {
            this(level, network, enabledItems, preferred, inventory, null);
        }

        public Context(ServerLevel level, VelocePipeNetwork network,
                       Set<Item> enabledItems, Map<Item, ResourceLocation> preferred,
                       @Nullable ItemInventory inventory,
                       @Nullable List<com.craftingveloce.inventory.VeloceCraftingBuffer> buffers) {
            this.level = level;
            this.network = network;
            this.enabledItems = enabledItems;
            this.preferred = preferred;
            this.inventory = inventory;
            this.buffers = buffers;
        }

        boolean isEnabled(Item item) {
            return enabledItems.contains(item);
        }
    }

    /**
     * Zapewnia, ze w sieci bedzie co najmniej {@code count} sztuk {@code item}.
     * Craftuje brakujaca ilosc, jesli item ma wlaczony auto-crafting.
     */
    public static CraftResult ensureAvailable(ServerLevel level, VelocePipeNetwork network,
                                              Item item, int count, Context ctx) {
        if (count <= 0) {
            return CraftResult.fail("niepoprawna ilosc");
        }

        // 1. Ekwipunek gracza ma priorytet.
        int inInventory = ctx.inventory == null ? 0 : ctx.inventory.count(item);
        if (inInventory >= count) {
            return CraftResult.ok(count);
        }

        // 2. Siec.
        long inNetwork = network.getAllItemCounts(level).getOrDefault(item, 0L);
        long available = inInventory + inNetwork;
        if (available >= count) {
            return CraftResult.ok(count);
        }

        // 3. Brakuje - trzeba wycraftowac. Wolno tylko gdy wlaczone.
        int missing = (int) Math.min(Integer.MAX_VALUE, count - available);
        if (!ctx.isEnabled(item)) {
            return CraftResult.fail("brak na stocku (auto-crafting wyłączony)");
        }

        // Faza 1: planowanie (symulacja na liczbach).
        Map<Item, Long> stock = snapshotStock(ctx);
        Plan plan = new Plan();
        if (!plan(level, ctx, item, missing, stock, plan, new HashSet<>(), 0)) {
            return CraftResult.fail("brak bazowych składników");
        }

        // Faza 2: wykonanie dokladnie tego, co zaplanowano.
        if (!execute(level, ctx, plan)) {
            return CraftResult.fail("nie udało się pobrać składników");
        }
        return CraftResult.ok(count);
    }

    /**
     * Ile sztuk danego itemu da sie uzyskac z tego, co jest obecnie dostepne.
     * Bierze pod uwage rekurencyjne craftowanie (tylko itemow wlaczonych).
     *
     * @return przewidywana liczba sztuk (0 gdy nic nie da sie zrobic)
     */
    public static long countCraftableNow(ServerLevel level, VelocePipeNetwork network,
                                         Item item, Set<Item> enabledItems,
                                         Map<Item, ResourceLocation> preferred) {
        if (!enabledItems.contains(item)) {
            return network.getAllItemCounts(level).getOrDefault(item, 0L);
        }
        Map<Item, Long> stock = new HashMap<>(network.getAllItemCounts(level));
        return maxCraftable(level, item, stock, enabledItems, new HashSet<>(), 0);
    }

    // ------------------------------------------------------------------
    // Faza planowania - czysta arytmetyka na liczbach
    // ------------------------------------------------------------------

    /** Plan: ile razy wykonac ktora recepture, w kolejnosci wykonania. */
    static final class Plan {
        final List<PlannedRun> runs = new ArrayList<>();

        void add(VeloceRecipeRegistry.CraftingEntry recipe, long times) {
            runs.add(new PlannedRun(recipe, times));
        }
    }

    record PlannedRun(VeloceRecipeRegistry.CraftingEntry recipe, long times) {
    }

    /**
     * Planuje uzyskanie {@code amount} sztuk {@code item}.
     * Modyfikuje {@code stock} (symulacja zuzycia). Nie rusza swiata.
     */
    private static boolean plan(ServerLevel level, Context ctx, Item item, long amount,
                                Map<Item, Long> stock, Plan plan, Set<Item> visiting, int depth) {
        if (amount <= 0) {
            return true;
        }
        if (depth > MAX_DEPTH || plan.runs.size() > MAX_PLAN_STEPS) {
            return false;
        }
        if (!visiting.add(item)) {
            return false;   // cykl receptur
        }
        try {
            // Najpierw zuzyj to, co juz jest na stanie.
            long have = stock.getOrDefault(item, 0L);
            long fromStock = Math.min(have, amount);
            stock.put(item, have - fromStock);
            long remaining = amount - fromStock;
            if (remaining <= 0) {
                return true;
            }

            // Rekursja tylko dla wlaczonych itemow.
            if (!ctx.isEnabled(item)) {
                return false;
            }

            List<VeloceRecipeRegistry.CraftingEntry> recipes =
                    orderRecipes(level, item, ctx.preferred);
            if (recipes.isEmpty()) {
                return false;
            }

            // Probuj kolejne receptury - pierwsza wykonalna wygrywa.
            for (VeloceRecipeRegistry.CraftingEntry recipe : recipes) {
                Map<Item, Long> snapshot = new HashMap<>(stock);
                int planMark = plan.runs.size();
                if (planRecipe(level, ctx, recipe, remaining, stock, plan, visiting, depth)) {
                    return true;
                }
                // Receptura nie wyszla - cofnij symulacje.
                stock.clear();
                stock.putAll(snapshot);
                while (plan.runs.size() > planMark) {
                    plan.runs.remove(plan.runs.size() - 1);
                }
            }
            return false;
        } finally {
            visiting.remove(item);
        }
    }

    /**
     * Planuje wykonanie jednej receptury tyle razy, by uzyskac {@code amount}.
     * Dla kazdego skladnika wybiera JEDNA opcje i zapewnia jej pelna ilosc.
     */
    private static boolean planRecipe(ServerLevel level, Context ctx,
                                      VeloceRecipeRegistry.CraftingEntry recipe, long amount,
                                      Map<Item, Long> stock, Plan plan,
                                      Set<Item> visiting, int depth) {
        long perCraft = Math.max(1, recipe.result().getCount());
        long times = (amount + perCraft - 1) / perCraft;
        if (times <= 0 || times > MAX_PLAN_STEPS) {
            return false;
        }

        // Najpierw zaplanuj skladniki (rekurencja), potem zapisz siebie.
        for (Ingredient ing : recipe.ingredients()) {
            List<ItemStack> options = nonEmpty(ing);
            if (options.isEmpty()) {
                continue;
            }
            Map<Item, Long> snapshot = new HashMap<>(stock);
            int planMark = plan.runs.size();

            boolean supplied = false;
            for (ItemStack opt : options) {
                Item optItem = opt.getItem();
                long avail = stock.getOrDefault(optItem, 0L);
                long need = times;
                if (avail >= need) {
                    stock.put(optItem, avail - need);
                    supplied = true;
                    break;
                }
                // Sprobuj dotworzyc brakujaca czesc.
                long lacking = need - avail;
                Map<Item, Long> snap2 = new HashMap<>(stock);
                int mark2 = plan.runs.size();
                stock.put(optItem, 0L);
                if (plan(level, ctx, optItem, lacking, stock, plan, visiting, depth + 1)) {
                    supplied = true;
                    break;
                }
                stock.clear();
                stock.putAll(snap2);
                while (plan.runs.size() > mark2) {
                    plan.runs.remove(plan.runs.size() - 1);
                }
            }
            if (!supplied) {
                stock.clear();
                stock.putAll(snapshot);
                while (plan.runs.size() > planMark) {
                    plan.runs.remove(plan.runs.size() - 1);
                }
                return false;
            }
        }

        plan.add(recipe, times);
        return true;
    }

    /**
     * Maksymalna liczba sztuk itemu mozliwa do uzyskania z danego stanu
     * (symulacja, bez wykonywania). Uwzglednia rekursje dla wlaczonych itemow.
     */
    private static long maxCraftable(ServerLevel level, Item item, Map<Item, Long> stock,
                                     Set<Item> enabled, Set<Item> visiting, int depth) {
        if (depth > MAX_DEPTH || !visiting.add(item)) {
            return 0L;
        }
        try {
            long fromStock = stock.getOrDefault(item, 0L);
            if (!enabled.contains(item)) {
                return fromStock;
            }
            var recipes = VeloceRecipeRegistry.getRecipesFor(level, item);
            if (recipes.isEmpty()) {
                return fromStock;
            }
            long best = fromStock;
            for (var recipe : recipes) {
                long runs = maxRuns(level, recipe, stock, enabled, visiting, depth);
                if (runs > 0) {
                    long produced = fromStock + runs * Math.max(1, recipe.result().getCount());
                    best = Math.max(best, produced);
                }
            }
            return best;
        } finally {
            visiting.remove(item);
        }
    }

    /** Ile razy da sie wykonac recepture, majac dany stan (z rekursja). */
    private static long maxRuns(ServerLevel level, VeloceRecipeRegistry.CraftingEntry recipe,
                                Map<Item, Long> stock, Set<Item> enabled,
                                Set<Item> visiting, int depth) {
        long limit = Long.MAX_VALUE;
        for (Ingredient ing : recipe.ingredients()) {
            List<ItemStack> options = nonEmpty(ing);
            if (options.isEmpty()) {
                continue;
            }
            long bestForIng = 0;
            for (ItemStack opt : options) {
                Item optItem = opt.getItem();
                long avail = stock.getOrDefault(optItem, 0L);
                long total = avail;
                if (enabled.contains(optItem) && depth < MAX_DEPTH && !visiting.contains(optItem)) {
                    Map<Item, Long> copy = new HashMap<>(stock);
                    long withCrafting = maxCraftable(level, optItem, copy, enabled, visiting, depth + 1);
                    total = Math.max(total, withCrafting);
                }
                bestForIng = Math.max(bestForIng, total);
            }
            if (bestForIng <= 0) {
                return 0L;
            }
            limit = Math.min(limit, bestForIng);
        }
        return limit == Long.MAX_VALUE ? 0L : limit;
    }

    // ------------------------------------------------------------------
    // Faza wykonania - fizyczne ruszanie itemow
    // ------------------------------------------------------------------

    private static boolean execute(ServerLevel level, Context ctx, Plan plan) {
        // Plan jest w kolejnosci post-order: skladniki produkowane przed uzyciem.
        for (PlannedRun run : plan.runs) {
            for (long i = 0; i < run.times(); i++) {
                if (!runOnce(level, ctx, run.recipe())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Jedno wykonanie receptury: pobierz skladniki, wstaw wynik. */
    private static boolean runOnce(ServerLevel level, Context ctx,
                                   VeloceRecipeRegistry.CraftingEntry recipe) {
        NonNullList<ItemStack> consumed = NonNullList.create();
        for (Ingredient ing : recipe.ingredients()) {
            ItemStack taken = takeOne(level, ctx, ing);
            if (taken.isEmpty()) {
                // Zwrot pobranych - nie gubimy itemow.
                for (ItemStack s : consumed) {
                    deposit(level, ctx, s);
                }
                return false;
            }
            consumed.add(taken);
        }
        ItemStack result = recipe.result().copy();
        deposit(level, ctx, result);
        return true;
    }

    /** Pobiera jedna sztuke pasujaca do skladnika. Priorytet: ekwipunek -> siec. */
    private static ItemStack takeOne(ServerLevel level, Context ctx, Ingredient ing) {
        for (ItemStack opt : nonEmpty(ing)) {
            if (ctx.inventory != null) {
                ItemStack fromInv = ctx.inventory.extract(opt.getItem(), 1);
                if (!fromInv.isEmpty()) {
                    return fromInv;
                }
            }
            ItemStack fromNet = ctx.network.extractItem(level, opt.getItem(), 1);
            if (!fromNet.isEmpty()) {
                return fromNet;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Wklada wynik do sieci.
     *
     * <p>Kolejnosc: najpierw bufor craftera (pamiec podreczna na nadwyzke),
     * potem zwykle endpointy sieci (skrzynie). Dzieki temu gdy z 1 logu
     * powstana 4 deski, a gracz chcial 1 - pozostale 3 zostaja w buforze
     * craftera i sa normalnie dostepne dla calej sieci.
     */
    private static void deposit(ServerLevel level, Context ctx, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        // 1. Bufor crafterow (pamiec podreczna).
        if (ctx.buffers != null) {
            for (var buf : ctx.buffers) {
                stack = buf.insert(stack);
                if (stack.isEmpty()) {
                    return;
                }
            }
        }
        // 2. Zwykle endpointy sieci.
        for (var endpoint : ctx.network.getEndpoints().values()) {
            if (endpoint.insertItem(level, stack)) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Pomocnicze
    // ------------------------------------------------------------------

    private static List<ItemStack> nonEmpty(Ingredient ing) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : ing.getItems()) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** Receptury w kolejnosci preferencji gracza. */
    private static List<VeloceRecipeRegistry.CraftingEntry> orderRecipes(
            ServerLevel level, Item item, Map<Item, ResourceLocation> preferred) {
        List<VeloceRecipeRegistry.CraftingEntry> all =
                VeloceRecipeRegistry.getRecipesFor(level, item);
        if (all.size() <= 1) {
            return all;
        }
        ResourceLocation pref = preferred.get(item);
        if (pref == null) {
            return all;
        }
        List<VeloceRecipeRegistry.CraftingEntry> ordered = new ArrayList<>(all.size());
        for (var e : all) {
            if (e.id().equals(pref)) {
                ordered.add(e);
            }
        }
        for (var e : all) {
            if (!e.id().equals(pref)) {
                ordered.add(e);
            }
        }
        return ordered;
    }

    /** Migawka stanu sieci + ekwipunku do symulacji. */
    private static Map<Item, Long> snapshotStock(Context ctx) {
        Map<Item, Long> stock = new HashMap<>(ctx.network.getAllItemCounts(ctx.level));
        if (ctx.inventory != null) {
            for (Item it : ctx.inventory.allItems()) {
                stock.merge(it, (long) ctx.inventory.count(it), Long::sum);
            }
        }
        return stock;
    }

    /** Abstrakcja ekwipunku gracza - zeby silnik dal sie testowac. */
    public interface ItemInventory {
        int count(Item item);

        ItemStack extract(Item item, int max);

        /** Wszystkie itemy w ekwipunku (do symulacji dostepnosci). */
        Set<Item> allItems();
    }
}
