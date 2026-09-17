package com.craftingveloce.block;


import com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity;
import com.craftingveloce.init.VeloceRegistry;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import com.craftingveloce.network.pipe.VeloceNetworkNode;
import com.craftingveloce.network.pipe.VeloceNodeBlocks;

/**
 * Veloce Crafting Table - an auto-crafter placed in an Integrale casing.
 *
 * <p><b>Appearance.</b> The block has a casing model ({@code veloce_integrale_frame}),
 * and inside, the client renders a crafting table (see {@code VeloceCaseContents}
 * and {@code VeloceCaseRenderer}). Thanks to that ALL our blocks look
 * the same: casing + base block inside (table, controller, dispenser,
 * observer, furnace).
 *
 * <p><b>History.</b> There used to be an additional {@code facade} state here and a separate
 * item "frame + table", which was created when a frame was converted into a table. After
 * unifying the appearance it was a POINTLESS DUPLICATE: a plain crafting table
 * looks exactly the same, so the player received two identical items.
 *
 * <p><b>No inventory of its own.</b> Crafting happens directly on the
 * network (see {@code VeloceAutoCrafter}) - nothing passes through a physical
 * block, apart from the buffer for production surplus.
 */
public class VeloceCraftingTableBlock extends BaseEntityBlock
        implements EntityBlock, VeloceNetworkNode {

    public static final MapCodec<VeloceCraftingTableBlock> CODEC = ChestBlock.simpleCodec(properties -> new VeloceCraftingTableBlock());

    public VeloceCraftingTableBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_CYAN)
                .sound(SoundType.WOOD)
                .strength(2.5F)
                .noOcclusion()
                .isViewBlocking((state, world, pos) -> false)
                .isSuffocating((state, world, pos) -> false)
                .lightLevel(s -> 7));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VeloceCraftingTableBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
        com.craftingveloce.util.VeloceLog.Block.attempt(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "auto-crafter right-clicked at %s by %s (client=%s)",
                pos, player.getName().getString(), world.isClientSide);
        if (!world.isClientSide && player instanceof ServerPlayer serverPlayer) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof VeloceCraftingTableBlockEntity ctBE) {
                ctBE.syncToPlayer(serverPlayer);
            }
        }
        return InteractionResult.sidedSuccess(world.isClientSide);
    }

    @Override
    public void setPlacedBy(Level world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(world, pos, state, placer, stack);
        if (!world.isClientSide) {
            com.craftingveloce.util.VeloceLog.Block.success(
                    com.craftingveloce.util.VeloceLog.Side.SERVER,
                    "auto-crafter placed at %s by %s (all recipes enabled by default)",
                    pos, placer == null ? "unknown" : placer.getName().getString());
            VeloceNodeBlocks.onNodePlaced(world, pos);
        }
    }

    @Override
    public void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            // Drop the surplus buffer - so that items do not vanish when the block is destroyed.
            if (!world.isClientSide && world.getBlockEntity(pos) instanceof VeloceCraftingTableBlockEntity ctBE) {
                int dropped = 0;
                for (int i = 0; i < ctBE.getBuffer().getContainerSize(); i++) {
                    if (!ctBE.getBuffer().getItem(i).isEmpty()) {
                        dropped++;
                    }
                }
                com.craftingveloce.util.VeloceLog.Block.success(
                        com.craftingveloce.util.VeloceLog.Side.SERVER,
                        "auto-crafter removed at %s - dropping %d stack(s) from buffer",
                        pos, dropped);
                dropBuffer(world, pos, ctBE);
            }
            super.onRemove(state, world, pos, newState, isMoving);
        }
    }

    /** Drops the entire contents of the buffer onto the ground. */
    private static void dropBuffer(Level world, BlockPos pos, VeloceCraftingTableBlockEntity be) {
        var buffer = be.getBuffer();
        for (int i = 0; i < buffer.getContainerSize(); i++) {
            ItemStack stack = buffer.getItem(i);
            if (!stack.isEmpty()) {
                net.minecraft.world.Containers.dropItemStack(world,
                        pos.getX(), pos.getY(), pos.getZ(), stack);
            }
        }
        buffer.clearContent();
    }

    @Override
    public void destroy(LevelAccessor world, BlockPos pos, BlockState state) {
        super.destroy(world, pos, state);
        VeloceNodeBlocks.onNodeRemoved(world, pos);
    }

    @Override
    public void neighborChanged(BlockState state, Level world, BlockPos pos, Block block, BlockPos neighbor, boolean isMoving) {
        super.neighborChanged(state, world, pos, block, neighbor, isMoving);
        if (!world.isClientSide) {
        }
    }

    @Override
    public boolean canConnectFrom(BlockState state, Direction dir) {
        return true;
    }

    /**
     * The crafter's buffer is a network endpoint.
     *
     * <p>Thanks to that the production surplus (e.g. 3 planks from 1 log, when the player
     * wanted 1) is visible to the whole network and can be pulled out with a terminal,
     * a pipe or a hopper. Previously this was recognized by a manual {@code instanceof}
     * in the BFS loop - now the node itself declares it.
     */
    @Override
    public boolean exposesCraftingBuffer(BlockState state) {
        return true;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    /** Casing cap properties: one per each side of the world. */
    @Override
    protected void createBlockStateDefinition(
            net.minecraft.world.level.block.state.StateDefinition.Builder<
                    net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        com.craftingveloce.block.VeloceIntegraleFrame.addProperties(builder);
    }

    /** On placement we immediately close the sides from which a cable comes in. */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return com.craftingveloce.block.VeloceIntegraleFrame.withPlacementClosures(
                context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    /** Closing the plate on the wall next to which a Veloce pipe stands. */
    @Override
    protected BlockState updateShape(BlockState state, net.minecraft.core.Direction facing,
                                     BlockState facingState, net.minecraft.world.level.LevelAccessor world,
                                     BlockPos pos, BlockPos facingPos) {
        return com.craftingveloce.block.VeloceIntegraleFrame.withClosure(state, facing, facingState);
    }
}
