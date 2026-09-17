package com.craftingveloce.block;

import com.craftingveloce.block.entity.VeloceThresholdSensorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import com.craftingveloce.network.pipe.VeloceNetworkNode;
import com.craftingveloce.network.pipe.VeloceNodeBlocks;

/**
 * Veloce Threshold Sensor - a network switch based on storage state.
 *
 * <p><b>What it is for.</b> The player picks an item and a threshold ("e.g. 64
 * iron"). The sensor watches how much of that item there PHYSICALLY is in the
 * network and emits redstone depending on the result. A typical use: the iron
 * ran out - turn on the power so the factory that delivers it starts up.
 *
 * <p><b>It is a NETWORK NODE</b> (like the terminal, crafter or furnace): it
 * must stand next to a pipe, and its chunk is kept so that it watches the state
 * also when the player is far away. Without that, the sensor would stop working
 * in exactly the situation it was made for - automation with no player around.
 *
 * <p><b>Redstone output.</b> We keep the result in the BLOCK STATE
 * ({@code POWERED}), and not only in the block entity. The reason is
 * mechanical: Minecraft broadcasts a block state change to neighbors, and that
 * very notification starts the machines next to it. A block entity alone sends
 * nothing to anyone.
 *
 * <p>We emit STRONG power (both {@code getSignal} and {@code getDirectSignal}),
 * so we behave like a redstone block: it works both on the machines next to it
 * and on a wire placed against the sensor. A weaker output would force the
 * player to guess where a wire may be placed.
 */
public class VeloceThresholdSensorBlock extends BaseEntityBlock
        implements EntityBlock, VeloceNetworkNode {

    /** Whether the sensor is currently emitting power. */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public VeloceThresholdSensorBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(3.0F)
                .noOcclusion()
                .isViewBlocking((state, world, pos) -> false)
                .isSuffocating((state, world, pos) -> false)
                .requiresCorrectToolForDrops());
        registerDefaultState(this.stateDefinition.any().setValue(POWERED, false));
    }


    /**
     * Closing the plate on the side a Veloce pipe stands against.
     *
     * <p>A player report: "the cable toggles, but the walls do not close -
     * it works on the crushing wheel, but not on the crafting table and the
     * furnace". The cause: those blocks did not even have the cap PROPERTIES
     * ({@code CLOSED_BY_DIRECTION}), so there was nothing to close. Now they
     * have them (see {@code createBlockStateDefinition}) and recompute them
     * here - this is the vanilla, safe path: a neighbor sends an update, we
     * return a new state (with no setBlock of our own on placement, which once
     * produced ghost blocks).
     */
    @Override
    protected BlockState updateShape(BlockState state, net.minecraft.core.Direction facing,
                                     BlockState facingState, net.minecraft.world.level.LevelAccessor world,
                                     BlockPos pos, BlockPos facingPos) {
        return com.craftingveloce.block.VeloceIntegraleFrame.withClosure(state, facing, facingState);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        com.craftingveloce.block.VeloceIntegraleFrame.addProperties(builder);
        builder.add(POWERED);
    }

    /** On placement we immediately close the sides a cable arrives from. */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return com.craftingveloce.block.VeloceIntegraleFrame.withPlacementClosures(
                context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // ------------------------------------------------------------------
    // Redstone output
    // ------------------------------------------------------------------

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return state.getValue(POWERED) ? 15 : 0;
    }

    @Override
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        // Strong power: the sensor should act like a redstone block, also on a
        // wire placed directly against it.
        return state.getValue(POWERED) ? 15 : 0;
    }

    // ------------------------------------------------------------------
    // Block entity, tick, menu
    // ------------------------------------------------------------------

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VeloceThresholdSensorBlockEntity(pos, state);
    }

    /**
     * The sensor MUST tick - otherwise it would never check the network.
     *
     * <p>The check is spread out: once every 10 ticks, not every tick. Scanning
     * the network's storages is expensive, and the sensor does not have to react
     * faster than the machine on the other side of the redstone will.
     */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (world.isClientSide) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof VeloceThresholdSensorBlockEntity sensor) {
                sensor.serverTick();
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!world.isClientSide && player instanceof ServerPlayer sp
                && world.getBlockEntity(pos) instanceof MenuProvider provider) {
            sp.openMenu(provider, pos);
        }
        return InteractionResult.sidedSuccess(world.isClientSide);
    }

    // ------------------------------------------------------------------
    // Network node - exactly like the other machines
    // ------------------------------------------------------------------

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
    public void neighborChanged(BlockState state, Level world, BlockPos pos, Block block,
                                BlockPos neighbor, boolean isMoving) {
        super.neighborChanged(state, world, pos, block, neighbor, isMoving);
        if (!world.isClientSide) {
        }
    }

    /** The sensor connects from every side - there is no front or back. */
    @Override
    public boolean canConnectFrom(BlockState state, Direction dir) {
        return true;
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(properties -> new VeloceThresholdSensorBlock());
    }
}
