package com.craftingveloce.block;

import com.craftingveloce.block.entity.VeloceTerminalBlockEntity;
import com.craftingveloce.network.OpenTerminalScreenPKT;
import com.craftingveloce.network.pipe.VeloceNetworkNode;
import com.craftingveloce.network.pipe.VeloceNodeBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
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
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;

/**
 * The Veloce Storage Terminal.
 *
 * <p><b>Why this class exists.</b> The terminal used to extend Tom's
 * {@code AbstractStorageTerminalBlock}, which was the last thing keeping Tom's
 * Simple Storage a hard dependency of this mod. A class cannot be loaded without
 * its superclass, so while the terminal inherited from Tom the mod could not
 * start without him - no matter how little of him was actually used.
 *
 * <p>What was really used from that superclass, measured rather than assumed: the
 * block state properties ({@code facing}/{@code pos}/{@code waterlogged}), a
 * directional collision shape, waterlogging, placement/rotation and
 * {@code canConnectFrom}. All of it lives here now.
 *
 * <p><b>The property names are deliberately identical</b> ({@code facing},
 * {@code pos}, {@code waterlogged}) because
 * {@code assets/craftingveloce/blockstates/veloce_terminal.json} keys its
 * variants on them; renaming one would break every model lookup and the block
 * would render as a missing texture.
 */
public class VeloceTerminalBlock extends BaseEntityBlock
        implements SimpleWaterloggedBlock, VeloceNetworkNode {

    public static final MapCodec<VeloceTerminalBlock> CODEC =
            ChestBlock.simpleCodec(properties -> new VeloceTerminalBlock());

    /** Where the screen sits: centred on a side, or facing up / down. */
    public static final EnumProperty<TerminalPos> TERMINAL_POS =
            EnumProperty.create("pos", TerminalPos.class);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    private static final VoxelShape SHAPE_N = Block.box(0, 0, 0, 16, 16, 6);
    private static final VoxelShape SHAPE_S = Block.box(0, 0, 10, 16, 16, 16);
    private static final VoxelShape SHAPE_E = Block.box(10, 0, 0, 16, 16, 16);
    private static final VoxelShape SHAPE_W = Block.box(0, 0, 0, 6, 16, 16);
    private static final VoxelShape SHAPE_U = Block.box(0, 10, 0, 16, 16, 16);
    private static final VoxelShape SHAPE_D = Block.box(0, 0, 0, 16, 6, 16);

    public VeloceTerminalBlock() {
        super(Block.Properties.of()
                .mapColor(MapColor.WOOD)
                .sound(SoundType.WOOD)
                                // Vanilla's furnace: hardness 3.5 and a PICKAXE is the tool that
                // yields the block. Both halves are needed - a block that requires the
                // correct tool but is missing from mineable/pickaxe is dropped by
                // NOTHING at all, which is worse than having no requirement.
                .strength(3.5F)
                .requiresCorrectToolForDrops()
                .lightLevel(s -> 6));
        registerDefaultState(defaultBlockState()
                .setValue(TERMINAL_POS, TerminalPos.CENTER)
                .setValue(WATERLOGGED, false)
                .setValue(FACING, Direction.NORTH));
    }

    /** Which way the screen points, taking the up/down positions into account. */
    public static Direction facingOf(BlockState state) {
        Direction d = state.getValue(FACING);
        TerminalPos p = state.getValue(TERMINAL_POS);
        if (p == TerminalPos.UP) return Direction.UP;
        if (p == TerminalPos.DOWN) return Direction.DOWN;
        return d;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VeloceTerminalBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state,
                                                                  BlockEntityType<T> type) {
        return world.isClientSide ? null : (lvl, pos, st, be) -> {
            if (be instanceof VeloceTerminalBlockEntity terminal) {
                terminal.updateServer();
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        com.craftingveloce.util.VeloceLog.Block.attempt(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "terminal right-clicked at %s by %s (client=%s)",
                pos, player.getName().getString(), world.isClientSide);
        if (!world.isClientSide && player instanceof ServerPlayer serverPlayer) {
            // THE PROFILER'S SESSION OPENS AT THE CLICK, and this is the only place where that
            // is correct: the order of a terminal opening is server-first (this block scans the
            // network and syncs the counts) and client-second (the packet below makes the client
            // build the screen). A session opened by the screen build would start after the
            // work that the click itself caused - and would have cleared it.
            //
            // It is opened here and not around the whole method so that the interaction logging
            // at the top is not inside the measured window.
            com.craftingveloce.util.VeloceProfiler.beginInteractionSession();
            try (var ignored = com.craftingveloce.util.VeloceProfiler.section("server.terminalOpen.rightClick")) {
                BlockEntity be = world.getBlockEntity(pos);
                if (be instanceof VeloceTerminalBlockEntity terminalBE) {
                    terminalBE.onPlayerOpenTerminal(serverPlayer);
                }
                // Our terminal is a client-side screen fed by our own packets, not a
                // container menu, so we open it ourselves instead of player.openMenu().
                try (var ignored2 = com.craftingveloce.util.VeloceProfiler.section("server.terminalOpen.sendOpenPacket")) {
                    PacketDistributor.sendToPlayer(serverPlayer, new OpenTerminalScreenPKT(pos));
                }
            }
        }
        return InteractionResult.sidedSuccess(world.isClientSide);
    }

    @Override
    public void setPlacedBy(Level world, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(world, pos, state, placer, stack);
        if (!world.isClientSide) {
            VeloceNodeBlocks.onNodePlaced(world, pos);
        }
    }

    @Override
    public void destroy(LevelAccessor world, BlockPos pos, BlockState state) {
        super.destroy(world, pos, state);
        VeloceNodeBlocks.onNodeRemoved(world, pos);
    }

    @Override
    public boolean canConnectFrom(BlockState state, Direction dir) {
        return dir != facingOf(state).getOpposite();
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, WATERLOGGED, TERMINAL_POS);
        // Casing caps. The terminal keeps its own directional model, so it adds the
        // properties but no casing multipart.
        VeloceIntegraleFrame.addProperties(builder);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction direction = context.getClickedFace().getOpposite();
        FluidState fluid = context.getLevel().getFluidState(context.getClickedPos());
        TerminalPos pos = TerminalPos.CENTER;
        if (direction.getAxis() == Direction.Axis.Y) {
            if (direction == Direction.UP) pos = TerminalPos.UP;
            if (direction == Direction.DOWN) pos = TerminalPos.DOWN;
            direction = context.getHorizontalDirection();
        }
        return defaultBlockState()
                .setValue(FACING, direction.getAxis() == Direction.Axis.Y ? Direction.NORTH : direction)
                .setValue(TERMINAL_POS, pos)
                .setValue(WATERLOGGED, fluid.getType() == Fluids.WATER);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction facing, BlockState facingState,
                                  LevelAccessor world, BlockPos pos, BlockPos facingPos) {
        if (state.getValue(WATERLOGGED)) {
            world.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(world));
        }
        return super.updateShape(state, facing, facingState, world, pos, facingPos);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos,
                               CollisionContext context) {
        return switch (state.getValue(TERMINAL_POS)) {
            case UP -> SHAPE_U;
            case DOWN -> SHAPE_D;
            case CENTER -> switch (state.getValue(FACING)) {
                case SOUTH -> SHAPE_S;
                case EAST -> SHAPE_E;
                case WEST -> SHAPE_W;
                default -> SHAPE_N;
            };
        };
    }

    /** Screen placement: centred on a side, or facing up / down. */
    public enum TerminalPos implements StringRepresentable {
        CENTER("center"),
        UP("up"),
        DOWN("down");

        private final String name;

        TerminalPos(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
