package com.craftingveloce.commands;

import com.craftingveloce.debug.ChunkTrace;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * JEDNA komenda odpalajaca cale debugowanie chunkow i sieci.
 *
 * <p><b>Zasada: nic nie idzie na czat.</b> Caly raport trafia do konsoli
 * i do pliku {@code logs/latest.log} pod prefiksem {@code [Veloce][CHUNKTRACE]}.
 * Na czacie pojawia sie tylko krotkie potwierdzenie, ze sledzenie dziala -
 * bez tego nie byloby wiadomo, czy komenda w ogole sie wykonala.
 *
 * <p><b>Co obejmuje jedna komenda:</b>
 * <ul>
 *   <li>wlaczenie sledzenia z nowa sesja (identyfikator w kazdej linii),</li>
 *   <li>wlaczenie monitora rozladowan chunkow,</li>
 *   <li>wlaczenie raportu operacji na niezaladowanych chunkach,</li>
 *   <li>zrzut stanu WSZYSTKICH sieci: chunki, wezly, magazyny, wymuszenia,</li>
 *   <li>zrzut zawartosci sieci z rozbiciem na endpointy (cache vs swiezy skan).</li>
 * </ul>
 */
public final class CVTraceCommand {

    private CVTraceCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("cv")
                .then(Commands.literal("trace")
                    // Bez argumentu: przelacza. To ma byc jedna komenda.
                    .executes(CVTraceCommand::toggle)
                    .then(Commands.literal("on")
                        .executes(ctx -> setTrace(ctx, true)))
                    .then(Commands.literal("off")
                        .executes(ctx -> setTrace(ctx, false)))
                    // Zrzut na zadanie - bez zmieniania stanu sledzenia.
                    .then(Commands.literal("dump")
                        .executes(CVTraceCommand::dump)))
        );
    }

    /** Przelacza sledzenie (wlacza/wylacza). */
    private static int toggle(CommandContext<CommandSourceStack> context) {
        return setTrace(context, !ChunkTrace.isEnabled());
    }

    /**
     * Wlacza lub wylacza sledzenie.
     *
     * <p>Przy wlaczeniu robi OD RAZU pelny zrzut stanu - dzieki temu w logu
     * jest punkt odniesienia ("jak bylo na starcie"), z ktorym mozna porownac
     * pozniejsze wpisy.
     */
    private static int setTrace(CommandContext<CommandSourceStack> context, boolean on) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();

        if (!on) {
            ChunkTrace.event("TRACE", "wylaczanie sledzenia na zadanie gracza");
            ChunkTrace.stop();
            com.craftingveloce.debug.ChunkDebugNotifier.setEnabled(false);
            com.craftingveloce.debug.ChunkOpNotifier.setEnabled(false);
            // Jedyne wyjscie na czat - potwierdzenie dla gracza.
            source.sendSuccess(() -> Component.literal(
                    "§8[§6Veloce§8] trace: §cOFF §7(szczegoly byly w konsoli)"), false);
            return 1;
        }

        String session = ChunkTrace.start(level);
        com.craftingveloce.debug.ChunkDebugNotifier.setEnabled(true);
        com.craftingveloce.debug.ChunkOpNotifier.setEnabled(true);

        ChunkTrace.event("TRACE", "wlaczone przez %s", source.getTextName());
        dumpAll(level, "START");

        source.sendSuccess(() -> Component.literal(
                "§8[§6Veloce§8] trace: §aON §7sesja §f" + session), false);
        source.sendSuccess(() -> Component.literal(
                "§7Wszystko leci do konsoli: §flogs/latest.log§7, szukaj §f[CHUNKTRACE]"), false);
        return 1;
    }

    /** Zrzut na zadanie, bez zmiany stanu. */
    private static int dump(CommandContext<CommandSourceStack> context) {
        if (!ChunkTrace.isEnabled()) {
            context.getSource().sendFailure(Component.literal(
                    "§cSledzenie wylaczone. Wlacz: §f/cv trace"));
            return 0;
        }
        dumpAll(context.getSource().getLevel(), "DUMP");
        context.getSource().sendSuccess(() -> Component.literal(
                "§7Zrzut zapisany w konsoli (§f[CHUNKTRACE]§7)."), false);
        return 1;
    }

    /** Zrzut wszystkich sieci w danym wymiarze. */
    private static void dumpAll(ServerLevel level, String label) {
        var manager = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(level);
        var networks = manager.getAllNetworks();

        ChunkTrace.event("DUMP", "%s: sieci=%d wymiar=%s", label,
                networks.size(), level.dimension().location());

        if (networks.isEmpty()) {
            ChunkTrace.event("DUMP", "%s: brak sieci w tym wymiarze", label);
            return;
        }
        for (var net : networks) {
            ChunkTrace.snapshotChunks(label, level, net);
            ChunkTrace.snapshotStock(label, level, net);
        }
    }
}
