package com.craftingveloce.commands;

import com.craftingveloce.block.VelocePipeBlock;
import com.craftingveloce.init.VeloceRegistry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a test Veloce network for checking behaviour on unloaded chunks.
 *
 * <p><b>Why this exists.</b> Manually placing 1000 pipes just to test chunk
 * unloading is impractical - and without a long network you cannot even get into
 * the situation where storage is outside the simulation. This command places the
 * whole layout with a single command and at the end prints ready-to-use teleport
 * commands.
 *
 * <p><b>Layout:</b>
 * <pre>
 *   [terminal] --- 1000 pipes to the north --- [barrel]
 *      start                                    end
 * </pre>
 *
 * <p><b>Why a barrel and not a chest.</b> A barrel ({@code barrel}) is a container
 * with no extra logic, so it is perfect as a "simple storage that is supposed to
 * unload". A vanilla chest merges into a double chest, which would change the
 * endpoint position.
 *
 * <p><b>Why the chunks are loaded for a moment.</b> The pipes have to connect to
 * each other and that requires simulation. The command first places the whole
 * layout with the chunks loaded and then lets them drop - only then does the test
 * make sense.
 */
public final class CVTestNetworkCommand {

    private CVTestNetworkCommand() {
    }

    /** Default network length in blocks. */
    private static final int DEFAULT_LENGTH = 1000;

    /**
     * How many chunks around the route we leave under force.
     *
     * <p>This is not a force-load - only a temporary force so that ALL pipes manage
     * to connect and the network is saved to NBT in one piece.
     */
    private static final int BUILD_CHUNK_MARGIN = 1;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            CVCommandRoot.root()
                .then(Commands.literal("testnet")
                    .executes(ctx -> build(ctx, DEFAULT_LENGTH))
                    .then(Commands.literal("build")
                        .executes(ctx -> build(ctx, DEFAULT_LENGTH))
                        .then(Commands.argument("length", IntegerArgumentType.integer(8, 4000))
                            .executes(ctx -> build(ctx, IntegerArgumentType.getInteger(ctx, "length")))))
                    .then(Commands.literal("clear")
                        .executes(CVTestNetworkCommand::clear))
                    .then(Commands.literal("tp")
                        .then(Commands.literal("start")
                            .executes(ctx -> teleport(ctx, true)))
                        .then(Commands.literal("end")
                            .executes(ctx -> teleport(ctx, false))))
                    // Teleport command reminder - without rebuilding the network.
                    .then(Commands.literal("where")
                        .executes(CVTestNetworkCommand::where))
                    .then(Commands.literal("modules")
                        .executes(CVTestNetworkCommand::buildModules))
                ));
    }


    private static int buildModules(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        net.minecraft.world.entity.player.Player player = source.getPlayer();
        if (player == null) return 0;
        
        BlockPos start = player.blockPosition().offset(2, 0, 0);
        int offsetZ = 0;
        
        for (com.craftingveloce.crafting.VeloceProcessingModule module : com.craftingveloce.crafting.VeloceProcessingRegistry.all()) {
            if (module.id().equals("brewing") || module.id().equals("furnace") || module.id().equals("crafting")) continue;
            
            BlockPos netPos = start.offset(0, 0, offsetZ);
            
            // Terminal at (0, 0, 0)
            level.setBlock(netPos, com.craftingveloce.init.VeloceRegistry.VELOCE_TERMINAL.get().defaultBlockState(), 3);
            
            // Pipe at (1, 0, 0)
            BlockPos pipePos = netPos.offset(1, 0, 0);
            level.setBlock(pipePos, com.craftingveloce.init.VeloceRegistry.VELOCE_PIPE.get().defaultBlockState(), 3);
            
            // Machine at (2, 0, 0)
            BlockPos machinePos = netPos.offset(2, 0, 0);
            net.minecraft.world.level.block.Block machineBlock = null;
            for (net.minecraft.world.level.block.Block b : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
                if (b instanceof com.craftingveloce.block.VeloceFeModuleBlock && net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath().contains(module.id())) {
                    machineBlock = b;
                    break;
                }
            }
            if (machineBlock == null) continue;
            level.setBlock(machinePos, machineBlock.defaultBlockState(), 3);
            if (level.getBlockEntity(machinePos) instanceof com.craftingveloce.block.entity.VeloceFeModuleBlockEntity be) {
                be.receiveEnergy(1000000, false);
            }
            
            // Barrel with ingredients at (-1, 0, 0)
            BlockPos barrel1 = netPos.offset(-1, 0, 0);
            level.setBlock(barrel1, net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState(), 3);
            
            // Unconnected Barrel with result at (-3, 0, 0)
            BlockPos barrel2 = netPos.offset(-3, 0, 0);
            level.setBlock(barrel2, net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState(), 3);
            
            
            com.craftingveloce.crafting.ProcessingEntry recipe = null;
            for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
                java.util.List<com.craftingveloce.crafting.ProcessingEntry> r = module.recipesAnywhere(level, item);
                if (!r.isEmpty()) {
                    recipe = r.get(0);
                    break;
                }
            }
            if (recipe != null) {
                if (level.getBlockEntity(barrel1) instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity be) {
                    for (int i = 0; i < recipe.ingredients().size(); i++) {
                        net.minecraft.world.item.crafting.Ingredient ing = recipe.ingredients().get(i);
                        if (!ing.isEmpty() && ing.getItems().length > 0) {
                            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(ing.getItems()[0].getItem(), recipe.ingredientCount(i) * 10);
                            stack.setCount(Math.min(stack.getMaxStackSize(), recipe.ingredientCount(i) * 10));
                            be.setItem(i, stack);
                        }
                    }
                }
                if (level.getBlockEntity(barrel2) instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity be) {
                    if (!recipe.results().isEmpty()) {
                        be.setItem(0, recipe.results().get(0).copy());
                    }
                }
            }
            offsetZ += 4;

        }
        
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Built module testnets!"), true);
        return 1;
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    private static int build(CommandContext<CommandSourceStack> context, int length) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();

        // We start on the player's FLOOR, not at their eyes - it is easier to hit
        // and you do not have to step back afterwards when something ends up
        // hanging in the air.
        BlockPos origin = source.getPlayer() != null
                ? source.getPlayer().blockPosition()
                : BlockPos.containing(source.getPosition());

        // The layout runs north so that it does not depend on the look direction
        // (the same test always comes out the same).
        Direction forward = Direction.NORTH;

        BlockPos startPos = origin;
        BlockPos endPos = origin.relative(forward, length);

        // Floor level: let us lay it down so there is something to walk on and so
        // the barrel/terminal does not hang in the air.
        List<BlockPos> floor = new ArrayList<>();
        for (int i = 0; i <= length; i++) {
            floor.add(origin.relative(forward, i));
        }

        // 1. Forcing chunks for the duration of the build.
        //
        // Without this the pipes in the further chunks would not connect (there is
        // no simulation) and the network would be saved to NBT as a chain without
        // links.
        List<ChunkPos> touched = new ArrayList<>();
        for (BlockPos p : floor) {
            ChunkPos cp = new ChunkPos(p);
            if (!touched.contains(cp)) {
                touched.add(cp);
            }
        }

        // TIME BUDGET FOR THIS PHASE.
        //
        // 1000 blocks in a line is ~63 chunks, and forcing a chunk means loading it
        // from disk (superflat: a few to a dozen or so ms). Without a limit a single
        // command would freeze the server for over a second - exactly the class of
        // bug that has already happened once in this mod.
        //
        // That is why the forcing phase has a hard budget. If it does not fit, we
        // SAY so plainly and abort, instead of bogging the game down.
        long buildDeadline = System.nanoTime() + 400_000_000L;   // 400 ms
        int forced = 0;
        for (ChunkPos cp : touched) {
            if (System.nanoTime() > buildDeadline) {
                for (int j = 0; j < forced; j++) {
                    ChunkPos done = touched.get(j);
                    level.setChunkForced(done.x, done.z, false);
                }
                source.sendFailure(Component.literal(
                        "§cBuild aborted: " + touched.size()
                                + " chunks did not fit in the time budget."));
                source.sendSuccess(() -> Component.literal(
                        "§7Try shorter: §f/cv testnet build 500§7, "
                                + "or split it into several commands."), false);
                return 0;
            }
            level.setChunkForced(cp.x, cp.z, true);
            forced++;
        }

        // 2. Floor + pipes + ends.
        int pipes = 0;
        for (int i = 0; i <= length; i++) {
            BlockPos p = origin.relative(forward, i);
            // Floor under the pipe - so you can walk and teleport.
            level.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 3);

            if (i == 0) {
                // Terminal at the start.
                //
                // NOTE: setBlock bypasses getStateForPlacement, so
                // defaultBlockState() gives the DEFAULT FACING. We set it manually
                // so that the front of the terminal is NOT turned towards the pipes
                // - otherwise canConnectFrom() rejects the connection and the
                // network never forms (exactly the bug we fixed in
                // canConnectFrom).
                //
                // The pipes run NORTH, the terminal stands south of them, so we
                // turn its front (that is, its "non-connectable" side) to SOUTH -
                // then the connectable side faces NORTH, towards the pipes.
                BlockState terminalState = VeloceRegistry.VELOCE_TERMINAL.get()
                        .defaultBlockState();
                if (terminalState.hasProperty(
                        net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)) {
                    terminalState = terminalState.setValue(
                            net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING,
                            Direction.SOUTH);
                }
                level.setBlock(p, terminalState, 3);
            } else if (i == length) {
                // Barrel at the end - simple storage that IS supposed to unload.
                level.setBlock(p, Blocks.BARREL.defaultBlockState(), 3);
            } else {
                placePipe(level, p);
                pipes++;
            }
        }

        // 3. Closing the connections: we walk through once more and recompute the
        //    state of every pipe and its neighbours so the network is coherent in
        //    NBT.
        for (int i = 0; i <= length; i++) {
            BlockPos p = origin.relative(forward, i);
            BlockState st = level.getBlockState(p);
            if (st.getBlock() instanceof VelocePipeBlock pipe) {
                level.setBlock(p, pipe.updateConnections(level, p, st), 3);
            }
        }

        // 4. Rebuild the network from scratch so the manager sees the whole chain.
        var manager = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(level);
        manager.clearPendingRebuilds();
        var net = manager.scanAndBuildNetwork(level, origin.relative(forward, 1), null);

        // 5. Releasing the chunks - from this moment the game follows its own rules.
        //
        // This is the essence of the test: we do NOT hold the chunks by force. Only
        // those with nodes (the terminal) will remain, and the rest are supposed to
        // drop.
        for (ChunkPos cp : touched) {
            level.setChunkForced(cp.x, cp.z, false);
        }

        // 6. Report + teleport commands.
        int nodes = net == null ? 0 : net.getTerminals().size();
        int endpoints = net == null ? 0 : net.getEndpoints().size();
        final int pipeCount = pipes;
        final int chunkCount = touched.size();
        final String netLabel = net == null
                ? "§cNONE"
                : net.getId().toString().substring(0, 8);

        source.sendSuccess(() -> Component.literal(
                "§6=== [CraftingVeloce] Test network built ==="), false);
        source.sendSuccess(() -> Component.literal(
                "§7Pipes: §f" + pipeCount + " §7| length: §f" + length
                        + " §7| chunks: §f" + chunkCount), false);
        source.sendSuccess(() -> Component.literal(
                "§7Networks: §f" + netLabel
                        + " §7| nodes: §f" + nodes + " §7| storage: §f" + endpoints), false);
        if (net != null && endpoints == 0) {
            source.sendSuccess(() -> Component.literal(
                    "§eNote: the barrel was not detected as storage. "
                            + "Check whether the network connected (§f/cv debug§e on the pipe)."), false);
        }
        if (source.getPlayer() != null) {
            rememberPositions(source.getPlayer(), origin, endPos);
        }

        // DETAILED WRITE TO THE CONSOLE - what we placed and what state the chunks
        // are in. On the chat only a confirmation, because a few dozen lines of
        // report are unreadable in the chat window and vanish after a moment.
        com.craftingveloce.debug.ChunkTrace.event("BUILD",
                "start=%s end=%s length=%d pipes=%d chunks=%d",
                origin.toShortString(), endPos.toShortString(), length, pipeCount, chunkCount);
        com.craftingveloce.debug.ChunkTrace.event("BUILD",
                "terminal@%s barrel@%s network=%s nodes=%d storage=%d",
                origin.toShortString(), endPos.toShortString(), netLabel, nodes, endpoints);
        if (net != null) {
            com.craftingveloce.debug.ChunkTrace.snapshotChunks("AFTER BUILD", level, net);
            com.craftingveloce.debug.ChunkTrace.snapshotStock("AFTER BUILD", level, net);
        }
        com.craftingveloce.debug.ChunkTrace.event("BUILD",
                "chunks forced for the duration of the build RELEASED - from now on the "
                        + "normal rules apply (the terminal holds, the barrel is supposed to drop)");

        printTeleportHints(source, origin, endPos);

        source.sendSuccess(() -> Component.literal(
                "§7Type §f/cv chunk§7 to enable the unload monitor."), false);
        return 1;
    }

    /** Places a pipe with correct connections. */
    private static void placePipe(ServerLevel level, BlockPos pos) {
        BlockState state = VeloceRegistry.VELOCE_PIPE.get().defaultBlockState();
        level.setBlock(pos, state, 3);
        BlockState placed = level.getBlockState(pos);
        if (placed.getBlock() instanceof VelocePipeBlock pipe) {
            level.setBlock(pos, pipe.updateConnections(level, pos, placed), 3);
        }
    }

    // ------------------------------------------------------------------
    // Cleanup and teleport
    // ------------------------------------------------------------------

    /**
     * Removes the built test network.
     *
     * <p>We delete only the blocks that we placed ourselves, and only between the
     * remembered start and end - so that it does not accidentally wipe out a real
     * player base standing next to it.
     */
    private static int clear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§cThis command requires a player."));
            return 0;
        }
        BlockPos start = readStartTag(player);
        BlockPos end = readEndTag(player);
        if (start == null || end == null) {
            source.sendFailure(Component.literal(
                    "§cI do not know the test position. Build a network: §f/cv testnet"));
            return 0;
        }

        int removed = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // The route is always a straight line, so we walk it step by step.
        int steps = (int) Math.sqrt(start.distSqr(end)) + 1;
        for (int i = 0; i <= steps; i++) {
            cursor.set(start.getX(), start.getY(), start.getZ() + (start.getZ() > end.getZ() ? -i : i));
            var st = level.getBlockState(cursor);
            if (st.getBlock() instanceof VelocePipeBlock
                    || st.is(VeloceRegistry.VELOCE_TERMINAL.get())
                    || st.is(Blocks.BARREL)) {
                level.removeBlock(cursor.immutable(), false);
                removed++;
            }
            // The floor we laid under the pipe.
            BlockPos below = cursor.below();
            if (level.getBlockState(below).is(Blocks.STONE)) {
                level.removeBlock(below, false);
                removed++;
            }
        }
        int total = removed;
        source.sendSuccess(() -> Component.literal(
                "§6Removed §f" + total + " §6blocks of the test network."), false);
        return 1;
    }

    private static int teleport(CommandContext<CommandSourceStack> context, boolean start) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("§cThis command requires a player."));
            return 0;
        }
        // The remembered position is stored on the player itself (persistent data),
        // so it also works after leaving and re-entering the world - and that is
        // exactly what the test looks like ("restart the game").
        BlockPos target = start ? readStartTag(player) : readEndTag(player);
        if (target == null) {
            context.getSource().sendFailure(Component.literal(
                    "§cI do not know the test position. Build a network: §f/cv testnet"));
            return 0;
        }
        player.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        context.getSource().sendSuccess(() -> Component.literal(
                "§7Teleport to the " + (start ? "§astart" : "§cend") + "§7."), false);
        return 1;
    }

    /** Key in the player data where we keep the test start position. */
    private static final String TAG_START = "VeloceTestStart";
    private static final String TAG_END = "VeloceTestEnd";

    /** Saves the test positions on the player (survives leaving to the menu). */
    private static void rememberPositions(ServerPlayer player, BlockPos start, BlockPos end) {
        var data = player.getPersistentData();
        data.putLong(TAG_START, start.asLong());
        data.putLong(TAG_END, end.asLong());
    }

    /** Test start position remembered on the player (null when absent). */
    public static BlockPos readStartTag(ServerPlayer player) {
        var data = player.getPersistentData();
        return data.contains(TAG_START) ? BlockPos.of(data.getLong(TAG_START)) : null;
    }

    /** Test end position remembered on the player (null when absent). */
    public static BlockPos readEndTag(ServerPlayer player) {
        var data = player.getPersistentData();
        return data.contains(TAG_END) ? BlockPos.of(data.getLong(TAG_END)) : null;
    }

    private static int where(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("§cThis command requires a player."));
            return 0;
        }
        BlockPos start = readStartTag(player);
        BlockPos end = readEndTag(player);
        if (start == null || end == null) {
            context.getSource().sendFailure(Component.literal(
                    "§cI do not know the test position. Build a network: §f/cv testnet"));
            return 0;
        }
        printTeleportHints(context.getSource(), start, end);
        return 1;
    }

    /** Prints two short, clickable teleport commands. */
    private static void printTeleportHints(CommandSourceStack source, BlockPos start, BlockPos end) {
        source.sendSuccess(() -> Component.literal("§6--- Teleport (click to run) ---"), false);
        source.sendSuccess(() -> tpLine("START", start, "§a"), false);
        source.sendSuccess(() -> tpLine("END", end, "§c"), false);
    }

    /** A single line with a clickable /tp command. */
    private static MutableComponent tpLine(String label, BlockPos pos, String color) {
        String coords = pos.getX() + " " + (pos.getY() + 1) + " " + pos.getZ();
        return Component.literal("  " + color + label + "§7: §f/tp " + coords)
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/tp @s " + coords))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to teleport there"))));
    }
}
