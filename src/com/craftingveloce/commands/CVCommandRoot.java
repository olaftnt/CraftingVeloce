package com.craftingveloce.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * The common root of the {@code /cv} commands - the ONE place that decides about
 * permissions.
 *
 * <p><b>Why a separate class, and not {@code Commands.literal("cv")} in every
 * file.</b> The {@code /cv} commands are registered in THREE files
 * ({@code /cv debug}, {@code /cv testnet}, {@code /cv trace}) and Brigadier
 * merges them into one node named {@code cv}. If each file declared its own
 * access condition, the nodes might fail to merge or (worse) one of them would
 * silently decide the access for all of them - and we would only find out after
 * the command stopped working or started working too broadly. That is exactly
 * the class of bug that has come up many times in this project: the same rule
 * written by hand in several places, which drifts apart over time.
 *
 * <p><b>Why a permission requirement at all.</b> Previously none of these
 * commands had {@code requires(...)}, which means that by default ALL players on
 * the server could invoke them. And {@code /cv testnet} forces a rebuild of the
 * pipe network, {@code /cv chunk cleanup} changes the state of the world, and
 * {@code /cv trace} enables detailed logging - those are administrative
 * operations, not for everyone.
 *
 * <p>Level 2 is the vanilla standard for administrative commands (e.g.
 * {@code /gamemode}). In singleplayer the world owner always has it when the
 * world has cheats enabled - and without that he would have no way to use his
 * own diagnostics.
 */
public final class CVCommandRoot {

    private CVCommandRoot() {
    }

    /** Permission level required for the {@code /cv} commands (2 = operator/cheats). */
    public static final int REQUIRED_PERMISSION_LEVEL = 2;

    /** The {@code /cv} root with the permission requirement. Use THIS, not your own literal(). */
    public static LiteralArgumentBuilder<CommandSourceStack> root() {
        return Commands.literal("cv").requires(CVCommandRoot::mayUse);
    }

    public static boolean mayUse(CommandSourceStack source) {
        return source.hasPermission(REQUIRED_PERMISSION_LEVEL);
    }
}
