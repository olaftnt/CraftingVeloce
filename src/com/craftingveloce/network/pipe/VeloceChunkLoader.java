package com.craftingveloce.network.pipe;

import com.craftingveloce.util.VeloceLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.HashSet;
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
 * <p>Rozwiazanie: liczymy referencje. Chunk jest force-loadowany, gdy trzyma
 * go co najmniej jeden wlasciciel, i zwalniany dopiero gdy ostatni go pusci.
 */
public final class VeloceChunkLoader {

    private VeloceChunkLoader() {
    }

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

    /** level -> (chunk -> liczba wlascicieli). Slabe klucze, zeby nie trzymac swiatow. */
    private static final Map<ServerLevel, Map<Long, Integer>> REFS = new WeakHashMap<>();

    /** Chcemy wiedziec, co realnie wymusilismy, zeby posprzatac przy zamknieciu. */
    private static final Map<ServerLevel, Set<Long>> APPLIED = new WeakHashMap<>();

    /**
     * Zgłasza, ze dany wlasciciel chce trzymac ten chunk.
     *
     * @return true, jesli to wlasnie my fizycznie wymusilismy zaladowanie
     */
    public static boolean retain(ServerLevel level, long chunkKey) {
        if (frozen) {
            // Swiat sie zapisuje - wymuszenie chunku zawiesiloby zapis.
            return false;
        }
        Map<Long, Integer> refs = REFS.computeIfAbsent(level, k -> new HashMap<>());
        int count = refs.merge(chunkKey, 1, Integer::sum);
        if (count > 1) {
            return false;   // ktos inny juz trzyma - nic nie robimy
        }
        level.setChunkForced(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), true);
        APPLIED.computeIfAbsent(level, k -> new HashSet<>()).add(chunkKey);
        return true;
    }

    /**
     * Zwalnia referencje jednego wlasciciela.
     *
     * <p>Chunk jest realnie rozladowywany dopiero, gdy licznik spadnie do zera.
     * To wlasnie ta regula ucina petle szarpania miedzy sieciami.
     */
    public static void release(ServerLevel level, long chunkKey) {
        Map<Long, Integer> refs = REFS.get(level);
        if (refs == null) {
            return;
        }
        Integer count = refs.get(chunkKey);
        if (count == null) {
            return;
        }
        if (count > 1) {
            refs.put(chunkKey, count - 1);
            return;
        }
        refs.remove(chunkKey);
        if (refs.isEmpty()) {
            REFS.remove(level);
        }
        level.setChunkForced(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), false);
        Set<Long> applied = APPLIED.get(level);
        if (applied != null) {
            applied.remove(chunkKey);
            if (applied.isEmpty()) {
                APPLIED.remove(level);
            }
        }
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
        if (released > 0) {
            VeloceLog.Network.success(VeloceLog.Side.SERVER,
                    "chunk loader: released all %d forced chunk(s) for level %s",
                    released, level.dimension().location());
        }
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
        Map<Long, Integer> refs = REFS.get(level);
        return refs != null && refs.containsKey(chunkKey);
    }

    /** Diagnostyka: ile chunkow realnie trzymamy na tym swiecie. */
    public static int appliedCount(ServerLevel level) {
        Set<Long> applied = APPLIED.get(level);
        return applied == null ? 0 : applied.size();
    }
}
