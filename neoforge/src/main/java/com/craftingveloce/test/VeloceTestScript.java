package com.craftingveloce.test;

import com.craftingveloce.util.VeloceLog;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a script of in-game commands against a live world and checks the results.
 *
 * <p><b>Why this exists.</b> The validators in {@code scripts/build.py} read the
 * source as text, so they can only prove that a line of code is <em>present</em> -
 * not that it does anything. They cannot tell whether a machine actually crafts,
 * whether a block drops, or whether an ingredient is really pulled out of a
 * chest. This runner drives the real server and reads what the commands actually
 * report.
 *
 * <p><b>How a script looks.</b> Plain text, one directive per line; {@code #}
 * starts a comment and blank lines are ignored.
 *
 * <pre>
 *   # place every block, then check the network built
 *   /cv showcase pipes
 *   expect: Veloce showcase: placed
 *   /cv testnet build
 *   expect: network
 * </pre>
 *
 * <p>A line starting with {@code /} is executed as a command. A line starting
 * with {@code expect:} asserts that the output of the commands since the last
 * {@code expect:} contains the given text (case-insensitive). {@code expectnot:}
 * is the negative form.
 *
 * <p><b>Output capture.</b> The commands are executed through a
 * {@link CommandSourceStack} whose {@link CommandSource} collects everything the
 * command reports. That is why this works without parsing the game log, and why
 * it also catches the failure replies ({@code sendFailure}) that never reach a
 * player who is not looking at chat.
 */
public final class VeloceTestScript {

    /** What one line of the script asked for. */
    public enum Kind { COMMAND, EXPECT, EXPECT_NOT }

    /** One parsed directive. */
    public record Step(Kind kind, String text, int line) {
    }

    /** The result of running a whole script. */
    public record Result(String name, int passed, int failed, List<String> failures) {
        public boolean ok() {
            return failed == 0;
        }
    }

    private VeloceTestScript() {
    }

    /** Parses a script; throws IllegalArgumentException naming the bad line. */
    public static List<Step> parse(String script) {
        List<Step> steps = new ArrayList<>();
        int lineNo = 0;
        for (String raw : script.split("\n")) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("expectnot:")) {
                steps.add(new Step(Kind.EXPECT_NOT, line.substring("expectnot:".length()).strip(), lineNo));
            } else if (line.startsWith("expect:")) {
                steps.add(new Step(Kind.EXPECT, line.substring("expect:".length()).strip(), lineNo));
            } else if (line.startsWith("/")) {
                steps.add(new Step(Kind.COMMAND, line, lineNo));
            } else {
                throw new IllegalArgumentException(
                        "script line " + lineNo + ": expected '/command', 'expect:' or 'expectnot:', got: " + line);
            }
        }
        return steps;
    }

    /**
     * Runs a parsed script as {@code player}.
     *
     * @param origin where the script's commands are executed from; commands that
     *               need a player position use the player, so this is normally
     *               the player's own position
     */
    public static Result run(String name, List<Step> steps, MinecraftServer server, ServerPlayer player) {
        List<String> failures = new ArrayList<>();
        int assertions = 0;
        int passed = 0;
        StringBuilder output = new StringBuilder();

        for (Step step : steps) {
            if (step.kind() == Kind.COMMAND) {
                String text = execute(server, player, step.text());
                output.append(text).append('\n');
                VeloceLog.Block.attempt(VeloceLog.Side.SERVER, "[test] %s", step.text());
                continue;
            }

            assertions++;
            String haystack = output.toString().toLowerCase();
            String needle = step.text().toLowerCase();
            boolean found = haystack.contains(needle);
            boolean want = step.kind() == Kind.EXPECT;

            if (found == want) {
                passed++;
            } else {
                failures.add("line " + step.line()
                        + (want ? ": expected to find \"" : ": expected NOT to find \"")
                        + step.text() + "\"");
            }
            // Only the commands since the last assertion are checked, so one
            // expectation cannot be satisfied by output from an earlier step.
            output.setLength(0);
        }
        return new Result(name, passed, assertions - passed, failures);
    }

    /**
     * Executes one command and returns everything it reported.
     *
     * <p>The source is positioned at the player, because several {@code /cv}
     * commands build relative to the source - that is how this runner drives them
     * without walking the player around.
     */
    private static String execute(MinecraftServer server, ServerPlayer player, String command) {
        StringBuilder captured = new StringBuilder();
        CommandSource collector = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                captured.append(component.getString()).append('\n');
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };

        ServerLevel level = player.serverLevel();
        CommandSourceStack stack = new CommandSourceStack(
                collector,
                Vec3.atCenterOf(player.blockPosition()),
                Vec2.ZERO,
                level,
                4,                    // permission level: the test runs as an operator
                "veloce-test",
                Component.literal("veloce-test"),
                server,
                player);

        try {
            server.getCommands().performPrefixedCommand(stack, command);
        } catch (Throwable t) {
            captured.append("command threw: ").append(t).append('\n');
            VeloceLog.Block.error(VeloceLog.Side.SERVER, t, "[test] command failed: %s", command);
        }
        return captured.toString();
    }
}
