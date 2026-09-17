package com.craftingveloce.network.pipe;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The ONE source of truth about what is a node of the pipe network.
 *
 * <p><b>Why this class exists.</b> This knowledge was scattered across two
 * places: {@code VelocePipeNetworkManager.nodeConnectsToPipe} (which asks
 * "does this node connect to a pipe on this side?") and
 * {@code collectNeighbours} (which collects nodes while building the network).
 * Both places listed block types BY HAND - and they drifted apart.
 *
 * <p>The effect was visible to the naked eye: the controller was not recognized
 * by either of those places as a node, so
 * {@link VelocePipeNetworkManager#getNetworkForTerminal} returned {@code null}
 * for it. The controller then received an EMPTY stock and an EMPTY set of items
 * with auto-crafting enabled - so every item (e.g. planks) showed up as
 * "auto-crafting disabled", even though the crafter in the network had it
 * enabled.
 *
 * <p>Additionally the controller did not make it into {@code network.getTerminals()},
 * so its chunk was not kept in memory.
 *
 * <p>Now both places ask THIS CLASS, and this class asks ONE interface
 * ({@link VeloceNetworkNode}). Adding a new node is implementing that interface
 * in its block - without touching the core and without importing a foreign mod.
 */
public final class VeloceNodeBlocks {

    private VeloceNodeBlocks() {
    }

    /**
     * Whether this block is a node of the pipe network.
     *
     * <p>A node is a block with its own block entity that must be simulated
     * (which is why its chunk is force-loaded) or that provides functions to the
     * network: terminal, controller, crafter, extractor, sensor, furnaces.
     */
    public static boolean isNode(Block block) {
        return block instanceof VeloceNetworkNode;
    }

    /**
     * Whether the node connects to a pipe standing on the side {@code towardPipe}.
     *
     * <p><b>The direction convention is crucial here</b> (and it was already
     * reversed once, which gave the symptom "the pipe sees the terminal, but the
     * terminal does not see the pipe"): {@code towardPipe} is the direction FROM
     * THE NODE TO THE PIPE, and not from the pipe to the node. A caller standing
     * at the pipe must therefore pass {@code d.getOpposite()}.
     *
     * @param state      the block state of the node
     * @param block      the node block (the same as {@code state.getBlock()})
     * @param towardPipe the direction from the node towards the pipe
     */
    public static boolean connectsFrom(BlockState state, Block block, Direction towardPipe) {
        return block instanceof VeloceNetworkNode node
                && node.canConnectFrom(state, towardPipe);
    }
    /**
     * Shared hook: a node has just been placed in the world.
     *
     * <p><b>Why it is factored out.</b> The same sequence (invalidate the node
     * in Tom's network, then report it to our manager) was copy-pasted in SEVEN
     * block-node classes. This is not cosmetics: when we were adding furnaces,
     * one of them did not get this hook and was not recognized by the network
     * until something else was touched. A new node now has ONE place to call,
     * instead of five lines to retype from memory.
     */

    public static void onNodePlaced(net.minecraft.world.level.Level world, net.minecraft.core.BlockPos pos) {
        if (world.isClientSide) {
            return;
        }
        if (world instanceof net.minecraft.server.level.ServerLevel sl) {
            VelocePipeNetworkManager.get(sl).onTerminalPlaced(sl, pos);
            // A new node = new possibilities: the number cache is no longer current.
            VelocePipeNetworkManager.get(sl).clearCraftableMemo(sl, pos);
        }
    }

    /**
     * Shared hook: a node has disappeared from the world.
     *
     * <p>Without it the network would keep an entry for a node that no longer
     * exists (a ghost in the terminal) - and placing the block again would create
     * a second entry.
     */
    public static void onNodeRemoved(net.minecraft.world.level.LevelAccessor world,
                                     net.minecraft.core.BlockPos pos) {
        if (world instanceof net.minecraft.server.level.ServerLevel sl) {
            VelocePipeNetworkManager.get(sl).onTerminalRemoved(sl, pos);
            VelocePipeNetworkManager.get(sl).clearCraftableMemo(sl, pos);
        }
    }
}
