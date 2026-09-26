package com.craftingveloce.block;

import com.craftingveloce.block.entity.VeloceImporterBlockEntity;
import com.craftingveloce.init.VeloceRegistry;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import com.craftingveloce.network.pipe.VeloceNetworkNode;

public class VeloceImporterBlock extends BaseEntityBlock implements VeloceNetworkNode {
    @Override
    public java.util.List<net.minecraft.world.item.ItemStack> getDrops(
            BlockState state, net.minecraft.world.level.storage.loot.LootParams.Builder params) {
        java.util.List<net.minecraft.world.item.ItemStack> out = new java.util.ArrayList<>();
        out.add(new net.minecraft.world.item.ItemStack(
                com.craftingveloce.init.VeloceRegistry.VELOCE_INTEGRALE_ITEM.get()));
        com.craftingveloce.block.VeloceIntegraleConversions.Conversion back = com.craftingveloce.block.VeloceIntegraleConversions.forResult(this);
        if (back != null) {
            net.minecraft.world.item.Item in =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(back.inputId());
            if (in != net.minecraft.world.item.Items.AIR) {
                out.add(new net.minecraft.world.item.ItemStack(in));
            }
        }
        return out;
    }

    /** Casing cap properties: one per each side of the world. */
    @Override
    protected void createBlockStateDefinition(
            net.minecraft.world.level.block.state.StateDefinition.Builder<
                    net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        com.craftingveloce.block.VeloceIntegraleFrame.addProperties(builder);
    }

    /** On placement we immediately close the sides a cable comes in from. */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return com.craftingveloce.block.VeloceIntegraleFrame.withPlacementClosures(
                context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    /** Closing the panel on the wall where a Veloce pipe stands. */
    @Override
    protected BlockState updateShape(BlockState state, net.minecraft.core.Direction facing,
                                     BlockState facingState, net.minecraft.world.level.LevelAccessor world,
                                     BlockPos pos, BlockPos facingPos) {
        return com.craftingveloce.block.VeloceIntegraleFrame.withClosure(state, facing, facingState);
    }

    @Override
    public boolean canConnectFrom(BlockState state, net.minecraft.core.Direction side) {
        return true;
    }
    public static final MapCodec<VeloceImporterBlock> CODEC = simpleCodec(VeloceImporterBlock::new);

    public VeloceImporterBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    public VeloceImporterBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .sound(SoundType.METAL)
                .strength(3.5F)
                .requiresCorrectToolForDrops()
                .noOcclusion()
                .isViewBlocking((state, world, pos) -> false)
                .isSuffocating((state, world, pos) -> false)
                .lightLevel(s -> 7));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VeloceImporterBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(blockEntityType, VeloceRegistry.VELOCE_IMPORTER_BE.get(), VeloceImporterBlockEntity::tick);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof VeloceImporterBlockEntity) {
                sp.openMenu((VeloceImporterBlockEntity) be, pos);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.getBlock() != newState.getBlock()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof VeloceImporterBlockEntity be) {
                Containers.dropContents(level, pos, be);
                level.updateNeighbourForOutputSignal(pos, this);
            }
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }
}
