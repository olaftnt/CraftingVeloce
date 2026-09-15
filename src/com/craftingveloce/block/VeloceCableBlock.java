package com.craftingveloce.block;

import com.mojang.serialization.MapCodec;
import com.tom.storagemod.block.IInventoryCable;
import com.tom.storagemod.inventory.InventoryCableNetwork;
import com.tom.storagemod.util.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

public class VeloceCableBlock extends PipeBlock implements SimpleWaterloggedBlock, IInventoryCable {
    public static final BooleanProperty UP = BlockStateProperties.UP;
    public static final BooleanProperty DOWN = BlockStateProperties.DOWN;
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty EAST = BlockStateProperties.EAST;
    public static final BooleanProperty WEST = BlockStateProperties.WEST;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    public static final BooleanProperty[] DIR_TO_PROPERTY = new BooleanProperty[] {DOWN, UP, NORTH, SOUTH, WEST, EAST};

    public static final MapCodec<VeloceCableBlock> CODEC = ChestBlock.simpleCodec(properties -> new VeloceCableBlock());

    private final VoxelShape[] shapeCache;

    public VeloceCableBlock() {
        super(0.125f, BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .sound(SoundType.METAL)
                .strength(2.0f)
                .noOcclusion());
        registerDefaultState(defaultBlockState()
                .setValue(DOWN, false)
                .setValue(UP, false)
                .setValue(NORTH, false)
                .setValue(EAST, false)
                .setValue(SOUTH, false)
                .setValue(WEST, false)
                .setValue(WATERLOGGED, false));
        this.shapeCache = makeShapes();
    }

    private VoxelShape[] makeShapes() {
        VoxelShape core = Block.box(6.0D, 6.0D, 6.0D, 10.0D, 10.0D, 10.0D);
        VoxelShape[] sideShapes = new VoxelShape[] {
                Block.box(6.0D, 0.0D, 6.0D, 10.0D, 6.0D, 10.0D),  // DOWN
                Block.box(6.0D, 10.0D, 6.0D, 10.0D, 16.0D, 10.0D), // UP
                Block.box(6.0D, 6.0D, 0.0D, 10.0D, 10.0D, 6.0D),   // NORTH
                Block.box(6.0D, 6.0D, 10.0D, 10.0D, 10.0D, 16.0D), // SOUTH
                Block.box(0.0D, 6.0D, 6.0D, 6.0D, 10.0D, 10.0D),   // WEST
                Block.box(10.0D, 6.0D, 6.0D, 16.0D, 10.0D, 10.0D)  // EAST
        };

        VoxelShape[] result = new VoxelShape[64];
        for (int i = 0; i < 64; i++) {
            VoxelShape shape = core;
            for (int d = 0; d < 6; d++) {
                if ((i & (1 << d)) != 0) {
                    shape = Shapes.or(shape, sideShapes[d]);
                }
            }
            result[i] = shape;
        }
        return result;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(UP, DOWN, NORTH, SOUTH, EAST, WEST, WATERLOGGED);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int mask = 0;
        if (state.getValue(DOWN)) mask |= (1 << 0);
        if (state.getValue(UP)) mask |= (1 << 1);
        if (state.getValue(NORTH)) mask |= (1 << 2);
        if (state.getValue(SOUTH)) mask |= (1 << 3);
        if (state.getValue(WEST)) mask |= (1 << 4);
        if (state.getValue(EAST)) mask |= (1 << 5);
        return shapeCache[mask];
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction facing, BlockState facingState, LevelAccessor world, BlockPos pos, BlockPos facingPos) {
        if (state.getValue(WATERLOGGED)) {
            world.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(world));
        }
        return state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(facing), IInventoryCable.canConnect(facingState, facing));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        FluidState fluid = level.getFluidState(pos);

        BlockState state = defaultBlockState().setValue(WATERLOGGED, fluid.getType() == Fluids.WATER);
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState neighborState = level.getBlockState(neighborPos);
            state = state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(dir), IInventoryCable.canConnect(neighborState, dir));
        }
        return state;
    }

    @Override
    public List<BlockFace> nextScan(Level world, BlockState state, BlockPos pos) {
        List<BlockFace> next = new ArrayList<>();
        for (Direction d : Direction.values()) {
            if (state.getValue(DIR_TO_PROPERTY[d.ordinal()])) {
                next.add(new BlockFace(pos.relative(d), d.getOpposite()));
            }
        }
        return next;
    }

    @Override
    public boolean isFunctionalNode() {
        return false;
    }

    @Override
    public void destroy(LevelAccessor world, BlockPos pos, BlockState state) {
        super.destroy(world, pos, state);
        if (world instanceof ServerLevel l) {
            InventoryCableNetwork.getNetwork(l).markNodeInvalid(pos);
        }
    }

    @Override
    public void neighborChanged(BlockState state, Level world, BlockPos pos, Block block, BlockPos neighbor, boolean isMoving) {
        super.neighborChanged(state, world, pos, block, neighbor, isMoving);
        if (!world.isClientSide) {
            InventoryCableNetwork n = InventoryCableNetwork.getNetwork(world);
            n.markNodeInvalid(pos);
            n.markNodeInvalid(neighbor);
        }
    }

    @Override
    protected MapCodec<? extends PipeBlock> codec() {
        return CODEC;
    }
}
