package com.craftingveloce.crafting;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;

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

    /**
     * Glebokosc rekurencji przy PRAWDZIWYM craftowaniu.
     *
     * <p>12 to i tak absurdalnie dlugi lancuch dla gracza, a kazdy poziom
     * mnozy liczbe galezi. Przy 24 drzewo receptur rozrastalo sie wykładniczo
     * i pojedyncze zadanie potrafilo zamrozic watek serwera na dziesiatki
     * sekund. Glebsze lancze i tak przegrywaja na budzecie czasowym.
     */
    private static final int CRAFT_MAX_DEPTH = 12;

    /**
     * Budzet czasu na planowanie PRAWDZIWEGO craftu (50 ms).
     *
     * <p>Wczesniej byl tu 0, czyli brak limitu czasu - jedynym bezpiecznikiem
     * byl licznik operacji, a ten jest o wiele za pozny, zeby uchronic tick.
     */
    private static final long CRAFT_PLAN_BUDGET_NS = 50_000_000L;

    /** Limit krokow planowania przy prawdziwym craftowaniu. */
    private static final int CRAFT_MAX_STEPS = 8192;

    /**
     * Awaryjny limit operacji w jednym szacowaniu.
     *
     * <p>To NIE jest glowne ograniczenie - throttling robi budzet czasowy
     * ({@link #ESTIMATE_DEADLINE}). Ten licznik istnieje tylko po to, zeby
     * szalony graf receptur nie krecil sie w nieskonczonosc, gdyby pomiar
     * czasu zawiodl.
     *
     * <p>Poprzednia wartosc (2000) byla glownym ograniczeniem i byla
     * <b>drastycznie za mala</b>: przy 5033 recepturach licznik konczyl sie
     * w polowie przeliczania i zwracal 0. Dlatego plotek z 2 klod pokazywal
     * sie jako niewykonalny - szacowanie nie dobiegalo konca.
     */
    private static final int MAX_ESTIMATE_OPS = 200_000;

    /**
     * Wynik "nie udalo sie policzyc" (budzet czasowy sie skonczyl).
     *
     * <p>Rozroznienie wazne: 0 znaczy "na pewno nie da sie zrobic", a
     * {@code UNKNOWN_COUNT} znaczy "nie wiem, sprobuj pozniej". Bez tego
     * przerwane planowanie kasowalo licznik w GUI.
     */
    public static final long UNKNOWN_COUNT = -1L;

    /**
     * Budzet na przeliczenie widocznej strony terminala.
     *
     * <p>To leci na watku serwera, wiec musi zostawiac zapas na reszte ticku.
     *
     * <p><b>Dlaczego 25 ms, a nie 8.</b> Przy 8 ms budzet pekal w polowie
     * strony (w logu: "instant craftable count for 45 item(s) -> 17 result(s)
     * in 8 ms (complete=false)"). A przy incomplete odpowiedzi klient CELOWO
     * zachowuje stare liczby dla niedokonczonych itemow - wiec uzytkownik
     * widzial nieaktualne "ile da sie zrobic" i to jest wlasnie zglaszany
     * blad. 25 ms zdarza sie tylko przy otwarciu/przewinieciu strony, nie co
     * tick, wiec jest bezpieczne.
     */
    public static final long DEFAULT_ESTIMATE_BUDGET_NS = 25_000_000L;

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
        /**
         * Ekwipunek gracza. <b>Zawsze {@code null} w obecnym kodzie</b> - patrz
         * dokumentacja {@link ItemInventory}. Galezie, ktore go sprawdzaja, sa
         * przygotowane, ale nieosiagalne.
         */
        @Nullable
        final ItemInventory inventory;

        /**
         * Bufory crafterow (pamiec podreczna). Nadwyzka produkcji trafia tutaj
         * i jest normalnie dostepna dla sieci.
         */
        @Nullable
        final List<com.craftingveloce.inventory.VeloceCraftingBuffer> buffers;

        /**
         * Gdzie wypuscic to, czego siec nie przyjela.
         *
         * <p>Bez tego przy pelnej sieci wycraftowane itemy (i skladniki
         * zwracane przy nieudanym wykonaniu) po prostu ginely.
         */
        @Nullable
        final net.minecraft.core.BlockPos dropPos;

        public Context(ServerLevel level, VelocePipeNetwork network,
                       Set<Item> enabledItems, Map<Item, ResourceLocation> preferred,
                       @Nullable ItemInventory inventory) {
            this(level, network, enabledItems, preferred, inventory, null, null);
        }

        public Context(ServerLevel level, VelocePipeNetwork network,
                       Set<Item> enabledItems, Map<Item, ResourceLocation> preferred,
                       @Nullable ItemInventory inventory,
                       @Nullable List<com.craftingveloce.inventory.VeloceCraftingBuffer> buffers) {
            this(level, network, enabledItems, preferred, inventory, buffers, null);
        }

        public Context(ServerLevel level, VelocePipeNetwork network,
                       Set<Item> enabledItems, Map<Item, ResourceLocation> preferred,
                       @Nullable ItemInventory inventory,
                       @Nullable List<com.craftingveloce.inventory.VeloceCraftingBuffer> buffers,
                       @Nullable net.minecraft.core.BlockPos dropPos) {
            this.level = level;
            this.network = network;
            this.enabledItems = enabledItems;
            this.preferred = preferred;
            this.inventory = inventory;
            this.buffers = buffers;
            this.dropPos = dropPos;
        }

        boolean isEnabled(Item item) {
            return enabledItems.contains(item);
        }

        /**
         * Ile przepalen siec ugnie TERAZ - budzet ciepla dla planera.
         *
         * <p>Liczony raz i zapamietany, bo pytanie jest zadawane przy KAZDYM
         * itemie rozpatrywanym przez planer, a odpowiedz wymaga przejscia po
         * wezlach sieci i odczytu ich block entity. Bez pamieci byloby to
         * setki takich przejsc na jedno klikniecie.
         *
         * <p>Zero oznacza "brak pieca" - i to jest JEDYNE zrodlo tej
         * informacji. Planer nie sprawdza typow blokow samodzielnie.
         */
        long heatOps() {
            if (heatOpsCache < 0) {
                heatOpsCache = VeloceHeatSources.totalOperations(level, network);
            }
            return heatOpsCache;
        }

        /** Receptury dla itemu, z piecem tylko gdy jest czym palic. */
        List<ProcessingEntry> recipesFor(Item item) {
            return allRecipesFor(level, network, item, heatOps() > 0);
        }

        /**
         * Maszyny modulow dla danego typu receptury - pobrane RAZ na wykonanie.
         *
         * <p>Ta sama lekcja co przy zrodlach ciepla: pobieranie listy maszyn
         * przy KAZDEJ sztuce oznaczaloby pelne przejscie po wezlach sieci
         * z sortowaniem setki razy w jednym ticku. W obrebie jednego zlecenia
         * zbior maszyn sie nie zmienia.
         */
        java.util.List<com.craftingveloce.block.entity.VeloceProcessingSource> moduleSourcesFor(
                RecipeType<?> type) {
            if (moduleSourcesCache == null) {
                moduleSourcesCache = new HashMap<>();
            }
            return moduleSourcesCache.computeIfAbsent(type,
                    t -> VeloceProcessingSources.forType(level, network, t));
        }

        private long heatOpsCache = -1L;
        private Map<RecipeType<?>, java.util.List<com.craftingveloce.block.entity.VeloceProcessingSource>>
                moduleSourcesCache;
    }

    /**
     * Receptury waniliowe PLUS receptury modulow z innych modow.
     *
     * <p>Kazdy modul, ktory stoi w sieci i jest zasilony, doklada swoje
     * receptury na ten item - w innym wypadku automat nigdy nie uzylby
     * kruszarki czy compaktora, mimo ze stoja podlaczone do rur.
     */
    private static List<ProcessingEntry> allRecipesFor(ServerLevel level, VelocePipeNetwork network,
                                                       Item item, boolean heatAvailable) {
        List<ProcessingEntry> vanilla = VeloceRecipeRegistry.getRecipesFor(level, item, heatAvailable);
        List<ProcessingEntry> modules = VeloceModuleRecipes.forItem(level, network, item);
        if (modules.isEmpty()) {
            return vanilla;
        }
        List<ProcessingEntry> out = new ArrayList<>(vanilla.size() + modules.size());
        out.addAll(vanilla);
        out.addAll(modules);
        return out;
    }

    /**
     * Zapewnia, ze w sieci bedzie co najmniej {@code count} sztuk {@code item}.
     * Craftuje brakujaca ilosc, jesli item ma wlaczony auto-crafting.
     */
    public static CraftResult ensureAvailable(ServerLevel level, VelocePipeNetwork network,
                                              Item item, int count, Context ctx) {
        return ensureAvailable(level, network, item, count, ctx, CRAFT_PLAN_BUDGET_NS);
    }

    /**
     * Jak wyzej, ale z jawnym budzetem planowania.
     *
     * <p>Wolane z tla (extractor), gdzie nie mozemy pozwolic sobie na pelny
     * budzet zadania gracza - inaczej jedno urzadzenie zjada tick.
     */
    public static CraftResult ensureAvailable(ServerLevel level, VelocePipeNetwork network,
                                              Item item, int count, Context ctx,
                                              long planBudgetNanos) {
        if (count <= 0) {
            return CraftResult.fail("craftingveloce.craft.error.amount");
        }

        VeloceLog.Craft.attempt(VeloceLog.Side.SERVER,
                "ensure %sx %s (enabled=%s)", count, item, ctx.isEnabled(item));

        // 1. Ekwipunek gracza ma priorytet.
        int inInventory = ctx.inventory == null ? 0 : ctx.inventory.count(item);
        if (inInventory >= count) {
            return CraftResult.ok(count);
        }

        // 2. Siec. JEDEN wymuszony skan - za chwile podejmujemy decyzje
        //    o pobraniu itemow, wiec nie mozemy pracowac na nieaktualnym
        //    stanie. Te sama migawke przekazujemy potem do planowania.
        Map<Item, Long> netStock = network.getAllItemCounts(level, true);
        long inNetwork = netStock.getOrDefault(item, 0L);
        long available = inInventory + inNetwork;
        long onStockBefore = inNetwork;
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "%s: inventory=%d, network=%d, requested=%d",
                item, inInventory, inNetwork, count);
        if (available >= count) {
            return CraftResult.ok(count);
        }

        // 3. Brakuje - trzeba wycraftowac. Wolno tylko gdy wlaczone.
        int missing = (int) Math.min(Integer.MAX_VALUE, count - available);
        if (!ctx.isEnabled(item)) {
            VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                    "%s has auto-crafting disabled - cannot craft", item);
            return CraftResult.fail("craftingveloce.craft.error.disabled");
        }

        // Faza 1: planowanie (symulacja na liczbach).
        // Ustawiamy twardy budzet czasu - planowanie drzewa receptur nie moze
        // zamrozic watku serwera, nawet gdy gracz poprosi o cos absurdalnie
        // zlozonego. Przy przekroczeniu mowimy "za zlozone", a nie "brak
        // skladnikow" - to dwie rozne sytuacje.
        startEstimate(planBudgetNanos);
        Map<Item, Long> stock = snapshotStock(ctx, netStock);
        Plan plan = new Plan(ctx.heatOps());

        // Ile UDALO sie zaplanowac (moze byc mniej niz missing).
        long planned = missing;

        if (!plan(level, ctx.network, ctx.enabledItems, ctx.preferred, item, missing, stock, plan, new HashSet<>(), 0)) {
            if (estimateAborted()) {
                VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                        "planning for %s x%d exceeded the %d ms budget - recipe tree too complex",
                        item, missing, planBudgetNanos / 1_000_000L);
                return CraftResult.fail("craftingveloce.craft.error.tooComplex");
            }

            // NIE MA DOść NA PELNA ILOSC - sprobuj zrobic MNIEJ.
            //
            // BUG, ktory tu byl: planowanie szlo na dokladnie `missing` sztuk,
            // a jesli sie nie udalo, cala operacja przepadala. Ekstraktor, ktory
            // chcial pelny stack (np. 64), nie dostawal NIC, nawet gdy w sieci
            // bylo dość materialu na 12 sztuk. Teraz schodzimy w dol, az
            // znajdziemy ilosc wykonalna.
            planned = planAsMuchAsPossible(level, ctx, item, missing, stock, plan, planBudgetNanos);
            if (planned <= 0) {
                // Rozroznienie wazne dla gracza: "nie zdazylem policzyc" to nie
                // to samo co "nie masz z czego". Wczesniej oba konczyly sie
                // komunikatem o braku skladnikow.
                if (estimateAborted()) {
                    VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                            "planning for %s x%d ran out of the %d ms budget",
                            item, missing, planBudgetNanos / 1_000_000L);
                    return CraftResult.fail("craftingveloce.craft.error.tooComplex");
                }
                logPlanFailure(level, ctx, item, missing, stock);
                return CraftResult.fail("craftingveloce.craft.error.noBase");
            }
        }

        // Faza 2: wykonanie dokladnie tego, co zaplanowano.
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "plan for %s x%d: %d recipe run(s) to execute",
                item, planned, plan.runs.size());
        if (!execute(level, ctx, plan)) {
            VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                    "execution failed for %s - ingredients vanished mid-craft", item);
            return CraftResult.fail("craftingveloce.craft.error.extract");
        }
        // Zwracamy ilosc, ktora REALNIE powstała - moze byc mniejsza od
        // zamowionej, gdy nie bylo dość materialu (patrz planAsMuchAsPossible).
        // Wczesniej log i wynik klamaly pelna iloscia, mimo ze wykonano mniej.
        // Ile realnie przybylo w sieci. to jest liczba, ktora wolajacy moze
        // bezpiecznie wyciagnac - nie zamowiona ilosc.
        long actuallyCrafted = Math.max(0L, planned);
        Map<Item, Long> after = network.getAllItemCounts(level, true);
        long gained = after.getOrDefault(item, 0L) - onStockBefore;
        if (gained > 0) {
            actuallyCrafted = gained;
        }
        VeloceLog.Craft.success(VeloceLog.Side.SERVER,
                "crafted %s x%d successfully (requested %d)", item, actuallyCrafted, count);
        return CraftResult.ok((int) Math.min(Integer.MAX_VALUE, actuallyCrafted));
    }

    /**
     * Licznik operacji biezacego szacowania.
     *
     * <p>ThreadLocal, bo szacowanie moze byc wolane z roznych watkow (choc
     * normalnie tylko z watku serwera), a nie chcemy zmieniac sygnatur
     * wszystkich metod rekurencyjnych.
     */
    private static final ThreadLocal<int[]> ESTIMATE_OPS =
            ThreadLocal.withInitial(() -> new int[]{0});

    /**
     * Termin zakonczenia biezacego szacowania (System.nanoTime).
     *
     * <p>0 = brak budzetu czasowego (tylko awaryjny limit operacji).
     */
    private static final ThreadLocal<long[]> ESTIMATE_DEADLINE =
            ThreadLocal.withInitial(() -> new long[]{0L});

    /**
     * Czy biezace szacowanie zostalo przerwane z braku budzetu.
     *
     * <p>Bez tego rozroznienia przerwane planowanie wygladalo identycznie jak
     * "nie da sie tego zrobic" - item dostawal 0 i znikalo mu licznik w GUI,
     * mimo ze tak naprawde po prostu nie zdazylismy policzyc.
     */
    private static final ThreadLocal<boolean[]> ESTIMATE_ABORTED =
            ThreadLocal.withInitial(() -> new boolean[]{false});

    /** Ustawia budzet czasowy dla biezacego szacowania. */
    private static void startEstimate(long budgetNanos) {
        ESTIMATE_OPS.get()[0] = 0;
        ESTIMATE_ABORTED.get()[0] = false;
        ESTIMATE_DEADLINE.get()[0] = budgetNanos > 0
                ? System.nanoTime() + budgetNanos
                : 0L;
    }

    /** Czy biezace szacowanie zostalo przerwane (wynik nieznany). */
    private static boolean estimateAborted() {
        return ESTIMATE_ABORTED.get()[0];
    }

    /**
     * Czy szacowanie powinno sie przerwac.
     *
     * <p><b>Historia tego buga.</b> Sprawdzanie czasu bylo probkowane co 64
     * wywolania ({@code (ops & 63) == 0}). Brzmi jak sensowna optymalizacja,
     * ale jest bledne: jesli cale planowanie jednego itemu robi MNIEJ niz 64
     * wywolania {@code plan}, kontrola wypada tylko raz - przy pierwszym
     * wywolaniu, kiedy czas jeszcze nie minal - i juz nigdy wiecej. Budzet
     * czasu byl wtedy calkowicie martwy, a jedynym ograniczeniem zostawal
     * awaryjny licznik operacji. Dokladnie to widac bylo w logu:
     *
     * <pre>
     *   TICK OVERRUN: 4520 ms total = scan 0 ms + chunks 0 ms + items 4520 ms
     * </pre>
     *
     * <p>Dlatego czas sprawdzamy teraz przy KAZDYM wejsciu. {@code nanoTime}
     * kosztuje kilkadziesiat nanosekund, a chroni przed zamrozeniem serwera.
     */
    private static boolean estimateBudgetExceeded() {
        int ops = ESTIMATE_OPS.get()[0]++;
        long deadline = ESTIMATE_DEADLINE.get()[0];
        if (deadline != 0L && System.nanoTime() > deadline) {
            ESTIMATE_ABORTED.get()[0] = true;
            return true;
        }
        if (ops > MAX_ESTIMATE_OPS) {
            ESTIMATE_ABORTED.get()[0] = true;
            return true;
        }
        return false;
    }

    /**
     * Ile sztuk danego itemu da sie <b>dorobic</b> auto-craftingiem ponad to,
     * co juz jest w sieci.
     *
     * <p>To jest liczba dla zoltego wskaznika "+N" w terminalu, wiec musi byc
     * <b>nadwyzka</b>, a nie suma. Wczesniej zwracala lacznie dostepne sztuki,
     * przez co po wycraftowaniu 4 desek (1 zabrana, 3 w buforze) pokazywalo
     * "+3" mimo braku jakiegokolwiek loga - bo licznik zeral wlasny stock.
     *
     * @return ile sztuk da sie jeszcze dorobic (0 gdy brak bazowych skladnikow)
     */
    public static long countCraftableNow(ServerLevel level, VelocePipeNetwork network,
                                         Item item, Set<Item> enabledItems,
                                         Map<Item, ResourceLocation> preferred) {
        return countCraftableNow(level, network, item, enabledItems, preferred,
                DEFAULT_ESTIMATE_BUDGET_NS);
    }

    /**
     * Jak wyzej, ale z jawnym budzetem czasowym.
     *
     * @param budgetNanos maksymalny czas w nanosekundach; 0 = bez limitu czasu
     *                    (tylko awaryjny limit operacji)
     */
    public static long countCraftableNow(ServerLevel level, VelocePipeNetwork network,
                                         Item item, Set<Item> enabledItems,
                                         Map<Item, ResourceLocation> preferred,
                                         long budgetNanos) {
        if (!enabledItems.contains(item)) {
            return 0L;
        }
        Map<Item, Long> stock = new HashMap<>(network.getAllItemCounts(level));
        return countCraftableFromStock(level, network, item, stock, enabledItems, preferred,
                budgetNanos, VeloceHeatSources.totalOperations(level, network));
    }

    /**
     * Ile sztuk da sie <b>dorobic z surowcow</b> - bez tego, co juz gotowe.
     *
     * <p><b>BUG, ktory to naprawia.</b> Poprzednia wersja liczyla tak:
     * <pre>
     *   total = maxCraftable(stock)      // = fromStock + lo
     *   craftable = total - onStock      // = lo
     * </pre>
     * i wydawalo sie, ze zapas jest odjety. Ale <b>nie jest</b>: funkcja
     * {@code plan} (uzywana w bisekcji wewnatrz {@code maxCraftable}) NAJPIERW
     * ZUZYWA to, co juz lezy na stanie, i dopiero reszte craftuje. Wiec
     * {@code lo} = maksimum "ile mozna MIEĆ", a nie "ile mozna DOROBIC" -
     * czyli zawiera zapas. Odjecie zapasu nic nie dawalo, bo ten sam zapas
     * byl juz wliczony w {@code lo}.
     *
     * <p><b>Objaw.</b> Terminal pokazywal licznik, ktory zmienial sie od
     * przelozenia gotowego itemu:
     * <pre>
     *   356  (0 na stanie)
     *   355  po scraftowaniu 1 logu: +3 do bufora, -1 log  (-4 z surowcow +3 zapas)
     *   354  po wyjeciu 1 sztuki z bufora                 (-1 zapas)
     *   353  po wyjeciu kolejnej                          (-1 zapas)
     * </pre>
     * Gotowe itemy w buforze pomniejszaly wiec liczbe "ile moge jeszcze
     * zrobic", choc te dwie rzeczy mialy byc rozdzielone.
     *
     * <p><b>Rozwiazanie.</b> Liczymy z zapasem tego itemu WYZEROWANYM.
     * Wtedy plan nie ma czego zuzyc na poczatek, wiec bisekcja znajduje
     * dokladnie to, ile da sie wyprodukowac z surowcow. Wynik nie zalezy od
     * tego, ile gotowych sztuk lezy w buforze, w skrzyni czy gdziekolwiek.
     *
     * @return ile da sie dorobic z surowcow (UNKNOWN_COUNT gdy brak budzetu)
     */
    private static long craftableFromRaw(ServerLevel level, VelocePipeNetwork network,
                                         Item item, Map<Item, Long> stock,
                                         Set<Item> enabled,
                                         Map<Item, ResourceLocation> preferred,
                                         long heatOps) {
        Map<Item, Long> rawStock = new HashMap<>(stock);
        rawStock.put(item, 0L);
        // maxCraftable przy zerowym zapasie zwraca samo "lo" (bo fromStock = 0),
        // czyli dokladnie ilosc wykonalna z surowcow.
        return maxCraftable(level, network, item, rawStock, enabled, preferred, heatOps);
    }

    public static long countCraftableFromStock(
            ServerLevel level, VelocePipeNetwork network, Item item, Map<Item, Long> stock,
            Set<Item> enabledItems, Map<Item, ResourceLocation> preferred,
            long budgetNanos, long heatOps) {
        if (!enabledItems.contains(item)) {
            return 0L;
        }
        startEstimate(budgetNanos);
        long craftable = craftableFromRaw(level, network, item, stock, enabledItems, preferred, heatOps);
        if (craftable == UNKNOWN_COUNT) {
            return UNKNOWN_COUNT;
        }
        return Math.max(0L, craftable);
    }

    /**
     * Wynik obliczenia partii.
     *
     * @param counts   ile da sie dorobic (tylko wartosci > 0)
     * @param complete czy przeliczono WSZYSTKIE zadane itemy
     * @param heatAvailable czy w sieci stoi JAKIEKOLWIEK zrodlo ciepla (piec).
     *        Do logu: bez tego nie da sie odroznic "nie ma pieca" od "piec
     *        wlasnie dopala paliwo" - a to dwa zupelnie rozne stany.
     */
    public record BatchResult(Map<Item, Long> counts, boolean complete,
                              boolean heatAvailable) {
        /** Wariant bez informacji o cieple - dla wczesnych wyjsc (brak sieci itp.). */
        public BatchResult(Map<Item, Long> counts, boolean complete) {
            this(counts, complete, false);
        }
    }

/**
     * Liczy wiele itemow naraz, wspoldzielac jeden budzet czasowy.
     *
     * <p>Uzywane do NATYCHMIASTOWEGO przeliczenia widocznej strony terminala.
     * Zamiast placic za odczyt stocku przy kazdym itemie, robimy go raz.
     * Dzieki temu 45 itemow liczy sie w kilka milisekund.
     *
     * @return mapa item -> ile da sie dorobic (tylko wartosci > 0)
     */
    public static Map<Item, Long> countCraftableBatch(
            ServerLevel level, VelocePipeNetwork network,
            java.util.Collection<Item> items, Set<Item> enabledItems,
            Map<Item, ResourceLocation> preferred, long budgetNanos) {
        return countCraftableBatchResult(level, network, items, enabledItems,
                preferred, budgetNanos).counts();
    }

    /**
     * Jak {@link #countCraftableBatch}, ale mowi tez czy partia zostala
     * przeliczona w calosci.
     *
     * <p>To wazne dla GUI: gdy budzet sie skonczyl i czesc itemow zostala
     * pominięta, klient NIE moze uznac braku wpisu za "nie da sie zrobic" -
     * inaczej liczby znikaly i nie wracaly.
     */
    public static BatchResult countCraftableBatchResult(
            ServerLevel level, VelocePipeNetwork network,
            java.util.Collection<Item> items, Set<Item> enabledItems,
            Map<Item, ResourceLocation> preferred, long budgetNanos) {
        Map<Item, Long> out = new HashMap<>();
        if (items == null || items.isEmpty()) {
            return new BatchResult(out, true, false);
        }

        // Jednorazowy odczyt stocku dla calej partii.
        Map<Item, Long> stockSnapshot = network.getAllItemCounts(level);

        // Cieplo dla LICZB: "czy w sieci stoi piec", a nie "ile ma TERAZ
        // w buforze".
        //
        // BUG, ktory to naprawia (zgloszenie gracza: "liczby sie nie laduja"):
        // bralismy totalOperations(), czyli biezacy bufor paliwa. Piec paliwowy
        // dopala paliwo i DOKLADA je sobie z sieci, wiec bufor cyklicznie
        // spada do zera - w tym oknie receptury pieca byly wylaczone, liczba
        // dla szkla wychodzila 0 i taka zostawala w GUI (zero sie nie rysuje),
        // a po skasowaniu tekstu w wyszukiwarce nowe zadanie trafialo na
        // moment z paliwem i liczba sie pojawiala. Liczba ma odpowiadac na
        // pytanie "ile moge miec z tego, co jest w sieci", a nie "na ile
        // starczy paliwa w tej sekundzie" (patrz estimateHeatOps).
        boolean heatAnywhere = VeloceHeatSources.hasAnyHeatSource(level, network);
        long heatOps = heatAnywhere ? ESTIMATE_HEAT_OPS : 0L;

        // Skoro liczymy "ile MOGE miec", to przy postawionym piecu itemy
        // z receptura pieca sa liczalne nawet w oknie bez paliwa - inaczej
        // wypadaly z `enabled` i dostawaly zero (patrz komentarz wyzej).
        Set<Item> countable = enabledItems;
        if (heatAnywhere && !VeloceRecipeRegistry.getAllFurnaceCraftableItems(level).isEmpty()) {
            Set<Item> merged = new HashSet<>(enabledItems);
            merged.addAll(VeloceRecipeRegistry.getAllFurnaceCraftableItems(level));
            countable = merged;
        }
        long deadline = budgetNanos > 0 ? System.nanoTime() + budgetNanos : 0L;
        boolean complete = true;

        // KOLEJNOSC: taka, jaka przyszla z klienta.
        //
        // Bylo tu przesuniecie startu ("rotacja"), zeby ogon listy tez kiedys
        // doczekal sie liczenia. Okazalo sie jednak gorsze od problemu: partia
        // startowala w losowym miejscu, konczyl sie budzet i POCZATEK listy
        // (np. glass w wyszukiwarce) nie mial liczb, a dol je mial - dokladnie
        // to zglosil gracz. Klient sam ustawia teraz itemy bez wartosci na
        // poczatku zadania (patrz VeloceCraftableCounts), wiec serwer ma po
        // prostu liczyc w podanej kolejnosci.
        List<Item> queue = items instanceof List<Item> list ? list : new ArrayList<>(items);
        int size = queue.size();
        int aborted = 0;

        for (int i = 0; i < size; i++) {
            Item item = queue.get(i);
            if (!countable.contains(item)) {
                // Wpis "nie da sie zrobic" jest POPRAWNY i musi trafic do
                // wyniku - inaczej GUI zachowaloby stara, zawyzona liczbe.
                out.put(item, 0L);
                continue;
            }
            // Przerwij, gdy minie budzet - reszta przy nastepnym zadaniu.
            if (deadline != 0L && System.nanoTime() > deadline) {
                complete = false;
                break;
            }
            Map<Item, Long> stock = new HashMap<>(stockSnapshot);
            // Budzet na TEN item = rowny udzial z reszty czasu, a nie cale okno.
            //
            // BUG, ktory to naprawia: kazdy item dostawal CALE pozostale okno,
            // wiec jeden zbyt zlozony (albo drogi) zjadal budzet calej partii
            // i reszta itemow nie dostawala liczb - w logu gracza widac bylo
            // "45 item(s) -> 0 result(s) (complete=false)". Rowny udzial
            // gwarantuje, ze kazdy item ma swoja szanse, a te, ktore nie
            // zdaza, wracaja w kolejnym zadaniu - na przodzie listy, bo klient
            // ustawia itemy bez wartosci pierwsze (VeloceCraftableCounts).
            long slice = 0L;
            if (deadline != 0L) {
                long left = Math.max(0L, deadline - System.nanoTime());
                slice = Math.max(MIN_ITEM_BUDGET_NS, left / Math.max(1, size - i));
            }
            startEstimate(slice);
            long total = craftableFromRaw(level, network, item, stock, countable, preferred, heatOps);
            // (indeks `i` sluzy tylko do rownego podzialu budzetu wyzej)
            if (total == UNKNOWN_COUNT) {
                // TEN item nie zmiescil sie w budzecie - pomijamy GO, ale NIE
                // przerywamy calej partii.
                //
                // BUG, ktory to naprawia: `break` konczyl partie po pierwszym
                // trudnym itemie. W logu gracza bylo to widac jako
                // "instant craftable count for 45 item(s) -> 0 result(s)
                // (complete=false)" - JEDEN zbyt zlozony item (albo chwilowy
                // brak czasu) odbieral liczby WSZYSTKIM pozostalym, a gracz
                // widzial to jako "kontroler nie umie policzyc pieca".
                //
                // Kolejny item dostaje swiezy budzet (startEstimate nizej),
                // a gdy skonczy sie CZAS, petla przerwie sie na sprawdzeniu
                // deadline'u na gorze - wiec nie ma ryzyka zapetlenia.
                complete = false;
                aborted++;
                continue;
            }
            // Zapisujemy TAKZE zera. Wczesniej wpis pojawial sie tylko dla
            // surplus > 0, wiec "nie da sie juz nic zrobic" bylo nieodroznialne
            // od "nie policzono tego itemu" i klient zachowywal stara, zawyzona
            // liczbe. Zero to konkretna, poprawna odpowiedz.
            out.put(item, Math.max(0L, total));
        }
        if (aborted > 0) {
            // Ile itemow nie zmiescilo sie w swoim udziale - to ta liczba
            // tlumaczy "GUI nie pokazuje liczb dla czesci itemow".
            VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                    "craftable count: %d z %d itemow przerwalo szacowanie "
                            + "(za ciezki item na swoj udzial czasu)", aborted, size);
        }
        return new BatchResult(out, complete, heatAnywhere);
    }

    /**
     * Znajduje NAJWIEKSZA ilosc itemu, ktora da sie zaplanowac z tego stocku.
     *
     * <p>Uzywane, gdy nie udalo sie zaplanowac pelnej zamowionej ilosci. Bisekcja
     * po ilosci: zamiast oddawac "nie da sie" i zostawiac gracza z niczym,
     * dostarczamy tyle, ile realnie jestesmy w stanie zrobic (np. 12 z 64).
     *
     * @return ilosc wpisana do {@code plan}, albo 0 gdy nie da sie zrobic nic
     */
    private static long planAsMuchAsPossible(ServerLevel level, Context ctx, Item item,
                                             long wanted, Map<Item, Long> stock, Plan plan,
                                             long planBudgetNanos) {
        // Krok 1: znajdz JAKAKOLWIEK wykonalna ilosc, schodzac po polowie.
        // To logarytmicznie malo prob.
        long found = 0;
        Plan best = null;
        for (long tryAmount = wanted / 2; tryAmount >= 1; tryAmount /= 2) {
            startEstimate(planBudgetNanos);
            Map<Item, Long> copy = new HashMap<>(stock);
            Plan candidate = new Plan(ctx.heatOps());
            if (plan(level, ctx.network, ctx.enabledItems, ctx.preferred, item, tryAmount, copy,
                    candidate, new HashSet<>(), 0)) {
                found = tryAmount;
                best = candidate;
                break;
            }
            if (estimateAborted()) {
                return 0;   // budzet - nie ma sensu probowac dalej
            }
        }
        if (found <= 0 || best == null) {
            return 0;
        }

        // Krok 2: dobij do gory, o ile budzet pozwala.
        //
        // Samo schodzenie po polowie gubilo ilosci: przy zamowieniu 64
        // znajdowalo 32 i konczylo, mimo ze wykonalne bylo np. 40. Zwiekszamy
        // wiec krokami polowy pozostalej odleglosci, az przestanie sie udawac.
        long lo = found;
        long hi = wanted;
        while (lo < hi && !estimateAborted()) {
            // mid jest zawsze > lo, gdy lo < hi, wiec petla zawsze sie posuwa.
            long mid = lo + (hi - lo + 1) / 2;
            startEstimate(planBudgetNanos);
            Map<Item, Long> copy = new HashMap<>(stock);
            Plan candidate = new Plan(ctx.heatOps());
            if (plan(level, ctx.network, ctx.enabledItems, ctx.preferred, item, mid, copy,
                    candidate, new HashSet<>(), 0)) {
                lo = mid;
                best = candidate;
            } else {
                hi = mid - 1;
                if (estimateAborted()) {
                    break;
                }
            }
        }

        plan.runs.clear();
        plan.runs.addAll(best.runs);
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "%s: full amount %d not craftable, doing %d instead", item, wanted, lo);
        return lo;
    }

    /**
     * Loguje, dlaczego planowanie sie nie udalo.
     *
     * <p>Idzie przez {@link VeloceLog}, a nie prosto do LOGGERa.
     *
     * <p><b>Bylo tu zrodlo smieci w logu.</b> Poprzednia wersja pisala przez
     * {@code CraftingVeloceMod.LOGGER.info(...)} z wlasnym prefiksem "[Veloce]",
     * wiec omijala i poziom debugowania, i przelaczniki kategorii z configu -
     * leciala ZAWSZE, przy kazdej nieudanej probie. A ze kazde klikniecie
     * itemu, ktorego nie da sie zrobic, konczy sie nieudanym planem, log
     * zapychal sie przy normalnym klikaniu po GUI.
     *
     * <p>Dodatkowo pierwszy skladnik kazdej receptury jest niczym wiecej jak
     * PRZYKLADEM (Ingredient.getItems() zwraca wszystkie akceptowane stosy),
     * wiec "ma=0" dla niego nie znaczy, ze brakuje wlasnie tego itemu.
     */
    private static void logPlanFailure(ServerLevel level, Context ctx, Item item,
                                       int missing, Map<Item, Long> stock) {
        if (!VeloceLog.Craft.isDetailEnabled(VeloceLog.Side.SERVER)) {
            return;   // tanie sprawdzenie - nie budujemy stringow na darmo
        }
        VeloceLog.Craft.why(VeloceLog.Side.SERVER,
                "craft %s x%d failed: no base ingredients (enabled=%d, %d item type(s) in stock)",
                item, missing, ctx.enabledItems.size(), stock.size());
        var recipes = ctx.recipesFor(item);
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "  %s has %d recipe(s)", item, recipes.size());
        for (var r : recipes) {
            StringBuilder sb = new StringBuilder();
            for (var ing : r.ingredients()) {
                var opts = ing.getItems();
                if (opts.length == 0) {
                    continue;
                }
                // Podajemy liczbe AKCEPTOWANYCH opcji, a nie jeden przyklad -
                // pojedynczy "ma=0" mylil, bo brakowalo innej opcji.
                int have = 0;
                for (var opt : opts) {
                    if (stock.getOrDefault(opt.getItem(), 0L) > 0) {
                        have++;
                    }
                }
                sb.append(have).append('/').append(opts.length).append(" option(s) available; ");
            }
            VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                    "  %s <- %s", r.id(), sb.toString());
        }
    }

    // ------------------------------------------------------------------
    // Faza planowania - czysta arytmetyka na liczbach
    // ------------------------------------------------------------------

    /** Plan: ile razy wykonac ktora recepture, w kolejnosci wykonania. */
    static final class Plan {
        final List<PlannedRun> runs = new ArrayList<>();

        /**
         * Ile przepalen jeszcze wolno zaplanowac.
         *
         * <p>Receptura pieca zjada jedno przepalenie za KAZDA sztuke, wiec
         * plan nie moze obiecac wiecej itemow, niz piec ugnie. Zero oznacza
         * takze "brak pieca" - i dlatego gasi receptury piecowe w trakcie
         * planowania, gdy budzet sie skonczy.
         */
        long heatRemaining;

        Plan(long heatRemaining) {
            this.heatRemaining = heatRemaining;
        }

        void add(ProcessingEntry recipe, long times) {
            runs.add(new PlannedRun(recipe, times));
        }

        /**
         * Wycofuje plan do znacznika - <b>razem z cieplem</b>.
         *
         * <p>To nie jest kosmetyka. Planowanie probuje kolejne receptury i
         * cofa nieudane proby, a kazda nieudana proba mogla juz zjesc cieplo.
         * Bez oddania go budzet topnialby przy samych niepowodzeniach i po
         * kilku probach planer uznalby, ze piec jest pusty - mimo ze nie
         * wykonano ani jednego przepalenia.
         */
        void rollbackTo(int mark) {
            while (runs.size() > mark) {
                PlannedRun removed = runs.remove(runs.size() - 1);
                if (removed.recipe().isFurnace()) {
                    heatRemaining += removed.times();
                }
            }
        }
    }

    record PlannedRun(ProcessingEntry recipe, long times) {
    }

    /**
     * Planuje uzyskanie {@code amount} sztuk {@code item}.
     * Modyfikuje {@code stock} (symulacja zuzycia). Nie rusza swiata.
     */
    private static boolean plan(ServerLevel level, VelocePipeNetwork network,
                                Set<Item> enabled,
                                Map<Item, ResourceLocation> preferred,
                                Item item, long amount,
                                Map<Item, Long> stock, Plan plan, Set<Item> visiting, int depth) {
        if (amount <= 0) {
            return true;
        }
        // Budzet czasowy sprawdzamy ZAWSZE, na samym wejsciu.
        //
        // BUG, ktory wisial tu wczesniej: ten warunek byl wciagniety do
        // srodka bloku "depth/step przekroczony". Czyli wykonywal sie tylko
        // wtedy, gdy limit juz i tak byl przekroczony - a wtedy zwracalo i
        // tak false. Efekt: plan() nie mial ZADNEGO throttlingu czasowego i
        // drzewo receptur rozrastalo sie wykładniczo na watku serwera.
        if (estimateBudgetExceeded()) {
            return false;
        }
        if (depth > CRAFT_MAX_DEPTH || plan.runs.size() > CRAFT_MAX_STEPS) {
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
            if (!enabled.contains(item)) {
                return false;
            }

            // Receptury pieca tylko dopoki jest czym zaplacic. Gdy budzet
            // ciepla sie skonczy, piec przestaje byc opcja - tak samo, jak
            // przestalby nia byc, gdyby brakowalo skladnikow.
            List<ProcessingEntry> recipes =
                    orderRecipes(level, network, item, preferred, plan.heatRemaining > 0,
                            network.prefersFurnace(item));
            if (recipes.isEmpty()) {
                return false;
            }

            // Probuj kolejne receptury - pierwsza wykonalna wygrywa.
            for (ProcessingEntry recipe : recipes) {
                Map<Item, Long> snapshot = new HashMap<>(stock);
                int planMark = plan.runs.size();
                if (planRecipe(level, network, enabled, preferred, recipe, remaining, stock, plan, visiting, depth)) {
                    return true;
                }
                // Receptura nie wyszla - cofnij symulacje.
                stock.clear();
                stock.putAll(snapshot);
                plan.rollbackTo(planMark);
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
    private static boolean planRecipe(ServerLevel level, VelocePipeNetwork network,
                                      Set<Item> enabled,
                                      Map<Item, ResourceLocation> preferred,
                                      ProcessingEntry recipe, long amount,
                                      Map<Item, Long> stock, Plan plan,
                                      Set<Item> visiting, int depth) {
        // Ile sztuk daje JEDNO wykonanie: wynik glowny razy jego liczba.
        //
        // Liczymy z wyniku GLOWNEGO (pierwszy gwarantowany), a nie z sumy
        // wszystkich - planowanie widzi tylko wyniki gwarantowane, zeby nigdy
        // nie obiecac itemu, ktory moze nie wypasc (patrz ProcessingEntry).
        ItemStack primary = recipe.primaryResult();
        long perCraft = Math.max(1, primary.getCount());
        long times = (amount + perCraft - 1) / perCraft;
        if (times <= 0 || times > CRAFT_MAX_STEPS) {
            return false;
        }

        // RECEPTURA PIECA PLACI CIEPLEM - jedno przepalenie na sztuke.
        //
        // Sprawdzamy PRZED planowaniem skladnikow, bo gdy ciepla nie ma,
        // planowanie ich nie ma sensu. Gdy reszta planu sie nie powiedzie,
        // wolajacy wycofa plan przez rollbackTo, ktore odda cieplo z powrotem.
        if (recipe.isFurnace()) {
            if (times > plan.heatRemaining) {
                return false;
            }
            plan.heatRemaining -= times;
        }

        // Najpierw zaplanuj skladniki (rekurencja), potem zapisz siebie.
        //
        // LICZBY SZTUK: skladnik moze wymagac wiecej niz jednej sztuki na
        // wykonanie (Alchemistry IngredientStack, Mekanism SizedIngredient).
        // Wczesniej planer zakladal `need = times`, wiec taka receptura
        // zuzywalaby w planie mniej, niz naprawde potrzebuje - liczby w GUI
        // bylyby zawyzone, a wykonanie gubilo itemy.
        List<Ingredient> ingredientList = recipe.ingredients();
        for (int ingIndex = 0; ingIndex < ingredientList.size(); ingIndex++) {
            Ingredient ing = ingredientList.get(ingIndex);
            long perIngredient = recipe.ingredientCount(ingIndex);
            if (!hasOptions(ing)) {
                continue;   // pusty slot siatki albo skladnik bez zadnej opcji
            }
            List<ItemStack> options = nonEmpty(ing);
            Map<Item, Long> snapshot = new HashMap<>(stock);
            int planMark = plan.runs.size();

            boolean supplied = false;
            for (ItemStack opt : options) {
                Item optItem = opt.getItem();
                long avail = stock.getOrDefault(optItem, 0L);
                long need = times * perIngredient;
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
                if (plan(level, network, enabled, preferred, optItem, lacking, stock, plan, visiting, depth + 1)) {
                    // ZUZYJ TO, CO WLASNIE ZAPLANOWANO.
                    //
                    // BUG, ktory tu byl: po udanym planowaniu zostawialismy
                    // w stocku WSZYSTKO, co powstalo, nie odejmujac `need`.
                    // Wczesniej ustawilismy stock[optItem] = 0, wiec plan()
                    // wyprodukowal `lacking` sztuk - ale nikt ich nie zuzywal.
                    // Zostawaly wiec jako darmowy nadmiar i kolejne skladniki
                    // (oraz kolejne iteracje bisekcji) widzialy itemy, ktorych
                    // w rzeczywistosci nie ma.
                    //
                    // Objaw: z 2 klod terminal pokazywal 6 plotkow (naprawde 1),
                    // a po skraftowaniu liczby "same sie zmienialy".
                    long produced = stock.getOrDefault(optItem, 0L);
                    stock.put(optItem, Math.max(0L, produced - need));
                    supplied = true;
                    break;
                }
                stock.clear();
                stock.putAll(snap2);
                plan.rollbackTo(mark2);
            }
            if (!supplied) {
                stock.clear();
                stock.putAll(snapshot);
                plan.rollbackTo(planMark);
                return false;
            }
        }

        plan.add(recipe, times);

        // KLUCZOWE: zalicz wyprodukowane itemy do symulowanego stocku.
        //
        // Bez tego planowanie nie widzialo wlasnych wynikow posrednich.
        // Przyklad: plotek potrzebuje 4 desek i 2 patykow. Deski zostaja
        // zaplanowane z klod (4 sztuki), ale stock[deski] dalej wynosil 0,
        // wiec kolejne sloty desek w recepturze nie mogly ich znalezc i plan
        // padal z "brak bazowych skladnikow" - mimo ze klody byly w sieci.
        // To bylo zrodlo bledu "nie moge zrobic fence a mam logi w skrzynce".
        //
        // Zaliczamy WSZYSTKIE wyniki gwarantowane (Fission ma dwa), a nie
        // tylko glowny - inaczej receptura wielowyjsciowa tworzylaby w planie
        // mniej, niz daje w rzeczywistosci.
        for (ItemStack guaranteed : recipe.guaranteedResults()) {
            if (!guaranteed.isEmpty()) {
                stock.merge(guaranteed.getItem(), times * guaranteed.getCount(), Long::sum);
            }
        }
        return true;
    }

    /**
     * Ile sztuk danego itemu mozna realnie uzyskac z tego stocku.
     *
     * <p><b>Dlaczego nie wzorem.</b> Poprzednia wersja liczyla limit osobno dla
     * kazdego skladnika, biorac dla kazdego PELNY zapas bazowy. Przy plotku
     * (4 deski + 2 patyki) deski liczylo z 7 klod i patyki tez z tych samych
     * 7 klod - mimo ze patyki robi sie Z desek, wiec oba ciagnely z jednego
     * zrodla. Wynik byl zawyzony i nie zgadzal sie z rzeczywistoscia.
     *
     * <p>Teraz sprawdzamy wykonalnosc PRAWDZIWYM planowaniem (ktore poprawnie
     * zuzywa skladniki i zalicza wyniki posrednie) i szukamy najwiekszej
     * mozliwej liczby bisekcja. Liczba jest prawdziwa, bo pochodzi z tego
     * samego kodu, ktory potem faktycznie craftuje.
     *
     * <p>Koszt: log2(N) planowan na jeden item. Planowanie jest tanie, bo
     * operuje na liczbach, bez ruszania swiata.
     *
     * @return laczna liczba sztuk dostepnych (stock + to, co da sie dorobic)
     */
    private static long maxCraftable(ServerLevel level, VelocePipeNetwork network,
                                     Item item, Map<Item, Long> stock,
                                     Set<Item> enabled,
                                     Map<Item, ResourceLocation> preferred,
                                     long realHeatOps) {
        // SZACOWANIE liczy "ile moge MIEC", a nie "ile piec ugnie w tej
        // sekundzie" - patrz estimateHeatOps(). Plan WYKONANIA nadal dostaje
        // prawdziwy budzet (Context.heatOps() -> consumeFrom).
        long heatOps = estimateHeatOps(realHeatOps);
        long fromStock = stock.getOrDefault(item, 0L);
        if (!enabled.contains(item)) {
            return fromStock;
        }
        List<ProcessingEntry> recipes = allRecipesFor(level, network, item, heatOps > 0);
        if (recipes.isEmpty()) {
            return fromStock;
        }

        // SZYBKA SCIEZKA dla itemow powstajacych WYLACZNIE w piecu z surowcow,
        // ktore same nie maja receptury (np. szklo z piasku).
        //
        // Po co: odpowiedz jest wtedy zwykla arytmetyka na stocku, a nie
        // bisekcja z pelnym planowaniem. Dzieki temu takie itemy (a jest ich
        // kilkadziesiat - patrz "furnace=74" w logu) nie zjadaja budzetu
        // partii i nie znikaja z GUI, gdy budzet sie skonczy.
        long furnaceOnly = countFurnaceOnly(level, item, stock, heatOps, recipes);
        if (furnaceOnly >= 0) {
            return fromStock + furnaceOnly;
        }

        // Gorna granica bisekcji.
        //
        // BUG, ktory tu byl: bralismy sume WSZYSTKICH sztuk w sieci i twierdzilismy,
        // ze "kazdy craft zuzywa co najmniej jeden item, wiec nie da sie zrobic
        // wiecej". To nieprawda dla receptur dajacych wiele sztuk: 1 kloda ->
        // 4 deski -> 16 patykow. Przy 64 klodach granica wychodzila 64, wiec
        // bisekcja NIGDY nie sprawdzila wiecej - i terminal pokazywal "64 plotki"
        // tam, gdzie naprawde mozna zrobic kilkaset.
        //
        // Teraz liczymy REALNA gorna granice: dla kazdego surowca mnozymy jego
        // ilosc przez to, ile sztuk danego itemu da sie z niego uzyskac w jednym
        // ciagu receptur. To wciaz tylko ograniczenie bisekcji (bezpieczne
        // zawyzenie), a prawdziwa wartosc i tak znajduje planer.
        long hi = Math.min(estimateUpperBound(level, network, item, stock, recipes, heatOps > 0),
                MAX_ESTIMATE_RESULT);
        if (hi <= 0) {
            return fromStock;
        }

        // Bisekcja: znajdz najwieksze N, dla ktorego planowanie sie udaje.
        long lo = 0;
        while (lo < hi) {
            long mid = (lo + hi + 1) >>> 1;
            if (canCraftAmount(level, network, item, mid, stock, enabled, preferred, heatOps)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        // Budzet sie skonczyl w trakcie - wynik jest niemiarodajny.
        // Zwracamy UNKNOWN, zeby GUI zachowalo poprzednia liczbe.
        if (estimateAborted()) {
            return UNKNOWN_COUNT;
        }
        return fromStock + lo;
    }

    /**
     * Ile sztuk itemu da sie zrobic w piecu - bez planera.
     *
     * <p>Stosuje sie TYLKO wtedy, gdy item powstaje wylacznie w piecach i z
     * surowcow, ktore same nie maja zadnej receptury. Wtedy limitem jest
     * wyłącznie stock surowca, wiec wynik to
     * {@code min(stock_surowca / ile_potrzeba) * ile_wychodzi} - dokladnie to,
     * co policzylby planer, tylko bez bisekcji.
     *
     * <p>Jesli item ma tez recepture craftingowa, albo ktorys surowiec da sie
     * dorobic (ma wlasna recepture), szybka sciezka sie wycofuje: wtedy wynik
     * zalezy od calego lancucha i musi go policzyc planer.
     *
     * @return policzona liczba sztuk albo {@code -1} = "uzyj planera"
     */
    private static long countFurnaceOnly(ServerLevel level, Item item,
                                         Map<Item, Long> stock, long heatOps,
                                         List<ProcessingEntry> recipes) {
        if (recipes.isEmpty()) {
            return 0L;
        }
        long best = 0;
        for (ProcessingEntry recipe : recipes) {
            if (!recipe.isFurnace()) {
                return -1L;   // jest tez crafting - to robota dla planera
            }
            long runs = Long.MAX_VALUE;
            List<Ingredient> ingredientList = recipe.ingredients();
            for (int ingIndex = 0; ingIndex < ingredientList.size(); ingIndex++) {
                Ingredient ingredient = ingredientList.get(ingIndex);
                // Skladnik moze wymagac kilku sztuk (Alchemistry, Mekanism) -
                // wtedy limit to stock podzielony przez te liczbe, a nie przez 1.
                long perIngredient = recipe.ingredientCount(ingIndex);
                long bestOption = 0;
                for (ItemStack option : ingredient.getItems()) {
                    Item raw = option.getItem();
                    if (!VeloceRecipeRegistry.getRecipesFor(level, raw, true).isEmpty()) {
                        // Surowiec sam jest craftowalny - sam stock nie jest
                        // prawdziwym limitem, planer policzy to lepiej.
                        return -1L;
                    }
                    long needed = Math.max(1, option.getCount()) * perIngredient;
                    bestOption = Math.max(bestOption, stock.getOrDefault(raw, 0L) / needed);
                }
                runs = Math.min(runs, bestOption);
            }
            if (runs == Long.MAX_VALUE) {
                return -1L;   // receptura bez sensownych skladnikow
            }
            // Bez ciepla piec nie zrobi nic - ale to juz wiemy po heatOps.
            if (heatOps <= 0) {
                return 0L;
            }
            best = Math.max(best, Math.min(MAX_ESTIMATE_RESULT,
                    runs * Math.max(1, recipe.primaryResult().getCount())));
        }
        return best;
    }

    /** Limit wyniku szacowania - chroni przed absurdalna bisekcja. */
    private static final long MAX_ESTIMATE_RESULT = 100_000L;

    /**
     * Cieplo dla SZACOWANIA - czyli dla liczby, ktora widzi gracz.
     *
     * <p><b>BUG, ktory to naprawia (zgloszenie gracza).</b> Szacowanie dostawalo
     * ten sam budzet ciepla, co plan wykonania, wiec liczba przy itemie z
     * receptura pieca byla obcinana do tego, ILE PIEC MA TERAZ W BUFORZE.
     * Przy piecu z 25 przepaleniami bufora terminal pokazywal 25 szkla
     * niezaleznie od tego, czy w skrzynce lezy 32 czy 64 piasku ("wyjme
     * polowe, dalej pokazuje 25"). Liczba ma odpowiadac na pytanie "ile moge
     * miec z tego, co jest w sieci", a nie "ile piec ugnie w tej sekundzie".
     *
     * <p>Rozroznienie jest takie samo jak przy {@code isPowered()}: zero
     * ciepla = receptury pieca wylaczone (nie ma czym palic), jakakolwiek
     * ilosc = receptury dzialaja, a liczba idzie za surowcem. Piec paliwowy
     * dociaga paliwo z sieci sam, wiec jego bufor nie jest limitem tego, ile
     * da sie zrobic - limitem jest surowiec (i paliwo w sieci). Piec
     * elektryczny ma zasob, ktory trzeba uzupelniac, ale i tak odpowiada na
     * inne pytanie: liczba mowi "ile MOGE miec z tego, co lezy w sieci", a
     * nie "na ile starczy jednego ladowania".
     *
     * <p>Wartosc jest wieksza od {@link #MAX_ESTIMATE_RESULT}, wiec nigdy nie
     * obetnie wyniku; jest zas malenka, zeby nie kusilo liczenia "w
     * nieskonczonosc" i nie ryzykowac przepelnienia.
     */
    /**
     * Najkrotszy budzet, jaki dostaje pojedynczy item w partii (2 ms).
     *
     * <p>Bez dolnej granicy rowny udzial przy dlugiej liscie spadlby do zera
     * i zadnego itemu nie daloby sie policzyc.
     *
     * <p><b>Dlaczego 2 ms, a nie 0.2 ms.</b> W logu gracza widac bylo partie
     * "45 item(s) -> 9 result(s) in 25 ms (complete=false)" powtarzane w kolko:
     * przy udziale 0.2 ms ciezkie itemy (szklo i jego warianty) PRZERYWALY
     * szacowanie przy kazdym zadaniu, wiec nigdy nie dostawaly liczby, a ich
     * miejsce w budzecie przepadalo. Wiekszy udzial sprawia, ze item dostaje
     * PRAWDZIWA odpowiedz albo w ogole nie jest ruszany - a wtedy kolejne
     * zadanie (klient ustawia braki pierwsze) bierze nastepne pozycje.
     */
    private static final long MIN_ITEM_BUDGET_NS = 2_000_000L;

    private static final long ESTIMATE_HEAT_OPS = 1_000_000L;

    /** Budzet ciepla dla szacowania: "jest czym palic" albo "nie ma". */
    private static long estimateHeatOps(long realHeatOps) {
        return realHeatOps > 0 ? ESTIMATE_HEAT_OPS : 0L;
    }

    /**
     * Bezpieczne ZAWYZENIE liczby sztuk, ktore mozna zrobic z danego stocku.
     *
     * <p>Dla kazdego surowca obecnego w sieci mnozymy jego ilosc przez najwiekszy
     * mozliwy uzysk w jednym ciagu receptur. Przyklad: 64 klody, a receptura
     * "1 kloda -> 4 deski" i "1 deska -> 4 patyki" daje uzysk 16, wiec gorna
     * granica to 1024 - i bisekcja ma miejsce, zeby znalezc prawdziwy wynik
     * (np. 170 plotkow), zamiast zatrzymywac sie na 64.
     *
     * <p>To jest wylacznie ograniczenie bisekcji. Wynik nie musi byc osiagalny -
     * o tym decyduje planer. Wazne, zeby nie byl ZA MALY, bo wtedy obcinalibysmy
     * poprawne odpowiedzi.
     */
    private static long estimateUpperBound(ServerLevel level, VelocePipeNetwork network,
                                           Item item, Map<Item, Long> stock,
                                           java.util.List<ProcessingEntry> recipes,
                                           boolean heatAvailable) {
        long total = 0;
        for (long v : stock.values()) {
            total += v;
        }
        if (total <= 0) {
            return 0;
        }
        if (recipes.isEmpty()) {
            return total;
        }

        // Ile sztuk naszego itemu da sie wycisnac z JEDNEJ sztuki surowca.
        //
        // BUG, ktory tu byl: zamiast prawdziwego lancucha liczylismy
        // najwiekszy uzysk JEDNEJ receptury i mnozylismy go przez wszystko
        // w sieci (z dolnym progiem 4). Dla itemu o dlugim lancuchu limit
        // wychodzil ZA MALY, wiec bisekcja nigdy nie sprawdzala wiekszych
        // wartosci - np. przy 64 klodach patyczki pokazywaly sie jako 256,
        // choc realnie wychodzi 512. Za maly limit obcina poprawna odpowiedz,
        // wiec musi to byc prawdziwe (a nie zgrubne) zawyzenie.
        double perRawUnit = maxYieldPerRawUnit(level, network, item,
                new HashMap<>(), new HashSet<>(), 0, heatAvailable);
        double bound = total * Math.max(1.0, perRawUnit);
        if (bound >= MAX_ESTIMATE_RESULT) {
            return MAX_ESTIMATE_RESULT;
        }
        return Math.max(total, (long) Math.ceil(bound));
    }

    /** Maksymalna glebokosc lancucha przy liczeniu gornej granicy. */
    private static final int UPPER_BOUND_MAX_DEPTH = 16;

    /**
     * Ile sztuk {@code item} da sie uzyskac z JEDNEJ sztuki surowca.
     *
     * <p>Schodzi rekurencyjnie po recepturach, wiec widzi cale lancuchy
     * (kloda -> deski -> patyczki), a nie tylko pierwszy krok. Wynik jest
     * celowo ZAWYZONY - to wylacznie ograniczenie bisekcji, a nie odpowiedz
     * dla gracza: gdy receptura pozwala wziac ten sam surowiec na kilka
     * sposobow, zakladamy, ze starczy go na wszystkie naraz.
     *
     * <p>Cykl receptur i zbyt gleboki lancuch traktujemy jak surowiec
     * (uzysk 1.0), zeby rekurencja byla skonczona.
     */
    private static double maxYieldPerRawUnit(ServerLevel level, VelocePipeNetwork network,
                                             Item item,
                                             Map<Item, Double> memo, Set<Item> visiting,
                                             int depth, boolean heatAvailable) {
        Double cached = memo.get(item);
        if (cached != null) {
            return cached;
        }
        if (depth >= UPPER_BOUND_MAX_DEPTH || !visiting.add(item)) {
            return 1.0;
        }
        try {
            double best = 1.0;
            for (var recipe : allRecipesFor(level, network, item, heatAvailable)) {
                double cost = rawCostOfRecipe(level, network, recipe, memo, visiting,
                        depth, heatAvailable);
                if (cost <= 0.0) {
                    continue;   // receptura bez zadnej dostepnej opcji skladnika
                }
                double yield = Math.max(1, recipe.primaryResult().getCount()) / cost;
                if (yield > best) {
                    best = yield;
                }
            }
            memo.put(item, best);
            return best;
        } finally {
            visiting.remove(item);
        }
    }

    /**
     * Koszt receptury w jednostkach "sztuk surowca" (mniejszy = taniej).
     *
     * <p>Zwraca 0, gdy receptury nie da sie uzyc (skladnik nie ma zadnej
     * opcji), bo wtedy nie wnosimy jej do maksimum.
     */
    private static double rawCostOfRecipe(ServerLevel level, VelocePipeNetwork network,
                                          ProcessingEntry recipe,
                                          Map<Item, Double> memo, Set<Item> visiting,
                                          int depth, boolean heatAvailable) {
        double cost = 0.0;
        List<Ingredient> ingredientList = recipe.ingredients();
        for (int ingIndex = 0; ingIndex < ingredientList.size(); ingIndex++) {
            Ingredient ing = ingredientList.get(ingIndex);
            double cheapest = Double.MAX_VALUE;
            for (ItemStack opt : nonEmpty(ing)) {
                double yield = maxYieldPerRawUnit(level, network, opt.getItem(), memo, visiting,
                        depth + 1, heatAvailable);
                if (yield < cheapest) {
                    cheapest = yield;
                }
            }
            if (cheapest == Double.MAX_VALUE) {
                return 0.0;
            }
            // Skladnik zuzywa `ingredientCount` sztuk na wykonanie, wiec
            // kosztuje tyle razy wiecej surowca.
            cost += recipe.ingredientCount(ingIndex) / cheapest;
        }
        return cost;
    }

    /**
     * Czy da sie wytworzyc {@code amount} sztuk itemu z danego stocku.
     *
     * <p>Uzywa prawdziwego planowania na KOPII stocku, wiec nie rusza niczego
     * na zewnatrz i nie zanieczyszcza stanu miedzy sprawdzeniami.
     */
    private static boolean canCraftAmount(ServerLevel level, VelocePipeNetwork network,
                                          Item item, long amount,
                                          Map<Item, Long> stock,
                                          Set<Item> enabled,
                                          Map<Item, ResourceLocation> preferred,
                                          long heatOps) {
        if (amount <= 0) {
            return true;
        }
        Map<Item, Long> copy = new HashMap<>(stock);
        Plan plan = new Plan(heatOps);
        return plan(level, network, enabled, preferred, item, amount, copy, plan,
                new HashSet<>(), 0);
    }

    // ------------------------------------------------------------------
    // Faza wykonania - fizyczne ruszanie itemow
    // ------------------------------------------------------------------

    private static boolean execute(ServerLevel level, Context ctx, Plan plan) {
        // Zrodla ciepla pobieramy RAZ na cale wykonanie planu.
        //
        // Kazde przepalenie wolalo wczesniej VeloceHeatSources.consume(), a to
        // robilo dwa pelne przejscia po terminalach sieci z sortowaniem. Plan
        // moze miec kilkaset przepalen (jeden naladowany piec elektryczny to
        // 125 operacji), wiec bylo to setki skanow w jednym ticku.
        //
        // Bezpieczenstwo: w obrebie jednego wykonania zbior zrodel sie nie
        // zmienia, a nawet gdyby chunk z piecem wypadl z symulacji, trzymana
        // referencja do block entity jest nadal waznym obiektem Java - czytamy
        // z niej tylko wlasne pola licznika.
        java.util.List<com.craftingveloce.block.entity.VeloceHeatSource> heat =
                VeloceHeatSources.allIn(level, ctx.network);

        // Plan jest w kolejnosci post-order: skladniki produkowane przed uzyciem.
        for (PlannedRun run : plan.runs) {
            for (long i = 0; i < run.times(); i++) {
                if (!runOnce(level, ctx, run.recipe(), heat)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Zaplata za JEDNO wykonanie receptury.
     *
     * <p><b>Trzy rodzaje receptur, trzy zrodla zaplaty:</b>
     * <ul>
     *   <li>rodzina {@code FREE} (crafting table, stonecutter, smithing) -
     *       nie placi niczym, bo nie potrzebuje zadnej maszyny,</li>
     *   <li>rodzina {@code FURNACE} - placi jednym przepaleniem z pieca,</li>
     *   <li>rodzina modulu (Create/Alchemistry/Mekanism/...) - placi jedna
     *       operacja z maszyny tego modulu (ktora sama przelicza to na FE).</li>
     * </ul>
     *
     * <p>Lista maszyn jest brana z {@link Context} (pamiec na czas jednego
     * zlecenia), zeby nie skanowac sieci przy kazdej sztuce.
     *
     * <p>Brak zaplaty NIE jest bledem konfiguracji - to zwykla sytuacja
     * (piec bez paliwa, maszyna bez pradu). Wolajacy zwraca wtedy pobrane
     * skladniki.
     */
    private static boolean payForOperation(ServerLevel level, Context ctx,
                                           ProcessingEntry recipe,
                                           java.util.List<com.craftingveloce.block.entity.VeloceHeatSource> heat) {
        if (VeloceRecipeFamilies.isFree(recipe.type())) {
            return true;
        }
        if (recipe.isFurnace()) {
            if (VeloceHeatSources.consumeFrom(heat, 1)) {
                return true;
            }
            VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                    "recipe %s needs heat, but no powered furnace could pay for it",
                    recipe.id());
            return false;
        }
        // Modul z innego moda - maszyna musi stac w sieci i miec energie.
        if (VeloceProcessingSources.consumeFrom(ctx.moduleSourcesFor(recipe.type()), 1)) {
            return true;
        }
        VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                "recipe %s needs a powered machine for %s, but none could pay for it",
                recipe.id(), recipe.type());
        return false;
    }

    /** Jedno wykonanie receptury: pobierz skladniki, wstaw wynik. */
    private static boolean runOnce(ServerLevel level, Context ctx,
                                   ProcessingEntry recipe,
                                   java.util.List<com.craftingveloce.block.entity.VeloceHeatSource> heat) {
        // 1. NAJPIERW POBRANIE SKLADNIKOW, POTEM ZAPLATA CIEPLEM.
        //
        // Odwrotna kolejnosc (cieplo najpierw) przepalala energie na darmo:
        // gdy po zapłacie okazywalo sie, ze brakuje skladnika, metoda
        // zwracala pobrane itemy, ale CIEPLA NIKT NIE ODDYWAL. Przy
        // recepturze, ktorej skladnik wlasnie sie skonczyl, kazda proba
        // zjadala jedno przepalenie z pieca i nic z tego nie wynikalo.
        //
        // Teraz: cieplo placimy dopiero, gdy mamy juz komplet skladnikow.
        // Wowczas jedyna przyczyna niepowodzenia jest brak pradu/paliwa,
        // a wtedy zwracamy rowniez pobrane itemy.
        NonNullList<ItemStack> consumed = NonNullList.create();
        List<Ingredient> ingredientList = recipe.ingredients();
        for (int ingIndex = 0; ingIndex < ingredientList.size(); ingIndex++) {
            Ingredient ing = ingredientList.get(ingIndex);
            // TA SAMA REGULA CO W PLANERZE (hasOptions).
            //
            // BUG, ktory to naprawia (zgloszenie gracza: "GUI pokazuje 2
            // crushing wheele, ale przy craftowaniu mowi, ze nie mam
            // itemkow"): planer pomijal skladniki bez opcji, a wykonanie
            // probowalo je "pobrac" i natychmiast padalo. Puste sloty siatki
            // (Ingredient.EMPTY) sa teraz usuwane juz przy budowie receptury,
            // ale skladnik z pustym tagiem nadal tu trafia - i musi byc
            // pominiety tak samo, jak w planie, bo inaczej plan i wykonanie
            // nie zgadzaja sie co do listy skladnikow.
            //
            // Gdyby plan i wykonanie liczyly skladniki ROZNYMI regulami,
            // dostajemy dokladnie ten objaw: liczba jest policzona, a craft
            // nie dziala nigdy.
            if (!hasOptions(ing)) {
                VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                        "recipe %s: pomijam skladnik bez zadnej opcji (pusty tag)",
                        recipe.id());
                continue;
            }
            // Skladnik moze wymagac kilku sztuk na jedno wykonanie
            // (Alchemistry IngredientStack, Mekanism SizedIngredient).
            // Plan liczy sie z ta sama liczba, wiec wykonanie musi ja
            // pobrac - inaczej powstalby darmowy nadmiar w sieci.
            int units = recipe.ingredientCount(ingIndex);
            for (int unit = 0; unit < units; unit++) {
                ItemStack taken = takeOne(level, ctx, ing);
                if (taken.isEmpty()) {
                    // MOWIMY WPROST, CZEGO ZABRAKLO.
                    //
                    // Bez tego w logu byl tylko komunikat "ingredients vanished
                    // mid-craft", a gracz zgłaszal "nie mam itemkow" - i nie
                    // dalo sie ustalic, ktorego skladnika dotyczy problem.
                    VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                            "recipe %s: brak skladnika %s (sztuk na wykonanie: %d, opcji: %d)",
                            recipe.id(), describeOptions(ing), units, nonEmpty(ing).size());
                    // Zwrot pobranych - nie gubimy itemow.
                    for (ItemStack s : consumed) {
                        deposit(level, ctx, s);
                    }
                    return false;
                }
                consumed.add(taken);
            }
        }

        // 2. Zaplata za operacje.
        //
        // Piec placi cieplem (VeloceHeatSources: najpierw elektryczny, potem
        // paliwowy), maszyna modulu placi energia wlasna (VeloceProcessingSources),
        // a receptury bez infrastruktury (crafting table, stonecutter, smithing)
        // nie placa niczym - dlatego rozpoznajemy je po rodzinie, a nie po tym,
        // "czy to nie piec".
        //
        // Przy niepowodzeniu nie zabieramy NICZEGO i zwracamy pobrane itemy.
        if (!payForOperation(level, ctx, recipe, heat)) {
            for (ItemStack s : consumed) {
                deposit(level, ctx, s);
            }
            return false;
        }

        // 3. Wyniki.
        //
        // Wszystkie, nie tylko glowny: Fission ma dwa, a crushing Create
        // bywa troche wiecej. Rzucamy koscia PER WYNIK (resultChances), wiec
        // gracz czasem dostanie wiecej, nigdy mniej niz zaplanowano - plan
        // bowiem widzi wylacznie wyniki gwarantowane (patrz ProcessingEntry).
        List<ItemStack> results = recipe.results();
        for (int i = 0; i < results.size(); i++) {
            ItemStack single = results.get(i);
            if (single.isEmpty()) {
                continue;
            }
            float chance = i < recipe.resultChances().size()
                    ? recipe.resultChances().get(i) : 1.0f;
            if (chance < 1.0f && level.random.nextFloat() >= chance) {
                continue;
            }
            deposit(level, ctx, single.copy());
        }
        return true;
    }

    /**
     * Pobiera jedna sztuke pasujaca do skladnika.
     *
     * <p><b>Kolejnosc jest krytyczna i musi byc symetryczna do
     * {@link #deposit}.</b> deposit wklada wyniki NAJPIERW do buforow
     * crafterow, wiec takeOne musi ich tam szukac PRZED siecia. Bez tego
     * wieloetapowe craftowanie (deski -> plotek) padalo: plan wiedzial, ze
     * deski beda, bo deposit je tam wklada, ale takeOne ich nie znajdowal
     * i zwracal EMPTY. Objaw w logu: "ingredients vanished mid-craft",
     * a w grze - materialy zostawaly niezuzyte.
     *
     * <p>Kolejnosc: ekwipunek gracza -> bufory crafterow -> siec.
     */
    private static ItemStack takeOne(ServerLevel level, Context ctx, Ingredient ing) {
        for (ItemStack opt : nonEmpty(ing)) {
            Item item = opt.getItem();

            // 1. Ekwipunek gracza ma priorytet.
            if (ctx.inventory != null) {
                ItemStack fromInv = ctx.inventory.extract(item, 1);
                if (!fromInv.isEmpty()) {
                    return fromInv;
                }
            }

            // 2. Bufory crafterow - tu deposit wklada wyniki posrednie.
            if (ctx.buffers != null) {
                for (var buf : ctx.buffers) {
                    ItemStack fromBuf = extractOneFromBuffer(buf, item);
                    if (!fromBuf.isEmpty()) {
                        return fromBuf;
                    }
                }
            }

            // 3. Zwykle endpointy sieci (skrzynie, RS).
            ItemStack fromNet = ctx.network.extractItem(level, item, 1);
            if (!fromNet.isEmpty()) {
                return fromNet;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Wyciaga jedna sztuke itemu z bufora craftera. */
    private static ItemStack extractOneFromBuffer(
            com.craftingveloce.inventory.VeloceCraftingBuffer buf, Item item) {
        for (int slot = 0; slot < buf.getContainerSize(); slot++) {
            ItemStack inSlot = buf.getItem(slot);
            if (!inSlot.isEmpty() && inSlot.getItem() == item) {
                ItemStack taken = buf.removeItem(slot, 1);
                if (!taken.isEmpty()) {
                    return taken;
                }
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
        ItemStack leftover = insertWhereverPossible(ctx, stack);
        if (!leftover.isEmpty()) {
            // BUG, ktory tu byl: reszta po przejsciu WSZYSTKICH endpointow byla
            // po cichu porzucana. Przy pelnej sieci ginely wiec wycraftowane
            // itemy - a takze SKLADNIKI zwracane przy nieudanym wykonaniu
            // (runOnce oddaje je przez deposit). Teraz reszta trafia na ziemie
            // przy bloku, ktory o craftowanie poprosil.
            dropLeftover(level, ctx, leftover);
        }
    }

    /**
     * Wklada stos tam, gdzie sie da, i zwraca to, co zostalo.
     *
     * <p>Kolejnosc: bufor craftera (pamiec podreczna na nadwyzke), potem
     * zwykle endpointy sieci (skrzynie).
     */
    private static ItemStack insertWhereverPossible(Context ctx, ItemStack stack) {
        ItemStack remaining = stack;
        if (remaining.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // 1. Bufory crafterow.
        if (ctx.buffers != null) {
            for (var buf : ctx.buffers) {
                remaining = buf.insert(remaining);
                if (remaining.isEmpty()) {
                    return ItemStack.EMPTY;
                }
            }
        }
        // 2. Zwykle endpointy sieci.
        //
        // KAZDY endpoint oddaje RESZTE i to wlasnie reszte przekazujemy dalej.
        // Poprzednia wersja przekazywala ten sam, pelny stos do kolejnych
        // endpointow: jesli pierwszy przyjal CZESC i zwrocil false, drugi
        // dostawal calosc i mogl ja przyjac - czyli przyjeta czesc byla
        // w sieci DWA razy.
        for (var endpoint : ctx.network.getEndpoints().values()) {
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }
            remaining = endpoint.insertItemLeftover(ctx.level, remaining);
        }
        return remaining;
    }

    /** Awaryjnie wypuszcza reszte przy bloku, ktory zlecil craftowanie. */
    private static void dropLeftover(ServerLevel level, Context ctx, ItemStack leftover) {
        if (ctx.dropPos == null) {
            VeloceLog.Craft.failure(VeloceLog.Side.SERVER,
                    "network full and no drop position - losing %s", leftover);
            return;
        }
        net.minecraft.world.level.block.Block.popResource(level, ctx.dropPos, leftover);
        VeloceLog.Craft.detail(VeloceLog.Side.SERVER,
                "network full - dropped %s at %s", leftover, ctx.dropPos);
    }

    // ------------------------------------------------------------------
    // Pomocnicze
    // ------------------------------------------------------------------

    /** Krotki opis skladnika do logu: lista id itemow, ktore go spelniaja. */
    private static String describeOptions(Ingredient ing) {
        StringBuilder sb = new StringBuilder("[");
        for (ItemStack option : nonEmpty(ing)) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(option.getItem()));
        }
        return sb.append(']').toString();
    }

    /**
     * Czy ten skladnik ma JAKAKOLWIEK opcje (czym go zastapic)?
     *
     * <p>JEDNO miejsce z ta regula dla planera i dla wykonania - rozjazd tych
     * dwoch miejsc daje objaw "liczba policzona, ale craft nigdy nie dziala".
     */
    private static boolean hasOptions(Ingredient ing) {
        for (ItemStack option : ing.getItems()) {
            if (!option.isEmpty()) {
                return true;
            }
        }
        return false;
    }

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
    private static List<ProcessingEntry> orderRecipes(
            ServerLevel level, VelocePipeNetwork network, Item item,
            Map<Item, ResourceLocation> preferred,
            boolean heatAvailable, boolean furnaceFirst) {
        List<ProcessingEntry> all = allRecipesFor(level, network, item, heatAvailable);
        if (all.size() <= 1) {
            return all;
        }

        // 1. KONKRETNA receptura wybrana w crafterze ma zawsze pierwszenstwo -
        //    to najdokladniejsza decyzja gracza.
        ResourceLocation pref = preferred.get(item);
        if (pref != null) {
            List<ProcessingEntry> ordered = new ArrayList<>(all.size());
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

        // 2. Preferencja "piec czy crafting" z kontrolera.
        //
        //    UWAGA: to PREFERENCJA, nie filtr. Przepalanie idzie pierwsze, ale
        //    receptury craftingowe ZOSTAJA na dalszych pozycjach - jesli piec
        //    nie ma czym zaplacic (heatAvailable == false), lista jest nietknieta
        //    i planer normalnie uzyje craftingu. Dzieki temu wybor "wole piec"
        //    nie odbiera graczowi mozliwosci zrobienia itemu inaczej.
        if (furnaceFirst && heatAvailable) {
            List<ProcessingEntry> ordered = new ArrayList<>(all.size());
            for (var e : all) {
                if (e.isFurnace()) {
                    ordered.add(e);
                }
            }
            for (var e : all) {
                if (!e.isFurnace()) {
                    ordered.add(e);
                }
            }
            return ordered;
        }

        // 3. Bez preferencji: kolejnosc domyslna (crafting przed piecem).
        return all;
    }

        /**
     * Migawka wszystkiego, co jest dostepne do craftowania.
     *
     * <p><b>Musi byc symetryczna z {@link #deposit}.</b> deposit wklada wyniki
     * najpierw do buforow crafterow, wiec jesli plan ich nie widzi, uznaje ze
     * skladnika nie ma - mimo ze deposit wlasnie go tam polozyl. To bylo
     * zrodlem bledu "ingredients vanished mid-craft" przy wieloetapowym
     * craftowaniu (deski -> plotek).
     *
     * <p>Kolejnosc zrodel: siec + ekwipunek + bufory crafterow.
     *
     * <p>Stan sieci mozna podac z zewnatrz, zeby {@link #ensureAvailable} nie
     * skanowal sieci DWA razy: raz na sprawdzenie dostepnosci, a chwile
     * pozniej drugi raz na migawke do planowania. Przy wymuszonym skanie
     * (force=true) to jest pelne przejscie po wszystkich inwentarzach sieci.
     */

    private static Map<Item, Long> snapshotStock(Context ctx, Map<Item, Long> networkCounts) {
        Map<Item, Long> stock = new HashMap<>(networkCounts);
        if (ctx.inventory != null) {
            for (Item it : ctx.inventory.allItems()) {
                stock.merge(it, (long) ctx.inventory.count(it), Long::sum);
            }
        }
        // UWAGA: buforow crafterow NIE dodajemy tutaj.
        //
        // Bufor jest juz endpointem sieci (CraftingBufferEndpoint - patrz
        // scanAndBuildNetwork), wiec networkCounts juz go zawiera. Dodawanie
        // go drugi raz liczylo zawartosc bufora PODWOJNIE: planer widzial
        // 4 deski tam, gdzie byly 2, planowal craft i wywalal sie dopiero na
        // wykonaniu z bledem "ingredients vanished mid-craft".
        //
        // Widac to bylo dokladnie tak:
        //   plan for minecraft:oak_fence: 1 recipe run(s) to execute
        //   execution failed for minecraft:oak_fence - ingredients vanished mid-craft
        return stock;
    }

    /**
     * Ekwipunek gracza jako zrodlo skladnikow.
     *
     * <p><b>UWAGA: to jest obecnie MARTWY KOD.</b> Interfejs nie ma zadnej
     * implementacji, a oba miejsca tworzace {@link Context} przekazuja
     * {@code null}:
     * <ul>
     *   <li>{@code VeloceTomTerminalBlockEntity.craftItemFromNetwork}</li>
     *   <li>{@code VeloceExtractorBlockEntity.craftFromNetwork}</li>
     * </ul>
     *
     * <p>Wszystkie trzy galezie "ekwipunek gracza ma priorytet"
     * ({@link #ensureAvailable}, {@code takeOne}, {@code snapshotStock}) sa
     * wiec nieosiagalne - auto-crafting korzysta WYLACZNIE z sieci.
     *
     * <p>Nie podlaczam tego bez decyzji wlasciciela, bo zmieniloby to
     * zachowanie: craftowanie zaczelyby zjadac itemy z ekwipunku gracza.
     * Jesli to jest pozadane, wystarczy zaimplementowac ten interfejs nad
     * {@code player.getInventory()} i przekazac go w obu miejscach.
     */
    public interface ItemInventory {
        int count(Item item);

        ItemStack extract(Item item, int max);

        /** Wszystkie itemy w ekwipunku (do symulacji dostepnosci). */
        Set<Item> allItems();
    }
}
