package com.craftingveloce.commands;

import com.craftingveloce.debug.ChunkTrace;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * ONE command that turns on all chunk and network debugging.
 *
 * <p><b>Rule: nothing goes to the chat.</b> The whole report goes to the console
 * and to the file {@code logs/latest.log} under the prefix
 * {@code [Veloce][CHUNKTRACE]}. The chat only shows a short confirmation that
 * tracing is working - without it there would be no way to tell whether the
 * command ran at all.
 *
 * <p><b>What one command covers:</b>
 * <ul>
 *   <li>enabling tracing with a new session (an identifier in every line),</li>
 *   <li>enabling the chunk unload monitor,</li>
 *   <li>enabling the report of operations on unloaded chunks,</li>
 *   <li>a dump of the state of ALL networks: chunks, nodes, storage, force-loads,</li>
 *   <li>a dump of network contents broken down by endpoint (cache vs fresh scan).</li>
 * </ul>
 */
public final class CVTraceCommand {

    private CVTraceCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            CVCommandRoot.root()
                .then(Commands.literal("trace")
                    // Without an argument: it toggles. This is meant to be one command.
                    .executes(CVTraceCommand::toggle)
                    .then(Commands.literal("on")
                        .executes(ctx -> setTrace(ctx, true)))
                    .then(Commands.literal("off")
                        .executes(ctx -> setTrace(ctx, false)))
                    // Dump on demand - without changing the tracing state.
                    .then(Commands.literal("dump")
                        .executes(CVTraceCommand::dump)))
        );
    }

    /** Toggles tracing (on/off). */
    private static int toggle(CommandContext<CommandSourceStack> context) {
        return setTrace(context, !ChunkTrace.isEnabled());
    }

    /**
     * Enables or disables tracing.
     *
     * <p>When enabling, it does a full state dump RIGHT AWAY - thanks to that the
     * log has a reference point ("how it was at the start") to compare later
     * entries against.
     */
    private static int setTrace(CommandContext<CommandSourceStack> context, boolean on) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();

        if (!on) {
            ChunkTrace.event("TRACE", "disabling tracing at the player's request");
            ChunkTrace.stop();
            com.craftingveloce.debug.ChunkDebugNotifier.setEnabled(false);
            com.craftingveloce.debug.ChunkOpNotifier.setEnabled(false);
            // The only chat output - a confirmation for the player.
            source.sendSuccess(() -> Component.literal(
                    "§8[§6Veloce§8] trace: §cOFF §7(details were in the console)"), false);
            return 1;
        }

        String session = ChunkTrace.start(level);
        com.craftingveloce.debug.ChunkDebugNotifier.setEnabled(true);
        com.craftingveloce.debug.ChunkOpNotifier.setEnabled(true);

        ChunkTrace.event("TRACE", "enabled by %s", source.getTextName());
        dumpAll(level, "START");

        source.sendSuccess(() -> Component.literal(
                "§8[§6Veloce§8] trace: §aON §7session §f" + session), false);
        source.sendSuccess(() -> Component.literal(
                "§7Everything goes to the console: §flogs/latest.log§7, look for §f[CHUNKTRACE]"), false);
        return 1;
    }

    /** Dump on demand, without changing the state. */
    private static int dump(CommandContext<CommandSourceStack> context) {
        if (!ChunkTrace.isEnabled()) {
            context.getSource().sendFailure(Component.literal(
                    "§cTracing is off. Enable it: §f/cv trace"));
            return 0;
        }
        dumpAll(context.getSource().getLevel(), "DUMP");
        context.getSource().sendSuccess(() -> Component.literal(
                "§7Dump saved to the console (§f[CHUNKTRACE]§7)."), false);
        return 1;
    }

    /** Dump of all networks in the given dimension. */
    private static void dumpAll(ServerLevel level, String label) {
        var manager = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(level);
        var networks = manager.getAllNetworks(level);

        ChunkTrace.event("DUMP", "%s: networks=%d dimension=%s", label,
                networks.size(), level.dimension().location());

        if (networks.isEmpty()) {
            ChunkTrace.event("DUMP", "%s: no networks in this dimension", label);
            return;
        }
        for (var net : networks) {
            ChunkTrace.snapshotChunks(label, level, net);
            ChunkTrace.snapshotStock(label, level, net);
        }
    }
}
