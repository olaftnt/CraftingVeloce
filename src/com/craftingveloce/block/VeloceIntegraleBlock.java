package com.craftingveloce.block;

import com.craftingveloce.network.pipe.VeloceNetworkNode;
import com.craftingveloce.network.pipe.VeloceNodeBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * Veloce Integrale - a decorative FRAME that becomes our machine.
 *
 * <p><b>Appearance.</b> The model is twelve thin rods running along the edges
 * of a cube plus purple glass in the windows, so the block looks like a drawn
 * square - lines along the corners, glass in the middle.
 *
 * <p><b>Collision.</b> AN ORDINARY FULL BLOCK - that is exactly what the player
 * wanted: the frame looks like a skeleton but behaves like a normal block (you
 * can walk on it, you cannot pass through it, nothing falls out of it). An
 * unusual collision shape was a bug: it made placing blocks next to it harder
 * and looked like a broken model.
 *
 * <p><b>Light and visibility.</b> The block does not occlude neighboring faces
 * ({@code noOcclusion}) and lets daylight through, so a machine standing next
 * to it renders normally instead of "in a dark hole".
 *
 * <p><b>What it does.</b> It is a <b>casing</b>: a right click with the right
 * vanilla block (see {@link VeloceIntegraleConversions}) <b>replaces the whole
 * frame with our machine</b> - a lectern becomes the controller, a dispenser
 * the extractor, an observer the threshold sensor, and a crafting table the
 * crafting table (that last one keeps the frame's appearance). Any other block
 * does nothing.
 *
 * <p><b>Why a block replacement and not a block entity.</b> The first version
 * kept a crafting table block entity inside the frame and pretended to be a
 * crafter, and the second one "put an exhibit on display" (an arbitrary block
 * inside). Both meant that the frame WAS NOT a machine: the recipe mod had
 * nothing to recognize, and the network had to know about exceptions.
 * Replacing the block removes those exceptions - our block simply stands in
 * the world.
 *
 * <p><b>Network.</b> The block is a node of the Veloce pipe network
 * ({@link VeloceNetworkNode}): it connects to a pipe from every side. The
 * chunk is NOT kept ({@link #keepChunkLoaded(BlockState)} = false), because the
 * frame does nothing - keeping chunks for a decoration is exactly the kind of
 * cost that has already bitten this project once (force-loads for pipes with
 * no logic). The machine that is made from the frame does keep its chunk -
 * because it works.
 */
public class VeloceIntegraleBlock extends Block implements VeloceNetworkNode {

    public VeloceIntegraleBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        VeloceIntegraleFrame.addProperties(builder);
    }

    /** On placement we immediately close the sides a cable arrives from. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return VeloceIntegraleFrame.withPlacementClosures(
                context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    /**
     * A neighbor change recomputes ONLY that one side.
     *
     * <p>Thanks to that, placing or breaking a cable next to it immediately
     * closes or opens the window - with no block entity, no ticker and no
     * refresh from the server (the block state travels in a normal sync
     * packet).
     */
    @Override
    protected BlockState updateShape(BlockState state, Direction facing, BlockState facingState,
                                     LevelAccessor world, BlockPos pos, BlockPos facingPos) {
        return VeloceIntegraleFrame.withClosure(state, facing, facingState);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * A right click with the right block turns the frame into our machine.
     *
     * <p>A block outside the table ({@link VeloceIntegraleConversions}) does
     * nothing - no "displaying something inside". That was wrong: the player
     * inserted a furnace and got an "exhibit" instead of a machine.
     */
    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack, BlockState state, Level world, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        VeloceIntegraleConversions.Conversion conversion =
                VeloceIntegraleConversions.forItem(stack);
        if (conversion == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!world.isClientSide) {
            convert(world, pos, state, conversion);
            if (player == null || !player.isCreative()) {
                stack.shrink(1);
            }
        }
        return ItemInteractionResult.sidedSuccess(world.isClientSide);
    }

    /**
     * Replaces the whole frame with a block from the table (e.g. lectern -&gt; controller).
     *
     * <p>We copy the window caps over only when the target block has a frame
     * (that is, our crafting table in the {@code facade} state) - an ordinary
     * machine does not have them and does not need them. After {@code setBlock}
     * the new block has to be reported to the network: {@code setBlock} first
     * calls {@code onRemove} of the old block, and that one (as a node) reports
     * itself as removed.
     */
    private static void convert(Level world, BlockPos pos, BlockState state,
                                VeloceIntegraleConversions.Conversion conversion) {
        BlockState result = conversion.resultBlock().defaultBlockState();
        result = VeloceIntegraleFrame.copyClosures(state, result);
        world.setBlock(pos, result, Block.UPDATE_ALL);
        // The inserted block STAYS inside: the casing builds itself element by
        // element (the crushing wheel, the crafter's eye), rather than "it is a
        // machine because it was placed".
        if (world.getBlockEntity(pos) instanceof VeloceCaseBuildable buildable) {
            buildable.addPart();
        }
        VeloceNodeBlocks.onNodePlaced(world, pos);
        world.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM,
                SoundSource.BLOCKS, 0.8F, 1.2F);
    }

    /**
     * The frame connects to a pipe from EVERY side - like the other machines in
     * the mod (there is no front or back).
     */
    @Override
    public boolean canConnectFrom(BlockState state, Direction towardPipe) {
        return true;
    }

    /**
     * THE FRAME DOES NOT KEEP ITS CHUNK LOADED.
     *
     * <p>It is decorative: nothing ticks and nothing is lost when its chunk
     * drops out of simulation. Keeping chunks for a decoration would bloat the
     * force-load list and push out of it the things that really work (the
     * furnace, the crafter, the module machines).
     */
    @Override
    public boolean keepChunkLoaded(BlockState state) {
        return false;
    }

    @Override
    public void setPlacedBy(Level world, BlockPos pos, BlockState state,
                            @Nullable net.minecraft.world.entity.LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(world, pos, state, placer, stack);
        if (!world.isClientSide) {
            VeloceNodeBlocks.onNodePlaced(world, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState,
                            boolean moved) {
        // Note: when REPLACING with a machine (convert) this hook reports the
        // node as removed - the caller must report it back right away.
        if (!state.is(newState.getBlock())) {
            VeloceNodeBlocks.onNodeRemoved(world, pos);
        }
        super.onRemove(state, world, pos, newState, moved);
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter world, BlockPos pos) {
        return true;
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter world, BlockPos pos) {
        return 1.0F;
    }

}
