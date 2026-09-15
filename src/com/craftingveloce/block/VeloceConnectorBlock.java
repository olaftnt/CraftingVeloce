package com.craftingveloce.block;

import com.craftingveloce.block.entity.VeloceConnectorBlockEntity;
import com.craftingveloce.init.VeloceRegistry;
import com.mojang.serialization.MapCodec;
import com.tom.storagemod.block.IInventoryCable;
import com.tom.storagemod.inventory.InventoryCableNetwork;
import com.tom.storagemod.util.BlockFace;
import com.tom.storagemod.util.TickerUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

public class VeloceConnectorBlock extends BaseEntityBlock implements EntityBlock, IInventoryCable {
    public static final BooleanProperty UP = BlockStateProperties.UP;
    public static final BooleanProperty DOWN = BlockStateProperties.DOWN;
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty EAST = BlockStateProperties.EAST;
    public static final BooleanProperty WEST = BlockStateProperties.WEST;
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public static final MapCodec<VeloceConnectorBlock> CODEC = ChestBlock.simpleCodec(properties -> new VeloceConnectorBlock());

    public VeloceConnectorBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .sound(SoundType.METAL)
                .strength(3.0f)
                .noOcclusion());
        registerDefaultState(defaultBlockState()
                .setValue(DOWN, false)
                .setValue(UP, false)
                .setValue(NORTH, false)
                .setValue(EAST, false)
                .setValue(SOUTH, false)
                .setValue(WEST, false)
                .setValue(FACING, Direction.DOWN));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VeloceConnectorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        return TickerUtil.createTicker(world, false, true);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(UP, DOWN, NORTH, SOUTH, EAST, WEST, FACING);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction facing, BlockState facingState, LevelAccessor world, BlockPos pos, BlockPos facingPos) {
        Direction f = state.getValue(FACING);
        if (facing == f) {
            return state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(facing), !facingState.isAir());
        } else {
            return state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(facing), IInventoryCable.canConnect(facingState, facing));
        }
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace().getOpposite();
        BlockState state = defaultBlockState().setValue(FACING, facing);
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();

        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState neighborState = level.getBlockState(neighborPos);
            boolean canConn = (dir == facing) ? !neighborState.isAir() : IInventoryCable.canConnect(neighborState, dir);
            state = state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(dir), canConn);
        }
        return state;
    }

    @Override
    public boolean canConnectFrom(BlockState state, Direction dir) {
        return state.getValue(FACING) != dir;
    }

    @Override
    public List<BlockFace> nextScan(Level world, BlockState state, BlockPos pos) {
        Direction f = state.getValue(FACING);
        List<BlockFace> next = new ArrayList<>();
        for (Direction d : Direction.values()) {
            if (d != f && state.getValue(VeloceCableBlock.DIR_TO_PROPERTY[d.ordinal()])) {
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
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        VoxelShape core = Block.box(4.0D, 4.0D, 4.0D, 12.0D, 12.0D, 12.0D);
        VoxelShape nozzle = switch (facing) {
            case DOWN -> Block.box(3.0D, 0.0D, 3.0D, 13.0D, 4.0D, 13.0D);
            case UP -> Block.box(3.0D, 12.0D, 3.0D, 13.0D, 16.0D, 13.0D);
            case NORTH -> Block.box(3.0D, 3.0D, 0.0D, 13.0D, 13.0D, 4.0D);
            case SOUTH -> Block.box(3.0D, 3.0D, 12.0D, 13.0D, 13.0D, 16.0D);
            case WEST -> Block.box(0.0D, 3.0D, 3.0D, 4.0D, 13.0D, 13.0D);
            case EAST -> Block.box(12.0D, 3.0D, 3.0D, 16.0D, 13.0D, 13.0D);
        };
        return Shapes.or(core, nozzle);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
}
