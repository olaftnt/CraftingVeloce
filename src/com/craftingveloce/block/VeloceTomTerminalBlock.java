package com.craftingveloce.block;

import com.craftingveloce.block.entity.VeloceTomTerminalBlockEntity;
import com.craftingveloce.network.OpenTerminalScreenPKT;
import com.mojang.serialization.MapCodec;
import com.tom.storagemod.block.AbstractStorageTerminalBlock;
import com.tom.storagemod.block.IInventoryCable;
import com.tom.storagemod.inventory.InventoryCableNetwork;
import com.tom.storagemod.util.BlockFace;
import com.tom.storagemod.util.TickerUtil;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VeloceTomTerminalBlock extends AbstractStorageTerminalBlock implements EntityBlock, IInventoryCable {
    public static final MapCodec<VeloceTomTerminalBlock> CODEC = ChestBlock.simpleCodec(properties -> new VeloceTomTerminalBlock());

    public VeloceTomTerminalBlock() {
        super();
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VeloceTomTerminalBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        return TickerUtil.createTicker(world, false, true);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
        com.craftingveloce.util.VeloceLog.Block.attempt(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "terminal right-clicked at %s by %s (client=%s)",
                pos, player.getName().getString(), world.isClientSide);
        if (!world.isClientSide && player instanceof ServerPlayer serverPlayer) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof VeloceTomTerminalBlockEntity terminalBE) {
                terminalBE.onPlayerOpenTerminal(serverPlayer);
            }
            // Send packet to open the Creative-style terminal screen
            PacketDistributor.sendToPlayer(serverPlayer, new OpenTerminalScreenPKT(pos));
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
    public void destroy(LevelAccessor world, BlockPos pos, BlockState state) {
        super.destroy(world, pos, state);
        if (world instanceof ServerLevel l) {
            InventoryCableNetwork.getNetwork(l).markNodeInvalid(pos);
            VelocePipeNetworkManager.get(l).onTerminalRemoved(l, pos);
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
        Direction d = state.getValue(FACING);
        TerminalPos p = state.getValue(TERMINAL_POS);
        if (p == TerminalPos.UP) d = Direction.UP;
        if (p == TerminalPos.DOWN) d = Direction.DOWN;
        return dir != d.getOpposite();
    }

    @Override
    public List<BlockFace> nextScan(Level world, BlockState state, BlockPos pos) {
        List<BlockFace> list = new ArrayList<>();
        for (Direction d : Direction.values()) {
            if (canConnectFrom(state, d)) {
                list.add(new BlockFace(pos.relative(d), d.getOpposite()));
            }
        }
        return list;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
}
