package com.craftingveloce.block;


import com.craftingveloce.block.entity.VeloceControllerBlockEntity;
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
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
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
 * Veloce Controller - blok monitorujacy siec logistyczna.
 *
 * <p>Otwiera liste wszystkich itemow w sieci z rozbudowanym filtrowaniem:
 * <ul>
 *   <li>kolory tla pokazuja, gdzie item jest dostepny (hotbar / crafting / stock)</li>
 *   <li>przyciski "show all / available / not available" filtruja widok</li>
 *   <li>itemy nieprodukowane przez craftery mozna znalezc i zaplanowac
 *       dla nich maszyne (extractor + skrzynia), zeby staly sie dostepne</li>
 * </ul>
 */
public class VeloceControllerBlock extends BaseEntityBlock
        implements EntityBlock, IInventoryCable, VeloceNetworkNode {

    public static final MapCodec<VeloceControllerBlock> CODEC =
            ChestBlock.simpleCodec(properties -> new VeloceControllerBlock());

    public VeloceControllerBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_LIGHT_BLUE)
                .sound(SoundType.METAL)
                .strength(3.0F)
                .lightLevel(s -> 5));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VeloceControllerBlockEntity(pos, state);
    }

    /**
     * Kontroler MUSI tykac, bo to on mierzy przeplyw itemow w sieci.
     *
     * <p>{@code VeloceFlowTracker} potrzebuje regularnych migawek, zeby
     * powiedziec, ile sztuk na sekunde przybywa, a ile ucieka. Bez tickera
     * mialby dane tylko wtedy, gdy ktos stoi i patrzy na GUI - czyli dokladnie
     * nic, bo tempo potrzebuje historii SPRZED otwarcia okna.
     *
     * <p>Koszt jest maly i rozlozony: migawka leci raz na 5 sekund, a nie co
     * tick, i korzysta z cache'u licznikow sieci.
     */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (world.isClientSide) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof VeloceControllerBlockEntity ctrl) {
                ctrl.serverTick();
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        com.craftingveloce.util.VeloceLog.Block.attempt(
                com.craftingveloce.util.VeloceLog.Side.SERVER,
                "controller right-clicked at %s by %s (client=%s)",
                pos, player.getName().getString(), world.isClientSide);
        if (!world.isClientSide && player instanceof ServerPlayer serverPlayer) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof VeloceControllerBlockEntity ctrl) {
                ctrl.syncToPlayer(serverPlayer);
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
    public void neighborChanged(BlockState state, Level world, BlockPos pos, Block block,
                                BlockPos neighbor, boolean isMoving) {
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
