package com.craftingveloce.debug;

import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Jednolite logowanie debugowania chunkow i sieci - WYLACZNIE do konsoli.
 *
 * <p><b>Po co osobny logger.</b> {@link com.craftingveloce.util.VeloceLog} filtruje
 * komunikaty po poziomie i kategorii z configu. Do diagnozy "co sie stalo"
 * potrzebujemy zapisu, ktory nie zniknie po zmianie configu ani po przelaczeniu
 * poziomu - dlatego ten logger pisze zawsze i pod jednym, stalym prefiksem
 * {@code [Veloce][CHUNKTRACE]}.
 *
 * <p><b>Dlaczego nie na czat.</b> Przy 1000 blokach sieci sam raport o chunkach
 * to kilkadziesiat linii - na czacie bylby nieczytelny i zniknalby po chwili.
 * W logu zostaje na stale, mozna go przeszukac i porownac miedzy sesjami.
 *
 * <p><b>Format.</b> Kazda linia ma ten sam uklad, zeby dalo sie ja parsowac:
 * <pre>
 *   [Veloce][CHUNKTRACE] &lt;SESJA&gt; | &lt;AKCJA&gt; | &lt;szczegoly&gt;
 * </pre>
 * gdzie {@code SESJA} to krotki identyfikator uruchomienia sledzenia, dzieki
 * ktoremu linie z jednego testu nie mieszaja sie z poprzednimi.
 */
public final class ChunkTrace {

    private static final Logger LOG = LoggerFactory.getLogger("craftingveloce-trace");

    private ChunkTrace() {
    }

    /** Czy sledzenie jest aktywne. */
    private static volatile boolean enabled = false;

    /** Identyfikator biezacej sesji sledzenia (krotki, do czytania w logu). */
    private static volatile String session = "-";

    /** Licznik kolejnych zdarzen w sesji - zeby widziec kolejnosc. */
    private static int seq = 0;

    /**
     * Kiedy (gameTime) ostatnio raportowalismy stan chunkow.
     *
     * <p>Slabe klucze na swiaty - nie trzymamy swiata w pamieci.
     */
    private static final Map<ServerLevel, Long> LAST_SNAPSHOT = new WeakHashMap<>();

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Wlacza sledzenie i zaklada nowa sesje.
     *
     * @return identyfikator sesji
     */
    public static String start(ServerLevel level) {
        session = Long.toHexString(System.currentTimeMillis() & 0xFFFFFFL);
        seq = 0;
        enabled = true;
        LOG.info("[Veloce][CHUNKTRACE] {} | SESSION START | wymiar={} gameTime={}",
                session, level.dimension().location(), level.getGameTime());
        return session;
    }

    /** Wylacza sledzenie. */
    public static void stop() {
        if (enabled) {
            LOG.info("[Veloce][CHUNKTRACE] {} | SESSION STOP | zdarzen={}", session, seq);
        }
        enabled = false;
    }

    public static String session() {
        return session;
    }

    // ------------------------------------------------------------------
    // Zdarzenia
    // ------------------------------------------------------------------

    /**
     * Zdarzenie ogolne.
     *
     * @param action  krotka nazwa akcji (np. BUILD, EXTRACT)
     * @param details szczegoly w formacie printf
     */
    public static void event(String action, String details, Object... args) {
        if (!enabled) {
            return;
        }
        seq++;
        String msg = args.length == 0 ? details : String.format(details, args);
        LOG.info("[Veloce][CHUNKTRACE] {} | #{} {} | {}", session, seq, action, msg);
    }

    /**
     * Zdarzenie na konkretnym bloku - z pozycja, chunkiem i stanem symulacji.
     *
     * <p>To jest najwazniejszy format w tym pliku: pozwala jednoznacznie
     * stwierdzic, CZY chunk byl rozladowany w chwili akcji.
     */
    public static void at(String action, ServerLevel level, net.minecraft.core.BlockPos pos,
                          String details, Object... args) {
        if (!enabled) {
            return;
        }
        seq++;
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        boolean loaded = level.isLoaded(pos);
        String msg = args.length == 0 ? details : String.format(details, args);
        LOG.info("[Veloce][CHUNKTRACE] {} | #{} {} | {} @[{},{},{}] chunk[{},{}] {} | {}",
                session, seq, action,
                level.getBlockState(pos).getBlock().getClass().getSimpleName(),
                pos.getX(), pos.getY(), pos.getZ(), cx, cz,
                loaded ? "LOADED" : "UNLOADED",
                msg);
    }

    /**
     * Pelny zrzut stanu chunkow sieci - do porownania przed/po.
     *
     * <p>Wypisuje KAZDY chunk sieci z osobna, z informacja:
     * <ul>
     *   <li>czy jest zaladowany,</li>
     *   <li>co w nim stoi (wezly, magazyny),</li>
     *   <li>czy jest przez nas wymuszony i z jakiego powodu.</li>
     * </ul>
     */
    public static void snapshotChunks(String label, ServerLevel level,
                                      com.craftingveloce.network.pipe.VelocePipeNetwork net) {
        if (!enabled) {
            return;
        }
        seq++;
        LOG.info("[Veloce][CHUNKTRACE] {} | #{} SNAPSHOT {} | siec={} rur={} wezlow={} magazynow={}",
                session, seq, label,
                net.getId().toString().substring(0, 8),
                net.getPipes().size(), net.getTerminals().size(), net.getEndpoints().size());

        // Wezly: to one trzymaja chunki na stale.
        for (net.minecraft.core.BlockPos p : net.getTerminals()) {
            logOneChunk("NODE", level, p, "siec=" + net.getId().toString().substring(0, 8));
        }
        // Magazyny: maja sie rozladowywac.
        for (net.minecraft.core.BlockPos p : net.getEndpoints().keySet()) {
            logOneChunk("STORAGE", level, p, "siec=" + net.getId().toString().substring(0, 8));
        }
        // Chunki wymuszone - z powodem.
        for (var hc : com.craftingveloce.network.pipe.VeloceChunkLoader.listHeld(level)) {
            LOG.info("[Veloce][CHUNKTRACE] {} | #{} FORCED chunk[{},{}] uzyc={} bilety={}",
                    session, seq, hc.x(), hc.z(), hc.hits(),
                    hc.tickets().isEmpty() ? "(brak)" : hc.tickets().stream()
                            .map(t -> t.reason() + ":" + t.owner())
                            .collect(java.util.stream.Collectors.joining(",")));
        }
        LOG.info("[Veloce][CHUNKTRACE] {} | #{} SNAPSHOT {} | wymuszonych razem: {}",
                session, seq, label,
                com.craftingveloce.network.pipe.VeloceChunkLoader.appliedCount(level));
    }

    /** Jeden wpis chunku z opisem zawartosci. */
    private static void logOneChunk(String role, ServerLevel level,
                                    net.minecraft.core.BlockPos pos, String extra) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        boolean loaded = level.isLoaded(pos);
        boolean held = com.craftingveloce.network.pipe.VeloceChunkLoader.isHeld(
                level, net.minecraft.world.level.ChunkPos.asLong(cx, cz));
        LOG.info("[Veloce][CHUNKTRACE] {} | {} chunk[{},{}] blok[{},{},{}] {} {} {}",
                session, role, cx, cz, pos.getX(), pos.getY(), pos.getZ(),
                loaded ? "LOADED" : "UNLOADED",
                held ? "FORCED" : "not-forced",
                extra);
    }

    /** Zrzut zawartosci sieci - co siec widzi i z jakiego zrodla. */
    public static void snapshotStock(String label, ServerLevel level,
                                     com.craftingveloce.network.pipe.VelocePipeNetwork net) {
        if (!enabled) {
            return;
        }
        seq++;
        Map<net.minecraft.world.item.Item, Long> counts = net.getAllItemCounts(level, true);
        LOG.info("[Veloce][CHUNKTRACE] {} | #{} STOCK {} | typow={}",
                session, seq, label, counts.size());
        List<String> lines = new ArrayList<>();
        for (Map.Entry<net.minecraft.world.item.Item, Long> e : counts.entrySet()) {
            lines.add(e.getKey().getDescription().getString() + "=" + e.getValue());
        }
        java.util.Collections.sort(lines);
        for (String line : lines) {
            LOG.info("[Veloce][CHUNKTRACE] {} | #{} STOCK {} |   {}",
                    session, seq, label, line);
        }

        // Z rozbiciem NA ENDPOINTY - to pokazuje, ktore dane pochodza z cache
        // (chunk rozladowany), a ktore ze swiezego skanu.
        for (Map.Entry<net.minecraft.core.BlockPos,
                com.craftingveloce.network.pipe.ConnectedEndpointInfo> e
                : net.getEndpoints().entrySet()) {
            var pos = e.getKey();
            var ep = e.getValue();
            boolean loaded = level.isLoaded(pos);
            LOG.info("[Veloce][CHUNKTRACE] {} | #{} STOCK {} |   endpoint {} [{}] {} typow={}{}",
                    session, seq, label, ep.getType(), pos.toShortString(),
                    loaded ? "LOADED(swiezy skan)" : "UNLOADED(z cache)",
                    ep.getCachedCounts().size(),
                    loaded ? "" : "  <-- dane z cache, nie ze swiata");
        }
    }

    // ------------------------------------------------------------------
    // Wynik operacji
    // ------------------------------------------------------------------

    /**
     * Wynik operacji na itemach - z jednoznacznym werdyktem.
     *
     * @param ok        czy operacja sie udala
     * @param viaQueue  czy poszla przez kolejke (chunk byl rozladowany)
     * @param chunkWasLoaded czy chunk byl zaladowany w chwili rozpoczecia
     */
    public static void result(String operation, boolean ok, boolean viaQueue,
                              boolean chunkWasLoaded, String details, Object... args) {
        if (!enabled) {
            return;
        }
        seq++;
        String msg = args.length == 0 ? details : String.format(details, args);
        String verdict;
        if (ok && viaQueue) {
            verdict = "OK(z kolejki - chunk byl UNLOADED)";
        } else if (ok) {
            verdict = "OK(chunk byl LOADED)";
        } else {
            verdict = "FAIL";
        }
        LOG.info("[Veloce][CHUNKTRACE] {} | #{} RESULT {} | {} | chunk={} | {}",
                session, seq, operation, verdict,
                chunkWasLoaded ? "LOADED" : "UNLOADED", msg);
    }
}
