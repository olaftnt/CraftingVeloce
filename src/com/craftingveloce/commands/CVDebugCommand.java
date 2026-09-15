package com.craftingveloce.commands;

import com.craftingveloce.block.entity.VeloceTomTerminalBlockEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class CVDebugCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("cv")
                .then(Commands.literal("debug")
                    .executes(CVDebugCommand::executeDebug))
        );
    }

    private static int executeDebug(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("This command can only be run by a player."));
            return 0;
        }

        HitResult hit = player.pick(20.0D, 0.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            player.sendSystemMessage(Component.literal("§c[CraftingVeloce] You are not looking at any block!"));
            return 0;
        }

        BlockHitResult blockHit = (BlockHitResult) hit;
        BlockEntity be = player.level().getBlockEntity(blockHit.getBlockPos());
        if (be instanceof VeloceTomTerminalBlockEntity terminalBE) {
            terminalBE.printDebugInfo(player);
            return 1;
        } else {
            player.sendSystemMessage(Component.literal("§c[CraftingVeloce] Looked-at block is not a Veloce Terminal! (Found: " + (be != null ? be.getClass().getSimpleName() : player.level().getBlockState(blockHit.getBlockPos()).getBlock().getName().getString()) + ")"));
            return 0;
        }
    }
}
