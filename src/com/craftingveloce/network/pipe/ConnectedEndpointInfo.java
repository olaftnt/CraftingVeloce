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
        CRAFTING_BUFFER
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

    /** Wolne sloty z ostatniego skanu; -1 gdy nieznane. */
    public int getCachedFreeSlots() {
        return cachedFreeSlots;
    }

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
        }
        lastScanTick = Long.MIN_VALUE;
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
            if (handler != null) {
                sawContainer = true;
                for (int i = 0; i < handler.getSlots(); i++) {
                    ItemStack stack = handler.getStackInSlot(i);
                    if (stack.isEmpty()) {
                        freeSlots++;
                    } else {
                        newCounts.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
                    }
                }
            } else if (be instanceof Container container) {
                sawContainer = true;
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack.isEmpty()) {
                        freeSlots++;
                    } else {
                        newCounts.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
                    }
                }
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
     * Kolejkuje fizyczne zabranie itemu z tego magazynu.
     *
     * <p>Wolane, gdy magazyn jest w chunku poza symulacja. Zadanie trafi do
     * {@link VeloceChunkTaskQueue}, ktora w swoim ticku zaladuje chunk,
     * zabierze itemy i OD RAZU zwolni chunk - zeby gra mogla go rozladowac.
     */
    private void scheduleExtract(ServerLevel level, Item item, int maxCount) {
        ConnectedEndpointInfo self = this;
        VeloceChunkTaskQueue.submit(new VeloceChunkTaskQueue.Task() {
            @Override
            public long chunkKey() {
                return ChunkPos.asLong(chunkPos.x, chunkPos.z);
            }

            @Override
            public net.minecraft.core.BlockPos pos() {
                return pos;
            }

            @Override
            public VeloceChunkTaskQueue.Kind kind() {
                return VeloceChunkTaskQueue.Kind.EXTRACT;
            }

            @Override
            public String describe() {
                return maxCount + "x " + item;
            }

            @Override
            public ItemStack run(ServerLevel lvl) {
                // Chunk jest juz zaladowany przez kolejke - robimy to samo,
                // co sciezka synchroniczna, i odswiezamy cache.
                ItemStack taken = self.extractNow(lvl, item, maxCount);
                refreshIfLoaded(lvl);
                return taken;
            }
        });
    }

    /**
     * Fizyczne zabranie itemu - BEZ sprawdzania i ladowania chunku.
     *
     * <p>Wydzielone z {@link #extractItem}, zeby kolejka mogla wykonac sama
     * czynnosc, gdy chunk jest juz zaladowany.
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

        if (type == Type.REFINED_STORAGE) {
            ItemStack extracted = RefinedStorageHelper.extractItem(level, pos, accessSide, new ItemStack(item), maxCount);
            if (!extracted.isEmpty()) {
                refreshIfLoaded(level);
            }
            return extracted;
        }

        // INVENTORY
        boolean wasLoaded = level.isLoaded(pos);
        long chunkKey = ChunkPos.asLong(chunkPos.x, chunkPos.z);
        if (!wasLoaded) {
            // CHUNK POZA SYMULACJA -> KOLEJKUJEMY, NIE LADUJEMY.
            //
            // BUG, ktory tu byl: wolalismy getChunk(..., true), czyli
            // BLOKUJACE, synchroniczne wczytanie chunku z dysku w srodku
            // ticku serwera. Jedno wyciagniecie itemu z odleglej skrzyni to
            // kilka do kilkudziesieciu milisekund zamulenia; przy serii
            // operacji (auto-crafting, extractor w petli) tick sie rozjezdzal.
            //
            // Teraz: decyzje podejmujemy na cache (skoro chunk jest
            // rozladowany, jego zawartosc sie nie zmienila - wiec zapamietane
            // liczby sa nadal prawdziwe), a fizyczne zabranie itemu robi
            // kolejka zadan w swoim ticku.
            if (VeloceChunkLoader.isFrozen()) {
                return ItemStack.EMPTY;
            }
            com.craftingveloce.debug.ChunkOpNotifier.reportLoad(level, chunkKey, pos,
                    com.craftingveloce.debug.ChunkOpNotifier.Op.EXTRACT);
            long cached = cachedCounts.getOrDefault(item, 0L);
            com.craftingveloce.debug.ChunkTrace.at("EXTRACT", level, pos,
                    "chunk UNLOADED -> kolejka; item=%s zadane=%d w cache=%d",
                    item, maxCount, cached);
            scheduleExtract(level, item, maxCount);
            // Gracz widzi skutek od razu (item zniknie z listy), a serwer
            // doważa go w tle.
            return new ItemStack(item, Math.min(maxCount, (int) Math.min(
                    Integer.MAX_VALUE, cached)));
        }
        // Chunk byl juz zaladowany - liczymy to jako uzycie, zeby czesto
        // odwiedzane chunki zostawaly w pamieci dluzej.
        VeloceChunkLoader.noteUse(level, chunkKey);
        com.craftingveloce.debug.ChunkTrace.at("EXTRACT", level, pos,
                "chunk LOADED -> zabranie natychmiast; item=%s zadane=%d w cache=%d",
                item, maxCount, cachedCounts.getOrDefault(item, 0L));

        ItemStack result = ItemStack.EMPTY;
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(level, pos, state, be, accessSide);
            if (handler != null) {
                int needed = maxCount;
                for (int i = 0; i < handler.getSlots(); i++) {
                    ItemStack inSlot = handler.getStackInSlot(i);
                    if (!inSlot.isEmpty() && inSlot.getItem() == item) {
                        ItemStack extracted = handler.extractItem(i, needed, false);
                        if (!extracted.isEmpty()) {
                            if (result.isEmpty()) {
                                result = extracted.copy();
                            } else {
                                result.grow(extracted.getCount());
                            }
                            needed -= extracted.getCount();
                            if (needed <= 0) break;
                        }
                    }
                }
            } else {
                if (be instanceof Container container) {
                    int needed = maxCount;
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        ItemStack inSlot = container.getItem(i);
                        if (!inSlot.isEmpty() && inSlot.getItem() == item) {
                            int toTake = Math.min(needed, inSlot.getCount());
                            ItemStack taken = container.removeItem(i, toTake);
                            if (!taken.isEmpty()) {
                                if (result.isEmpty()) {
                                    result = taken.copy();
                                } else {
                                    result.grow(taken.getCount());
                                }
                                needed -= taken.getCount();
                                if (needed <= 0) break;
                            }
                        }
                    }
                }
            }

            refreshIfLoaded(level);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (!wasLoaded) {
                VeloceChunkLoader.release(level, chunkKey, "op:extract");
            }
        }

        return result;
    }

    /**
     * Wklada item do tego endpointu (odwrotnosc {@link #extractItem}).
     *
     * <p>Uzywane przez auto-crafter do odkładania wynikow craftowania.
     * Obsluguje te same trzy rodzaje magazynow co ekstrakcja: Refined Storage,
     * NeoForge ItemHandler oraz vanilla Container.
     *
     * @return true, jesli udalo sie wlozyc cala stacke
     */
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

    public ItemStack insertItemLeftover(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        if (type == Type.REFINED_STORAGE) {
            ItemStack left = RefinedStorageHelper.insertItemLeftover(level, pos, accessSide, stack);
            if (left.getCount() != stack.getCount()) {
                refreshIfLoaded(level);
            }
            return left;
        }

        boolean wasLoaded = level.isLoaded(pos);
        boolean weLoadedChunk = false;
        long chunkKey = ChunkPos.asLong(chunkPos.x, chunkPos.z);
        if (!wasLoaded) {
            // Bez wymuszania przy zapisie swiata - patrz extractItem.
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
            // mimo ze miejsca bylo duzo. Dodawanie skrzyn nie pomagalo, bo
            // problemem nie byla pojemnosc, tylko to, ze wkladanie w ogole
            // nie docieralo do magazynu.
            //
            // Wniosek: wolajacy MUSI znac prawdziwy wynik, zeby zabrac graczowi
            // DOKLADNIE tyle, ile weszlo. Dlatego chunk wymuszamy tu i teraz
            // (z biletem operacyjnym, zwalnianym w finally), zamiast kolejkować.
            // Wkladanie jest akcja gracza (albo odlozeniem wyniku craftu),
            // wiec jednorazowe wczytanie chunku jest tu w pelni uzasadnione.
            ChunkTrace.at("INSERT", level, pos,
                    "chunk UNLOADED -> wczytuje synchronicznie; stos=%dx %s",
                    stack.getCount(), stack.getItem());
            if (!VeloceChunkLoader.tryReserveOpLoad(level)) {
                // Budzet blokujacych wczytan na ten tick wyczerpany. Odmawiamy
                // CALEGO stosu - wolajacy zatrzyma itemy u gracza. To jest
                // bezpieczne (zero duplikacji), a kolejny tick znowu ma budzet.
                ChunkTrace.at("INSERT", level, pos,
                        "budzet loadow wyczerpany (%d/tick) -> odmowa, stos zostaje u gracza",
                        VeloceChunkLoader.MAX_OP_LOADS_PER_TICK);
                return stack;
            }
            VeloceChunkLoader.retain(level, chunkKey, "op:insert",
                    VeloceChunkLoader.Reason.OPERATION, pos);
            weLoadedChunk = true;
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
            t.printStackTrace();
        } finally {
            if (weLoadedChunk) {
                VeloceChunkLoader.release(level, chunkKey, "op:insert");
            }
        }
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
