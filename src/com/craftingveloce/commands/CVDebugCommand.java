package com.craftingveloce.commands;

import com.craftingveloce.block.entity.VelocePipeBlockEntity;
import com.craftingveloce.block.entity.VeloceTomTerminalBlockEntity;
import com.craftingveloce.network.pipe.ConnectedEndpointInfo;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Map;

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
        BlockPos pos = blockHit.getBlockPos();
        BlockEntity be = player.level().getBlockEntity(pos);
        ServerLevel sl = player.serverLevel();

        if (be instanceof VeloceTomTerminalBlockEntity terminalBE) {
            terminalBE.printDebugInfo(player);
            return 1;
        } else if (be instanceof VelocePipeBlockEntity) {
            VelocePipeNetworkManager manager = VelocePipeNetworkManager.get(sl);
            VelocePipeNetwork net = manager.getNetworkForPipe(pos);
            player.sendSystemMessage(Component.literal("§6=== [CraftingVeloce Pipe Debug] ==="));
            player.sendSystemMessage(Component.literal("§7Pipe Pos: §f[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]"));
            if (net == null) {
                player.sendSystemMessage(Component.literal("§cPipe is not currently assigned to any active network."));
            } else {
                player.sendSystemMessage(Component.literal("§aNetwork ID: §e" + net.getId()));
                player.sendSystemMessage(Component.literal("§7Pipes in network: §f" + net.getPipes().size() + "§7, Terminals: §f" + net.getTerminals().size()));
                player.sendSystemMessage(Component.literal("§bTracked Chunks (" + net.getTrackedChunks().size() + "):"));
                for (ChunkPos cp : net.getTrackedChunks()) {
                    boolean loaded = sl.isLoaded(cp.getWorldPosition());
                    player.sendSystemMessage(Component.literal("  §7Chunk [" + cp.x + ", " + cp.z + "]: " + (loaded ? "§a[LOADED]" : "§c[UNLOADED]")));
                }
                player.sendSystemMessage(Component.literal("§bConnected Endpoints (" + net.getEndpoints().size() + "):"));
                for (Map.Entry<BlockPos, ConnectedEndpointInfo> entry : net.getEndpoints().entrySet()) {
                    BlockPos epPos = entry.getKey();
                    ConnectedEndpointInfo ep = entry.getValue();
                    boolean loaded = sl.isLoaded(epPos);
                    player.sendSystemMessage(Component.literal("  §e" + ep.getType() + " §7at [" + epPos.getX() + ", " + epPos.getY() + ", " + epPos.getZ() + "] " + (loaded ? "§a[LOADED]" : "§c[UNLOADED]") + " §7(cached types: §f" + ep.getCachedCounts().size() + "§7)"));
                }
                Map<Item, Long> netCounts = net.getAllItemCounts(sl);
                player.sendSystemMessage(Component.literal("§6Total Network Resources (" + netCounts.size() + " types):"));
                for (Map.Entry<Item, Long> itemEntry : netCounts.entrySet()) {
                    player.sendSystemMessage(Component.literal("  §e" + itemEntry.getKey().getDescription().getString() + " §7x§a" + itemEntry.getValue()));
                }
            }
            player.sendSystemMessage(Component.literal("§6==================================="));
            return 1;
        } else {
            player.sendSystemMessage(Component.literal("§c[CraftingVeloce] Looked-at block is not a Veloce Terminal or Pipe! (Found: " + (be != null ? be.getClass().getSimpleName() : player.level().getBlockState(pos).getBlock().getName().getString()) + ")"));
            return 0;
        }
    }
}
