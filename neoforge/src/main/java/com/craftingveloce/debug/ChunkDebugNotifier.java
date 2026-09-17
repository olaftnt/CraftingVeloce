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
 * Chat debug: notifies about the loading and unloading of chunks
 * containing Veloce network elements.
 *
 * <p>Goal: testing network behaviour in chunks without keeping them loaded.
 * When a chunk with a chest, crafter, extractor or terminal drops out of the
 * simulation, the player sees on chat exactly what disappeared - and the same
 * on its return.
 *
 * <p>DISABLED by default and independent of the main debug setting in the config.
 * Enable it with the {@code /velocedebug chunk} command.
 */
public final class ChunkDebugNotifier {

    /** Whether to send messages about chunks. */
    private static boolean enabled = false;

    /**
     * Whether to append the list of network blocks lying in the chunk.
     *
     * <p>A constant, not a toggle: the monitor is meant to be ONE command with
     * no subcommands, so there is nothing to toggle this with - and without
     * this list the report is of little use (you cannot tell whether the
     * unloaded chunk concerns us at all).
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
     * Sends the players a message about a chunk change.
     *
     * @param loaded         true = loaded, false = unloaded
     * @param affectedThings descriptions of the blocks located in this chunk
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

    /** A general message (e.g. when the debug is enabled). */
    public static void announce(ServerPlayer player, String message) {
        player.displayClientMessage(
                Component.literal("§8[§6Veloce§8] §7" + message), false);
    }

    /**
     * An immediate message about an ACTUAL chunk unload.
     *
     * <p>Called directly from the {@code ChunkEvent.Unload} event, so it shows
     * the unloads that really happened - and not the theoretical ones computed
     * from the player's distance. That is the whole point of this message:
     * without it there is no way to establish whether the tested area unloads at
     * all.
     *
     * <p>It additionally says whether this chunk was our force-load. If it was,
     * it means the unload happened even though we were holding it - that is, the
     * test is not meaningful anyway and it is worth knowing that right away.
     */
    public static void notifyUnload(ServerLevel level, ChunkPos pos,
                                    VelocePipeNetworkManager manager) {
        if (!enabled) {
            return;
        }

        long chunkKey = ChunkPos.asLong(pos.x, pos.z);
        boolean wasHeld = com.craftingveloce.network.pipe.VeloceChunkLoader
                .isHeld(level, chunkKey);

        // What exactly lay in this chunk - so that we know whether we are testing
        // an area that concerns us at all.
        List<String> things = verbose ? describeContents(level, pos, manager) : List.of();

        // A write to the console - ALWAYS, regardless of whether anyone is
        // watching the chat. This is exactly the proof we are looking for in the
        // log.
        com.craftingveloce.debug.ChunkTrace.event("CHUNK",
                "%s chunk[%d,%d] %s", "UNLOAD", pos.x, pos.z,
                wasHeld ? "was our force-load (!)" : "was not held by us");

        MutableComponent header = Component.literal(
                "§8[§6Veloce§8] §cUNLOAD §7chunk §f" + pos.x + ", " + pos.z
                        + (wasHeld ? " §8(§e! was our force-load§8)" : ""));

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

    /** Descriptions of the network blocks lying in the given chunk. */
    private static List<String> describeContents(ServerLevel level, ChunkPos pos,
                                                 VelocePipeNetworkManager manager) {
        List<String> out = new java.util.ArrayList<>();
        for (VelocePipeNetwork net : manager.getAllNetworks(level)) {
            for (BlockPos p : net.getTerminals()) {
                if ((p.getX() >> 4) == pos.x && (p.getZ() >> 4) == pos.z) {
                    out.add("node [" + p.getX() + ", " + p.getY() + ", " + p.getZ()
                            + "] network " + net.getId().toString().substring(0, 8));
                }
            }
            for (BlockPos p : net.getEndpoints().keySet()) {
                if ((p.getX() >> 4) == pos.x && (p.getZ() >> 4) == pos.z) {
                    out.add("storage [" + p.getX() + ", " + p.getY() + ", " + p.getZ() + "]");
                }
            }
        }
        return out;
    }
}
