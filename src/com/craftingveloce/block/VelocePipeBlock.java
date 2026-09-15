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

    // Precise VoxelShapes exactly matching Pipez dimensions
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
        if (pipeBE != null && pipeBE.isDisconnected(dir)) {
            return false;
        }
        BlockPos neighborPos = pos.relative(dir);
        BlockState neighborState = (neighborStateOverride != null) ? neighborStateOverride : level.getBlockState(neighborPos);

        // 1. Another Veloce Pipe - connect directly unless the other side was disconnected
        if (neighborState.getBlock() instanceof VelocePipeBlock) {
            BlockEntity nbe = level.getBlockEntity(neighborPos);
            if (nbe instanceof VelocePipeBlockEntity otherPipe && otherPipe.isDisconnected(dir.getOpposite())) {
                return false;
            }
            return true;
        }

        // 2. Storage Terminal (connects from any side except the front screen)
        if (neighborState.getBlock() instanceof VeloceTomTerminalBlock terminal) {
            return terminal.canConnectFrom(neighborState, dir.getOpposite());
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
            boolean extracting = (pipeBE != null && pipeBE.isExtracting(dir)) && connected;
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

        if (side != null) {
            BlockPos neighborPos = pos.relative(side);
            boolean isInventory = canConnectToInventory(world, neighborPos, side.getOpposite())
                    || RefinedStorageHelper.hasRSNetwork(world, neighborPos, side.getOpposite());
            boolean isNeighborPipe = world.getBlockState(neighborPos).getBlock() instanceof IInventoryCable;

            if (isInventory) {
                boolean extracting = pipeBE.isExtracting(side);
                boolean disconnected = pipeBE.isDisconnected(side);
                if (!extracting && !disconnected) {
                    // Normal PUSHPULL -> Sucking PULL (displays extractor nozzle)
                    pipeBE.setExtracting(side, true);
                    pipeBE.setDisconnected(side, false);
                    if (player instanceof ServerPlayer sp) {
                        sp.displayClientMessage(Component.literal("§7[CraftingVeloce] Tryb: §6PULL (Tylko ssanie)"), true);
                    }
                } else if (extracting) {
                    // Sucking PULL -> Disconnected
                    pipeBE.setExtracting(side, false);
                    pipeBE.setDisconnected(side, true);
                    if (player instanceof ServerPlayer sp) {
                        sp.displayClientMessage(Component.literal("§7[CraftingVeloce] Tryb: §cROZŁĄCZONO"), true);
                    }
                } else {
                    // Disconnected -> Normal PUSHPULL (normal pipe connection)
                    pipeBE.setExtracting(side, false);
                    pipeBE.setDisconnected(side, false);
                    if (player instanceof ServerPlayer sp) {
                        sp.displayClientMessage(Component.literal("§7[CraftingVeloce] Tryb: §dPUSHPULL (Domyślny)"), true);
                    }
                }
            } else if (isNeighborPipe) {
                boolean disconnected = pipeBE.isDisconnected(side);
                pipeBE.setDisconnected(side, !disconnected);
                // Also update neighbor if it's our pipe
                BlockEntity nbe = world.getBlockEntity(neighborPos);
                if (nbe instanceof VelocePipeBlockEntity otherPipe) {
                    otherPipe.setDisconnected(side.getOpposite(), !disconnected);
                    BlockState otherState = updateConnections(world, neighborPos, world.getBlockState(neighborPos));
                    world.setBlockAndUpdate(neighborPos, otherState);
                    InventoryCableNetwork.getNetwork(world).markNodeInvalid(neighborPos);
                }
                if (player instanceof ServerPlayer sp) {
                    sp.displayClientMessage(Component.literal("§7[CraftingVeloce] Połączenie: " + (!disconnected ? "§cROZŁĄCZONO" : "§aPOŁĄCZONO")), true);
                }
            }
        } else {
            // Clicked core -> toggle face clicked
            Direction hitDir = hit.getDirection();
            boolean disconnected = pipeBE.isDisconnected(hitDir);
            pipeBE.setDisconnected(hitDir, !disconnected);

            BlockPos neighborPos = pos.relative(hitDir);
            BlockEntity nbe = world.getBlockEntity(neighborPos);
            if (nbe instanceof VelocePipeBlockEntity otherPipe) {
                otherPipe.setDisconnected(hitDir.getOpposite(), !disconnected);
                BlockState otherState = updateConnections(world, neighborPos, world.getBlockState(neighborPos));
                world.setBlockAndUpdate(neighborPos, otherState);
                InventoryCableNetwork.getNetwork(world).markNodeInvalid(neighborPos);
            }
        }

        BlockState newState = updateConnections(world, pos, state);
        world.setBlockAndUpdate(pos, newState);
        InventoryCableNetwork.getNetwork(world).markNodeInvalid(pos);

        if (world instanceof ServerLevel sl) {
            VelocePipeNetworkManager.get(sl).rebuildAt(sl, pos);
            if (side != null) {
                VelocePipeNetworkManager.get(sl).rebuildAt(sl, pos.relative(side));
            }
        }

        world.playSound(null, pos, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 1.0F, 1.2F);
        if (player != null) {
            player.swing(hand, true);
        }

        return ItemInteractionResult.sidedSuccess(world.isClientSide);
    }

    @Nullable
    public Direction getClickedSide(BlockState state, BlockPos pos, Vec3 hitLocation) {
        Vec3 rel = hitLocation.subtract(pos.getX(), pos.getY(), pos.getZ());
        if (rel.z < 0.3125 && state.getValue(NORTH)) return Direction.NORTH;
        if (rel.z > 0.6875 && state.getValue(SOUTH)) return Direction.SOUTH;
        if (rel.x < 0.3125 && state.getValue(WEST)) return Direction.WEST;
        if (rel.x > 0.6875 && state.getValue(EAST)) return Direction.EAST;
        if (rel.y < 0.3125 && state.getValue(DOWN)) return Direction.DOWN;
        if (rel.y > 0.6875 && state.getValue(UP)) return Direction.UP;
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
