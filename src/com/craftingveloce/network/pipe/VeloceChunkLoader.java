package com.craftingveloce.network.pipe;

import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Globalny, referencyjnie liczony wlasciciel force-loadowanych chunkow.
 *
 * <p><b>Dlaczego to istnieje.</b> {@code ServerLevel.setChunkForced(x, z, bool)}
 * to jeden GLOBALNY znacznik na chunk, a nie licznik. Wczesniej kazda siec
 * trzymala wlasny zbior {@code forcedChunks} i wolala {@code setChunkForced}
 * samodzielnie. Gdy dwie sieci dzielily ten sam chunk i mialy o nim rozne
 * zdanie (jedna go chciala, druga nie), dochodzilo do szarpaniny:
 *
 * <pre>
 *   siec A: setChunkForced(X, true)    -> chunk sie laduje
 *   siec B: setChunkForced(X, false)   -> chunk sie rozladowuje
 *   siec A: setChunkForced(X, true)    -> znowu...
 * </pre>
 *
 * <p>W praktyce oznaczalo to ~41 000 cykli load/unload jednego chunka w ciagu
 * pieciu minut. Kazdy cykl odpalal przebudowe sieci, uniewaznienie cache
 * endpointow i zdarzenie do debug-loga, co zjadalo watek serwera i dawalo
 * dokladnie te lagi, ktore widac bylo w grze.
 *
 * <p><b>Bilety zamiast samego licznika.</b> Sam licznik referencji nie odpowiada
 * na dwa pytania, ktore sa potrzebne przy diagnozie: KTO trzyma ten chunk i PO
 * CO. Dlatego kazde zatrzymanie to bilet ({@link Ticket}) z wlascicielem,
 * powodem i pozycja bloku, ktory o to poprosil.
 *
 * <p><b>Czestotliwosc uzycia.</b> Chunk, po ktory operacje sieciowe siegaja bez
 * przerwy, jest trzymany DLUZEJ. Bez tego dochodzilo do cyklu: operacja
 * wymusza chunk, operacja sie konczy, chunk wypada, za chwile znowu trzeba go
 * wymusic. Kazde takie kolko to pelne wczytanie chunku z dysku. Licznik
 * {@code hits} pozwala odroznic chunk "uzywany raz na minute" od "uzywany
 * kilka razy na sekunde".
 */
public final class VeloceChunkLoader {

    private VeloceChunkLoader() {
    }

    /**
     * Po co chunk jest trzymany.
     *
     * <p>Kolejnosc ma znaczenie tylko dla czytelnosci raportu - nie wplywa na
     * dzialanie.
     */
    public enum Reason {
        /** Siec rur: rury i wezly musza byc symulowane, gdy gracz odejdzie. */
        NETWORK,
        /** Operacja na itemach siegnela do chunku poza symulacja. */
        OPERATION,
        /** Rura, ktora wlasnie sie przebudowuje albo bada otoczenie. */
        SCAN,
        /** Nieznany powod - wpis awaryjny. */
        OTHER
    }

    /**
     * Bilet: jeden wlasciciel trzymajacy jeden chunk.
     *
     * @param owner     kto trzyma (nazwa sieci, nazwa operacji)
     * @param reason    po co
     * @param ownerPos  blok, ktory o to poprosil (do raportu i teleportu)
     * @param since     tick gry, od ktorego bilet dziala
     */
    public record Ticket(String owner, Reason reason, BlockPos ownerPos, long since) {
    }

    /**
     * Stan jednego chunku w ksiegowosci.
     *
     * <p>Mutowalny celowo: bilety dochodza i odchodza, a licznik uzycia zyje
     * dluzej niz pojedynczy bilet.
     */
    private static final class Entry {
        /** Aktywne bilety. Ten sam wlasciciel moze miec tylko jeden. */
        final Map<String, Ticket> tickets = new HashMap<>();
        /** Ile razy siegnieto tu po dane - do decyzji o dluższym trzymaniu. */
        int hits;
        /** Do kiedy (gameTime) trzymamy ten chunk z powodu czestego uzycia. */
        long holdUntilTick = Long.MIN_VALUE;
    }

    /** level -> (chunk -> stan). Slabe klucze, zeby nie trzymac swiatow. */
    private static final Map<ServerLevel, Map<Long, Entry>> REFS = new WeakHashMap<>();

    /** Chcemy wiedziec, co realnie wymusilismy, zeby posprzatac przy zamknieciu. */
    private static final Map<ServerLevel, Set<Long>> APPLIED = new WeakHashMap<>();

    /**
     * Czy swiat sie zamyka/zapisuje.
     *
     * <p><b>Po co to.</b> Podczas zapisu swiata Minecraft rozladowuje chunki.
     * Jesli w tym momencie cokolwiek je znowu wymusza, zapis nie moze sie
     * skonczyc: chunk wraca, jest rozladowywany, wraca... W logu widac to jako
     * tysiace cykli "chunk [x, z] loaded / unloaded" w trakcie "Saving worlds",
     * czyli zawieszony zapis swiata.
     *
     * <p>Flaga zyje tutaj, bo {@link #retain} jest wspolnym wejsciem dla
     * WSZYSTKICH force-loadow - takze tych awaryjnych z pobierania itemow,
     * ktore wczesniej omijaly blokade w cache'u craftowalnosci.
     */
    private static volatile boolean frozen = false;

    // ------------------------------------------------------------------
    // Bilety - utrzymywanie w pamieci
    // ------------------------------------------------------------------

    /** Zamraza force-loady (serwer sie zamyka / zapisuje swiat). */
    public static void freeze() {
        frozen = true;
    }

    /** Odmraza force-loady (weszlismy do swiata). */
    public static void unfreeze() {
        frozen = false;
    }

    public static boolean isFrozen() {
        return frozen;
    }

    /**
     * Zgłasza, ze dany wlasciciel chce trzymac ten chunk.
     *
     * @return true, jesli to wlasnie my fizycznie wymusilismy zaladowanie
     */
    public static boolean retain(ServerLevel level, long chunkKey) {
        return retain(level, chunkKey, "network", Reason.NETWORK, null);
    }

    /**
     * Zgłasza zatrzymanie chunku, zapisujac KTO i PO CO.
     *
     * <p>Ten sam wlasciciel moze zglosic sie wielokrotnie - bilet jest jeden,
     * wiec licznik nie rosnie od samego powtarzania. Dzieki temu awaria w
     * jednym miejscu nie zostawia chunku trzymanego na zawsze.
     *
     * @return true, jesli to wlasnie my fizycznie wymusilismy zaladowanie
     */
    public static boolean retain(ServerLevel level, long chunkKey, String owner,
                                 Reason reason, BlockPos ownerPos) {
        if (frozen) {
            // Swiat sie zapisuje - wymuszenie chunku zawiesiloby zapis.
            return false;
        }
        Entry entry = REFS.computeIfAbsent(level, k -> new HashMap<>())
                .computeIfAbsent(chunkKey, k -> new Entry());

        entry.tickets.put(owner, new Ticket(owner, reason, ownerPos, level.getGameTime()));

        if (entry.tickets.size() > 1) {
            return false;   // ktos inny juz trzyma - nic nie robimy
        }
        level.setChunkForced(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), true);
        APPLIED.computeIfAbsent(level, k -> new HashSet<>()).add(chunkKey);
        return true;
    }

    /**
     * Zwalnia bilet jednego wlasciciela.
     *
     * <p>Chunk jest realnie rozladowywany dopiero, gdy nie ma ZADNEGO biletu
     * i nie jest trzymany z powodu czestego uzycia. To wlasnie ta regula ucina
     * petle szarpania miedzy sieciami.
     */
    public static void release(ServerLevel level, long chunkKey) {
        release(level, chunkKey, "network");
    }

    /** Zwalnia bilet konkretnego wlasciciela. */
    public static void release(ServerLevel level, long chunkKey, String owner) {
        Map<Long, Entry> byChunk = REFS.get(level);
        if (byChunk == null) {
            return;
        }
        Entry entry = byChunk.get(chunkKey);
        if (entry == null) {
            return;
        }
        entry.tickets.remove(owner);
        // Bilety innych wlascicieli zostaja - chunk jest nadal komus potrzebny.
        if (!entry.tickets.isEmpty()) {
            return;
        }
        // Brak biletow, ale chunk byl czesto uzywany - trzymamy go jeszcze
        // przez HOLD_TICKS, zeby nie wchodzic w cykl ladowania.
        if (entry.holdUntilTick != Long.MIN_VALUE
                && level.getGameTime() < entry.holdUntilTick) {
            return;
        }
        unforce(level, chunkKey);
        byChunk.remove(chunkKey);
        if (byChunk.isEmpty()) {
            REFS.remove(level);
        }
    }

    /** Fizycznie zdejmuje wymuszenie i sprzata ksiegowosc. */
    private static void unforce(ServerLevel level, long chunkKey) {
        level.setChunkForced(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), false);
        Set<Long> applied = APPLIED.get(level);
        if (applied != null) {
            applied.remove(chunkKey);
            if (applied.isEmpty()) {
                APPLIED.remove(level);
            }
        }
    }

    // ------------------------------------------------------------------
    // Czestotliwosc uzycia - inteligentne trzymanie
    // ------------------------------------------------------------------

    /**
     * Ile tickow trzymamy chunk po uzyciu, zanim pozwolimy mu wypasc.
     *
     * <p>Jedna minuta. Krotkie operacje sieciowe (wyciagniecie stacka, wlozenie
     * do skrzyni) zdarzaja sie seriami - bez tego kazda z nich konczylaby sie
     * rozladowaniem chunku i kolejnym wczytaniem z dysku.
     */
    private static final long HOLD_TICKS = 1200L;

    /**
     * Od ilu uzyc chunk uznajemy za "goracy".
     *
     * <p>Trzy uzycia w oknie {@link #HOT_WINDOW_TICKS} to juz seria, a nie
     * przypadkowa pojedyncza operacja.
     */
    private static final int HOT_THRESHOLD = 3;

    /** Dlugosc okna, w ktorym liczymy uzycia. */
    private static final long HOT_WINDOW_TICKS = 200L;

    /** chunk -> (ostatni tick, w ktorym liczono) - do wygaszania licznika. */
    private static final Map<ServerLevel, Map<Long, Long>> HIT_WINDOW = new WeakHashMap<>();

    /**
     * Odnotowuje, ze ktos wlasnie siegnal do tego chunku.
     *
     * <p>Wolane przy kazdej operacji na zawartosci chunku. Gdy uzyc jest duzo
     * w krotkim czasie, chunk dostaje dluzsze trzymanie - to jest wlasnie
     * "inteligentna lista priorytetow": czesto uzywane chunki zostaja w pamieci,
     * a te dotkniete raz moga wypasc.
     */
    public static void noteUse(ServerLevel level, long chunkKey) {
        if (frozen) {
            return;
        }
        Entry entry = REFS.computeIfAbsent(level, k -> new HashMap<>())
                .computeIfAbsent(chunkKey, k -> new Entry());

        long now = level.getGameTime();
        Map<Long, Long> windows = HIT_WINDOW.computeIfAbsent(level, k -> new HashMap<>());
        Long lastCounted = windows.get(chunkKey);
        if (lastCounted == null || now < lastCounted || now - lastCounted > HOT_WINDOW_TICKS) {
            // Nowe okno - licznik zaczyna od nowa.
            entry.hits = 1;
            windows.put(chunkKey, now);
        } else {
            entry.hits++;
        }

        if (entry.hits >= HOT_THRESHOLD) {
            entry.holdUntilTick = now + HOLD_TICKS;
            // Goracy chunk MUSI byc realnie wymuszony - inaczej "trzymanie"
            // jest tylko wpisem w ksiegowosci.
            if (entry.tickets.isEmpty()) {
                retain(level, chunkKey, "hot", Reason.OPERATION, null);
            }
        }
    }

    /**
     * Zwalnia wygasle bilety "goracych" chunkow.
     *
     * <p>Wolane raz na sekunde z ticku serwera. Bez tego chunk uznany kiedys za
     * goracy zostawal wymuszony na zawsze.
     */
    public static void expireHotTickets(ServerLevel level) {
        Map<Long, Entry> byChunk = REFS.get(level);
        if (byChunk == null || byChunk.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        List<Long> expired = new ArrayList<>();
        for (Map.Entry<Long, Entry> e : byChunk.entrySet()) {
            Entry entry = e.getValue();
            if (entry.holdUntilTick == Long.MIN_VALUE) {
                continue;
            }
            // now < holdUntilTick przy cofnietym czasie swiata: wtedy uznajemy,
            // ze jeszcze trzymamy - inaczej zwolnilibysmy chunk natychmiast.
            if (now >= entry.holdUntilTick) {
                entry.holdUntilTick = Long.MIN_VALUE;
                entry.hits = 0;
                if (entry.tickets.isEmpty()
                        || (entry.tickets.size() == 1 && entry.tickets.containsKey("hot"))) {
                    entry.tickets.remove("hot");
                    expired.add(e.getKey());
                }
            }
        }
        for (long key : expired) {
            if (byChunk.get(key).tickets.isEmpty()) {
                unforce(level, key);
                byChunk.remove(key);
            }
        }
    }

    // ------------------------------------------------------------------
    // Diagnostyka
    // ------------------------------------------------------------------

    /** Opis jednego trzymanego chunku - do raportu na czacie. */
    public record HeldChunk(long chunkKey, int x, int z, List<Ticket> tickets, int hits) {
    }

    /**
     * Lista wszystkich chunkow, ktore ta instancja loadera realnie wymusila.
     *
     * <p>Zwraca dane z ksiegowosci loadera, a nie z cache'y - dzieki temu widac
     * TAKZE chunki wymuszone awaryjnie, poza normalnym utrzymywaniem sieci.
     */
    public static List<HeldChunk> listHeld(ServerLevel level) {
        List<HeldChunk> out = new ArrayList<>();
        Set<Long> applied = APPLIED.get(level);
        if (applied == null || applied.isEmpty()) {
            return out;
        }
        Map<Long, Entry> byChunk = REFS.get(level);
        for (long key : applied) {
            Entry entry = byChunk != null ? byChunk.get(key) : null;
            List<Ticket> tickets = entry == null
                    ? List.of()
                    : new ArrayList<>(entry.tickets.values());
            out.add(new HeldChunk(key, ChunkPos.getX(key), ChunkPos.getZ(key), tickets,
                    entry == null ? 0 : entry.hits));
        }
        // Stabilna kolejnosc, zeby raport nie skakal miedzy wywolaniami.
        out.sort((a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x())
                : Integer.compare(a.z(), b.z()));
        return out;
    }

    /**
     * Czy ten chunk jest przez nas realnie wymuszony na tym swiecie.
     *
     * <p>Potrzebne do uzgodnienia ksiegowosci: cache moze myslec, ze trzyma
     * chunk, ktory loader zdazyl juz zwolnic (np. przy rozladowaniu swiata).
     * Bez tego sprawdzenia albo nie wymusilbysmy go ponownie, albo - gorzej -
     * doliczylibysmy druga referencje do chunku, ktora nigdy nie zniknie.
     */
    public static boolean isHeld(ServerLevel level, long chunkKey) {
        Set<Long> applied = APPLIED.get(level);
        return applied != null && applied.contains(chunkKey);
    }

    /** Diagnostyka: ile chunkow realnie trzymamy na tym swiecie. */
    public static int appliedCount(ServerLevel level) {
        Set<Long> applied = APPLIED.get(level);
        return applied == null ? 0 : applied.size();
    }

    /** Zwalnia wszystko, co kiedykolwiek wymusilismy na tym swiecie. */
    public static void releaseAll(ServerLevel level) {
        Set<Long> applied = APPLIED.get(level);
        int released = applied == null ? 0 : applied.size();
        if (applied != null) {
            for (long key : applied) {
                level.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), false);
            }
        }
        REFS.remove(level);
        APPLIED.remove(level);
        HIT_WINDOW.remove(level);
        if (released > 0) {
            VeloceLog.Network.success(VeloceLog.Side.SERVER,
                    "chunk loader: released all %d forced chunk(s) for level %s",
                    released, level.dimension().location());
        }
    }
}
