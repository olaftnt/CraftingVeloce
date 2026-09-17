package com.craftingveloce.block;

import com.craftingveloce.block.entity.VeloceElectricFurnaceBlockEntity;
import com.craftingveloce.network.pipe.VeloceNetworkNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import javax.annotation.Nullable;
import com.craftingveloce.network.pipe.VeloceNodeBlocks;

/**
 * Velocity Electric Furnace - a Forge Energy powered heat source.
 *
 * <p>The block is a network NODE (like the crafter or the extractor), so it can
 * stand anywhere a pipe can - and the crafter will find it and use it with
 * PRIORITY over a fuel furnace.
 *
 * <p>It has no ticker: energy comes from the cable network through the
 * {@code EnergyStorage} capability, and it is consumed only when smelting in the
 * crafter. Thanks to that the furnace does nothing when nobody is crafting.
 */
public class VeloceElectricFurnaceBlock extends BaseEntityBlock
        implements EntityBlock, VeloceNetworkNode {

    public VeloceElectricFurnaceBlock(Properties properties) {
        super(properties);
    }

    /**
     * The electric furnace has no front or back, so it connects to a pipe on
     * every side - just like the other machines in the mod.
     */
    @Override
    public boolean canConnectFrom(BlockState state, Direction towardPipe) {
        return true;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VeloceElectricFurnaceBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * The electric furnace ticks ONLY to refresh the energy bar in the GUI.
     *
     * <p>It has no logic of its own per tick: smelting is instantaneous and is
     * settled by the crafter, which takes heat from this accumulator.
     * Without a ticker the bar in the GUI would freeze at the value from the
     * moment the window was opened.
     */
    @Override
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
            Level world, BlockState state,
            net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        if (world.isClientSide) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof VeloceElectricFurnaceBlockEntity furnace) {
                furnace.serverTick();
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        // Menu with the energy bar. The furnace has no item slots - it is a power
        // buffer for the crafter, so the screen only shows the accumulator.
        if (!world.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer sp
                && world.getBlockEntity(pos) instanceof net.minecraft.world.MenuProvider provider) {
            sp.openMenu(provider, pos);
        }
        return InteractionResult.sidedSuccess(world.isClientSide);
    }

    /**
     * Reports the furnace to the pipe network on placement.
     *
     * <p><b>The BUG this fixes.</b> The furnace was registered as a node
     * (VeloceNodeBlocks) and could work as a heat source, but it did NOT report
     * itself on placement - unlike the terminal, the controller, the crafter and
     * the extractor. The result: placing a furnace next to an existing pipe did
     * not refresh the network, so the furnace was not seen in it (and without
     * that the crafter could not find it and had nothing to smelt with).
     *
     * <p>It only worked one way: when the pipe was placed AFTER the furnace, the
     * pipe's scan discovered it by itself. The reverse order - and nothing.
     */
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
    public void destroy(net.minecraft.world.level.LevelAccessor world, BlockPos pos,
                        BlockState state) {
        super.destroy(world, pos, state);
        VeloceNodeBlocks.onNodeRemoved(world, pos);
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(VeloceElectricFurnaceBlock::new);
    }

    /** Casing cap properties: one for each side of the world. */
    @Override
    protected void createBlockStateDefinition(
            net.minecraft.world.level.block.state.StateDefinition.Builder<
                    net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        com.craftingveloce.block.VeloceIntegraleFrame.addProperties(builder);
    }

    /** On placement we immediately close off the sides a cable arrives from. */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return com.craftingveloce.block.VeloceIntegraleFrame.withPlacementClosures(
                context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    /** Closing the sheet metal on the face a Veloce pipe stands against. */
    @Override
    protected BlockState updateShape(BlockState state, net.minecraft.core.Direction facing,
                                     BlockState facingState, net.minecraft.world.level.LevelAccessor world,
                                     BlockPos pos, BlockPos facingPos) {
        return com.craftingveloce.block.VeloceIntegraleFrame.withClosure(state, facing, facingState);
    }
}
