package com.craftingveloce.network.pipe;

import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class VelocePipeNetwork {
    private final UUID id;
    private final Set<BlockPos> pipes = new HashSet<>();
    private final Set<BlockPos> terminals = new HashSet<>();
    private final Map<BlockPos, ConnectedEndpointInfo> endpoints = new HashMap<>();

    /**
     * CACHE liczb "ile da sie dorobic" - JEDEN na siec, wspolny dla terminala
     * i kontrolera.
     *
     * <p>Gracz: "cyferki ladują sie powoli od lewej do prawej ... chcialbym,
     * zeby sie keszowaly na serwerze dla tego terminala ... klient przy
     * otwarciu GUI instant dostaje gotowy kesz, a dopiero potem odpala sie
     * logika liczenia widocznego". Cache zyje przy SIECI (nie przy terminalu
     * ani kontrolerze), bo oba GUI pokazuja praktycznie te same dane - dwa
     * osobne kesze rozjechalyby sie.
     *
     * <p>Wypelnia go kazde liczenie, ktore juz sie odbywa (widoczna strona
     * terminala/kontrolera) - cache sam sie doucza, bez osobnego budowania
     * w tle i bez obciazania serwera.
     */
    private final Map<Item, Long> craftableMemo = new HashMap<>();

    /**
     * Itemy, dla ktorych gracz woli PRZEPALANIE od craftingu.
     *
     * <p><b>To PREFERENCJA, nie filtr.</b> Mowi tylko, ktora droga jest
     * pierwsza w kolejnosci - druga nadal zostaje dostepna, gdyby pierwszej
     * zabraklo (brak skladnikow albo brak ciepla).
     *
     * <p>Trzymamy to na SIECI, a nie na pojedynczym piecu czy crafterze:
     * gracz wybiera "crafting albo furnace", a nie "ten konkretny piec".
     * Przy okazji siec jest jedynym miejscem, ktore autokrafter ma zawsze
     * pod reka, wiec planer nie musi niczego dodatkowo wyszukiwac.
     */
    private final Set<Item> preferFurnace = new HashSet<>();

    /** Czy dla tego itemu przepalanie ma byc probowane PRZED craftingiem. */
    public boolean prefersFurnace(Item item) {
        return preferFurnace.contains(item);
    }

    /** Ustawia preferencje dla itemu (true = piec pierwszy). */
    public void setPrefersFurnace(Item item, boolean value) {
        if (value) {
            preferFurnace.add(item);
        } else {
            preferFurnace.remove(item);
        }
    }

    /** Kopia zbioru - dla GUI (zeby nikt nie pisal po zywej mapie). */
    public Set<Item> getFurnacePreferred() {
        return Set.copyOf(preferFurnace);
    }
    private final Set<ChunkPos> trackedChunks = new HashSet<>();

    /**
     * Cache zagregowanego stanu sieci.
     *
     * <p>Kilku odbiorcow (cache craftowalnosci, GUI terminala, pakiety,
     * wyswietlacze) pyta o ten sam stan w tym samym ticku. Bez tego cache
     * kazdy z nich zamawial wlasny pelny skan wszystkich inwentarzy.
     */
    private Map<Item, Long> aggregateCache;

    /** Tick, w ktorym policzono {@link #aggregateCache}. */
    private long aggregateCacheTick = Long.MIN_VALUE;

    /** Jak dlugo agregat jest uznawany za swiezy, w tickach. */
    private static final int AGGREGATE_TTL_TICKS = 10;

    /** Powyzej tego czasu pojedynczy skan jest raportowany jako wolny. */
    private static final long SLOW_ENDPOINT_NS = 20_000_000L;

    /**
     * Budzet na przeskanowanie WSZYSTKICH endpointow w jednym przebiegu.
     *
     * <p>Po jego wyczerpaniu pozostale endpointy uzywaja zapamietanych liczb
     * (starych, ale nie znikaja z GUI). Skanowanie jest i tak throttlowane do
     * raz na 10 tickow na inwentarz - to jest bezpiecznik na pojedynczy wolny
     * magazyn, ktory inaczej zablokowalby tick.
     */
    private static final long SCAN_BUDGET_NS = 20_000_000L;

    public VelocePipeNetwork(UUID id) {
        this.id = id != null ? id : UUID.randomUUID();
    }

    public UUID getId() {
        return id;
    }

    public Set<BlockPos> getPipes() {
        return pipes;
    }

    /**
     * Wezly sieci: terminale, craftery ORAZ extractory.
     *
     * <p><b>Uwaga na nazwe.</b> Metoda nazywa sie "terminals" historycznie, ale
     * zwraca WSZYSTKIE bloki-wezly, nie tylko terminale. W raportach liczba
     * "Terminals: 2" oznaczala wiec czesto crafter + extractor, a nie dwa
     * terminale - co mylilo takze przy diagnozie.
     *
     * <p>To wlasnie te bloki maja dzialajace block entity, wiec ich chunki sa
     * trwale force-loadowane (patrz {@code VeloceCraftingCache}).
     */
    public Set<BlockPos> getTerminals() {
        return terminals;
    }

    public Map<BlockPos, ConnectedEndpointInfo> getEndpoints() {
        return endpoints;
    }

    public Set<ChunkPos> getTrackedChunks() {
        return Collections.unmodifiableSet(trackedChunks);
    }

    public void updateTrackedChunks() {
        trackedChunks.clear();
        for (BlockPos p : pipes) {
            trackedChunks.add(new ChunkPos(p));
        }
        for (BlockPos p : terminals) {
            trackedChunks.add(new ChunkPos(p));
        }
        for (BlockPos p : endpoints.keySet()) {
            trackedChunks.add(new ChunkPos(p));
        }
    }

    /**
     * Czysci zapamietane liczby wszystkich endpointow.
     *
     * <p>Wywolywane gdy zmienil sie uklad sieci (podlaczenie/odlaczenie
     * inventory, zaladowanie chunka). Bez tego endpoint usunietej skrzyni
     * nadal raportowalby swoja dawna zawartosc.
     */
    public void invalidateEndpointCache(ServerLevel level) {
        for (ConnectedEndpointInfo ep : endpoints.values()) {
            // invalidateCache(), a nie samo getCachedCounts().clear():
            // czyszczenie bez zwolnienia throttlingu oznaczalo, ze przez
            // kolejne 10 tickow endpoint raportowal ZERO itemow.
            //
            // level jest potrzebny, zeby NIE czyscic liczb dla chunku poza
            // symulacja - tam nie ma jak ich odtworzyc (patrz invalidateCache).
            ep.invalidateCache(level);
        }
        aggregateCache = null;
        aggregateCacheTick = Long.MIN_VALUE;
    }

    /**
     * Wklada item do pierwszego magazynu sieci, ktory go przyjmie.
     *
     * <p><b>Pomija bufory crafterow.</b> Bufor to pamiec robocza na wyniki
     * posrednie auto-craftingu, a nie magazyn gracza - wrzucenie tam itemu
     * mieszaloby planowanie (planer widzi bufor jako czesc stocku, wiec
     * item gracza udawalby material wyprodukowany przez crafter).
     *
     * <p>Ekstraktory nie sa endpointami sieci, wiec nie trzeba ich osobno
     * wykluczac - one tylko wydaja.
     *
     * @return to, czego NIE udalo sie wlozic (EMPTY gdy wszystko przyjete)
     */
    public ItemStack insertIntoStorage(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        for (ConnectedEndpointInfo endpoint : endpoints.values()) {
            if (remaining.isEmpty()) {
                break;
            }
            if (endpoint.getType() == ConnectedEndpointInfo.Type.CRAFTING_BUFFER) {
                continue;   // bufor craftera to nie magazyn
            }
            // Kazdy endpoint oddaje RESZTE, wiec czesciowe przyjecie nie ginie.
            remaining = endpoint.insertItemLeftover(level, remaining);
        }
        return remaining;
    }

    /**
     * Suma zawartosci sieci, odswiezana z throttlingiem (raz na
     * {@link ConnectedEndpointInfo#SCAN_INTERVAL_TICKS} tickow na inwentarz).
     *
     * <p>To wariant domyslny, uzywany przez wszystko co tylko CZYTA stan:
     * cache w tle, GUI, wyswietlacze, podpowiedzi. Wczesniej kazde takie
     * zapytanie skanowalo od zera wszystkie inwentarze sieci, a ze cache
     * pytal o to co kilka tickow, watek serwera spalil sie na samym czytaniu.
     */
    /**
     * Pozycje OBCYCH zrodel energii podpietych do tej sieci.
     *
     * <p>Wypelnia je skan sieci (sasiad z capability {@code EnergyStorage.BLOCK},
     * ktory NIE jest naszym blokiem). Nasze maszyny sciagaja z nich prad, ale
     * tylko one moga to robic - i tylko gdy maja wolne miejsce.
     */
    private final java.util.Set<BlockPos> energyEndpoints = new java.util.HashSet<>();

    /** Skan znalazl obce zrodlo energii - zapamietaj. */
    public void addEnergyEndpoint(BlockPos pos) {
        energyEndpoints.add(pos.immutable());
    }

    /** Pozycje obcych zrodel energii w tej sieci. */
    public java.util.Set<BlockPos> getEnergyEndpoints() {
        return java.util.Set.copyOf(energyEndpoints);
    }

    /** Skan od nowa wypelnia liste (topologia sie zmienila). */
    public void clearEnergyEndpoints() {
        energyEndpoints.clear();
    }

    /** Dopisuje swiezo policzone liczby do cache'u sieci (nadpisuje starsze). */
    public void rememberCraftable(Map<Item, Long> counts) {
        if (counts == null || counts.isEmpty()) {
            return;
        }
        craftableMemo.putAll(counts);
    }

    /**
     * Po UDANYM crafcie: liczba "ile jeszcze moge zrobic" maleje o to, co
     * wlasnie zeszlo z sieci.
     *
     * <p>Gracz: "jak craftuje item z automatu, to ta liczba sie nie zmniejsza -
     * zrob z niej -1, a jesli craftuje stack to -64". Bez tego GUI pokazywalo
     * stale liczby, dopoki ktos nie wymusil przeliczenia.
     *
     * <p>Wpis, ktorego nie ma w cache, zostaje bez zmian - nie zgadujemy.
     */
    public void noteCrafted(Item item, long amount) {
        if (item == null || amount <= 0) {
            return;
        }
        Long known = craftableMemo.get(item);
        if (known == null) {
            return;
        }
        long left = known - amount;
        craftableMemo.put(item, Math.max(0L, left));
    }

    /** Migawka cache'u - to leci do klienta NATYCHMIST po otwarciu GUI. */
    public Map<Item, Long> getCraftableMemo() {
        return new HashMap<>(craftableMemo);
    }

    /**
     * Kasuje cache, gdy zmienilo sie cos, co zmienia wynik.
     *
     * <p>Wolane przy zmianach sieci (magazyn, maszyna, wlaczniki auto-craftingu,
     * przeladowanie chunkow) - inaczej gracz widzialby stare liczby.
     */
    public void clearCraftableMemo() {
        craftableMemo.clear();
    }

    public Map<Item, Long> getAllItemCounts(ServerLevel level) {
        return getAllItemCounts(level, false);
    }

    /**
     * @param force gdy true, skanuje inwentarze nawet jesli robil to chwile
     *              temu. Uzywane TYLKO przed operacja, ktora musi widziec
     *              stan na zywo (pobranie itemu, planowanie prawdziwego
     *              craftu) - nigdy w petli tla.
     */
    public Map<Item, Long> getAllItemCounts(ServerLevel level, boolean force) {
        long now = level.getGameTime();

        // Agregat jest cache'owany na poziomie sieci. Bez tego kazdy z kilku
        // odbiorcow (cache w tle, GUI, wyswietlacze, pakiety) zamawial wlasny
        // pelny skan tej samej sieci - w tym samym ticku.
        // `now >= aggregateCacheTick` - jak w ConnectedEndpointInfo: przy
        // cofnietym czasie swiata roznica bylaby ujemna, wiec warunek
        // "mlodsze niz TTL" bylby spelniony i agregat nigdy by sie nie odswiezyl.
        if (!force && aggregateCache != null
                && now >= aggregateCacheTick
                && now - aggregateCacheTick < AGGREGATE_TTL_TICKS) {
            return new HashMap<>(aggregateCache);
        }

        long scanStart = System.nanoTime();
        Map<Item, Long> total = new HashMap<>();
        int skipped = 0;

        // UWAGA: nie ma tu juz przechodzenia po "polaczonych sieciach".
        //
        // Bylo to resztka po starym modelu, w ktorym sieci sie scalaly i dzielily
        // (byla nawet klasa VeloceNetworkGraph, ktorej juz nie ma - komentarz
        // sie do niej odwolywal). Plaska struktura zalatwia to inaczej: gracz
        // laczy rury, wiec powstaje JEDEN komponent i jedna siec. Lista
        // `linked` nie byla NIGDY wypelniana (setLinkedNetworks nie mial ani
        // jednego wolajacego), wiec ta petla byla martwa - a gdyby ktos ja
        // kiedys ozywil, dwie sieci wskazujace na siebie dalyby nieskonczona
        // rekurencje i StackOverflowError w ticku. Usuniete razem z polem.

        for (ConnectedEndpointInfo endpoint : endpoints.values()) {
            // BUDZET SKANU. To byla ostatnia niezbudzetowana ciezka operacja
            // na watku serwera: przeskanowanie JEDNEGO wolnego inwentarza
            // (np. ogromnej sieci Refined Storage) blokowalo tick bez limitu.
            //
            // Po wyczerpaniu budzetu NIE skanujemy kolejnych endpointow - ale
            // nadal sumujemy ich ZAPAMIETANE liczby, wiec nic nie znika z GUI.
            // Wyjatkiem jest force=true (przed realnym pobraniem itemu), gdzie
            // liczy sie poprawnosc, a nie czas - i to jest sciezka gracza.
            if (!force && System.nanoTime() - scanStart > SCAN_BUDGET_NS) {
                skipped++;
            } else {
                long epStart = System.nanoTime();
                if (force) {
                    endpoint.forceRefresh(level, now);
                } else {
                    endpoint.refreshIfLoadedThrottled(level, now);
                }
                long epNanos = System.nanoTime() - epStart;
                if (epNanos > SLOW_ENDPOINT_NS) {
                    // Nazwany winowajca zamiast "siec jest wolna".
                    VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                            "slow endpoint scan: %d ms for %s (type=%s)",
                            epNanos / 1_000_000L, endpoint.getPos(), endpoint.getType());
                }
            }
            for (Map.Entry<Item, Long> entry : endpoint.getCachedCounts().entrySet()) {
                if (entry.getValue() > 0) {
                    total.merge(entry.getKey(), entry.getValue(), Long::sum);
                }
            }
        }
        aggregateCache = total;
        aggregateCacheTick = now;

        long scanNanos = System.nanoTime() - scanStart;
        if (scanNanos > SLOW_ENDPOINT_NS || skipped > 0) {
            VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                    "slow network stock scan: %d ms for %d endpoint(s), %d item type(s)"
                            + "%s",
                    scanNanos / 1_000_000L, endpoints.size(), total.size(),
                    skipped > 0
                            ? " - budget exhausted, " + skipped + " endpoint(s) used cached counts"
                            : "");
        }
        return new HashMap<>(total);
    }

    /**
     * Ile sztuk KONKRETNIE TEGO itemu przyjmie cala siec.
     *
     * <p>To jest wlasciwe pytanie, gdy chcemy odlozyc item - a nie "ile jest
     * wolnych slotow". Roznica jest realna: skrzynia wypelniona niedopelnionymi
     * stosami kamienia ma ZERO pustych slotow, ale przyjmie jeszcze setki
     * kamieni. Licznik pustych slotow raportowal ja jako pelna i terminal
     * pokazywal "network full" na stale.
     *
     * <p>Miejsce w niedopelnionych stosach liczymy PER TYP, wiec kamien i ziemia
     * maja osobne odpowiedzi - tak jak powinno byc.
     *
     * @return liczba sztuk (0 = naprawde nie wejdzie ani jedna) albo {@code -1}
     *         gdy nie wiemy. <b>0 to dowod, -1 to brak wiedzy</b> - wolajacy
     *         nie moze traktowac ich zamiennie.
     */
    public long capacityFor(ServerLevel level, Item item) {
        long total = 0;
        for (ConnectedEndpointInfo ep : endpoints.values()) {
            if (level != null && !level.isLoaded(ep.getPos())) {
                return -1;   // niezaladowany chunk - jego pojemnosc jest nieaktualna
            }
            long cap = ep.capacityFor(item);
            if (cap < 0) {
                return -1;   // nieznana pojemnosc - nie zgadujemy
            }
            total += cap;
        }
        return total;
    }

    public ItemStack extractItem(ServerLevel level, Item item, int maxCount) {
        for (ConnectedEndpointInfo endpoint : endpoints.values()) {
            if (endpoint.getCachedCounts().getOrDefault(item, 0L) > 0) {
                ItemStack extracted = endpoint.extractItem(level, item, maxCount);
                if (!extracted.isEmpty()) {
                    return extracted;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** Zapis cache'u liczb do NBT sieci - ma przezyc restart swiata. */
    public void saveCraftableMemo(CompoundTag netTag) {
        CompoundTag memo = new CompoundTag();
        for (Map.Entry<Item, Long> entry : craftableMemo.entrySet()) {
            memo.putLong(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(entry.getKey()).toString(), entry.getValue());
        }
        netTag.put("CraftableMemo", memo);
    }

    /** Odczyt cache'u liczb z NBT sieci (po wczytaniu swiata). */
    public void restoreCraftableMemo(CompoundTag netTag) {
        craftableMemo.clear();
        CompoundTag memo = netTag.getCompound("CraftableMemo");
        for (String key : memo.getAllKeys()) {
            net.minecraft.resources.ResourceLocation id =
                    net.minecraft.resources.ResourceLocation.tryParse(key);
            if (id == null) {
                continue;
            }
            craftableMemo.put(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id),
                    memo.getLong(key));
        }
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);

        long[] pipeArr = new long[pipes.size()];
        int i = 0;
        for (BlockPos p : pipes) {
            pipeArr[i++] = p.asLong();
        }
        tag.putLongArray("Pipes", pipeArr);

        long[] termArr = new long[terminals.size()];
        i = 0;
        for (BlockPos p : terminals) {
            termArr[i++] = p.asLong();
        }
        tag.putLongArray("Terminals", termArr);

        ListTag endList = new ListTag();
        for (ConnectedEndpointInfo ep : endpoints.values()) {
            endList.add(ep.toNbt());
        }
        tag.put("Endpoints", endList);

        // Preferencja "piec czy crafting" musi przetrwac restart swiata -
        // inaczej gracz ustawialby ja po kazdym wejsciu.
        ListTag prefList = new ListTag();
        for (Item it : preferFurnace) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it);
            if (rl != null) {
                prefList.add(StringTag.valueOf(rl.toString()));
            }
        }
        tag.put("PreferFurnace", prefList);

        return tag;
    }

    public static VelocePipeNetwork fromNbt(CompoundTag tag) {
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        VelocePipeNetwork net = new VelocePipeNetwork(id);

        long[] pipeArr = tag.getLongArray("Pipes");
        for (long l : pipeArr) {
            net.pipes.add(BlockPos.of(l));
        }

        long[] termArr = tag.getLongArray("Terminals");
        for (long l : termArr) {
            net.terminals.add(BlockPos.of(l));
        }

        ListTag endList = tag.getList("Endpoints", Tag.TAG_COMPOUND);

        // Preferencje: nazwy itemow po identyfikatorach rejestru.
        for (Tag el : tag.getList("PreferFurnace", Tag.TAG_STRING)) {
            ResourceLocation rl = ResourceLocation.tryParse(el.getAsString());
            if (rl == null) {
                continue;
            }
            Item resolved = BuiltInRegistries.ITEM.get(rl);
            if (resolved != null && resolved != Items.AIR) {
                net.preferFurnace.add(resolved);
            }
        }
        for (int j = 0; j < endList.size(); j++) {
            ConnectedEndpointInfo ep = ConnectedEndpointInfo.fromNbt(endList.getCompound(j));
            // null = wpis nieczytelny (zly side albo nieznany typ). POMIJAMY go,
            // zamiast wywalac wczytywanie calego zapisu swiata - pojedynczy
            // popsuty endpoint nie moze blokowac wejscia do gry.
            if (ep != null) {
                net.endpoints.put(ep.getPos(), ep);
            }
        }

        net.updateTrackedChunks();
        return net;
    }
}
