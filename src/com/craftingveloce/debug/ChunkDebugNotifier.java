package com.craftingveloce.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;

import java.util.List;

/**
 * Debug na czacie: powiadamia o zaladowaniu i rozladowaniu chunkow
 * zawierajacych elementy sieci Veloce.
 *
 * <p>Cel: testowanie zachowania sieci w chunkach bez ich ciaglego ladowania.
 * Gdy chunk ze skrzynia, crafterem, extractorem czy terminalem wypada
 * z symulacji, gracz widzi na czacie co dokladnie zniknelo - i to samo
 * przy powrocie.
 *
 * <p>Domyslnie WYLACZONE i niezalezne od glownego debugowania w configu.
 * Wlacz komenda {@code /velocedebug chunk}.
 */
public final class ChunkDebugNotifier {

    /** Czy wysylac komunikaty o chunkach. */
    private static boolean enabled = false;

    /**
     * Czy dopisywac liste blokow sieci lezacych w chunku.
     *
     * <p>Stala, a nie przelacznik: monitor ma byc JEDNA komenda bez podkomend,
     * wiec nie ma czym tego przelaczac - a bez tej listy raport jest malo
     * przydatny (nie wiadomo, czy rozladowany chunk nas w ogole obchodzi).
     */
    private static final boolean verbose = true;

    private ChunkDebugNotifier() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * Wysyla graczom komunikat o zmianie chunku.
     *
     * @param loaded         true = zaladowany, false = rozladowany
     * @param affectedThings opisy blokow znajdujacych sie w tym chunku
     */
    public static void notifyChunkChange(ServerLevel level, ChunkPos pos, boolean loaded,
                                         List<String> affectedThings) {
        if (!enabled) {
            return;
        }

        String arrow = loaded ? "LOADED" : "UNLOADED";
        String color = loaded ? "§a" : "§c";

        Component header = Component.literal(String.format(
                "§8[§6Veloce§8] %s%s §7chunk §f%d, %d §7(%d element(s))",
                color, arrow, pos.x, pos.z, affectedThings.size()));

        for (ServerPlayer player : level.players()) {
            player.displayClientMessage(header, false);
            if (verbose && !affectedThings.isEmpty()) {
                for (String thing : affectedThings) {
                    player.displayClientMessage(
                            Component.literal("§8    - §7" + thing), false);
                }
            }
        }
    }

    /** Komunikat ogolny (np. przy wlaczaniu debugu). */
    public static void announce(ServerPlayer player, String message) {
        player.displayClientMessage(
                Component.literal("§8[§6Veloce§8] §7" + message), false);
    }

    /**
     * Natychmiastowy komunikat o FAKTYCZNYM rozladowaniu chunka.
     *
     * <p>Wolane wprost ze zdarzenia {@code ChunkEvent.Unload}, wiec pokazuje
     * rozladowania, ktore naprawde nastapily - a nie teoretyczne, wyliczone
     * z odleglosci gracza. To jest caly sens tego komunikatu: bez niego nie da
     * sie ustalic, czy testowany obszar w ogole sie rozladowuje.
     *
     * <p>Dodatkowo mowi, czy ten chunk byl naszym force-loadem. Jesli byl, to
     * znaczy, ze rozladowanie nastapilo mimo ze go trzymalismy - czyli test
     * i tak nie jest miarodajny i warto o tym wiedziec od razu.
     */
    public static void notifyUnload(ServerLevel level, ChunkPos pos,
                                    VelocePipeNetworkManager manager) {
        if (!enabled) {
            return;
        }

        long chunkKey = ChunkPos.asLong(pos.x, pos.z);
        boolean wasHeld = com.craftingveloce.network.pipe.VeloceChunkLoader
                .isHeld(level, chunkKey);

        // Co dokladnie lezalo w tym chunku - po to, zeby wiedziec, czy testujemy
        // obszar, ktory nas w ogole obchodzi.
        List<String> things = verbose ? describeContents(level, pos, manager) : List.of();

        // Zapis do konsoli - ZAWSZE, niezaleznie od tego, czy ktos patrzy
        // na czat. To jest wlasnie dowod, ktorego szukamy w logu.
        com.craftingveloce.debug.ChunkTrace.event("CHUNK",
                "%s chunk[%d,%d] %s", "UNLOAD", pos.x, pos.z,
                wasHeld ? "byl naszym force-loadem (!)" : "nie byl przez nas trzymany");

        MutableComponent header = Component.literal(
                "§8[§6Veloce§8] §cUNLOAD §7chunk §f" + pos.x + ", " + pos.z
                        + (wasHeld ? " §8(§e! byl naszym force-loadem§8)" : ""));

        for (ServerPlayer player : level.players()) {
            player.displayClientMessage(header, false);
            if (!things.isEmpty()) {
                for (String thing : things) {
                    player.displayClientMessage(
                            Component.literal("§8    - §7" + thing), false);
                }
            }
        }
    }

    /** Opisy blokow sieci lezacych w danym chunku. */
    private static List<String> describeContents(ServerLevel level, ChunkPos pos,
                                                 VelocePipeNetworkManager manager) {
        List<String> out = new java.util.ArrayList<>();
        for (VelocePipeNetwork net : manager.getAllNetworks(level)) {
            for (BlockPos p : net.getTerminals()) {
                if ((p.getX() >> 4) == pos.x && (p.getZ() >> 4) == pos.z) {
                    out.add("wezel [" + p.getX() + ", " + p.getY() + ", " + p.getZ()
                            + "] siec " + net.getId().toString().substring(0, 8));
                }
            }
            for (BlockPos p : net.getEndpoints().keySet()) {
                if ((p.getX() >> 4) == pos.x && (p.getZ() >> 4) == pos.z) {
                    out.add("magazyn [" + p.getX() + ", " + p.getY() + ", " + p.getZ() + "]");
                }
            }
        }
        return out;
    }
}
