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
import net.minecraft.world.item.ItemStack;
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
                .then(Commands.literal("perf")
                    .executes(CVDebugCommand::executePerf))
                .then(Commands.literal("chunkdebug")
                    .executes(ctx -> setChunkDebug(ctx, null))
                    .then(Commands.literal("on")
                        .executes(ctx -> setChunkDebug(ctx, true)))
                    .then(Commands.literal("off")
                        .executes(ctx -> setChunkDebug(ctx, false)))
                    .then(Commands.literal("verbose")
                        .executes(ctx -> setChunkVerbose(ctx, null))
                        .then(Commands.literal("on")
                            .executes(ctx -> setChunkVerbose(ctx, true)))
                        .then(Commands.literal("off")
                            .executes(ctx -> setChunkVerbose(ctx, false))))
                    // Operacje na itemach w niezaladowanych chunkach.
                    .then(Commands.literal("ops")
                        .executes(ctx -> setChunkOps(ctx, null))
                        .then(Commands.literal("on")
                            .executes(ctx -> setChunkOps(ctx, true)))
                        .then(Commands.literal("off")
                            .executes(ctx -> setChunkOps(ctx, false)))))
                // Lista chunkow, ktore trzymamy w pamieci - z powodem i blokiem.
                .then(Commands.literal("chunks")
                    .executes(CVDebugCommand::executeListChunks)
                    .then(Commands.literal("strict")
                        .executes(CVDebugCommand::executeCheckStrict)))
        );
    }

    /**
     * Jednym poleceniem wypisuje wszystko, co potrzebne do diagnozy lagow.
     *
     * <p>Po to, zeby jeden test w grze dawal komplet liczb zamiast zgadywania:
     * ile cache'ow zyje (wyciek?), ile chunkow realnie trzymamy, ile przebudow
     * czeka, jak dlugo trwal ostatni tick cache i czy kiedykolwiek go
     * przekroczylismy.
     */
    private static int executePerf(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel sl = source.getLevel();
        var manager = VelocePipeNetworkManager.get(sl);

        source.sendSuccess(() -> Component.literal("§6=== [CraftingVeloce Perf] ==="), false);
        source.sendSuccess(() -> Component.literal(
                "§7Live crafting caches: §f" + com.craftingveloce.crafting.VeloceCraftingCache.liveCount()),
                false);
        source.sendSuccess(() -> Component.literal(
                "§7Forced chunks (loader, this level): §f"
                        + com.craftingveloce.network.pipe.VeloceChunkLoader.appliedCount(sl)),
                false);
        source.sendSuccess(() -> Component.literal(
                "§7Pending network rebuilds: §f" + manager.pendingRebuildCount()
                        + " §7| networks: §f" + manager.getAllNetworks().size()),
                false);
        source.sendSuccess(() -> Component.literal(
                "§7Ticking server: §fyes §7| gameTime: §f" + sl.getGameTime()), false);
        for (VelocePipeNetwork net : manager.getAllNetworks()) {
            source.sendSuccess(() -> Component.literal(
                    "  §8net §7" + net.getId().toString().substring(0, 8)
                            + " §7pipes=§f" + net.getPipes().size()
                            + " §7endpoints=§f" + net.getEndpoints().size()), false);
        }
        source.sendSuccess(() -> Component.literal("§6======================================="), false);
        return 1;
    }

    /**
     * Wlacza/wylacza debug chunkow na czacie.
     *
     * <p>Bez argumentu przelacza stan. To niezalezne od glownego debugowania
     * w configu - sluzy do testowania zachowania sieci bez ciaglego ladowania
     * chunkow.
     */
    private static int setChunkDebug(CommandContext<CommandSourceStack> context, Boolean value) {
        boolean target = value == null
                ? !com.craftingveloce.debug.ChunkDebugNotifier.isEnabled()
                : value;
        com.craftingveloce.debug.ChunkDebugNotifier.setEnabled(target);

        String state = target ? "§aON" : "§cOFF";
        context.getSource().sendSuccess(() -> Component.literal(
                "§8[§6Veloce§8] chunk debug: " + state), false);
        if (target) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "§7Chunki z elementami sieci beda raportowane na czacie."), false);
        }
        return 1;
    }

    /** Steruje szczegolowoscia raportu (czy listowac bloki). */
    private static int setChunkVerbose(CommandContext<CommandSourceStack> context, Boolean value) {
        boolean target = value == null
                ? !com.craftingveloce.debug.ChunkDebugNotifier.isVerbose()
                : value;
        com.craftingveloce.debug.ChunkDebugNotifier.setVerbose(target);
        String state = target ? "§aON" : "§cOFF";
        context.getSource().sendSuccess(() -> Component.literal(
                "§8[§6Veloce§8] chunk debug verbose: " + state), false);
        return 1;
    }

    /** Wlacza/wylacza raport operacji na niezaladowanych chunkach. */
    private static int setChunkOps(CommandContext<CommandSourceStack> context, Boolean value) {
        boolean target = value == null
                ? !com.craftingveloce.debug.ChunkOpNotifier.isEnabled()
                : value;
        com.craftingveloce.debug.ChunkOpNotifier.setEnabled(target);
        String state = target ? "§aON" : "§cOFF";
        context.getSource().sendSuccess(() -> Component.literal(
                "§8[§6Veloce§8] chunk ops debug: " + state), false);
        if (target) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "§7Operacje na itemach w niezaladowanych chunkach beda raportowane."), false);
        }
        return 1;
    }

    /**
     * Wypisuje liste chunkow trzymanych w pamieci.
     *
     * <p>Dla kazdego chunku: wspolrzedne, siec, blok ktory go trzyma i powod.
     * Wspolrzedne sa KLIKALNE (teleport), bo inaczej taka lista jest bezuzyteczna
     * - nie da sie sprawdzic, co siedzi w srodku.
     */
    private static int executeListChunks(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel sl = source.getLevel();

        var held = com.craftingveloce.network.pipe.VeloceChunkLoader.listHeld(sl);
        source.sendSuccess(() -> Component.literal(
                "§6=== [CraftingVeloce] Trzymane chunki: §f" + held.size()
                        + " §6w §f" + sl.dimension().location() + " §6==="), false);

        if (held.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "§7Brak. Zaden chunk nie jest aktualnie wymuszony."), false);
            return 1;
        }

        var manager = VelocePipeNetworkManager.get(sl);
        for (var hc : held) {
            boolean loaded = sl.isLoaded(new ChunkPos(hc.x(), hc.z()).getWorldPosition());
            source.sendSuccess(() -> Component.literal(
                    "§8- §f[" + hc.x() + ", " + hc.z() + "] "
                            + (loaded ? "§a[SYMULOWANY]" : "§c[POZA SYMULACJA]")
                            + " §7uzyc: §f" + hc.hits()), false);

            if (hc.tickets().isEmpty()) {
                source.sendSuccess(() -> Component.literal(
                        "§8    powod: §7(nieznany - wpis bez biletu)"), false);
                continue;
            }
            for (var t : hc.tickets()) {
                String netId = networkIdAt(manager, t.ownerPos());
                source.sendSuccess(() -> Component.literal(
                        "§8    siec: §e" + netId
                                + " §8| powod: §f" + t.reason()
                                + " §8| wlasciciel: §7" + t.owner()), false);
                if (t.ownerPos() != null) {
                    source.sendSuccess(() -> blockLine(t.ownerPos()), false);
                }
            }
        }
        source.sendSuccess(() -> Component.literal(
                "§7Kliknij wspolrzedne bloku, aby sie teleportowac."), false);
        return 1;
    }

    /**
     * Wykrywa chunki, ktore wygladaja na trzymane bez powodu.
     *
     * <p>To test na wyciek: chunk wymuszony, ale zaden blok zadnej sieci w nim
     * nie lezy - czyli zostal po sieci, ktora juz nie istnieje.
     */
    private static int executeCheckStrict(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel sl = source.getLevel();
        var manager = VelocePipeNetworkManager.get(sl);

        var held = com.craftingveloce.network.pipe.VeloceChunkLoader.listHeld(sl);
        int orphaned = 0;
        for (var hc : held) {
            // Czy w tym chunku jest cokolwiek, co usprawiedliwia trzymanie?
            boolean justified = false;
            for (VelocePipeNetwork net : manager.getAllNetworks()) {
                for (BlockPos p : net.getPipes()) {
                    if ((p.getX() >> 4) == hc.x() && (p.getZ() >> 4) == hc.z()) {
                        justified = true;
                        break;
                    }
                }
                if (justified) {
                    break;
                }
                for (BlockPos p : net.getTerminals()) {
                    if ((p.getX() >> 4) == hc.x() && (p.getZ() >> 4) == hc.z()) {
                        justified = true;
                        break;
                    }
                }
                if (justified) {
                    break;
                }
            }
            if (!justified) {
                orphaned++;
                int fx = hc.x();
                int fz = hc.z();
                source.sendSuccess(() -> Component.literal(
                        "§cSierota: §f[" + fx + ", " + fz + "] §7- zaden blok sieci tu nie lezy"), false);
            }
        }
        int total = held.size();
        int found = orphaned;
        source.sendSuccess(() -> Component.literal(
                "§6Wynik: §f" + found + " §7sierot na §f" + total + " §7trzymanych chunkow."), false);
        return 1;
    }

    /** ID sieci wlasciciela danej pozycji (skrocone) albo "-". */
    private static String networkIdAt(VelocePipeNetworkManager manager, BlockPos pos) {
        if (pos == null) {
            return "-";
        }
        VelocePipeNetwork byPipe = manager.getNetworkForPipe(pos);
        VelocePipeNetwork net = byPipe != null ? byPipe : manager.getNetworkForTerminal(null, pos);
        return net == null ? "-" : net.getId().toString().substring(0, 8);
    }

    /** Linia z klikalnymi wspolrzednymi bloku. */
    private static Component blockLine(BlockPos pos) {
        String plain = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        return Component.literal("§8      blok: §f" + plain)
                .withStyle(style -> style
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                                "/tp @s " + plain))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Kliknij, aby sie teleportowac"))));
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
        } else if (be instanceof com.craftingveloce.block.entity.VeloceExtractorBlockEntity extractorBE) {
            player.sendSystemMessage(Component.literal("§6=== [CraftingVeloce Extractor Debug] ==="));
            player.sendSystemMessage(Component.literal("§7Pos: §f[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]"));
            VelocePipeNetworkManager manager = VelocePipeNetworkManager.get(sl);
            VelocePipeNetwork net = manager.getNetworkForTerminal(sl, pos);
            player.sendSystemMessage(Component.literal("§7Connected Network: §f" + (net != null ? net.getId() : "§cNONE")));
            player.sendSystemMessage(Component.literal("§b--- Configured Filters (3x3) ---"));
            for (int i = 0; i < 9; i++) {
                ItemStack filter = extractorBE.getFilter(i);
                ItemStack output = extractorBE.getOutputInventory().getItem(i);
                player.sendSystemMessage(Component.literal("  §7Slot " + (i + 1) + ": Filter=§e" + (!filter.isEmpty() ? filter.getHoverName().getString() : "EMPTY") + " §7| Stored=§a" + (!output.isEmpty() ? output.getCount() + "x " + output.getHoverName().getString() : "EMPTY")));
            }
            player.sendSystemMessage(Component.literal("§6======================================="));
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
