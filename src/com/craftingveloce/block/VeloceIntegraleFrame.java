package com.craftingveloce.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Veloce Integrale frame states - shared by TWO blocks.
 *
 * <p><b>Why a separate class.</b> The same six caps (the window on the side a
 * pipe arrives from is closed with a plate) now describe two blocks:
 * <ul>
 *   <li>{@link VeloceIntegraleBlock} - the decorative frame (the machine
 *       casing),</li>
 *   <li>{@link VeloceCraftingTableBlock} in the {@code facade} state - the real
 *       crafting table standing in the frame (see {@code VeloceIntegraleBlock}
 *       "block replacement").</li>
 * </ul>
 * If each of them counted the caps its own way, there would be two places with
 * the same rule - and in this project that is a documented source of bugs (the
 * controller was not a network node, because the node list was duplicated).
 *
 * <p>We use vanilla {@link PipeBlock} states (the same names: {@code north},
 * {@code east}, ...), because they are exactly what is needed - one value per
 * side - and thanks to that the direction -&gt; property map already exists in
 * vanilla ({@link PipeBlock#PROPERTY_BY_DIRECTION}). A block state property can
 * be shared between blocks: it is only a descriptor, not a registry.
 */
public final class VeloceIntegraleFrame {

    /**
     * The frame's six windows: whether the window on a given side is PLATED
     * OVER.
     *
     * <p><b>OUR OWN properties, not borrowed from {@code PipeBlock}.</b>
     * A player report: "some blocks have as their default state the fact that
     * they are as if closed, even though nothing is connected". The borrowed
     * {@code PipeBlock.*} had a default value of TRUE, so EVERY placed machine
     * started with all walls closed (confirmed in game: an empty furnace showed
     * `down=true, east=true, ... west=true`). Our own properties have a default
     * of FALSE: a machine starts open, and the caps are only closed by a
     * neighboring pipe (see withClosure).
     *
     * <p>The names stay the same (north/east/south/west/up/down), because the
     * blockstates (multipart) and the states saved in the world rely on them.
     */
    public static final BooleanProperty DOWN = BooleanProperty.create("down");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty EAST = BooleanProperty.create("east");

    /** The frame's six windows in order: down, up, north, south, west, east. */
    public static final BooleanProperty[] CLOSED_BY_DIRECTION = {
            DOWN, UP, NORTH, SOUTH, WEST, EAST,
    };

    private VeloceIntegraleFrame() {
    }

    /** Adds the six caps to the block state definition. */
    public static void addProperties(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CLOSED_BY_DIRECTION);
    }

    /**
     * Initial state: caps closed wherever a Veloce pipe already stands.
     *
     * <p>Thanks to that, placing the block next to a cable immediately looks
     * right - with no block entity, no ticker and no waiting for an update from
     * the server.
     */
    public static BlockState withPlacementClosures(BlockGetter level, BlockPos pos, BlockState state) {
        for (Direction direction : Direction.values()) {
            state = state.setValue(property(direction), isPipe(level.getBlockState(pos.relative(direction))));
        }
        return state;
    }

    /**
     * Recomputes ONLY one side (the neighbor on that side changed).
     *
     * <p>Returns THE SAME state when nothing changed - otherwise every
     * neighbor update would send a block update packet for no reason.
     */
    public static BlockState withClosure(BlockState state, Direction facing, BlockState neighbor) {
        BooleanProperty property = property(facing);
        if (property == null) {
            return state;
        }
        boolean pipe = isPipe(neighbor);
        return state.getValue(property) == pipe ? state : state.setValue(property, pipe);
    }

    /**
     * Sets the cap of one side when the CALLER knows whether it is covered.
     *
     * <p>The frame asks about Veloce pipes, but a Create kinetic machine also
     * covers a side when POWER arrives from it (player: "this side should
     * behave as if a cable were connected on that side"). The core cannot know
     * about Create, so the caller makes the decision.
     */
    public static BlockState withClosure(BlockState state, Direction facing, boolean covered) {
        BooleanProperty property = property(facing);
        if (property == null || !state.hasProperty(property)) {
            return state;
        }
        return state.getValue(property) == covered ? state : state.setValue(property, covered);
    }

    /** Whether the window on this side is covered with a plate. */
    public static boolean isClosed(BlockState state, Direction direction) {
        BooleanProperty property = property(direction);
        return property != null && state.getValue(property);
    }

    /**
     * Copies the caps from one state to another - used when REPLACING a block
     * (frame -&gt; machine): a block that has a frame looks the same.
     *
     * <p>A target block without a frame (an ordinary Veloce machine) simply
     * skips these properties - which is why the method does not require it to
     * have them all.
     */
    public static BlockState copyClosures(BlockState from, BlockState to) {
        BlockState result = to;
        for (Direction direction : Direction.values()) {
            BooleanProperty property = property(direction);
            if (property == null || !result.hasProperty(property)) {
                continue;
            }
            result = result.setValue(property, isClosed(from, direction));
        }
        return result;
    }

    private static BooleanProperty property(Direction direction) {
        return switch (direction) {
            case DOWN -> DOWN;
            case UP -> UP;
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
        };
    }

    private static boolean isPipe(BlockState state) {
        return state.getBlock() instanceof VelocePipeBlock;
    }
}
