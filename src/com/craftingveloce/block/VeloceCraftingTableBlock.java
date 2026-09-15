package com.craftingveloce.block;


import com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity;
import com.craftingveloce.init.VeloceRegistry;
import com.mojang.serialization.MapCodec;
import com.tom.storagemod.block.IInventoryCable;
import com.tom.storagemod.inventory.InventoryCableNetwork;
import com.tom.storagemod.util.BlockFace;
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

public class VeloceCraftingTableBlock extends BaseEntityBlock
        implements EntityBlock, IInventoryCable, VeloceNetworkNode {

    public static final MapCodec<VeloceCraftingTableBlock> CODEC = ChestBlock.simpleCodec(properties -> new VeloceCraftingTableBlock());

    public VeloceCraftingTableBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_CYAN)
                .sound(SoundType.WOOD)
                .strength(2.5F)
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
            // Drop bufora nadwyzki - zeby itemy nie zniknely przy zniszczeniu bloku.
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

    /** Wyrzuca cala zawartosc bufora na ziemie. */
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
            InventoryCableNetwork n = InventoryCableNetwork.getNetwork(world);
            n.markNodeInvalid(pos);
            n.markNodeInvalid(neighbor);
        }
    }

    @Override
    public boolean canConnectFrom(BlockState state, Direction dir) {
        return true;
    }

    /**
     * Bufor craftera jest endpointem sieci.
     *
     * <p>Dzieki temu nadwyzka produkcji (np. 3 deski z 1 logu, gdy gracz
     * chcial 1) jest widoczna dla calej sieci i mozna ja wyciagnac terminalem,
     * rura czy hopperem. Wczesniej rozpoznawalo to reczne {@code instanceof}
     * w petli BFS - teraz mowi o tym sam wezel.
     */
    @Override
    public boolean exposesCraftingBuffer() {
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
