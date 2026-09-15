package com.craftingveloce.block;

import com.craftingveloce.block.entity.VeloceExtractorBlockEntity;
import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import com.mojang.serialization.MapCodec;
import com.tom.storagemod.block.IInventoryCable;
import com.tom.storagemod.inventory.InventoryCableNetwork;
import com.tom.storagemod.util.BlockFace;
import com.tom.storagemod.util.TickerUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
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
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class VeloceExtractorBlock extends BaseEntityBlock implements EntityBlock, IInventoryCable {

    public static final MapCodec<VeloceExtractorBlock> CODEC = ChestBlock.simpleCodec(properties -> new VeloceExtractorBlock());

    public VeloceExtractorBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .sound(SoundType.METAL)
                .strength(3.0F)
                .lightLevel(s -> 7));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VeloceExtractorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        if (world.isClientSide) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof VeloceExtractorBlockEntity extractorBE) {
                extractorBE.serverTick();
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
        com.craftingveloce.util.VeloceLog.Block.attempt(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "extractor right-clicked at %s by %s (client=%s)",
                pos, player.getName().getString(), world.isClientSide);
        if (!world.isClientSide && player instanceof ServerPlayer serverPlayer) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof VeloceExtractorBlockEntity extractorBE) {
                serverPlayer.openMenu(extractorBE, buf -> buf.writeBlockPos(pos));
                extractorBE.syncFiltersToPlayer(serverPlayer);
            }
        }
        return InteractionResult.sidedSuccess(world.isClientSide);
    }

    @Override
    public void setPlacedBy(Level world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(world, pos, state, placer, stack);
        if (!world.isClientSide) {
            InventoryCableNetwork n = InventoryCableNetwork.getNetwork(world);
            n.markNodeInvalid(pos);
            if (world instanceof ServerLevel sl) {
                VelocePipeNetworkManager.get(sl).onTerminalPlaced(sl, pos);
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof VeloceExtractorBlockEntity extractorBE) {
                Containers.dropContents(world, pos, extractorBE.getOutputInventory());
            }
            super.onRemove(state, world, pos, newState, isMoving);
        }
    }

    @Override
    public void destroy(LevelAccessor world, BlockPos pos, BlockState state) {
        super.destroy(world, pos, state);
        if (world instanceof ServerLevel l) {
            InventoryCableNetwork.getNetwork(l).markNodeInvalid(pos);
            VelocePipeNetworkManager.get(l).onTerminalRemoved(pos);
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
    public boolean canConnectFrom(BlockState state, Direction dir) {
        return true;
    }

    @Override
    public List<BlockFace> nextScan(Level world, BlockState state, BlockPos pos) {
        List<BlockFace> list = new ArrayList<>();
        for (Direction d : Direction.values()) {
            list.add(new BlockFace(pos.relative(d), d.getOpposite()));
        }
        return list;
    }

    @Override
    public boolean isFunctionalNode() {
        return true;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
}
