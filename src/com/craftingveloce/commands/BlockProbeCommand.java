package com.craftingveloce.commands;

import com.craftingveloce.block.entity.VeloceModuleInfoSource;
import com.craftingveloce.network.pipe.VeloceNetworkNode;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * {@code /cv block} - statistics of the block you are looking at.
 *
 * <p>Player: "I have a creative motor that theoretically gives 256 rotation, and
 * the module shows not enough. Add a command that shows the statistics of the
 * block I am looking at - how much speed it is missing and everything about that
 * block".
 *
 * <p>It takes the data from the core {@link VeloceModuleInfoSource#moduleInfo} -
 * the same one that fills the machine window and Jade. That is why the command
 * works for Create, Mekanism and Alchemistry machines without knowing a single
 * type from those mods.
 */
public final class BlockProbeCommand {

    /** "Look" reach - as in vanilla (creative reach is 5, survival 4.5). */
    private static final double REACH = 8.0;

    private BlockProbeCommand() {
    }

    /** Prints everything we know about the block under the crosshair. */
    public static int describe(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command only works for a player."));
            return 0;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        BlockHitResult hit = (BlockHitResult) player.pick(REACH, 0.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendSuccess(() -> Component.literal("§eLook at some block."), false);
            return 0;
        }
        BlockPos pos = hit.getBlockPos();
        BlockEntity be = level.getBlockEntity(pos);
        line(source, ChatFormatting.GOLD, "=== [Veloce] block " + pos.toShortString() + " ===");
        line(source, ChatFormatting.GRAY, "block: " + level.getBlockState(pos).getBlock()
                + "  state: " + level.getBlockState(pos));
        line(source, ChatFormatting.GRAY, "block entity: "
                + (be == null ? "none" : be.getClass().getSimpleName())
                + (be instanceof VeloceNetworkNode ? "  [pipe network node]" : ""));

        if (be instanceof VeloceModuleInfoSource module) {
            describeModule(source, module.moduleInfo(level));
        } else {
            line(source, ChatFormatting.YELLOW,
                    "This is not one of our machines - no module statistics.");
        }
        return 1;
    }

    /** Machine statistics: speed and HOW MUCH IS MISSING, SU, parts, energy, network. */
    private static void describeModule(CommandSourceStack source, CompoundTag info) {
        if (info.contains("speed")) {
            float speed = info.getFloat("speed");
            int required = info.getInt("requiredSpeed");
            float missing = required - speed;
            line(source, ChatFormatting.AQUA, "--- kinetic (Create) ---");
            line(source, ChatFormatting.WHITE, "speed: " + fmt(speed)
                    + " RPM / required " + fmt(required) + " RPM");
            line(source, missing > 0 ? ChatFormatting.RED : ChatFormatting.GREEN,
                    missing > 0
                            ? "MISSING: " + fmt(missing) + " RPM"
                            : "speed is sufficient (with a margin of " + fmt(-missing) + " RPM)");
            line(source, ChatFormatting.YELLOW, "SU draw: " + fmt(info.getFloat("suDraw"))
                    + "  (module demands " + fmt(info.getFloat("suNeeded")) + " SU)");
            line(source, ChatFormatting.YELLOW, "kinetic network: stress "
                    + fmt(info.getFloat("suStress")) + " / capacity "
                    + fmt(info.getFloat("suCapacity")));
            line(source, ChatFormatting.GRAY, "clicked-in parts: " + info.getInt("parts"));
        }
        if (info.contains("energy")) {
            long energy = info.getLong("energy");
            long capacity = Math.max(1L, info.getLong("energyCapacity"));
            line(source, ChatFormatting.AQUA, "--- energy (FE) ---");
            line(source, ChatFormatting.WHITE, "energy: "
                    + com.craftingveloce.util.VeloceFormat.feCompact(energy) + " / "
                    + com.craftingveloce.util.VeloceFormat.feCompact(capacity) + " FE ("
                    + fmt((float) (100.0 * energy / capacity)) + "%)");
            line(source, info.getBoolean("powered") ? ChatFormatting.GREEN : ChatFormatting.RED,
                    "cycle cost: "
                            + com.craftingveloce.util.VeloceFormat.feCompact(
                                    info.getLong("fePerOperation"))
                            + " FE  ->  cycles: "
                            + com.craftingveloce.util.VeloceFormat.compact(
                                    info.getLong("operations"))
                            + (info.getBoolean("powered") ? "  [powered]" : "  [NO POWER]"));
        }
        if (info.contains("networkNodes")) {
            line(source, ChatFormatting.GRAY, "pipe network: nodes " + info.getInt("networkNodes")
                    + ", storage " + info.getInt("networkStorages")
                    + ", item types "
                    + com.craftingveloce.util.VeloceFormat.compact(info.getInt("networkItems")));
        }
    }

    /** Numbers only through the shared formatter (otherwise tooltips drift in style). */
    private static String fmt(float value) {
        return com.craftingveloce.util.VeloceFormat.rate(value);
    }

    private static String fmt(int value) {
        return com.craftingveloce.util.VeloceFormat.compact(value);
    }

    private static void line(CommandSourceStack source, ChatFormatting color, String text) {
        source.sendSuccess(() -> Component.literal(text).withStyle(color), false);
    }
}
