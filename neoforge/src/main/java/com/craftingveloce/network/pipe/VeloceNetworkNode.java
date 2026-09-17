package com.craftingveloce.network.pipe;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block that is a node of the Veloce pipe network.
 *
 * <p><b>Why this interface exists.</b> The core recognized nodes with a
 * hand-written {@code instanceof} chain in two places ({@link VeloceNodeBlocks}
 * and the BFS loop in {@code VelocePipeNetworkManager}). That chain had two
 * costs:
 * <ul>
 *   <li>adding a node required registering it in EACH of those places (and it
 *       already drifted once - the controller was not recognized as a node),</li>
 *   <li>a node from outside the core (e.g. a block from {@code com.craftingveloce.compat.create})
 *       would require importing a foreign mod into the core, and then the mod
 *       would not start without that mod ({@code NoClassDefFoundError} while
 *       linking the class).</li>
 * </ul>
 *
 * <p>Now the core knows EXCLUSIVELY this interface. A block from {@code compat/*}
 * can be a full-fledged node without bringing a single foreign import into the
 * core.
 *
 * <p><b>Isolation.</b> This interface deliberately has no foreign mod types -
 * it may only use vanilla and Veloce core classes.
 */
public interface VeloceNetworkNode {

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
     * @param towardPipe the direction from the node towards the pipe
     */
    boolean canConnectFrom(BlockState state, Direction towardPipe);

    /**
     * Whether this node exposes its crafting buffer as a network endpoint.
     *
     * <p>It concerns the crafter: its buffer is the place where the production
     * surplus lands, so the whole network (terminal, pipes, hoppers) has access
     * to it. Every other node returns {@code false} - which is why this is a
     * default method, and not an obligation of every block.
     */
    default boolean exposesCraftingBuffer(net.minecraft.world.level.block.state.BlockState state) {
        return false;
    }

    /**
     * Whether the chunk with this node should be kept in memory (force-load).
     *
     * <p>By default yes, because a node is usually a machine or a terminal:
     * without simulation it stops working (the furnace does not burn, the
     * crafter does not craft, the sensor does not watch the thresholds).
     * DECORATIVE blocks (e.g. the {@code veloce_integrale} cage) return
     * {@code false} - they have no block entity and lose nothing when their
     * chunk drops out, while keeping them would cost a slot on the force-load
     * list, pushing out what really works.
     */
    default boolean keepChunkLoaded(net.minecraft.world.level.block.state.BlockState state) {
        return true;
    }

    /** Optional label for diagnostics and logs. */
    default String nodeName() {
        return getClass().getSimpleName();
    }
}
