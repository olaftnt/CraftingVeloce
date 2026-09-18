package com.craftingveloce.commands;

import com.craftingveloce.block.entity.VelocePipeBlockEntity;
import com.craftingveloce.block.entity.VeloceTerminalBlockEntity;
import com.craftingveloce.network.pipe.ConnectedEndpointInfo;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import com.craftingveloce.block.VelocePipeBlock;
import com.craftingveloce.block.VeloceTerminalBlock;
import com.craftingveloce.network.pipe.VelocePipeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import java.util.UUID;

public class CVDebugCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            CVCommandRoot.root()
                .then(Commands.literal("debug")
                    .executes(CVDebugCommand::executeDebug))
                .then(Commands.literal("block")
                    .executes(BlockProbeCommand::describe))
                .then(Commands.literal("perf")
                    .executes(CVDebugCommand::executePerf))
                // ONE switch for chunk monitoring.
                //
                // Previously there were three separate commands for this (chunkdebug,
                // its subcommands and chunks). While testing that got in the way: you
                // had to remember which one enables what, and even then it was not
                // clear whether the mechanism worked at all.
                //
                // Now: a single command, it works as a switch and turns the WHOLE
                // monitor on. Without an argument it toggles, with "on"/"off" it sets
                // the state explicitly.
                .then(Commands.literal("chunk")
                    .executes(ctx -> toggleChunkMonitor(ctx, null))
                    .then(Commands.literal("on")
                        .executes(ctx -> toggleChunkMonitor(ctx, true)))
                    .then(Commands.literal("off")
                        .executes(ctx -> toggleChunkMonitor(ctx, false)))
                    .then(Commands.literal("status")
                        .executes(CVDebugCommand::executeChunkStatus))
                    // Manual: release orphaned force-loads. Minecraft persists
                    // setChunkForced PERMANENTLY, so chunks forced by an older
                    // version of the code stayed loaded forever.
                    .then(Commands.literal("cleanup")
                        .executes(CVDebugCommand::cleanupOrphans)))
        );
    }

    /**
     * Prints everything needed to diagnose lag with a single command.
     *
     * <p>The point is that one in-game test gives the complete numbers instead of
     * guesswork: how many caches are alive (leak?), how many chunks we really
     * hold, how many rebuilds are pending, how long the last cache tick took and
     * whether we ever exceeded it.
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
                        + " §7| networks: §f" + manager.getAllNetworks(sl).size()),
                false);
        source.sendSuccess(() -> Component.literal(
                "§7Ticking server: §fyes §7| gameTime: §f" + sl.getGameTime()), false);
        // Loader helper map counters. Growing over time = memory leak; they are
        // supposed to oscillate around the number of chunks the network REALLY
        // touches.
        source.sendSuccess(() -> Component.literal(
                "§7Chunk loader tracking maps: §f"
                        + com.craftingveloce.network.pipe.VeloceChunkLoader.trackingMapSizes(sl)),
                false);
        for (VelocePipeNetwork net : manager.getAllNetworks(sl)) {
            source.sendSuccess(() -> Component.literal(
                    "  §8net §7" + net.getId().toString().substring(0, 8)
                            + " §7pipes=§f" + net.getPipes().size()
                            + " §7endpoints=§f" + net.getEndpoints().size()), false);
        }
        source.sendSuccess(() -> Component.literal("§6======================================="), false);
        return 1;
    }

    /**
     * ONE switch for the whole chunk monitor.
     *
     * <p>It turns on everything the test needs at once:
     * <ul>
     *   <li>a message on EVERY chunk unload (with coordinates),</li>
     *   <li>a report of item operations in unloaded chunks,</li>
     *   <li>lists of the network blocks in the chunk that is unloading.</li>
     * </ul>
     *
     * <p>This is NOT the ordinary debug from the config - it works independently,
     * because it serves one specific test: checking whether a given chunk even
     * tries to unload. Without it you can easily test an area that in reality
     * stays in memory the whole time and learn nothing.
     *
     * <p>Without an argument it toggles the state (on <-> off).
     */
    private static int toggleChunkMonitor(CommandContext<CommandSourceStack> context, Boolean value) {
        boolean target = value == null
                ? !com.craftingveloce.debug.ChunkDebugNotifier.isEnabled()
                : value;
        com.craftingveloce.debug.ChunkDebugNotifier.setEnabled(target);
        // Item operations go together with the monitor - it is one mechanism,
        // not two independent switches to remember.
        com.craftingveloce.debug.ChunkOpNotifier.setEnabled(target);

        String state = target ? "§aON" : "§cOFF";
        context.getSource().sendSuccess(() -> Component.literal(
                "§8[§6Veloce§8] chunk monitor: " + state), false);
        if (target) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "§7Every chunk unload -> a message with coordinates."), false);
            context.getSource().sendSuccess(() -> Component.literal(
                    "§7Item operations in unloaded chunks -> a report."), false);
            context.getSource().sendSuccess(() -> Component.literal(
                    "§7Use §f/cv chunk status§7 to see the chunks being held."), false);
        } else {
            context.getSource().sendSuccess(() -> Component.literal(
                    "§7Monitor disabled - the chat stays clean."), false);
        }
        return 1;
    }

    /**
     * Manually releases orphaned force-loads.
     *
     * <p>Minecraft persists {@code setChunkForced} PERMANENTLY in the world data,
     * while our bookkeeping lives only in memory - so chunks forced by an older
     * version of the code stayed loaded forever, even though our report showed
     * zero. This command cleans them up.
     */
    private static int cleanupOrphans(CommandContext<CommandSourceStack> context) {
        ServerLevel sl = context.getSource().getLevel();
        int before = com.craftingveloce.network.pipe.VeloceChunkLoader.gameForcedCount(sl);
        int orphans = VelocePipeNetworkManager.get(sl).releaseOrphanForceLoads(sl);
        int after = com.craftingveloce.network.pipe.VeloceChunkLoader.gameForcedCount(sl);
        context.getSource().sendSuccess(() -> Component.literal(
                "§8[§6Veloce§8] cleanup: the game held §f" + before
                        + " §7forced, orphans released: §f" + orphans
                        + "§7, remaining: §f" + after), false);
        return 1;
    }

    /** Short status: is the monitor on and how many chunks are we holding. */
    private static int executeChunkStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean on = com.craftingveloce.debug.ChunkDebugNotifier.isEnabled();
        source.sendSuccess(() -> Component.literal("§6=== [CraftingVeloce] Chunk monitor ==="), false);
        source.sendSuccess(() -> Component.literal(
                "§7State: " + (on ? "§aON" : "§cOFF")
                        + " §7| operations: " + (com.craftingveloce.debug.ChunkOpNotifier.isEnabled()
                        ? "§aON" : "§cOFF")), false);
        // The per-tick chunk loading budget - this is now the ONLY limit on the
        // rate of operations on distant storage (the task queue is gone, because
        // it handed out items "on credit" and duplicated them when a task failed).
        source.sendSuccess(() -> Component.literal(
                "§7Blocking chunk loads: §fmax "
                        + com.craftingveloce.network.pipe.VeloceChunkLoader.MAX_OP_LOADS_PER_TICK
                        + " §7/tick"), false);
        // The full list of held chunks - right away, without a second command.
        executeListChunks(context);
        return 1;
    }

    /**
     * Prints the list of chunks held in memory.
     *
     * <p>For each chunk: coordinates, network, the block that holds it and the
     * reason. The coordinates are CLICKABLE (teleport), because otherwise such a
     * list is useless - you cannot check what is inside.
     */
    private static int executeListChunks(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel sl = source.getLevel();
        var manager = VelocePipeNetworkManager.get(sl);
        var world = manager.getWorld();

        source.sendSuccess(() -> Component.literal(
                "§6=== [CraftingVeloce] Pipe structure - dimension §f"
                        + sl.dimension().location() + " §6==="), false);
        source.sendSuccess(() -> Component.literal(
                "§7Pipes: §f" + world.pipeCount()
                        + " §7| components: §f" + world.componentCount()
                        + " §7| cached descriptions: §f" + world.cachedComponentCount()
                        + " §7| forced chunks: §f"
                        + com.craftingveloce.network.pipe.VeloceChunkLoader.appliedCount(sl)), false);
        // DISCREPANCY: the game may hold more than we know about - Minecraft
        // persists setChunkForced PERMANENTLY, while our bookkeeping is in memory
        // only.
        int gameForced = com.craftingveloce.network.pipe.VeloceChunkLoader.gameForcedCount(sl);
        int oursForced = com.craftingveloce.network.pipe.VeloceChunkLoader.appliedCount(sl);
        if (gameForced != oursForced) {
            source.sendSuccess(() -> Component.literal(
                    "§c§lWARNING: §cthe game holds §f" + gameForced
                            + " §cforced chunks while we hold only §f" + oursForced
                            + "§c. Fix: §f/cv chunk cleanup"), false);
        }

        if (world.pipeCount() == 0) {
            source.sendSuccess(() -> Component.literal(
                    "§7The structure is empty - there are no pipes."), false);
            return 1;
        }

        // --- ALL ELEMENTS: nodes and storage, with chunk state ---
        //
        // This is exactly the testing tool: every element clearly says
        // LOADED/UNLOADED, so you can see which storage is outside the simulation
        // and whether its contents come from the cache.
        int loadedCount = 0;
        int unloadedCount = 0;

        source.sendSuccess(() -> Component.literal("§b--- NODES (hold a chunk permanently) ---"), false);
        java.util.List<BlockPos> allNodes = new java.util.ArrayList<>();
        for (var net : manager.getAllNetworks(sl)) {
            allNodes.addAll(net.getTerminals());
        }
        if (allNodes.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  §7(no nodes)"), false);
        }
        for (BlockPos p : sorted(allNodes)) {
            boolean loaded = sl.isLoaded(p);
            if (loaded) {
                loadedCount++;
            } else {
                unloadedCount++;
            }
            source.sendSuccess(() -> Component.literal(
                    "  §a" + describeNode(sl, p) + " §7" + shortPos(p)
                            + " §8" + chunkTag(p) + " " + loadedTag(sl, p)), false);
            source.sendSuccess(() -> blockLine(p), false);
        }

        source.sendSuccess(() -> Component.literal("§b--- STORAGE (is supposed to unload) ---"), false);
        java.util.Map<BlockPos, ConnectedEndpointInfo> allStorages = new java.util.LinkedHashMap<>();
        for (var net : manager.getAllNetworks(sl)) {
            allStorages.putAll(net.getEndpoints());
        }
        if (allStorages.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  §7(no storage)"), false);
        }
        for (var e : sortedEntries(allStorages)) {
            BlockPos p = e.getKey();
            ConnectedEndpointInfo info = e.getValue();
            boolean loaded = sl.isLoaded(p);
            if (loaded) {
                loadedCount++;
            } else {
                unloadedCount++;
            }
            source.sendSuccess(() -> Component.literal(
                    "  §e" + info.getType() + " §7" + shortPos(p)
                            + " §8" + chunkTag(p) + " " + loadedTag(sl, p)
                            + (loaded ? " §7(data from the world)" : " §6(data from CACHE)")
                            + " §7| types: §f" + info.getCachedCounts().size()), false);
            // Contents - this is exactly what we test on unloaded ones.
            for (var ic : info.getCachedCounts().entrySet()) {
                if (ic.getValue() > 0) {
                    source.sendSuccess(() -> Component.literal(
                            "      §7" + ic.getKey().getDescription().getString()
                                    + " §fx" + ic.getValue()), false);
                }
            }
            source.sendSuccess(() -> blockLine(p), false);
        }

        int lc = loadedCount;
        int uc = unloadedCount;
        source.sendSuccess(() -> Component.literal(
                "§6Summary: §a" + lc + " LOADED §7| §c" + uc + " UNLOADED"), false);

        // --- FORCED CHUNKS, with the reason ---
        var held = com.craftingveloce.network.pipe.VeloceChunkLoader.listHeld(sl);
        source.sendSuccess(() -> Component.literal(
                "§b--- Forced chunks: §f" + held.size() + " ---"), false);
        for (var hc : held) {
            boolean loaded = sl.isLoaded(new ChunkPos(hc.x(), hc.z()).getWorldPosition());
            source.sendSuccess(() -> Component.literal(
                    "  §f[" + hc.x() + ", " + hc.z() + "] "
                            + (loaded ? "§a[SIMULATED]" : "§c[OUTSIDE SIMULATION]")
                            + " §7uses: §f" + hc.hits()), false);
            for (var t : hc.tickets()) {
                source.sendSuccess(() -> Component.literal(
                        "      §8reason: §f" + t.reason()
                                + " §8| owner: §7" + t.owner()), false);
                if (t.ownerPos() != null) {
                    source.sendSuccess(() -> blockLine(t.ownerPos()), false);
                }
            }
        }

        // --- TASK QUEUE ---

        source.sendSuccess(() -> Component.literal(
                "§7Click the block coordinates to teleport there."), false);
        return 1;
    }




    /** A line with clickable block coordinates. */
    private static Component blockLine(BlockPos pos) {
        String plain = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        return Component.literal("§8      block: §f" + plain)
                .withStyle(style -> style
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                                "/tp @s " + plain))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to teleport there"))));
    }

    /**
     * Full network report: ALL attached blocks, with type and state.
     *
     * <p><b>Why so detailed.</b> Without it you cannot answer the basic question:
     * what is part of this network and what therefore keeps its chunks in memory.
     * Previously the report showed only numbers ("Pipes: 218, Terminals: 2") from
     * which it did not follow where those blocks are or what they are.
     *
     * <p>We distinguish four kinds of entries, because each means something
     * different:
     * <ul>
     *   <li><b>NODE</b> - terminal, crafter, extractor. It has a block entity that
     *       WORKS, so its chunk MUST be held.</li>
     *   <li><b>STORAGE</b> - chest, barrel, RS. Just a container; its chunk is NOT
     *       held permanently, it is only loaded for the duration of an operation
     *       and then released.</li>
     *   <li><b>PIPE</b> - a connector with no logic. It holds nothing.</li>
     * </ul>
     */
    private static void reportNetwork(ServerPlayer player, ServerLevel sl,
                                      VelocePipeNetwork net, BlockPos pipePos) {
        var manager = VelocePipeNetworkManager.get(sl);
        var world = manager.getWorld();

        player.sendSystemMessage(Component.literal("§6=== [CraftingVeloce] Connection trace ==="));
        player.sendSystemMessage(Component.literal(
                "§7Start pipe: §f" + shortPos(pipePos) + " §8" + chunkTag(pipePos)
                        + " " + loadedTag(sl, pipePos)));

        // --- 1. THE PIPE'S OWN CONNECTIONS (direct neighbours) ---
        var neighbours = world.neighbours(pipePos);
        player.sendSystemMessage(Component.literal(
                "§b--- Direct connections: " + neighbours.size() + " of 6 possible ---"));
        if (neighbours.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "  §c(none - this pipe does not connect to anything)"));
        }
        // We show ALL 6 directions, including the unconnected ones - it is the
        // fastest way to see where the network broke off.
        for (Direction d : Direction.values()) {
            BlockPos np = pipePos.relative(d);
            // NOTE: neighbours() contains ONLY pipes. For nodes and storage you
            // have to ask their own canConnectFrom - otherwise the report would
            // show "[-]" next to a correctly connected terminal (and mislead
            // during diagnosis, because it looked like a severed network).
            boolean linked = neighbours.contains(np);
            boolean isNode = false;
            if (!linked && sl.isLoaded(np)) {
                var st = sl.getBlockState(np);
                var b = st.getBlock();
                // Is it a node - we ask the SHARED source of truth, not a
                // hand-copied list of types.
                //
                // The BUG that was here: that list had the terminal, extractor,
                // crafter and controller, but did NOT have the two furnaces or the
                // threshold sensor. So the diagnostics reported that a furnace
                // standing next to a pipe was NOT a node ("link=no"), i.e. it lied
                // about exactly the thing the player was debugging. Every new block
                // required remembering about this place - and this is the same
                // pattern that has already drifted apart three times in this
                // project.
                if (com.craftingveloce.network.pipe.VeloceNodeBlocks.isNode(b)) {
                    isNode = true;
                    linked = VelocePipeNetworkManager.nodeConnectsToPipe(
                            sl, np, d.getOpposite());
                } else if (VelocePipeBlock.canConnectToInventory(sl, np, d.getOpposite())) {
                    linked = true;   // storage always connects
                }
            }
            String what = describeAt(sl, world, np);
            String note = linked
                    ? (isNode ? " §8(node connected)" : "")
                    : " §8(not connected)";
            player.sendSystemMessage(Component.literal(
                    "  " + (linked ? "§a[+] " : "§8[-] ") + "§7" + d.name().toLowerCase()
                            + " -> " + what + note));
        }

        // --- 2. THE WHOLE COMPONENT (walking the connections) ---
        var members = world.componentMembers(pipePos);
        player.sendSystemMessage(Component.literal(
                "§b--- The whole connected group: §f" + members.size() + " §bpipes ---"));
        player.sendSystemMessage(Component.literal(
                "  §7Representative: §f" + shortPos(world.componentOf(pipePos))));

        // --- 3. NODES: machines and terminals ---
        var nodes = net.getTerminals();
        player.sendSystemMessage(Component.literal("§b--- Nodes (machines): " + nodes.size() + " ---"));
        if (nodes.isEmpty()) {
            player.sendSystemMessage(Component.literal("  §7(none)"));
        }
        for (BlockPos p : sorted(nodes)) {
            player.sendSystemMessage(Component.literal(
                    "  §a" + describeNode(sl, p) + " §7" + shortPos(p)
                            + " §8" + chunkTag(p) + " " + loadedTag(sl, p)));
            player.sendSystemMessage(coordsLine(p));
        }

        // --- 4. STORAGE: chests, barrels, RS ---
        var endpooints = net.getEndpoints();
        player.sendSystemMessage(Component.literal("§b--- Storage: " + endpooints.size() + " ---"));
        if (endpooints.isEmpty()) {
            player.sendSystemMessage(Component.literal("  §7(none)"));
        }
        for (var e : sortedEntries(endpooints)) {
            BlockPos ep = e.getKey();
            ConnectedEndpointInfo info = e.getValue();
            boolean loaded = sl.isLoaded(ep);
            player.sendSystemMessage(Component.literal(
                    "  §e" + info.getType() + " §7" + shortPos(ep)
                            + " §8" + chunkTag(ep) + " " + loadedTag(sl, ep)
                            + " §7| item types: §f" + info.getCachedCounts().size()
                            + (loaded ? "" : " §8<- from cache, not from the world")));
            // Contents - for unloaded ones this is exactly the cache we want to
            // test.
            for (var ic : info.getCachedCounts().entrySet()) {
                if (ic.getValue() > 0) {
                    player.sendSystemMessage(Component.literal(
                            "      §7" + ic.getKey().getDescription().getString()
                                    + " §fx" + ic.getValue()));
                }
            }
            player.sendSystemMessage(coordsLine(ep));
        }

        // --- 5. CHUNKS HELD BY THIS NETWORK ---
        var held = com.craftingveloce.network.pipe.VeloceChunkLoader.listHeld(sl);
        String prefix = "net:" + net.getId().toString().substring(0, 8);
        int mine = 0;
        player.sendSystemMessage(Component.literal("§b--- Chunks held by this network ---"));
        for (var hc : held) {
            boolean ours = hc.tickets().stream().anyMatch(t -> t.owner().equals(prefix));
            if (!ours) {
                continue;
            }
            mine++;
            player.sendSystemMessage(Component.literal(
                    "  §f[" + hc.x() + ", " + hc.z() + "] §7reason: NETWORK"));
        }
        player.sendSystemMessage(Component.literal("  §7total: §f" + mine + " §7chunk(s)"));

        player.sendSystemMessage(Component.literal("§6==================================="));
    }

    /** Description of the block at the given position - for the connection list. */
    private static String describeAt(ServerLevel sl, VelocePipeWorld world, BlockPos p) {
        if (!sl.isLoaded(p)) {
            return world.hasPipe(p)
                    ? "§7pipe §8" + chunkTag(p) + " §c[UNLOADED]"
                    : "§8(unloaded chunk)";
        }
        var st = sl.getBlockState(p);
        var b = st.getBlock();
        if (b instanceof com.craftingveloce.block.VelocePipeBlock) {
            return "§7pipe " + loadedTag(sl, p);
        }
        if (b instanceof com.craftingveloce.block.VeloceTerminalBlock) {
            return "§aTERMINAL " + loadedTag(sl, p);
        }
        if (b instanceof com.craftingveloce.block.VeloceExtractorBlock) {
            return "§aEXTRACTOR " + loadedTag(sl, p);
        }
        if (b instanceof com.craftingveloce.block.VeloceCraftingTableBlock) {
            return "§aCRAFTER " + loadedTag(sl, p);
        }
        if (b instanceof com.craftingveloce.block.VeloceControllerBlock) {
            return "§aCONTROLLER " + loadedTag(sl, p);
        }
        if (b instanceof com.craftingveloce.block.VeloceVelocityFurnaceBlock) {
            return "§aFUEL FURNACE " + loadedTag(sl, p);
        }
        if (b instanceof com.craftingveloce.block.VeloceElectricFurnaceBlock) {
            return "§aELECTRIC FURNACE " + loadedTag(sl, p);
        }
        if (b instanceof com.craftingveloce.block.VeloceThresholdSensorBlock) {
            return "§aTHRESHOLD SENSOR " + loadedTag(sl, p);
        }
        // Storage: we check ALL sides, not just the top.
        //
        // There used to be `canConnectToInventory(sl, p, Direction.UP)` here - so a
        // container that only accepts from the side (e.g. a machine with a front)
        // described itself as a plain block. In diagnosing "why does the network
        // not see this", that is exactly the information you are looking for.
        for (Direction d : Direction.values()) {
            if (VelocePipeBlock.canConnectToInventory(sl, p, d)) {
                return "§eSTORAGE (" + b.getName().getString() + ") " + loadedTag(sl, p);
            }
        }
        return "§8" + b.getName().getString();
    }


    /** Type of a node block by class name (terminal / crafter / extractor). */
    private static String describeNode(ServerLevel sl, BlockPos p) {
        var be = sl.getBlockEntity(p);
        if (be == null) {
            return "EMPTY(?)";
        }
        String n = be.getClass().getSimpleName();
        if (n.contains("Terminal")) {
            return "TERMINAL";
        }
        if (n.contains("CraftingTable")) {
            return "CRAFTER";
        }
        if (n.contains("Extractor")) {
            return "EXTRACTOR";
        }
        return n.toUpperCase(java.util.Locale.ROOT);
    }

    private static String shortPos(BlockPos p) {
        return "[" + p.getX() + ", " + p.getY() + ", " + p.getZ() + "]";
    }

    private static String chunkTag(BlockPos p) {
        return "chunk[" + (p.getX() >> 4) + ", " + (p.getZ() >> 4) + "]";
    }

    private static String loadedTag(ServerLevel sl, BlockPos p) {
        return sl.isLoaded(p) ? "§a[LOADED]" : "§c[UNLOADED]";
    }

    /** Clickable block coordinates - clicking teleports. */
    private static Component coordsLine(BlockPos pos) {
        String plain = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        return Component.literal("§8      /tp " + plain)
                .withStyle(style -> style
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                                "/tp @s " + plain))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to teleport there"))));
    }

    /** Sorted positions - so that the report is reproducible. */
    private static java.util.List<BlockPos> sorted(java.util.Collection<BlockPos> in) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>(in);
        out.sort(java.util.Comparator.comparingInt((BlockPos p) -> p.getX())
                .thenComparingInt((BlockPos p) -> p.getZ())
                .thenComparingInt((BlockPos p) -> p.getY()));
        return out;
    }

    private static java.util.List<Map.Entry<BlockPos, ConnectedEndpointInfo>> sortedEntries(
            Map<BlockPos, ConnectedEndpointInfo> in) {
        java.util.List<Map.Entry<BlockPos, ConnectedEndpointInfo>> out =
                new java.util.ArrayList<>(in.entrySet());
        out.sort(java.util.Comparator.comparingInt(
                (Map.Entry<BlockPos, ConnectedEndpointInfo> e) -> e.getKey().getX())
                .thenComparingInt(e -> e.getKey().getZ())
                .thenComparingInt(e -> e.getKey().getY()));
        return out;
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

        if (be instanceof VeloceTerminalBlockEntity terminalBE) {
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
            VelocePipeNetwork net = manager.getNetworkForPipe(sl, pos);
            player.sendSystemMessage(Component.literal("§6=== [CraftingVeloce Pipe Debug] ==="));
            player.sendSystemMessage(Component.literal("§7Pipe Pos: §f[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]"));
            if (net == null) {
                player.sendSystemMessage(Component.literal("§cPipe is not currently assigned to any active network."));
                player.sendSystemMessage(Component.literal("§6==================================="));
                return 1;
            }
            reportNetwork(player, sl, net, pos);
            player.sendSystemMessage(Component.literal("§6==================================="));
            return 1;
        } else {
            player.sendSystemMessage(Component.literal("§c[CraftingVeloce] Looked-at block is not a Veloce Terminal or Pipe! (Found: " + (be != null ? be.getClass().getSimpleName() : player.level().getBlockState(pos).getBlock().getName().getString()) + ")"));
            return 0;
        }
    }
}
