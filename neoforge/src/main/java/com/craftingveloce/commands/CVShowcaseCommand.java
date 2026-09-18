package com.craftingveloce.commands;

import com.craftingveloce.CraftingVeloceMod;
import com.craftingveloce.block.VeloceCaseBuildable;
import com.craftingveloce.init.VeloceRegistry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code /cv showcase} - places ALL our blocks next to each other, for testing.
 *
 * <p><b>Why.</b> To look at the rendering of the casings (grinder, saw, crusher,
 * Mekanism/Alchemistry modules, vanilla blocks, empty Integrale and the pipe) you
 * would have to place them all by hand - a few dozen blocks and a couple of
 * minutes of clicking. This command places them in a grid in front of the player,
 * each with the proper state (drive shaft, pipe sheets), and machines built from
 * parts are immediately filled with parts so that their full appearance is
 * visible.

 * <p>Usage:
 * <ul>
 *   <li>{@code /cv showcase} - the blocks only,</li>
 *   <li>{@code /cv showcase pipes} - additionally a Veloce pipe next to each block
 *       (you can see the casing side being closed by a sheet),</li>
 *   <li>{@code /cv showcase clear} - cleans up what this command placed.</li>
 * </ul>
 *
 * <p><b>{@code /cv showpanel} is the same collection in the other arrangement</b> - see
 * {@link Layout}. The same command, the same block list, the same parts; only the
 * position of each block changes. Keeping it in one class is deliberate: two copies of
 * "walk the registry and place everything" is the second list this project keeps
 * having to delete.
 *
 * <p><b>The block list comes from the REGISTRY</b> ({@code craftingveloce:*}), not
 * from a hand-written list - otherwise a new block would never make it into the
 * showcase (exactly the kind of drift between two lists that kept coming back in
 * this project).
 */
public final class CVShowcaseCommand {

    /** How many blocks per row. */
    private static final int COLUMNS = 6;

    /** Spacing between blocks in the FLOOR layout (one block of air so nothing touches). */
    private static final int SPACING = 2;

    /**
     * The two arrangements the same collection can be built in.
     *
     * <p><b>FLOOR</b> is the original showcase: a grid on the ground, one block of air
     * between everything, so each casing can be judged on its own.
     *
     * <p><b>WALL</b> is {@code /cv showpanel}: the same blocks pressed together into a
     * vertical plane - side by side and one on top of another, with NO gap at all - which
     * is how a texture set has to be judged, because a seam or a palette that only holds
     * when the blocks are apart is not a palette. Nothing is spaced out here; spacing is
     * the one thing this arrangement exists to remove.
     */
    public enum Layout {
        FLOOR,
        WALL
    }

    /** How many parts to fill in built machines (wheels: 2, crafter: 9 = 3x3). */
    private static final int FILL_PARTS = 9;

    private CVShowcaseCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(CVCommandRoot.root()
                .then(Commands.literal("showcase")
                        .executes(context -> place(context, false, Layout.FLOOR))
                        .then(Commands.literal("pipes")
                                .executes(context -> place(context, true, Layout.FLOOR)))
                        .then(Commands.literal("clear").executes(CVShowcaseCommand::clear)))
                // The same blocks as a wall: touching, and stacked upwards instead of
                // laid out on the ground.
                .then(Commands.literal("showpanel")
                        .executes(context -> place(context, false, Layout.WALL))
                        .then(Commands.literal("pipes")
                                .executes(context -> place(context, true, Layout.WALL)))
                        .then(Commands.literal("clear").executes(CVShowcaseCommand::clear))));
    }

    /** Places all our blocks in front of the player, in the given arrangement. */
    private static int place(CommandContext<CommandSourceStack> context, boolean withPipes,
                             Layout layout) {
        ServerPlayer player = context.getSource().getPlayer();
        ServerLevel level = context.getSource().getLevel();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("/cv showcase is for a player only"));
            return 0;
        }
        List<Block> blocks = ourBlocks();
        int placed = 0;
        for (int i = 0; i < blocks.size(); i++) {
            BlockPos pos = slot(player, i, layout);
            if (withPipes) {
                // The pipe BEFORE the machine: placing the machine notifies the
                // neighbour, so the pipe recomputes its own shape and connections.
                level.setBlock(pos.north(), VeloceRegistry.VELOCE_PIPE.get().defaultBlockState(),
                        Block.UPDATE_ALL);
            }
            BlockState state = placementState(player, level, pos, blocks.get(i));
            level.setBlock(pos, state, Block.UPDATE_ALL);
            fillParts(level, pos);
            placed++;
        }
        int columns = Math.min(COLUMNS, Math.max(1, blocks.size()));
        int total = placed;
        String what = layout == Layout.WALL
                ? "wall: " + columns + " per row, no gap, stacked upwards"
                : "grid: " + columns + " per row, spacing " + SPACING;
        context.getSource().sendSuccess(() -> Component.literal(
                "Veloce showcase (" + what + "): " + total + " blocks"
                        + (withPipes ? " + pipes" : "")), true);
        CraftingVeloceMod.LOGGER.info("[Veloce][SHOWCASE] placed {} blocks ({}) at {}",
                placed, layout, player.blockPosition());
        return placed;
    }

    /** Cleans up the grid placed by showcase (only our blocks and pipes). */
    private static int clear(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        ServerLevel level = context.getSource().getLevel();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("/cv showcase is for a player only"));
            return 0;
        }
        int removed = 0;
        // BOTH arrangements: a wall left standing by /cv showcase clear (or the other way
        // round) is exactly the litter this subcommand exists to prevent, and the two
        // overlap in the world.
        for (Layout layout : Layout.values()) {
            for (int i = 0; i < ourBlocks().size(); i++) {
                BlockPos pos = slot(player, i, layout);
                if (isOurs(level.getBlockState(pos))) {
                    level.removeBlock(pos, false);
                    removed++;
                }
                if (isOurs(level.getBlockState(pos.north()))) {
                    level.removeBlock(pos.north(), false);
                    removed++;
                }
            }
        }
        int total = removed;
        context.getSource().sendSuccess(() -> Component.literal(
                "Veloce showcase: removed " + total + " blocks"), true);
        return removed;
    }

    /** Block state from its own placement logic (drive shaft, sheets, pipe connections). */
    private static BlockState placementState(ServerPlayer player, ServerLevel level, BlockPos pos,
                                             Block block) {
        BlockPlaceContext placeContext = new BlockPlaceContext(player, InteractionHand.MAIN_HAND,
                ItemStack.EMPTY, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        BlockState state = block.getStateForPlacement(placeContext);
        return state == null ? block.defaultBlockState() : state;
    }

    /** Fills built machines with parts so that their full appearance is visible. */
    private static void fillParts(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof VeloceCaseBuildable buildable)) {
            return;
        }
        for (int i = 0; i < FILL_PARTS; i++) {
            if (!buildable.addPart()) {
                break;   // machine full (wheels: 2) or it does not accept parts
            }
        }
    }

    /**
     * Position of the i-th block in front of the player, in the given arrangement.
     *
     * <p>FLOOR: rows go AWAY from the player, and everything is SPACING apart.
     *
     * <p>WALL: rows go UP, columns go sideways, and the step is 1 - the blocks touch on
     * all four sides. There is no SPACING term here at all, which is the point: this
     * arrangement exists to show what the textures do when they meet.
     */
    private static BlockPos slot(ServerPlayer player, int index, Layout layout) {
        Direction facing = player.getDirection();
        Direction right = facing.getClockWise();
        int row = index / COLUMNS;
        int column = index % COLUMNS;
        if (layout == Layout.WALL) {
            return player.blockPosition()
                    .relative(facing, 3)
                    .relative(right, column)
                    .above(row);
        }
        return player.blockPosition()
                .relative(facing, 3 + row * SPACING)
                .relative(right, column * SPACING);
    }

    private static boolean isOurs(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && CraftingVeloceMod.MODID.equals(id.getNamespace());
    }

    /** All our blocks from the registry, in a stable order. */
    private static List<Block> ourBlocks() {
        List<Block> blocks = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet()) {
            if (CraftingVeloceMod.MODID.equals(id.getNamespace())) {
                Block block = BuiltInRegistries.BLOCK.get(id);
                if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
                    blocks.add(block);
                }
            }
        }
        blocks.sort(Comparator.comparing(block -> {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            return id == null ? "" : id.getPath();
        }));
        return blocks;
    }
}
