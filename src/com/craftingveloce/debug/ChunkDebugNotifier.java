package com.craftingveloce.debug;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

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

    /** Czy pokazywac liste blokow w chunku. */
    private static boolean verbose = true;

    private ChunkDebugNotifier() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isVerbose() {
        return verbose;
    }

    public static void setVerbose(boolean value) {
        verbose = value;
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
}
