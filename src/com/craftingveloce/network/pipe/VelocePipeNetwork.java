package com.craftingveloce.network.pipe;

import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
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
    public void invalidateEndpointCache() {
        for (ConnectedEndpointInfo ep : endpoints.values()) {
            // invalidateCache(), a nie samo getCachedCounts().clear():
            // czyszczenie bez zwolnienia throttlingu oznaczalo, ze przez
            // kolejne 10 tickow endpoint raportowal ZERO itemow.
            ep.invalidateCache();
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
