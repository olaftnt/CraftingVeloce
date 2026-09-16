package com.craftingveloce.network.pipe;

import com.craftingveloce.rs.RefinedStorageHelper;
import com.craftingveloce.debug.ChunkTrace;

import javax.annotation.Nullable;
import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.HashMap;
import java.util.Map;

public class ConnectedEndpointInfo {

    public enum Type {
        INVENTORY,
        REFINED_STORAGE,
    /**
     * Bufor auto-craftera - pamiec podreczna bloku, nie zasobnik w swiecie.
     * Rozpoznawany po typie, zeby po restarcie swiata odtworzyc wlasciwa
     * implementacje endpointu (patrz {@link CraftingBufferEndpoint}).
     */
        CRAFTING_BUFFER,

        /**
         * Obcy blok z Forge Energy (Energy Cube, generator, bank energii).
         *
         * <p>Nasze maszyny SAME z niego sciagaja prad (patrz VeloceEnergyPull):
         * to jedyny kierunek, w jakim energia plynie przez nasze rury. Nasze
         * rury nie sa przewodnikiem dla innych modow - nic nie moze z nich
         * pobrac, a nasze maszyny sa wylacznie odbiornikami.
         */
        ENERGY
    }

    private final BlockPos pos;
    private final Direction accessSide;
    private final ChunkPos chunkPos;
    private final Type type;
    private final Map<Item, Long> cachedCounts = new HashMap<>();

    /**
     * Ile WOLNYCH slotow ma ten magazyn (z ostatniego skanu).
     *
     * <p>{@code -1} = nie wiemy (np. Refined Storage, gdzie pojemnosc nie jest
     * liczba slotow). Wartosc "nie wiem" jest wazna: klient NIE blokuje wtedy
     * akcji, bo wolimy przepuscic operacje i pozwolic serwerowi zdecydowac,
     * niz zablokowac cos, co mogloby sie udac.
     *
     * <p>Liczymy tylko sloty CALKOWICIE puste. Slot czesciowo zapelniony moze
     * przyjac tylko ten sam item, wiec nie jest "wolnym slotem" dla dowolnego
     * przedmiotu - klient sprawdza to osobno, patrzac, czy item juz jest
     * w sieci.
     */
    private int cachedFreeSlots = -1;

    /**
     * Wolne miejsce w NIEPELNYCH stosach, per typ itemu.
     *
     * <p><b>Po co to jest.</b> Sam licznik pustych slotow KŁAMIE. Wyobraz sobie
     * skrzynie 27 slotow, w ktorej kazdy slot trzyma po 40 kamienia. Pustych
     * slotow jest ZERO, wiec {@link #cachedFreeSlots} mowi "0" i siec
     * raportowala sie jako PELNA. A przeciez do kazdego z tych stosow wejdzie
     * jeszcze po 24 kamienie - czyli 648 sztuk!
     *
     * <p>Objaw byl dokladnie taki, jak zgłosił gracz: terminal krzyczy
     * "network full", choc na koncu sieci stoi skrzynia z wolnym miejscem.
     * I to na stale, bo dopoki stosy nie zostana dopelnione, pusty slot sie
     * nie pojawi - wiec licznik nigdy nie drgnie z zera.
     *
     * <p>Dlatego pamietamy OSOBNO, ile sztuk kazdego typu jeszcze sie zmiesci,
     * zanim jego stosy sie dopelnia. Dzieki temu pojemnosc liczymy DLA KONKRETNEGO
     * ITEMU ({@link #capacityFor}), a nie "w ogole" - bo miejsce po kamieniu
     * nie pomoze, gdy chcemy wlozyc ziemie.
     */
    private final Map<Item, Integer> cachedPartialSpace = new HashMap<>();

    /**
     * Tick, w ktorym ostatnio przeskanowalismy ten inwentarz.
     *
     * <p>Skanowanie polega na przejsciu WSZYSTKICH slotow i wywolaniu
     * {@code getStackInSlot} na kazdym. Dla moddowanych magazynow to potrafi
     * kopiowac stosy razem z NBT, wiec jest to operacja droga. Wczesniej
     * odswiezalismy sie przy KAZDYM zapytaniu o stan sieci (a to leci
     * kilka razy na sekunde), co zamulalo watek serwera.
     */
    private long lastScanTick = Long.MIN_VALUE;

    /** Minimalny odstep miedzy skanami tego samego inwentarza, w tickach. */
    public static final int SCAN_INTERVAL_TICKS = 10;

    /** Czy blad skanu zostal juz zaraportowany (zeby nie spamowac logu). */
    private boolean scanFailureLogged;

    public ConnectedEndpointInfo(BlockPos pos, Direction accessSide, Type type) {
        this.pos = pos;
        this.accessSide = accessSide;
        this.chunkPos = new ChunkPos(pos);
        this.type = type;
    }

    public BlockPos getPos() {
        return pos;
    }

    public Direction getAccessSide() {
        return accessSide;
    }

    public ChunkPos getChunkPos() {
        return chunkPos;
    }

    public Type getType() {
        return type;
    }

    /**
     * Czy wolno do tego magazynu WSTAWIAĆ przedmioty.
     *
     * <p><b>Po co to jest.</b> Wrench ustawia strone rury w jeden z trzech
     * trybow: Push/Pull, Pull, Disconnected. W trybie <b>Pull</b> magazyn ma
     * byc WYLACZNIE zrodlem - siec ma z niego tylko zabierac (np. z pieca,
     * ktory sam produkuje, albo ze skrzyni, do ktorej nie chcemy, zeby cokolwiek
     * wpadalo). Wczesniej tryb ten byl ignorowany przy wstawianiu: rura
     * wiedziala o nim tylko po to, zeby narysowac dysze, a siec i tak
     * wrzucala do tego magazynu wszystko, co chciala odlozyc.
     *
     * <p>Domyslnie {@code true}, zeby magazyn bez zadnej rury w trybie Pull
     * zachowywal sie dokladnie jak dotad.
     */
    public boolean acceptsInsert() {
        return acceptsInsert;
    }

    /** Ustawia, czy ten magazyn przyjmuje wstawiane przedmioty. */
    public void setAcceptsInsert(boolean accepts) {
        this.acceptsInsert = accepts;
    }

    /**
     * Dolacza informacje z KOLEJNEJ rury dotykajacej tego samego magazynu.
     *
     * <p>Jeden magazyn moze miec kilka rur, kazda w innym trybie. Siec ma
     * do niego wstawiac wtedy, gdy <b>choc jedna</b> strona na to pozwala -
     * wiec tryb Pull wygrywa tylko wtedy, gdy obejmuje wszystkie polaczenia.
     * Bez tego ostatnia rura w kolejnosci budowy decydowalaby o calym
     * magazynie i wynik zalezalby od kolejnosci przechodzenia sieci.
     */
    public void mergeAcceptsInsert(boolean accepts) {
        this.acceptsInsert = this.acceptsInsert || accepts;
    }

    /**
     * Oznacza, ze to pierwsza rura rejestrujaca ten magazyn w tym przebiegu -
     * czyli flage trzeba ustawic, a nie dolaczyc.
     */
    public void resetAcceptsInsert(boolean accepts) {
        this.acceptsInsert = accepts;
    }

    private boolean acceptsInsert = true;

    public Map<Item, Long> getCachedCounts() {
        return cachedCounts;
    }

    /**
     * Odswieza liczniki, ale nie czesciej niz raz na
     * {@link #SCAN_INTERVAL_TICKS} tickow.
     *
     * <p>To wariant dla WSZYSTKICH odczytow tla (cache, GUI, wyswietlacze).
     * Stan starszy o pol sekundy jest dla nich w zupelnosci wystarczajacy,
     * a oszczedza skanowanie calej sieci kilka razy na sekunde.
     */
    public void refreshIfLoadedThrottled(ServerLevel level, long gameTime) {
        // `gameTime >= lastScanTick` nie jest zbedne: gdyby czas swiata cofnal sie
        // (wczytanie starszego save'a), roznica bylaby UJEMNA, a wiec mniejsza
        // od interwalu - i skan bylby pomijany bez konca.
        if (lastScanTick != Long.MIN_VALUE
                && gameTime >= lastScanTick
                && gameTime - lastScanTick < SCAN_INTERVAL_TICKS) {
            return;
        }
        lastScanTick = gameTime;
        refreshIfLoaded(level);
    }

    /** Wymusza skan teraz (do operacji, ktore musza widziec stan na zywo). */
    public void forceRefresh(ServerLevel level, long gameTime) {
        lastScanTick = gameTime;
        refreshIfLoaded(level);
    }

    /**
     * Uniewaznia zapamietane liczniki I pozwala na natychmiastowy ponowny skan.
     *
     * <p><b>UWAGA: to NIE moze czyscic liczb dla rozladowanego chunku.</b>
     *
     * <p>BUG, ktory tu byl i ktory dawal glowny zglaszany objaw ("w
     * niezaladowanym chunku nie mam itemow, ktore tam sa"): metoda czyscila
     * {@code cachedCounts}, a {@link #refreshIfLoaded} dla rozladowanego
     * chunku <b>nie robi nic</b> - nie ma z czego odtworzyc zawartosci.
     *
     * <p>Czyli: cache zostawal wyzerowany i nie mial jak sie odbudowac, bo
     * chunk jest poza symulacja. A ze uniewaznienie leci przy kazdej zmianie
     * sasiedztwa sieci i przy kazdej przebudowie, starczylo cokolwiek
     * przestawic w bazie albo oddalic sie od skrzyni - i jej zawartosc
     * znikala z GUI na stale.
     *
     * <p>Teraz rozrozniamy dwa przypadki:
     * <ul>
     *   <li><b>chunk zaladowany</b> - czyscimy, bo za chwile odczytamy
     *       prawdziwy stan ze swiata,</li>
     *   <li><b>chunk rozladowany</b> - zostawiamy ostatnia znana zawartosc.
     *       Jest nadal prawdziwa: skoro chunk nie jest symulowany, nikt tych
     *       itemow nie ruszyl. To jest wlasnie zalozenie calego mechanizmu.</li>
     * </ul>
     *
     * <p>Zerowanie {@code lastScanTick} zostaje w obu przypadkach - mowi
     * "ten wpis jest niewazny, zeskanuj go przy nastepnym pytaniu".
     */
    public void invalidateCache(ServerLevel level) {
        if (level == null || level.isLoaded(pos)) {
            cachedCounts.clear();
            // Liczniki czyscimy RAZEM - inaczej wolne miejsce liczyloby sie
            // wzgledem stosow, ktorych juz nie pamietamy.
            cachedPartialSpace.clear();
        }
        lastScanTick = Long.MIN_VALUE;
    }

    /**
     * Ile sztuk KONKRETNIE TEGO itemu ten magazyn jeszcze przyjmie.
     *
     * <p>Liczymy dwie rzeczy, bo kazda osobno jest bledna:
     * <ul>
     *   <li>puste sloty - kazdy z nich przyjmie pelny stos (szacujemy
     *       {@link Item#getDefaultMaxStackSize()}; nadmiarowy szacunek jest
     *       bezpieczny, bo wtedy tylko nie zablokujemy za wczesnie),</li>
     *   <li>wolne miejsce w NIEPELNYCH stosach TEGO SAMEGO itemu - to jest
     *       wlasnie przypadek "skrzynia niby pelna, a jednak wejdzie".</li>
     * </ul>
     *
     * <p>Miejsce po innym itemu swiadomie POMIJAMY: 10 wolnych sztuk w stosie
     * kamienia nie pomoze, gdy wkladamy ziemie.
     *
     * @return liczba sztuk (moze byc 0 = naprawde pelny) albo {@code -1} gdy
     *         nie wiemy (chunk niezaladowany, Refined Storage, brak odczytu).
     *         Wolajacy MUSI odroznic 0 od -1: 0 to dowod, -1 to brak wiedzy.
     */
    public long capacityFor(Item item) {
        // Magazyn w trybie Pull nie przyjmie NICZEGO, wiec nie wnosci zadnej
        // pojemnosci. Bez tego licznik miejsca w sieci pokazywalby wolne sloty
        // magazynu, do ktorego wstawianie jest zabronione - i gracz dostawalby
        // "jest miejsce", a potem cicho nic by sie nie odlozylo.
        if (!acceptsInsert) {
            return 0;
        }
        if (cachedFreeSlots < 0) {
            return -1;
        }
        long total = (long) cachedFreeSlots * item.getDefaultMaxStackSize();
        total += cachedPartialSpace.getOrDefault(item, 0);
        return total;
    }

    /**
     * Odnotowuje, ze endpointu nie da sie teraz odczytac.
     *
     * <p>Logujemy RAZ na endpoint, zeby nie zasmiecac loga przy kazdym
     * ladowaniu chunku, ale zeby dalo sie to w ogole zobaczyc.
     */
    private void noteNotReadable() {
        if (!notReadableLogged) {
            notReadableLogged = true;
            VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                    "endpoint at %s not readable yet (chunk loading?) - keeping known counts",
                    pos);
        }
    }

    /** Czy juz logowalismy, ze endpointu nie da sie odczytac. */
    private boolean notReadableLogged = false;

    /**
     * Do kiedy NIE wczytujemy chunku dla tego magazynu po nieudanej operacji.
     *
     * <p><b>BUG, ktory to naprawia (petla load/unload).</b> Gdy magazyn lezy
     * w niezaladowanym chunku, operacja wczytuje go na chwile
     * ({@code getChunk(..., true)} - bez force). Jesli cache tego magazynu byl
     * NIEAKTUALNY (mowil "mam ten item", a w srodku go nie bylo), to po
     * wczytaniu nic nie udawalo sie zabrac - a cache NIE byl poprawiany, bo
     * odswiezanie robilo sie tylko przy sukcesie. Wolajacy (ekstraktory,
     * piec, crafter) pytal wiec dalej, a KAZDE pytanie wczytywalo chunk
     * jeszcze raz: prawdziwa petla load/unload, w praktyce 10 razy na sekunde
     * przez cale minuty.
     *
     * <p>Nasz licznik petli tego nie widzial, bo pilnowal wylacznie
     * {@code setChunkForced} - a to sa zwykle wczytania bez wymuszenia.
     *
     * <p>Po nieudanej probie odczekujemy wiec pelny czas i dopiero wtedy
     * probujemy znowu. Nieudana proba znaczy "cache klamal", a nie "sprobuj
     * jeszcze raz za dwie sekundy".
     */
    private long opLoadBlockedUntilTick = Long.MIN_VALUE;

    /** Jak dlugo nie wczytujemy chunku po nieudanej operacji (tickow). */
    private static final long FAILED_OP_LOAD_COOLDOWN_TICKS = 200L;

    /** Wynik odczytu jednego pojemnika: liczby, wolne sloty, miejsce w czesciowych stosach. */
    private record SlotScan(Map<Item, Long> counts, int freeSlots, Map<Item, Integer> partialSpace) {
    }

    /**
     * JEDNA regula czytania pojemnika - dla kazdego rodzaju magazynu.
     *
     * <p><b>Po co wydzielone.</b> Ta sama petla byla skopiowana w dwoch galeziach
     * (NeoForge ItemHandler i waniliowy Container) linia w linie. To dokladnie
     * ten wzorzec, ktory w tym projekcie juz kilka razy sie rozjechal: poprawka
     * "pojemnosc liczymy per item, a nie po pustych slotach" musiala trafic do
     * OBU kopii, a przy trzecim rodzaju magazynu trzeba by ja bylo powtorzyc
     * jeszcze raz. Teraz regula jest w jednym miejscu.
     *
     * @param slots   ile slotow ma pojemnik
     * @param getSlot skad wziac stos z danego slotu
     */
    private static SlotScan scanSlots(int slots, java.util.function.IntFunction<ItemStack> getSlot) {
        Map<Item, Long> counts = new HashMap<>();
        Map<Item, Integer> partialSpace = new HashMap<>();
        int freeSlots = 0;
        for (int i = 0; i < slots; i++) {
            ItemStack stack = getSlot.apply(i);
            if (stack.isEmpty()) {
                freeSlots++;
                continue;
            }
            counts.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
            int room = stack.getMaxStackSize() - stack.getCount();
            if (room > 0) {
                partialSpace.merge(stack.getItem(), room, Integer::sum);
            }
        }
        return new SlotScan(counts, freeSlots, partialSpace);
    }

    public void refreshIfLoaded(ServerLevel level) {
        if (!level.isLoaded(pos)) {
            return;
        }

        if (type == Type.REFINED_STORAGE) {
            // To samo zabezpieczenie co dla INVENTORY: gdy block entity jeszcze
            // nie istnieje (chunk w trakcie ladowania), licznik RS jest chwilowo
            // niedostepny - nie nadpisujemy znanych liczb zerem.
            if (level.getBlockEntity(pos) == null) {
                noteNotReadable();
                return;
            }
            Map<Item, Long> rsCounts = RefinedStorageHelper.getRSItemCounts(level, pos, accessSide);
            cachedCounts.clear();
            cachedCounts.putAll(rsCounts);
            // RS nie ma pojemnosci wyrazonej w slotach - nie udajemy, ze wiemy.
            cachedFreeSlots = -1;
            return;
        }

        // Standard INVENTORY
        Map<Item, Long> newCounts = new HashMap<>();
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(level, pos, state, be, accessSide);

            // CZY WIDZIELISMY POJEMNIK?
            //
            // BUG, ktory tu byl: ponizsze `cachedCounts.clear()` wykonywalo sie
            // ZAWSZE, takze gdy NIE udalo sie odczytac zadnego pojemnika.
            // Wtedy `newCounts` bylo puste i znane liczby zostawaly WYMAZANE
            // ZEREM.
            //
            // Kiedy to zachodzi w praktyce: gracz teleportuje sie obok skrzyni,
            // jej chunk zaczyna sie ladowac, ale block entity jeszcze nie
            // powstalo (`getBlockEntity` zwraca null). Nasz skan czyta wtedy
            // pustke i zapisuje zero - a ze chunk zaraz znowu sie rozladowuje,
            // nie ma jak tego poprawic. Objaw: "terminal zgubil siec", bo
            // magazyn raportuje 0 typow.
            boolean sawContainer = false;
            int freeSlots = 0;
            Map<Item, Integer> partialSpace = new HashMap<>();
            if (handler != null) {
                sawContainer = true;
                SlotScan scan = scanSlots(handler.getSlots(), handler::getStackInSlot);
                newCounts.putAll(scan.counts());
                freeSlots = scan.freeSlots();
                partialSpace = scan.partialSpace();
            } else if (be instanceof Container container) {
                sawContainer = true;
                SlotScan scan = scanSlots(container.getContainerSize(), container::getItem);
                newCounts.putAll(scan.counts());
                freeSlots = scan.freeSlots();
                partialSpace = scan.partialSpace();
            }

            if (!sawContainer) {
                // Nie ma czego czytac - chunk sie jeszcze laduje albo blok
                // zniknal. ZOSTAWIAMY ostatnie znane liczby zamiast ich
                // kasowac: lepsza nieaktualna liczba niz falszywe zero.
                noteNotReadable();
                return;
            }

            cachedCounts.clear();
            cachedCounts.putAll(newCounts);
            cachedFreeSlots = freeSlots;
            cachedPartialSpace.clear();
            cachedPartialSpace.putAll(partialSpace);
            scanFailureLogged = false;
            notReadableLogged = false;
        } catch (Throwable t) {
            // NIE polykamy tego po cichu.
            //
            // Wczesniej byl tu `catch (Throwable ignored) {}`. Gdy skanowanie
            // inwentarza rzucalo (np. zepsuty magazyn z innego moda),
            // cachedCounts zostawalo ze STARYMI wartosciami - gracz widzial
            // nieaktualne liczby i nie mial jak zgadnac dlaczego. Teraz
            // przynajmniej raz na endpoint mowimy o tym w logu.
            if (!scanFailureLogged) {
                scanFailureLogged = true;
                VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                        "endpoint scan failed at %s (type=%s) - counts stay stale: %s",
                        pos, type, t);
            }
        }
    }

    /**
     * Fizyczne zabranie itemu - BEZ sprawdzania i ladowania chunku.
     *
     * <p>Wydzielone z {@link #extractItem} jako sciezka "chunk jest juz
     * zaladowany" - nie sprawdza i nie wczytuje chunku samodzielnie.
     */
    public ItemStack extractNow(ServerLevel level, Item item, int maxCount) {
        if (type == Type.REFINED_STORAGE) {
            return RefinedStorageHelper.extractItem(level, pos, accessSide,
                    new ItemStack(item), maxCount);
        }
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(
                    level, pos, state, be, accessSide);
            if (handler != null) {
                ItemStack result = ItemStack.EMPTY;
                int needed = maxCount;
                for (int i = 0; i < handler.getSlots() && needed > 0; i++) {
                    ItemStack inSlot = handler.getStackInSlot(i);
                    if (!inSlot.isEmpty() && inSlot.getItem() == item) {
                        ItemStack extracted = handler.extractItem(i, needed, false);
                        if (!extracted.isEmpty()) {
                            result = result.isEmpty() ? extracted.copy() : grow(result, extracted);
                            needed -= extracted.getCount();
                        }
                    }
                }
                return result;
            }
            if (be instanceof Container container) {
                ItemStack result = ItemStack.EMPTY;
                int needed = maxCount;
                for (int i = 0; i < container.getContainerSize() && needed > 0; i++) {
                    ItemStack inSlot = container.getItem(i);
                    if (!inSlot.isEmpty() && inSlot.getItem() == item) {
                        int toTake = Math.min(needed, inSlot.getCount());
                        ItemStack taken = container.removeItem(i, toTake);
                        if (!taken.isEmpty()) {
                            result = result.isEmpty() ? taken.copy() : grow(result, taken);
                            needed -= taken.getCount();
                        }
                    }
                }
                return result;
            }
        } catch (Throwable t) {
            VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                    "deferred extract at %s failed: %s", pos, t);
        }
        return ItemStack.EMPTY;
    }

    /** Dokłada zawartosc do stosu wynikowego. */
    private static ItemStack grow(ItemStack into, ItemStack from) {
        into.grow(from.getCount());
        return into;
    }

    public ItemStack extractItem(ServerLevel level, Item item, int maxCount) {
        if (cachedCounts.getOrDefault(item, 0L) <= 0) {
            return ItemStack.EMPTY;
        }

        // JEDNO miejsce, ktore fizycznie zabiera itemy - {@link #extractNow}.
        // Wczesniej ta metoda miala wlasna, przeklejona kopie tej samej petli,
        // wiec poprawka w jednej z nich nie docierala do drugiej.
        if (type == Type.REFINED_STORAGE) {
            ItemStack extracted = extractNow(level, item, maxCount);
            if (!extracted.isEmpty()) {
                refreshIfLoaded(level);
            }
            return extracted;
        }

        // INVENTORY
        boolean wasLoaded = level.isLoaded(pos);
        long chunkKey = ChunkPos.asLong(chunkPos.x, chunkPos.z);
        if (!wasLoaded) {
            // WYCIAGANIE MUSI BYC SYNCHRONICZNE - i to jest krytyczne.
            //
            // ============================================================
            // BUG, ktory tu byl (DUPLIKACJA): wersja z kolejka zwracala
            // wolajacemu STOS OD RAZU, "na kredyt":
            //
            //     scheduleExtract(...);                  // zrob to pozniej
            //     return new ItemStack(item, cached);    // a tu wez teraz
            //
            // a kolejka, gdy juz fizycznie zabrala itemy, WYRZUCALA wynik
            // (byl tylko logowany). Dopoki wszystko szlo zgodnie z zalozeniem,
            // suma sie zgadzala. Wystarczylo jednak, ze obietnica nie zostala
            // dotrzymana, i itemy mnozyly sie z niczego:
            //   - kolejka byla pelna (MAX_QUEUE) i zadanie zostalo ODRZUCONE,
            //   - chunku nie dalo sie wczytac,
            //   - w skrzyni bylo mniej, niz mowil cache (hopper, inny gracz,
            //     maszyna z innego moda) - obietnica wieksza od stanu,
            //   - kontener zniknal.
            // W kazdym z tych przypadkow gracz, crafter albo piec dostawal
            // itemy, ktore NIE zostaly nikomu zabrane.
            //
            // Wolajacy MUSI znac prawdziwy wynik - inaczej nie da sie zachowac
            // zasady "zabierz dokladnie tyle, ile wydales". Dlatego chunk
            // wczytujemy tu i teraz, dokladnie tak samo jak przy wkladaniu.
            // ============================================================
            //
            // Bez force-loadu: getChunk(..., true) wczytuje chunk na czas tego
            // wywolania, a operacja konczy sie w tym samym ticku, wiec chunk
            // wypada pozniej NORMALNYM mechanizmem gry. Budzet na tick chroni
            // przed zamuleniem, gdy wolajacy idzie w petli (crafter, extractor).
            // Blokada po nieudanej probie - patrz opLoadBlockedUntilTick.
            // Bez tego ten sam magazyn z nieaktualnym cache wczytywalby chunk
            // w kolko (load/unload co kilkadziesiat tickow).
            if (level.getGameTime() < opLoadBlockedUntilTick) {
                return ItemStack.EMPTY;
            }
            if (VeloceChunkLoader.isFrozen()) {
                return ItemStack.EMPTY;
            }
            if (!VeloceChunkLoader.tryReserveOpLoad(level)) {
                com.craftingveloce.debug.ChunkTrace.at("EXTRACT", level, pos,
                        "budzet loadow wyczerpany (%d/tick) -> nic nie zabrano",
                        VeloceChunkLoader.MAX_OP_LOADS_PER_TICK);
                return ItemStack.EMPTY;
            }
            com.craftingveloce.debug.ChunkOpNotifier.reportLoad(level, chunkKey, pos,
                    com.craftingveloce.debug.ChunkOpNotifier.Op.EXTRACT);
            VeloceChunkLoader.noteOpLoad(level, chunkKey, pos, "wyciagniecie");
            com.craftingveloce.debug.ChunkTrace.at("EXTRACT", level, pos,
                    "chunk UNLOADED -> wczytuje na czas operacji (bez force); item=%s zadane=%d",
                    item, maxCount);
            level.getChunkSource().getChunk(
                    chunkPos.x, chunkPos.z,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
        } else {
            // Chunk byl juz zaladowany - liczymy to jako uzycie, zeby czesto
            // odwiedzane chunki zostawaly w pamieci dluzej.
            VeloceChunkLoader.noteUse(level, chunkKey);
            com.craftingveloce.debug.ChunkTrace.at("EXTRACT", level, pos,
                    "chunk LOADED -> zabranie natychmiast; item=%s zadane=%d w cache=%d",
                    item, maxCount, cachedCounts.getOrDefault(item, 0L));
        }

        ItemStack result = extractNow(level, item, maxCount);
        if (!result.isEmpty()) {
            refreshIfLoaded(level);
        } else if (!wasLoaded) {
            // Cache klamal: wczytalismy chunk, a itemu nie bylo. Poprawiamy
            // prawde (chunk jest juz zaladowany, wiec to tani odczyt) ORAZ
            // odstawiamy ten magazyn na chwile. Bez tego wolajacy pytalby
            // dalej, a kazde pytanie wczytywalo chunk od nowa - petla
            // load/unload dokladnie taka, jaka widac bylo w logu.
            refreshIfLoaded(level);
            opLoadBlockedUntilTick = level.getGameTime() + FAILED_OP_LOAD_COOLDOWN_TICKS;
        }
        return result;
    }


    /**
     * Fizyczne wlozenie stosu - BEZ sprawdzania i ladowania chunku.
     *
     * @return to, czego NIE udalo sie wlozyc
     */
    public ItemStack insertNow(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (type == Type.REFINED_STORAGE) {
            return RefinedStorageHelper.insertItemLeftover(level, pos, accessSide, stack);
        }
        ItemStack remaining = stack.copy();
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(
                    level, pos, state, be, accessSide);
            if (handler != null) {
                for (int i = 0; i < handler.getSlots() && !remaining.isEmpty(); i++) {
                    remaining = handler.insertItem(i, remaining, false);
                }
            } else if (be instanceof Container container) {
                for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
                    ItemStack inSlot = container.getItem(i);
                    int max = Math.min(container.getMaxStackSize(), remaining.getMaxStackSize());
                    if (inSlot.isEmpty()) {
                        int move = Math.min(max, remaining.getCount());
                        container.setItem(i, remaining.split(move));
                        container.setChanged();
                    } else if (ItemStack.isSameItemSameComponents(inSlot, remaining)) {
                        int space = max - inSlot.getCount();
                        if (space > 0) {
                            int move = Math.min(space, remaining.getCount());
                            ItemStack merged = inSlot.copy();
                            merged.grow(move);
                            container.setItem(i, merged);
                            remaining.shrink(move);
                            container.setChanged();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                    "deferred insert at %s failed: %s", pos, t);
        }
        return remaining;
    }

    /**
     * Wklada ile sie da i zwraca RESZTE.
     *
     * <p><b>Po co zostaw pozostaly stos, a nie sam boolean.</b> Poprzednia
     * wersja zwracala {@code remaining.isEmpty()}, wiec przy CZESCIOWYM
     * przyjeciu (np. beczka prawie pelna) mowila "nie udalo sie" - mimo ze
     * czesc itemow juz fizycznie weszla. Wolajacy nie zabieral wtedy niczego
     * graczowi, a itemy byly juz w magazynie: DUPLIKACJA.
     *
     * @return to, czego NIE udalo sie wlozyc (EMPTY gdy wszystko przyjete)
     */
    public ItemStack insertItemLeftover(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        // TRYB PULL: strona rury ustawiona na "Pull" oznacza, ze z tego
        // magazynu wolno TYLKO zabierac. Sprawdzamy to jako PIERWSZA rzecz,
        // zanim cokolwiek wczytamy czy dotkniemy - i zwracamy CALY stos, wiec
        // wolajacy zatrzymuje przedmioty u siebie (nic nie ginie i nic sie
        // nie duplikuje).
        //
        // Jedno miejsce na cala regule: te sciezke przechodzi terminal,
        // crafter (odkladanie wyniku) i piec (oddawanie nadwyzki paliwa).
        // Sprawdzanie tego w kazdym z nich osobno gwarantowaloby, ze kiedys
        // jedno z nich zostanie pominięte.
        if (!acceptsInsert) {
            ChunkTrace.at("INSERT", level, pos,
                    "tryb PULL - wstawianie zabronione; stos=%dx %s",
                    stack.getCount(), stack.getItem());
            return stack;
        }

        if (type == Type.REFINED_STORAGE) {
            ItemStack left = RefinedStorageHelper.insertItemLeftover(level, pos, accessSide, stack);
            if (left.getCount() != stack.getCount()) {
                refreshIfLoaded(level);
            }
            return left;
        }

        boolean wasLoaded = level.isLoaded(pos);
        long chunkKey = ChunkPos.asLong(chunkPos.x, chunkPos.z);
        if (!wasLoaded) {
            // Bez wczytywania przy zapisie swiata - patrz extractItem.
            if (VeloceChunkLoader.isFrozen()) {
                return stack;
            }
            com.craftingveloce.debug.ChunkOpNotifier.reportLoad(level, chunkKey, pos,
                    com.craftingveloce.debug.ChunkOpNotifier.Op.INSERT);
            // WKLADANIE MUSI BYC SYNCHRONICZNE - i to jest krytyczne.
            //
            // BUG, ktory tu byl (DUPLIKACJA + falszywe "network full"):
            // wersja z kolejka robila tak:
            //     scheduleInsert(stack.copy());   // kolejka WSTAWIA caly stos
            //     return stack;                   // a wolajacemu mowimy "nie przyjete"
            // Wolajacy (storeFromPlayer) widzial wiec "nic nie weszlo", NIE
            // zabieral itemow graczowi - a kolejka w swoim ticku wstawiala je
            // do sieci. Efekt: itemy byly JEDNOCZESNIE w sieci i u gracza.
            //
            // Dodatkowo, gdy magazyn stal w niezaladowanym chunku (a tylko
            // WEZLY sa force-loadowane, nie magazyny), wkladanie ZAWSZE szlo
            // ta sciezka - wiec terminal na stale krzyczal "network full",
            // mimo ze miejsca bylo duzo.
            //
            // Wniosek: wolajacy MUSI znac prawdziwy wynik, zeby zabrac graczowi
            // DOKLADNIE tyle, ile weszlo. Dlatego chunk wczytujemy tu i teraz.
            //
            // ============================================================
            // NIE WOLNO TU UZYWAC retain()/release() - i to jest osobny blad,
            // ktory tu byl. retain() robi setChunkForced(true), a release()
            // setChunkForced(false), czyli w JEDNYM ticku przelaczamy znacznik
            // wymuszenia w OBIE strony.
            //
            // Skutek widac bylo w grze jako petle: loaded -> unloaded ->
            // loaded -> ... przy KAZDYM wkladaniu. A ze wkladanie bywa
            // powtarzalne (crafter odklada wynik, piec oddaje nadwyzke
            // paliwa, gracz klika), petla nie konczyla sie nigdy. Do tego
            // setChunkForced jest zapisywany TRWALE w danych swiata, wiec
            // kazde przelaczenie to takze zapis.
            //
            // To wymuszanie bylo zupelnie zbedne. getChunk(..., true) sam
            // wczytuje chunk synchronicznie na czas tego wywolania, a cala
            // operacja konczy sie w TYM SAMYM ticku - nie ma okna, w ktorym
            // chunk moglby zostac rozladowany pod naszymi rekoma. Po powrocie
            // chunk nie ma zadnego biletu wymuszenia, wiec wypada NORMALNIE,
            // zwyklym mechanizmem gry - dokladnie tak, jak powinno byc.
            // ============================================================
            // Blokada po nieudanej probie - patrz opLoadBlockedUntilTick.
            // Ta sama petla load/unload co przy wyciaganiu: magazyn z
            // nieaktualnym cache przyjmowalby (albo odrzucal) stos w kolko.
            if (level.getGameTime() < opLoadBlockedUntilTick) {
                return stack;
            }
            ChunkTrace.at("INSERT", level, pos,
                    "chunk UNLOADED -> wczytuje na czas operacji (bez force); stos=%dx %s",
                    stack.getCount(), stack.getItem());
            if (!VeloceChunkLoader.tryReserveOpLoad(level)) {
                // Budzet blokujacych wczytan na ten tick wyczerpany. Odmawiamy
                // CALEGO stosu - wolajacy zatrzyma itemy u gracza. To jest
                // bezpieczne (zero duplikacji), a kolejny tick znowu ma budzet.
                ChunkTrace.at("INSERT", level, pos,
                        "budzet loadow wyczerpany (%d/tick) -> odmowa, stos zostaje u gracza",
                        VeloceChunkLoader.MAX_OP_LOADS_PER_TICK);
                // Zwykly log (bez /cv trace), bo to JEDYNA sytuacja, w ktorej
                // wkladanie odmawia mimo wolnego miejsca. Bez tego sladu
                // wygladaloby to dokladnie jak stary bug "network full".
                VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                        "insert at %s deferred: blocking-load budget used (%d/tick), stack kept by caller",
                        pos, VeloceChunkLoader.MAX_OP_LOADS_PER_TICK);
                return stack;
            }
            VeloceChunkLoader.noteOpLoad(level, chunkKey, pos, "wlozenie");
            level.getChunkSource().getChunk(
                    chunkPos.x, chunkPos.z,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
        } else {
            VeloceChunkLoader.noteUse(level, chunkKey);
        }

        ItemStack remaining = stack.copy();
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(level, pos, state, be, accessSide);
            if (handler != null) {
                for (int i = 0; i < handler.getSlots() && !remaining.isEmpty(); i++) {
                    remaining = handler.insertItem(i, remaining, false);
                }
            } else if (be instanceof Container container) {
                for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
                    ItemStack inSlot = container.getItem(i);
                    int max = Math.min(container.getMaxStackSize(), remaining.getMaxStackSize());
                    if (inSlot.isEmpty()) {
                        int move = Math.min(max, remaining.getCount());
                        container.setItem(i, remaining.split(move));
                        container.setChanged();
                    } else if (ItemStack.isSameItemSameComponents(inSlot, remaining)) {
                        int space = max - inSlot.getCount();
                        if (space > 0) {
                            int move = Math.min(space, remaining.getCount());
                            // BUG, ktory tu byl: modyfikowalismy stos ZWRÓCONY
                            // przez getItem(i) i tylko zmniejszalismy `remaining`.
                            // Kontener, ktory zwraca KOPIE (a to jest wlasnie ten
                            // przypadek awaryjny - nie ma tu capability, wiec
                            // trafilismy na zwykly Container), nigdy nie
                            // zapisywal tej zmiany. Efekt: `remaining` sie
                            // zmniejszalo, a przedmiot NIE byl wkladany - czyli
                            // ciche gubienie itemow.
                            //
                            // Dlatego budujemy NOWY stos i zapisujemy go przez
                            // setItem, zamiast modyfikowac to, co przyszlo.
                            ItemStack merged = inSlot.copy();
                            merged.grow(move);
                            container.setItem(i, merged);
                            remaining.shrink(move);
                            container.setChanged();
                        }
                    }
                }
            }

            refreshIfLoaded(level);
        } catch (Throwable t) {
            // Wyjatek trafia do loga moda RAZEM ze stosem - wczesniej lecial
            // na stderr, obok systemu logow (bez kategorii i bez mozliwosci
            // wylaczenia go configiem).
            VeloceLog.Network.error(VeloceLog.Side.SERVER, t,
                    "insert at %s failed", pos);
        }
        // ZADNEGO release() - patrz komentarz wyzej. Chunk nie ma biletu
        // wymuszenia, wiec wypada normalnie, kiedy gra uzna to za stosowne.
        return remaining;
    }

    /** Zgodnosc: true gdy wszystko przyjete. */
    public boolean insertItem(ServerLevel level, ItemStack stack) {
        return insertItemLeftover(level, stack).isEmpty();
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Pos", pos.asLong());
        tag.putInt("Side", accessSide.ordinal());
        tag.putString("Type", type.name());

        ListTag list = new ListTag();
        for (Map.Entry<Item, Long> entry : cachedCounts.entrySet()) {
            if (entry.getValue() <= 0) continue;
            CompoundTag itemTag = new CompoundTag();
            itemTag.putString("id", BuiltInRegistries.ITEM.getKey(entry.getKey()).toString());
            itemTag.putLong("cnt", entry.getValue());
            list.add(itemTag);
        }
        tag.put("Cache", list);
        return tag;
    }

    /**
     * Odtwarza endpoint z NBT.
     *
     * <p><b>Nigdy nie rzuca.</b> Ten kod chodzi w trakcie wczytywania zapisanych
     * danych, a {@code VelocePipeNetworkManager.load} deserializuje WSZYSTKIE
     * sieci jednym przejsciem. Wyjatek tutaj przerywal wiec caly load - czyli
     * swiat nie wczytywal sie w ogole z powodu jednego popsutego wpisu.
     *
     * <p>Bylo to realne: {@code Direction.values()[side]} rzucalo
     * ArrayIndexOutOfBoundsException dla strony spoza zakresu (starszy zapis,
     * inny uklad enumow), a {@code Type.valueOf} rzucalo
     * IllegalArgumentException dla nieznanej nazwy typu.
     *
     * @return odtworzony endpoint albo {@code null}, gdy wpisu nie da sie
     *         zrozumiec (wolajacy ma go po prostu pominac)
     */
    @Nullable
    public static ConnectedEndpointInfo fromNbt(CompoundTag tag) {
        BlockPos pos = BlockPos.of(tag.getLong("Pos"));

        Direction[] sides = Direction.values();
        int sideIndex = tag.getInt("Side");
        if (sideIndex < 0 || sideIndex >= sides.length) {
            VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                    "endpoint at %s has invalid side %d - entry skipped", pos, sideIndex);
            return null;
        }
        Direction side = sides[sideIndex];

        Type type;
        String typeName = tag.getString("Type");
        try {
            type = Type.valueOf(typeName);
        } catch (IllegalArgumentException ex) {
            VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                    "endpoint at %s has unknown type '%s' - entry skipped", pos, typeName);
            return null;
        }

        // Bufor craftera ma wlasna implementacje (czyta z bufora bloku,
        // a nie z zasobnika w swiecie) - trzeba ją odtworzyc po restarcie.
        ConnectedEndpointInfo info = type == Type.CRAFTING_BUFFER
                ? new CraftingBufferEndpoint(pos, side)
                : new ConnectedEndpointInfo(pos, side, type);
        ListTag list = tag.getList("Cache", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag itemTag = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(itemTag.getString("id"));
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                Item item = BuiltInRegistries.ITEM.get(id);
                long count = itemTag.getLong("cnt");
                info.cachedCounts.put(item, count);
            }
        }
        return info;
    }
}
