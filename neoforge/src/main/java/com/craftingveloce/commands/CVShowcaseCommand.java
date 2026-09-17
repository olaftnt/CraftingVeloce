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
 * <p><b>The block list comes from the REGISTRY</b> ({@code craftingveloce:*}), not
 * from a hand-written list - otherwise a new block would never make it into the
 * showcase (exactly the kind of drift between two lists that kept coming back in
 * this project).
 */
public final class CVShowcaseCommand {

    /** How many blocks per row. */
    private static final int COLUMNS = 6;

    /** Spacing between blocks (one block of air so that nothing touches). */
    private static final int SPACING = 2;

    /** How many parts to fill in built machines (wheels: 2, crafter: 9 = 3x3). */
    private static final int FILL_PARTS = 9;

    private CVShowcaseCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(CVCommandRoot.root()
                .then(Commands.literal("showcase")
                        .executes(context -> place(context, false))
                        .then(Commands.literal("pipes").executes(context -> place(context, true)))
                        .then(Commands.literal("clear").executes(CVShowcaseCommand::clear))));
    }

    /** Places all our blocks in a grid in front of the player. */
    private static int place(CommandContext<CommandSourceStack> context, boolean withPipes) {
        ServerPlayer player = context.getSource().getPlayer();
        ServerLevel level = context.getSource().getLevel();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("/cv showcase is for a player only"));
            return 0;
        }
        List<Block> blocks = ourBlocks();
        int placed = 0;
        for (int i = 0; i < blocks.size(); i++) {
            BlockPos pos = slot(player, i);
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
        context.getSource().sendSuccess(() -> Component.literal(
                "Veloce showcase: " + total + " blocks (" + columns + " per row, spacing "
                        + SPACING + ")" + (withPipes ? " + pipes" : "")), true);
        CraftingVeloceMod.LOGGER.info("[Veloce][SHOWCASE] placed {} blocks at {}",
                placed, player.blockPosition());
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
        for (int i = 0; i < ourBlocks().size(); i++) {
            BlockPos pos = slot(player, i);
            if (isOurs(level.getBlockState(pos))) {
                level.removeBlock(pos, false);
                removed++;
            }
            if (isOurs(level.getBlockState(pos.north()))) {
                level.removeBlock(pos.north(), false);
                removed++;
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

    /** Position of the i-th block in the grid in front of the player (rows go away from the player). */
    private static BlockPos slot(ServerPlayer player, int index) {
        Direction facing = player.getDirection();
        Direction right = facing.getClockWise();
        int row = index / COLUMNS;
        int column = index % COLUMNS;
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
