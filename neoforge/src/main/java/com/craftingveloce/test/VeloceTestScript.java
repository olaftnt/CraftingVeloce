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
import java.util.function.Consumer;

/**
 * Runs a script of in-game commands against a live world and checks the results.
 *
 * <p><b>Why this exists.</b> The validators in {@code scripts/build.py} read the
 * source as text, so they can only prove that a line of code is <em>present</em> -
 * not that it does anything. They cannot tell whether a machine actually crafts
 * or whether a block drops. This runner drives the real server and reads what the
 * commands actually report.
 *
 * <p><b>How a script looks.</b> Plain text, one directive per line; {@code #}
 * starts a comment and blank lines are ignored.
 *
 * <pre>
 *   /cv testmodule furnace
 *   wait: 80
 *   /cv testmodule result
 *   expect: PASS
 * </pre>
 *
 * <ul>
 *   <li>{@code /…} - run a command.</li>
 *   <li>{@code expect:} / {@code expectnot:} - assert on the output of the
 *       commands since the previous assertion, so stale output cannot satisfy a
 *       later check.</li>
 *   <li>{@code wait: N} - let {@code N} ticks pass.</li>
 * </ul>
 *
 * <p><b>Why the runner is tick-driven rather than a single pass.</b> A command
 * executes inside one tick, but the things worth testing take time: a pipe
 * network needs dozens of ticks to scan its surroundings, and an auto-crafter
 * needs another batch of ticks to plan and produce. A synchronous runner could
 * only ever assert on work that was already finished - precisely the work that
 * matters least. {@code wait:} therefore suspends the script and resumes it on a
 * later tick.
 *
 * <p><b>Output capture.</b> Commands run through a {@link CommandSourceStack}
 * whose {@link CommandSource} collects everything reported. That is why no log
 * parsing is needed, and why {@code sendFailure} replies are caught too - a
 * player who is not watching chat never sees those.
 */
public final class VeloceTestScript {

    /** What one line of the script asked for. */
    public enum Kind { COMMAND, EXPECT, EXPECT_NOT, WAIT, EXPECT_ANY }

    /** One parsed directive. */
    public record Step(Kind kind, String text, int line) {
    }

    /** The result of running a whole script. */
    public record Result(String name, int passed, int failed, List<String> failures) {
        public boolean ok() {
            return failed == 0;
        }
    }

    /** State carried while a script is suspended between ticks. */
    private static final class State {
        final String name;
        final List<Step> steps;
        final List<String> failures = new ArrayList<>();
        final StringBuilder output = new StringBuilder();
        int passed;
        int assertions;

        State(String name, List<Step> steps) {
            this.name = name;
            this.steps = steps;
        }

        Result toResult() {
            return new Result(name, passed, assertions - passed, failures);
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
            if (line.startsWith("expectany:")) {
                // Two answers can both be correct, and they mean different things.
                // "PASS or SKIP" is not indecision: SKIP says the machine has no
                // recipe in this pack, so there was nothing to test - a fact about
                // the pack, not about the machine.
                steps.add(new Step(Kind.EXPECT_ANY, line.substring("expectany:".length()).strip(), lineNo));
            } else if (line.startsWith("expectnot:")) {
                steps.add(new Step(Kind.EXPECT_NOT, line.substring("expectnot:".length()).strip(), lineNo));
            } else if (line.startsWith("expect:")) {
                steps.add(new Step(Kind.EXPECT, line.substring("expect:".length()).strip(), lineNo));
            } else if (line.startsWith("wait:")) {
                steps.add(new Step(Kind.WAIT, line.substring("wait:".length()).strip(), lineNo));
            } else if (line.startsWith("/")) {
                steps.add(new Step(Kind.COMMAND, line, lineNo));
            } else {
                throw new IllegalArgumentException(
                        "script line " + lineNo
                                + ": expected '/command', 'expect:', 'expectany:', 'expectnot:' or 'wait:', got: " + line);
            }
        }
        return steps;
    }

    /**
     * Runs a parsed script as {@code player}, calling {@code onDone} when it
     * finishes - which may be many ticks later when the script waits.
     */
    public static void run(String name, List<Step> steps, MinecraftServer server,
                           ServerPlayer player, Consumer<Result> onDone) {
        advance(new State(name, steps), 0, server, player, onDone);
    }

    private static void advance(State state, int index, MinecraftServer server,
                                ServerPlayer player, Consumer<Result> onDone) {
        while (index < state.steps.size()) {
            Step step = state.steps.get(index);

            switch (step.kind()) {
                case COMMAND -> {
                    // The buffer is cleared when a command STARTS, not after every
                    // assertion: several assertions may legitimately describe one
                    // command's output, while output from a PREVIOUS command must
                    // still never satisfy a later check.
                    state.output.setLength(0);
                    state.output.append(execute(server, player, step.text())).append('\n');
                    VeloceLog.Block.attempt(VeloceLog.Side.SERVER, "[test] %s", step.text());
                }
                case WAIT -> {
                    int ticks = parseTicks(step);
                    final int next = index + 1;
                    scheduleWait(server, ticks,
                            () -> advance(state, next, server, player, onDone));
                    return;
                }
                case EXPECT_ANY -> {
                    state.assertions++;
                    String haystack = state.output.toString().toLowerCase();
                    boolean matched = false;
                    for (String alternative : step.text().split("\\|")) {
                        if (haystack.contains(alternative.strip().toLowerCase())) {
                            matched = true;
                            break;
                        }
                    }
                    if (matched) {
                        state.passed++;
                    } else {
                        String seen = state.output.toString().strip();
                        if (seen.length() > 600) {
                            seen = seen.substring(0, 600) + " \u2026";
                        }
                        state.failures.add("line " + step.line()
                                + ": expected any of \"" + step.text() + "\""
                                + (seen.isEmpty() ? " (no output)" : " | saw: " + seen.replace("\n", " / ")));
                    }
                }
                case EXPECT, EXPECT_NOT -> {
                    state.assertions++;
                    String haystack = state.output.toString().toLowerCase();
                    String needle = step.text().toLowerCase();
                    boolean found = haystack.contains(needle);
                    boolean want = step.kind() == Kind.EXPECT;
                    if (found == want) {
                        state.passed++;
                    } else {
                        // The captured output is part of the failure: without it a
                        // failure only says what was expected, never what the
                        // command actually replied - which is the one thing needed
                        // to fix it.
                        String seen = state.output.toString().strip();
                        if (seen.length() > 600) {
                            seen = seen.substring(0, 600) + " …";
                        }
                        state.failures.add("line " + step.line()
                                + (want ? ": expected to find \"" : ": expected NOT to find \"")
                                + step.text() + "\""
                                + (seen.isEmpty() ? " (no output)" : " | saw: " + seen.replace("\n", " / ")));
                    }
                }
            }
            index++;
        }
        onDone.accept(state.toResult());
    }

    private static int parseTicks(Step step) {
        try {
            return Math.max(0, Math.min(20 * 60, Integer.parseInt(step.text())));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "script line " + step.line() + ": wait: needs a number of ticks, got: " + step.text());
        }
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

    /**
     * Waits that have not come due yet.
     *
     * <p><b>Why a wait is NOT {@code server.tell(new TickTask(...))}.</b> That is what this
     * used to do, and it never waited once. The driver runs on the server thread, and
     * {@code MinecraftServer} is a reentrant blockable event loop: a task submitted from
     * the thread that owns the loop runs IMMEDIATELY, and the tick number baked into the
     * {@code TickTask} is never consulted.
     *
     * <p>The bug was invisible because a script that skips its waits still runs to
     * completion - it simply measures a world that never moved. It was finally caught by
     * timing a {@code wait: 200} with a wall clock: two milliseconds passed instead of ten
     * seconds. Everything downstream of a wait had been reading the state of the world as
     * it was when the command was typed, which is why a machine bolted to a spinning shaft
     * reported 0 RPM - Create had had no tick in which to propagate rotation.
     *
     * <p>Waits are therefore pumped from a real server tick, which the thread the script
     * happens to run on cannot short-circuit.
     */
    private static final List<Pending> PENDING = new ArrayList<>();

    /** One suspended script, due at a given server tick. */
    private record Pending(long dueTick, Runnable action) {
    }

    /**
     * Starts the pump. Called once during setup.
     *
     * <p>Cheap when idle: the listener does nothing until a script actually waits.
     */
    public static void installWaitPump() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class,
                event -> pumpWaits(event.getServer()));
    }

    /**
     * Resumes every wait that has come due. Called once per server tick.
     *
     * <p>The due waits are collected before any of them runs, because a resumed script may
     * suspend again immediately and would otherwise be adding to the list under iteration.
     */
    private static void pumpWaits(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        List<Pending> due = new ArrayList<>();
        for (Pending pending : PENDING) {
            if (now >= pending.dueTick()) {
                due.add(pending);
            }
        }
        if (due.isEmpty()) {
            return;
        }
        PENDING.removeAll(due);
        for (Pending pending : due) {
            pending.action().run();
        }
    }

    /**
     * Runs {@code action} after {@code ticks} real server ticks.
     *
     * <p>Public because {@code CVModuleTestCommand} carried the same defect in its scan
     * delay: it judged a crafting network in the very tick it built it, before the scan it
     * was nominally waiting for had run.
     */
    public static void scheduleWait(MinecraftServer server, int ticks, Runnable action) {
        if (ticks <= 0) {
            action.run();
            return;
        }
        PENDING.add(new Pending(server.getTickCount() + ticks, action));
    }
}
