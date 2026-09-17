package com.craftingveloce.test;

import com.craftingveloce.commands.CVCommandRoot;
import com.craftingveloce.util.VeloceLog;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Drives the in-game test scripts.
 *
 * <p><b>The gap this fills.</b> The guards in {@code scripts/build.py} read the
 * sources as text, so they prove a line is <em>present</em>, never that it
 * <em>works</em>. Nothing in the project started a world, placed a machine and
 * asked whether it actually crafted. This runs the real server and checks what
 * the commands report.
 *
 * <p><b>Two ways to run a script.</b>
 * <ul>
 *   <li><b>Live, in game</b> - {@code /cv test run <name>} or {@code /cv test list}.
 *       Nothing has to be restarted, which is what makes it usable while
 *       iterating: build the jar, run the client, type one command.</li>
 *   <li><b>Automatically</b> - start the game with
 *       {@code -Dveloce.test.script=<path to script>}. On the first player join
 *       the script runs, the result is printed, and the game exits with a
 *       non-zero code when anything failed. That is the form a CI job would use.</li>
 * </ul>
 *
 * <p>Scripts live in {@code <game dir>/veloce-tests/<name>.txt}. The format is
 * documented in {@link VeloceTestScript}.
 *
 * <p><b>Why the player is needed.</b> Several {@code /cv} commands build relative
 * to the player and use their look direction to pick block states, so a headless
 * dedicated server would place blocks with default states and prove much less.
 * The runner therefore drives those commands exactly as a player would, which
 * also means it exercises the same code path a player does.
 */
public final class VeloceTestDriver {

    /** System property naming a script to run automatically, then exit. */
    public static final String SCRIPT_PROPERTY = "veloce.test.script";

    /** Directory (relative to the game dir) holding the scripts. */
    public static final String SCRIPT_DIR = "veloce-tests";

    /**
     * Where the result of an automatic run is written.
     *
     * <p><b>Why a file and not just the process exit code.</b> Minecraft does not
     * exit with the test's status - an automated run that PASSES still ended with
     * exit code 255, because that is simply how the client shuts down. A CI job
     * reading the exit code would fail on every green run. The build task
     * therefore ignores the process status and reads this file instead.
     */
    public static final String RESULT_FILE = "veloce-test-result.txt";

    private static boolean autoRunDone;

    private VeloceTestDriver() {
    }

    /** Registers {@code /cv test ...}. Called from the command registration. */
    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(CVCommandRoot.root()
                .then(Commands.literal("test")
                        .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                        .then(Commands.literal("run")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((ctx, builder) ->
                                                SharedSuggestionProvider.suggest(availableNames(), builder))
                                        .executes(ctx -> run(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))));
    }

    /** Wires the automatic run-on-join. Called once during mod setup. */
    public static void installAutoRun() {
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, event -> {
            String script = System.getProperty(SCRIPT_PROPERTY);
            if (script == null || script.isBlank() || autoRunDone) {
                return;
            }
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            autoRunDone = true;
            // A short delay: the world is still settling when the player joins
            // (chunks loading, block entities appearing), and a script that runs
            // into that would report failures that are not real.
            player.server.execute(() -> runAutomatically(player, Path.of(script)));
        });
    }

    private static int list(CommandSourceStack source) {
        List<String> names = availableNames();
        if (names.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "§7No scripts found in " + scriptsDir() + " (create <name>.txt there)"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§6Veloce test scripts:"), false);
        for (String name : names) {
            source.sendSuccess(() -> Component.literal("  §f" + name), false);
        }
        return names.size();
    }

    private static int run(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§c/cv test run needs a player (the commands build relative to one)"));
            return 0;
        }
        Path path = scriptsDir().resolve(name + ".txt");
        if (!Files.isRegularFile(path)) {
            source.sendFailure(Component.literal("§cNo such script: " + path));
            return 0;
        }
        try {
            VeloceTestScript.Result result = execute(path, player.server, player);
            report(source, result);
            return result.ok() ? result.passed() : 0;
        } catch (IOException e) {
            source.sendFailure(Component.literal("§cCannot read " + path + ": " + e.getMessage()));
            return 0;
        }
    }

    private static void runAutomatically(ServerPlayer player, Path path) {
        MinecraftServer server = player.server;
        VeloceLog.Block.attempt(VeloceLog.Side.SERVER, "[test] auto-run: %s", path);
        VeloceTestScript.Result result;
        try {
            result = execute(path, server, player);
        } catch (IOException e) {
            VeloceLog.Block.error(VeloceLog.Side.SERVER, e, "[test] cannot read %s", path);
            server.halt(false);
            return;
        }

        // The result goes to the log as well as to chat, because a headless run
        // is read from the log.
        VeloceLog.Block.attempt(VeloceLog.Side.SERVER,
                "[test] %s: %d passed, %d failed", result.name(), result.passed(), result.failed());
        for (String failure : result.failures()) {
            VeloceLog.Block.error(VeloceLog.Side.SERVER, null, "[test] FAIL %s", failure);
        }
        report(player.createCommandSourceStack(), result);

        writeResultFile(result);
        if (result.ok()) {
            VeloceLog.Block.attempt(VeloceLog.Side.SERVER, "[test] RESULT: PASS");
        } else {
            VeloceLog.Block.error(VeloceLog.Side.SERVER, null, "[test] RESULT: FAIL");
        }
        server.halt(false);
    }

    /** Writes PASS/FAIL and every failure line, for the build task to read. */
    private static void writeResultFile(VeloceTestScript.Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.ok() ? "PASS" : "FAIL").append('\n');
        sb.append("script: ").append(result.name()).append('\n');
        sb.append("passed: ").append(result.passed()).append('\n');
        sb.append("failed: ").append(result.failed()).append('\n');
        for (String failure : result.failures()) {
            sb.append("failure: ").append(failure).append('\n');
        }
        try {
            Files.writeString(Path.of(RESULT_FILE), sb.toString());
        } catch (IOException e) {
            VeloceLog.Block.error(VeloceLog.Side.SERVER, e, "[test] cannot write %s", RESULT_FILE);
        }
    }

    private static VeloceTestScript.Result execute(Path path, MinecraftServer server, ServerPlayer player)
            throws IOException {
        String text = Files.readString(path);
        String name = path.getFileName().toString().replace(".txt", "");
        return VeloceTestScript.run(name, VeloceTestScript.parse(text), server, player);
    }

    private static void report(CommandSourceStack source, VeloceTestScript.Result result) {
        String head = (result.ok() ? "§a" : "§c")
                + "test '" + result.name() + "': " + result.passed() + " passed, " + result.failed() + " failed";
        source.sendSuccess(() -> Component.literal(head), false);
        for (String failure : result.failures()) {
            source.sendSuccess(() -> Component.literal("  §c" + failure), false);
        }
    }

    /** Script names (without .txt) found in the scripts directory. */
    public static List<String> availableNames() {
        Path dir = scriptsDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = new ArrayList<>();
            files.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .map(p -> p.getFileName().toString().replace(".txt", ""))
                    .sorted()
                    .forEach(names::add);
            return names;
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Where the scripts are read from, shown in error messages. */
    public static Path scriptsDir() {
        return Path.of(SCRIPT_DIR);
    }
}
