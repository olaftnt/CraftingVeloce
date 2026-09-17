package com.craftingveloce.block;

import com.craftingveloce.block.entity.VelocePipeBlockEntity;
import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.item.VeloceWrenchItem;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import com.craftingveloce.rs.RefinedStorageHelper;
import com.mojang.serialization.MapCodec;
import com.tom.storagemod.block.IInventoryCable;
import com.tom.storagemod.inventory.InventoryCableNetwork;
import com.tom.storagemod.inventory.PlatformInventoryAccess.BlockInventoryAccess;
import com.tom.storagemod.util.BlockFace;
import com.tom.storagemod.util.TickerUtil;
import com.craftingveloce.client.ClientTerminalHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class VelocePipeBlock extends BaseEntityBlock implements EntityBlock, SimpleWaterloggedBlock, IInventoryCable {

    public static final BooleanProperty DOWN = PipeBlock.DOWN;
    public static final BooleanProperty UP = PipeBlock.UP;
    public static final BooleanProperty NORTH = PipeBlock.NORTH;
    public static final BooleanProperty SOUTH = PipeBlock.SOUTH;
    public static final BooleanProperty WEST = PipeBlock.WEST;
    public static final BooleanProperty EAST = PipeBlock.EAST;

    public static final BooleanProperty DOWN_EXTRACT = BooleanProperty.create("down_extract");
    public static final BooleanProperty UP_EXTRACT = BooleanProperty.create("up_extract");
    public static final BooleanProperty NORTH_EXTRACT = BooleanProperty.create("north_extract");
    public static final BooleanProperty SOUTH_EXTRACT = BooleanProperty.create("south_extract");
    public static final BooleanProperty WEST_EXTRACT = BooleanProperty.create("west_extract");
    public static final BooleanProperty EAST_EXTRACT = BooleanProperty.create("east_extract");

    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    public static final BooleanProperty[] EXTRACT_BY_DIRECTION = new BooleanProperty[] {
            DOWN_EXTRACT, UP_EXTRACT, NORTH_EXTRACT, SOUTH_EXTRACT, WEST_EXTRACT, EAST_EXTRACT
    };

    public static final MapCodec<VelocePipeBlock> CODEC = ChestBlock.simpleCodec(properties -> new VelocePipeBlock());

    // Precise VoxelShapes matching the pipe models (restored to 6x6).
    // The dimensions MUST match the models in assets/.../models/block/
    // (pipe_core.json, pipe_part.json, pipe_extract.json).
    // The pipe is 6x6 px (5..11), and the nozzle is 8x8 px (4..12).
    //
    // MIN/MAX are the normalized bounds of the pipe cross-section (5/16 and 11/16).
    // getClickedSide() uses them to work out which arm the wrench hit.
    public static final double MIN = 5.0D / 16.0D;
    public static final double MAX = 11.0D / 16.0D;

    public static final VoxelShape SHAPE_CORE = Block.box(5.0D, 5.0D, 5.0D, 11.0D, 11.0D, 11.0D);
    public static final VoxelShape SHAPE_NORTH = Block.box(5.0D, 5.0D, 0.0D, 11.0D, 11.0D, 5.0D);
    public static final VoxelShape SHAPE_SOUTH = Block.box(5.0D, 5.0D, 11.0D, 11.0D, 11.0D, 16.0D);
    public static final VoxelShape SHAPE_WEST = Block.box(0.0D, 5.0D, 5.0D, 5.0D, 11.0D, 11.0D);
    public static final VoxelShape SHAPE_EAST = Block.box(11.0D, 5.0D, 5.0D, 16.0D, 11.0D, 11.0D);
    public static final VoxelShape SHAPE_UP = Block.box(5.0D, 11.0D, 5.0D, 11.0D, 16.0D, 11.0D);
    public static final VoxelShape SHAPE_DOWN = Block.box(5.0D, 0.0D, 5.0D, 11.0D, 5.0D, 11.0D);

    public static final VoxelShape SHAPE_EXTRACT_NORTH = Shapes.or(SHAPE_NORTH, Block.box(4.0D, 4.0D, 0.0D, 12.0D, 12.0D, 1.0D)).optimize();
    public static final VoxelShape SHAPE_EXTRACT_SOUTH = Shapes.or(SHAPE_SOUTH, Block.box(4.0D, 4.0D, 15.0D, 12.0D, 12.0D, 16.0D)).optimize();
    public static final VoxelShape SHAPE_EXTRACT_WEST = Shapes.or(SHAPE_WEST, Block.box(0.0D, 4.0D, 4.0D, 1.0D, 12.0D, 12.0D)).optimize();
    public static final VoxelShape SHAPE_EXTRACT_EAST = Shapes.or(SHAPE_EAST, Block.box(15.0D, 4.0D, 4.0D, 16.0D, 12.0D, 12.0D)).optimize();
    public static final VoxelShape SHAPE_EXTRACT_UP = Shapes.or(SHAPE_UP, Block.box(4.0D, 15.0D, 4.0D, 12.0D, 16.0D, 12.0D)).optimize();
    public static final VoxelShape SHAPE_EXTRACT_DOWN = Shapes.or(SHAPE_DOWN, Block.box(4.0D, 0.0D, 4.0D, 12.0D, 1.0D, 12.0D)).optimize();

    public static final VoxelShape[] SIDE_SHAPES = new VoxelShape[] {
            SHAPE_DOWN, SHAPE_UP, SHAPE_NORTH, SHAPE_SOUTH, SHAPE_WEST, SHAPE_EAST
    };
    public static final VoxelShape[] EXTRACT_SHAPES = new VoxelShape[] {
            SHAPE_EXTRACT_DOWN, SHAPE_EXTRACT_UP, SHAPE_EXTRACT_NORTH, SHAPE_EXTRACT_SOUTH, SHAPE_EXTRACT_WEST, SHAPE_EXTRACT_EAST
    };

    public VelocePipeBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .sound(SoundType.METAL)
                .strength(2.0F)
                .noOcclusion());
        registerDefaultState(defaultBlockState()
                .setValue(DOWN, false)
                .setValue(UP, false)
                .setValue(NORTH, false)
                .setValue(SOUTH, false)
                .setValue(WEST, false)
                .setValue(EAST, false)
                .setValue(DOWN_EXTRACT, false)
                .setValue(UP_EXTRACT, false)
                .setValue(NORTH_EXTRACT, false)
                .setValue(SOUTH_EXTRACT, false)
                .setValue(WEST_EXTRACT, false)
                .setValue(EAST_EXTRACT, false)
                .setValue(WATERLOGGED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(DOWN, UP, NORTH, SOUTH, WEST, EAST,
                DOWN_EXTRACT, UP_EXTRACT, NORTH_EXTRACT, SOUTH_EXTRACT, WEST_EXTRACT, EAST_EXTRACT,
                WATERLOGGED);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VelocePipeBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        return TickerUtil.createTicker(world, false, true);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    public static boolean canConnectToInventory(Level level, BlockPos pos, Direction side) {
        BlockState state = level.getBlockState(pos);
        return BlockInventoryAccess.hasInventoryAt(level, pos, state, side);
    }

    public boolean canConnectDirection(Level level, BlockPos pos, Direction dir, @Nullable VelocePipeBlockEntity pipeBE) {
        return canConnectDirection(level, pos, dir, pipeBE, null);
    }

    public boolean canConnectDirection(Level level, BlockPos pos, Direction dir, @Nullable VelocePipeBlockEntity pipeBE, @Nullable BlockState neighborStateOverride) {
        return canConnectDirection(level, pos, dir, pipeBE, neighborStateOverride, false);
    }

    /**
     * Whether there is ANYTHING in this direction that this pipe can connect to.
     *
     * <p><b>Why the {@code ignoreDisconnectFlags} flag exists.</b> Normally a
     * disconnected side does not connect to anything - and that is correct for
     * BUILDING the network. But the wrench has to be able to read what lies
     * behind a disconnected side so that it can show it again: without that,
     * after disconnecting, the arm disappeared from the block state, and with
     * it the ability to click that spot. The connection could no longer be
     * restored.
     *
     * <p>Rather than writing a second, parallel "what is standing here"
     * condition (which would sooner or later drift apart from this one), the
     * same method therefore accepts information about whether it should skip
     * the disconnect flags.
     */
    public boolean canConnectDirection(Level level, BlockPos pos, Direction dir, @Nullable VelocePipeBlockEntity pipeBE, @Nullable BlockState neighborStateOverride, boolean ignoreDisconnectFlags) {
        if (!ignoreDisconnectFlags && pipeBE != null && pipeBE.isDisconnected(dir)) {
            return false;
        }
        BlockPos neighborPos = pos.relative(dir);
        BlockState neighborState = (neighborStateOverride != null) ? neighborStateOverride : level.getBlockState(neighborPos);

        // 1. Another Veloce Pipe - connect directly unless the other side was disconnected
        if (neighborState.getBlock() instanceof VelocePipeBlock) {
            BlockEntity nbe = level.getBlockEntity(neighborPos);
            if (!ignoreDisconnectFlags
                    && nbe instanceof VelocePipeBlockEntity otherPipe
                    && otherPipe.isDisconnected(dir.getOpposite())) {
                return false;
            }
            return true;
        }

        // 2. Storage Terminal (connects from any side except the front screen)
        if (neighborState.getBlock() instanceof VeloceTomTerminalBlock terminal) {
            return terminal.canConnectFrom(neighborState, dir.getOpposite());
        }

        // 2. OUR NODES (terminal, controller, crafter, extractor, BOTH FURNACES).
        //
        // The BUG this fixes: this condition listed block types BY HAND and the
        // furnace was not among them. The pipe therefore did not set an arm
        // toward the furnace - it looked unconnected, and Tom's cable network
        // did not see it. This is exactly the same bug that had already once
        // pulled the controller out of its own network.
        //
        // Now we ask VeloceNodeBlocks - the ONE source of truth about nodes,
        // which network building also uses. A new node will therefore work in
        // both places at once, with no need to remember about the other.
        if (com.craftingveloce.network.pipe.VeloceNodeBlocks.isNode(neighborState.getBlock())
                && com.craftingveloce.network.pipe.VeloceNodeBlocks.connectsFrom(
                        neighborState, neighborState.getBlock(), dir.getOpposite())) {
            return true;
        }

        // 3. Another IInventoryCable (Tom's Storage cable, etc.)
        if (neighborState.getBlock() instanceof IInventoryCable cable) {
            return cable.canConnectFrom(neighborState, dir.getOpposite());
        }

        // 4. Refined Storage (Interface, Controller, Cables, etc.)
        if (RefinedStorageHelper.hasRSNetwork(level, neighborPos, dir.getOpposite())) {
            return true;
        }

        // 5. Inventory block
        return canConnectToInventory(level, neighborPos, dir.getOpposite());
    }

    public BlockState updateConnections(Level level, BlockPos pos, BlockState state) {
        return updateConnections(level, pos, state, null, null);
    }

    public BlockState updateConnections(Level level, BlockPos pos, BlockState state, @Nullable Direction changedFacing, @Nullable BlockState changedNeighborState) {
        BlockEntity be = level.getBlockEntity(pos);
        VelocePipeBlockEntity pipeBE = (be instanceof VelocePipeBlockEntity p) ? p : null;

        for (Direction dir : Direction.values()) {
            BlockState neighborOverride = (dir == changedFacing) ? changedNeighborState : null;
            boolean connected = canConnectDirection(level, pos, dir, pipeBE, neighborOverride);
            BlockPos neighborPos = pos.relative(dir);
            BlockState neighborState = (neighborOverride != null) ? neighborOverride : level.getBlockState(neighborPos);
            boolean isExtractor = neighborState.getBlock() instanceof VeloceExtractorBlock
                    || neighborState.getBlock() instanceof com.craftingveloce.block.VeloceCraftingTableBlock;
            boolean extracting = !isExtractor && (pipeBE != null && pipeBE.isExtracting(dir)) && connected;
            state = state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(dir), connected);
            state = state.setValue(EXTRACT_BY_DIRECTION[dir.ordinal()], extracting);
        }
        return state;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        FluidState fluid = level.getFluidState(pos);
        BlockState state = defaultBlockState().setValue(WATERLOGGED, fluid.getType() == Fluids.WATER);
        return updateConnections(level, pos, state, null, null);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction facing, BlockState facingState, LevelAccessor world, BlockPos pos, BlockPos facingPos) {
        if (state.getValue(WATERLOGGED)) {
            world.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(world));
        }
        if (world instanceof Level level) {
            return updateConnections(level, pos, state, facing, facingState);
        }
        return state;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack itemStack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (VeloceWrenchItem.isWrench(itemStack)) {
            return onWrenchClicked(state, level, pos, player, hand, hit);
        }
        return super.useItemOn(itemStack, state, level, pos, player, hand, hit);
    }

    public ItemInteractionResult onWrenchClicked(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof VelocePipeBlockEntity pipeBE)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        Direction side = getClickedSide(state, pos, hit.getLocation());

        // ON THE CLIENT WE DO NOT CHANGE THE WORLD.
        //
        // Previously this whole method ran on both sides, so the client also
        // executed world.setBlockAndUpdate() on the pipe and on its neighbor,
        // plus markNodeInvalid() in Tom's cable network. That is a world
        // modification on the client side: it causes flicker, needless block
        // entity rebuilds, and state drift that the server then has to roll
        // back. The server does exactly the same thing and sends an update -
        // the client only has to swing its hand.
        if (world.isClientSide) {
            if (player != null) {
                player.swing(hand, true);
            }
            return ItemInteractionResult.SUCCESS;
        }

        if (side == null) {
            // Click on the CORE. The direction is the wall we hit - but ONLY
            // when something is really standing in that direction.
            //
            // The BUG this fixes: without this condition, a click on the core
            // from above (or on any wall with no neighbor) toggled "up" and
            // reported "Disconnected" without changing ANYTHING. The player saw
            // a message about a disconnection that never happened - and had no
            // way to guess that they had hit a direction with no connection.
            Direction face = hit.getDirection();
            if (!canConnectDirection(world, pos, face, pipeBE, null,
                    /* ignoreDisconnectFlags */ true)) {
                if (player instanceof ServerPlayer sp) {
                    sp.displayClientMessage(
                            Component.literal("Nothing to connect on this side"), true);
                }
                world.playSound(null, pos, SoundEvents.ITEM_FRAME_ROTATE_ITEM,
                        SoundSource.BLOCKS, 1.0F, 0.8F);
                if (player != null) {
                    player.swing(hand, true);
                }
                return ItemInteractionResult.sidedSuccess(world.isClientSide);
            }
            side = face;
        }

        {
            BlockPos neighborPos = pos.relative(side);
            BlockState neighborState = world.getBlockState(neighborPos);
            boolean isExtractor = neighborState.getBlock() instanceof VeloceExtractorBlock
                    || neighborState.getBlock() instanceof com.craftingveloce.block.VeloceCraftingTableBlock;
            boolean isInventory = !isExtractor && (canConnectToInventory(world, neighborPos, side.getOpposite())
                    || RefinedStorageHelper.hasRSNetwork(world, neighborPos, side.getOpposite()));

            if (isInventory) {
                boolean extracting = pipeBE.isExtracting(side);
                boolean disconnected = pipeBE.isDisconnected(side);
                if (!extracting && !disconnected) {
                    // Normal PUSHPULL -> Sucking PULL (displays extractor nozzle)
                    pipeBE.setExtracting(side, true);
                    pipeBE.setDisconnected(side, false);
                    if (player instanceof ServerPlayer sp) {
                        sp.displayClientMessage(Component.literal("Pull"), true);
                    }
                } else if (extracting) {
                    // Sucking PULL -> Disconnected
                    pipeBE.setExtracting(side, false);
                    pipeBE.setDisconnected(side, true);
                    if (player instanceof ServerPlayer sp) {
                        sp.displayClientMessage(Component.literal("Disconnected"), true);
                    }
                } else {
                    // Disconnected -> Normal PUSHPULL (normal pipe connection)
                    pipeBE.setExtracting(side, false);
                    pipeBE.setDisconnected(side, false);
                    if (player instanceof ServerPlayer sp) {
                        sp.displayClientMessage(Component.literal("Push/Pull"), true);
                    }
                }
            } else if (neighborState.getBlock() instanceof VelocePipeBlock) {
                togglePipeLink(world, pos, pipeBE, side, neighborPos, player);
            } else {
                // A node (terminal, crafter, extractor, furnace) or anything
                // else that connects but has no modes. We say so outright:
                // there used to be a SILENT no-op here, so the player clicked
                // and nothing happened, with no information as to why.
                if (player instanceof ServerPlayer sp) {
                    sp.displayClientMessage(
                            Component.literal("This side is a machine - nothing to toggle"), true);
                }
            }
        }

        BlockState newState = updateConnections(world, pos, state);
        world.setBlockAndUpdate(pos, newState);
        InventoryCableNetwork.getNetwork(world).markNodeInvalid(pos);

        if (world instanceof ServerLevel sl) {
            // THE SAME PATH AS ON A NEIGHBOR CHANGE - and that matters.
            //
            // The BUG here was: we only called rebuildAt(), which does a full
            // BFS in the middle of a click and does NOT synchronize the flat
            // structure. Whether a disconnection split the network at all was
            // therefore decided by a SIDE EFFECT of the block update (the
            // neighbor's neighborChanged) - so it worked sometimes and not
            // others, depending on whether the game happened to send the
            // notification. That is exactly the "it worked for a moment and
            // then fixed itself" report.
            //
            // onNeighborChanged does three things we need:
            //   1. syncAround - the flat structure learns about the change
            //      immediately,
            //   2. queueRebuild - the rebuild is DEFERRED to a tick, so a
            //      player click does not pay for scanning the whole network,
            //   3. invalidation of the endpoint cache - without it the terminal
            //      would keep showing the contents of the disconnected chest.
            com.craftingveloce.network.pipe.VelocePipeNetworkManager mgr =
                    com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(sl);
            mgr.onNeighborChanged(sl, pos, side != null ? pos.relative(side) : pos);
            if (side != null) {
                // The other side too - after the split it belongs to a
                // DIFFERENT network, so its cache has to be invalidated
                // separately.
                mgr.onNeighborChanged(sl, pos.relative(side), pos);
            }
        }

        world.playSound(null, pos, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 1.0F, 1.2F);
        if (player != null) {
            player.swing(hand, true);
        }

        return ItemInteractionResult.sidedSuccess(world.isClientSide);
    }

    /**
     * Toggles the connection between TWO pipes.
     *
     * <p><b>The flag belongs to ONE of them</b> - the one with the lower position.
     *
     * <p><b>Why not to both (as it used to be).</b> The previous version set
     * the same value on both pipes. And since the connection is broken when
     * EITHER side is disconnected, a single moment in which the flags drifted
     * apart (e.g. one side set from another code path) was enough, and then
     * this kept happening:
     * <ul>
     *   <li>a click on a side that was ALREADY disconnected reported
     *       "Disconnected" and really changed nothing - because the connection
     *       was already broken, and the player had no way to see that,</li>
     *   <li>on disconnection the arm disappears from the block state on BOTH
     *       sides, so it stopped being clickable - and the connection could not
     *       be restored.</li>
     * </ul>
     *
     * <p>A single owner (deterministically: the lower position) makes both
     * sides read and write THE SAME flag. The result does not depend on which
     * side you started from, and each side is clickable in turn.
     *
     * <p>We recompute the state on BOTH pipes, because the arm disappears on
     * both sides.
     */
    private void togglePipeLink(Level world, BlockPos pos, VelocePipeBlockEntity pipeBE,
                                Direction side, BlockPos neighborPos, Player player) {
        BlockEntity nbe = world.getBlockEntity(neighborPos);
        if (!(nbe instanceof VelocePipeBlockEntity other)) {
            return;   // the neighbor vanished in the meantime
        }
        Direction back = side.getOpposite();

        boolean selfOwns = pos.asLong() <= neighborPos.asLong();
        VelocePipeBlockEntity owner = selfOwns ? pipeBE : other;
        Direction ownerSide = selfOwns ? side : back;
        VelocePipeBlockEntity follower = selfOwns ? other : pipeBE;
        Direction followerSide = selfOwns ? back : side;

        // THE DECISION FOLLOWS FROM THE STATE OF THE LINK, NOT FROM ONE FLAG.
        //
        // The BUG this fixes: looking only at the owner's flag, with a
        // pre-existing asymmetric state (the flag had been left on the other
        // pipe) the owner considered the link OPEN, so it "toggled" it to
        // closed - while it was already closed. The player got the
        // "Disconnected" message and NOTHING changed. This is exactly the same
        // symptom the player reported, only for a different reason.
        //
        // The link is closed when EITHER end is disconnected.
        boolean linkCut = owner.isDisconnected(ownerSide)
                || follower.isDisconnected(followerSide);

        owner.setExtracting(ownerSide, false);
        owner.setDisconnected(ownerSide, !linkCut);
        // The second pipe does NOT hold the flag. Its old value would be
        // precisely that source of asymmetry the bug described - so we always
        // clean it up.
        follower.setDisconnected(followerSide, false);

        world.setBlockAndUpdate(pos, updateConnections(world, pos, world.getBlockState(pos)));
        world.setBlockAndUpdate(neighborPos,
                updateConnections(world, neighborPos, world.getBlockState(neighborPos)));
        InventoryCableNetwork.getNetwork(world).markNodeInvalid(pos);
        InventoryCableNetwork.getNetwork(world).markNodeInvalid(neighborPos);

        if (player instanceof ServerPlayer sp) {
            sp.displayClientMessage(
                    Component.literal(linkCut ? "Connected" : "Disconnected"), true);
        }
    }

    @Nullable
    public Direction getClickedSide(BlockState state, BlockPos pos, Vec3 hitLocation) {
        Vec3 rel = hitLocation.subtract(pos.getX(), pos.getY(), pos.getZ());
        // The bounds must match the pipe geometry (4..12 px => 0.25 .. 0.75).
        // It used to be 5..11 px (0.3125 .. 0.6875) with a 6x6 pipe.
        if (rel.z < MIN && state.getValue(NORTH)) return Direction.NORTH;
        if (rel.z > MAX && state.getValue(SOUTH)) return Direction.SOUTH;
        if (rel.x < MIN && state.getValue(WEST)) return Direction.WEST;
        if (rel.x > MAX && state.getValue(EAST)) return Direction.EAST;
        if (rel.y < MIN && state.getValue(DOWN)) return Direction.DOWN;
        if (rel.y > MAX && state.getValue(UP)) return Direction.UP;
        return null;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        if (context instanceof EntityCollisionContext entityContext) {
            if (entityContext.getEntity() instanceof Player player && player.level().isClientSide()) {
                HitResult hitResult = ClientTerminalHelper.getClientHitResult();
                if (hitResult instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(pos)) {
                    if (VeloceWrenchItem.isHoldingWrench(player)) {
                        Direction side = getClickedSide(state, pos, blockHit.getLocation());
                        if (side != null) {
                            int idx = side.ordinal();
                            return state.getValue(EXTRACT_BY_DIRECTION[idx]) ? EXTRACT_SHAPES[idx] : SIDE_SHAPES[idx];
                        } else {
                            return SHAPE_CORE;
                        }
                    }
                }
            }
        }

        VoxelShape shape = SHAPE_CORE;
        for (Direction dir : Direction.values()) {
            int idx = dir.ordinal();
            if (state.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(dir))) {
                shape = Shapes.or(shape, state.getValue(EXTRACT_BY_DIRECTION[idx]) ? EXTRACT_SHAPES[idx] : SIDE_SHAPES[idx]);
            }
        }
        return shape.optimize();
    }

    @Override
    public void setPlacedBy(Level world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(world, pos, state, placer, stack);
        if (!world.isClientSide) {
            InventoryCableNetwork.getNetwork(world).markNodeInvalid(pos);
            for (Direction d : Direction.values()) {
                InventoryCableNetwork.getNetwork(world).markNodeInvalid(pos.relative(d));
            }
            if (world instanceof ServerLevel sl) {
                VelocePipeNetworkManager.get(sl).onPipePlaced(sl, pos);
            }
        }
    }

    @Override
    public List<BlockFace> nextScan(Level world, BlockState state, BlockPos pos) {
        List<BlockFace> next = new ArrayList<>();
        for (Direction d : Direction.values()) {
            if (state.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d))) {
                next.add(new BlockFace(pos.relative(d), d.getOpposite()));
            }
        }
        return next;
    }

    @Override
    public boolean isFunctionalNode() {
        return true;
    }

    @Override
    public void destroy(LevelAccessor world, BlockPos pos, BlockState state) {
        super.destroy(world, pos, state);
        if (world instanceof ServerLevel l) {
            InventoryCableNetwork.getNetwork(l).markNodeInvalid(pos);
            VelocePipeNetworkManager.get(l).onPipeBroken(l, pos);
        }
    }

    @Override
    public void neighborChanged(BlockState state, Level world, BlockPos pos, Block block, BlockPos neighbor, boolean isMoving) {
        super.neighborChanged(state, world, pos, block, neighbor, isMoving);
        if (!world.isClientSide) {
            BlockState updated = updateConnections(world, pos, state);
            if (!updated.equals(state)) {
                world.setBlockAndUpdate(pos, updated);
            }
            InventoryCableNetwork.getNetwork(world).markNodeInvalid(pos);
            InventoryCableNetwork.getNetwork(world).markNodeInvalid(neighbor);
            if (world instanceof ServerLevel sl) {
                VelocePipeNetworkManager.get(sl).onNeighborChanged(sl, pos, neighbor);
            }
        }
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
}
