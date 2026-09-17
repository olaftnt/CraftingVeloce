package com.craftingveloce.block;

import com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity;
import com.craftingveloce.network.pipe.VeloceNetworkNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import javax.annotation.Nullable;
import com.craftingveloce.network.pipe.VeloceNodeBlocks;

/**
 * Velocity Furnace - the heat source block for the auto-crafter.
 *
 * <p>The block is a network NODE (just like the crafter or the extractor): it
 * must be placed next to a pipe, then the network sees it and keeps its chunk
 * loaded, so the furnace can burn ALL THE TIME - even when the player is far away.
 */
public class VeloceVelocityFurnaceBlock extends BaseEntityBlock
        implements EntityBlock, VeloceNetworkNode {

    public VeloceVelocityFurnaceBlock(Properties properties) {
        super(properties);
    }

    /**
     * A fuel furnace has no front or back, so it connects to a pipe from every
     * side - just like the other machines in the mod.
     */
    @Override
    public boolean canConnectFrom(BlockState state, Direction towardPipe) {
        return true;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VeloceVelocityFurnaceBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (world.isClientSide) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof VeloceVelocityFurnaceBlockEntity furnace) {
                furnace.serverTick();
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!world.isClientSide && player instanceof ServerPlayer serverPlayer) {
            // We open the normal menu. The screen (flame + 6 filters) is
            // registered in RegisterMenuScreensEvent - without that entry
            // opening the menu would crash the client.
            //
            // The block entity may be temporarily unavailable (chunk currently
            // loading) - then we simply open nothing.
            if (world.getBlockEntity(pos) instanceof MenuProvider provider) {
                serverPlayer.openMenu(provider, pos);
            }
        }
        return InteractionResult.sidedSuccess(world.isClientSide);
    }

    /**
     * Registers the furnace with the pipe network when placed.
     *
     * <p><b>The BUG this fixes.</b> The furnace was registered as a node
     * (VeloceNodeBlocks) and could work as a heat source, but it did NOT
     * register itself when placed - unlike the terminal, the controller, the
     * crafter and the extractor. The effect: placing the furnace next to an
     * existing pipe did not refresh the network, so the furnace was not seen in
     * it (and without that the crafter did not find it and had nothing to smelt with).
     *
     * <p>It only worked in one direction: when the pipe was placed AFTER the
     * furnace, the pipe's scan discovered it on its own. The reverse order -
     * and nothing.
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
        // The fuel lying in the furnace is REAL items (the furnace pulls them
        // from the network itself), so they must drop - otherwise breaking the
        // furnace loses them.
        //
        // Note: the fuel filters are PHANTOM (clicking only copies the item into
        // the filter, it does not take it from the player). Returning them would
        // create items out of nothing, so we return ONLY the real fuel slot.
        if (world instanceof net.minecraft.server.level.ServerLevel sl
                && sl.getBlockEntity(pos) instanceof com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity be) {
            net.minecraft.world.Container fuel = be.getFuelSlot();
            net.minecraft.world.item.ItemStack inFuel = fuel.getItem(0);
            if (!inFuel.isEmpty()) {
                net.minecraft.world.Containers.dropItemStack(sl,
                        pos.getX(), pos.getY(), pos.getZ(), inFuel.copy());
                fuel.setItem(0, net.minecraft.world.item.ItemStack.EMPTY);
            }
        }
        super.destroy(world, pos, state);
        VeloceNodeBlocks.onNodeRemoved(world, pos);
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(VeloceVelocityFurnaceBlock::new);
    }


    /** Casing cap properties: one per each side of the world. */
    @Override
    protected void createBlockStateDefinition(
            net.minecraft.world.level.block.state.StateDefinition.Builder<
                    net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        com.craftingveloce.block.VeloceIntegraleFrame.addProperties(builder);
    }

    /** When placed, we immediately close the sides where a cable comes in. */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return com.craftingveloce.block.VeloceIntegraleFrame.withPlacementClosures(
                context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    /** Closing of the plate on the wall where a Veloce pipe stands. */
    @Override
    protected BlockState updateShape(BlockState state, net.minecraft.core.Direction facing,
                                     BlockState facingState, net.minecraft.world.level.LevelAccessor world,
                                     BlockPos pos, BlockPos facingPos) {
        return com.craftingveloce.block.VeloceIntegraleFrame.withClosure(state, facing, facingState);
    }
}
