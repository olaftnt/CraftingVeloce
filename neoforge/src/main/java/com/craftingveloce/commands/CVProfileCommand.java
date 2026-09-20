package com.craftingveloce.commands;

import com.craftingveloce.config.VeloceConfig;
import com.craftingveloce.util.VeloceProfiler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * The profiler's switch, reachable from the game: {@code /cv profile [on|off|report|reset]}.
 *
 * <p><b>Why a command and not only the config file.</b> The profiler exists for large packs, and
 * a large pack is exactly the situation where restarting the game to change one config line
 * costs minutes - the numbers are wanted at the moment the freeze happens, not after a reload.
 * The config entry remains the persistent switch; this command changes it for the running
 * session and says so, so nobody has to guess whether it took effect.
 *
 * <p><b>Nothing goes to the chat but the answer.</b> Section rows would be unreadable there and
 * the point of the tool is the file: the confirmation names the prefix to look for
 * ({@code [Veloce][PROF]} in {@code logs/latest.log}) and nothing else is printed while
 * profiling runs.
 */
public final class CVProfileCommand {

    private CVProfileCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                CVCommandRoot.root()
                        .then(Commands.literal("profile")
                                // Bare: toggles - one command to type in a hurry.
                                .executes(CVProfileCommand::toggle)
                                .then(Commands.literal("on")
                                        .executes(ctx -> set(ctx, true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> set(ctx, false)))
                                // Prints what is currently held without changing the switch.
                                // Useful when a session is still open and its numbers would
                                // otherwise be lost by the next request opening a new one.
                                .then(Commands.literal("report")
                                        .executes(CVProfileCommand::report))
                                .then(Commands.literal("reset")
                                        .executes(CVProfileCommand::reset))));
    }

    private static int toggle(CommandContext<CommandSourceStack> context) {
        return set(context, !VeloceProfiler.enabled());
    }

    /**
     * Turns profiling on or off.
     *
     * <p>Turning it OFF reports first: a session that is open at that moment holds measurements
     * the player asked for, and dropping them silently would waste the run that produced them.
     */
    private static int set(CommandContext<CommandSourceStack> context, boolean on) {
        CommandSourceStack source = context.getSource();
        if (!on && VeloceProfiler.enabled()) {
            VeloceProfiler.report("profiling switched off by command");
        }
        VeloceConfig.PROFILER_ENABLED.set(on);
        // The profiler caches the switch - see VeloceProfiler.enabled - so the change has to be
        // pushed to it or the command would appear to do nothing until the next game start.
        VeloceProfiler.refreshEnabled();
        // Persist as well, so the answer survives leaving the world: `set` alone only changes
        // the value in memory, and a player who turned the profiler on for a test would find it
        // off again next launch with no explanation.
        try {
            VeloceConfig.SPEC.save();
        } catch (Throwable couldNotSave) {
            // Not fatal: the session still has the switch, which is the point of the command.
            com.craftingveloce.CraftingVeloceMod.LOGGER.warn(
                    "[Veloce][PROF] could not write the profiler switch to the config file: {}",
                    couldNotSave.toString());
        }
        source.sendSuccess(() -> Component.literal(on
                ? "§8[§6Veloce§8] profiler: §aON §7- open the terminal, then read "
                        + "§flogs/latest.log §7for §f[Veloce][PROF]"
                : "§8[§6Veloce§8] profiler: §cOFF"), false);
        return 1;
    }

    private static int report(CommandContext<CommandSourceStack> context) {
        if (!VeloceProfiler.enabled()) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "§8[§6Veloce§8] profiler is §cOFF §7- turn it on with §f/cv profile on"),
                    false);
            return 0;
        }
        VeloceProfiler.report("on demand (/cv profile report)");
        context.getSource().sendSuccess(() -> Component.literal(
                "§8[§6Veloce§8] profiler report written to §flogs/latest.log"), false);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        VeloceProfiler.reset();
        context.getSource().sendSuccess(() -> Component.literal(
                "§8[§6Veloce§8] profiler counters cleared"), false);
        return 1;
    }
}
