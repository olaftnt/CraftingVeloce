package com.craftingveloce.block;

import com.craftingveloce.block.entity.VeloceThresholdSensorBlockEntity;
import com.tom.storagemod.block.IInventoryCable;
import com.tom.storagemod.inventory.InventoryCableNetwork;
import com.tom.storagemod.util.BlockFace;
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
import com.craftingveloce.network.pipe.VeloceNodeBlocks;

/**
 * Veloce Threshold Sensor - wylacznik sieciowy oparty na stanie magazynu.
 *
 * <p><b>Do czego sluzy.</b> Gracz wybiera item i prog ("np. 64 zelaza").
 * Sensor patrzy, ile tego itemu jest FIZYCZNIE w sieci, i wystawia redstone
 * zaleznie od wyniku. Typowe uzycie: skonczylo sie zelazo - wlacz prad, zeby
 * ruszyla fabryka, ktora je dowozi.
 *
 * <p><b>To WEZEL sieci</b> (jak terminal, crafter czy piec): musi stac obok
 * rury, a jego chunk jest utrzymywany, zeby pilnowal stanu takze wtedy, gdy
 * gracz jest daleko. Bez tego sensor przestalby dzialac dokladnie w tej
 * sytuacji, do ktorej zostal zrobiony - czyli przy automatyzacji bez gracza.
 *
 * <p><b>Wyjscie redstone.</b> Wynik trzymamy w STANIE BLOKU ({@code POWERED}),
 * a nie tylko w block entity. Powod jest mechaniczny: Minecraft rozglasza
 * zmiane stanu bloku sasiadom, i wlasnie to powiadomienie uruchamia maszyny
 * obok. Sam block entity nie wysyla nikomu nic.
 *
 * <p>Dajemy moc MOCNA (zarowno {@code getSignal}, jak i {@code getDirectSignal}),
 * czyli zachowujemy sie jak blok redstone: dziala i na maszyny obok, i na
 * przewod polozony przy sensorze. Slabsze wyjscie zmuszaloby gracza do
 * zgadywania, gdzie wolno postawic przewod.
 */
public class VeloceThresholdSensorBlock extends BaseEntityBlock implements EntityBlock, IInventoryCable {

    /** Czy sensor wystawia teraz prad. */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public VeloceThresholdSensorBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(3.0F)
                .requiresCorrectToolForDrops());
        registerDefaultState(this.stateDefinition.any().setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // ------------------------------------------------------------------
    // Wyjscie redstone
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
        // Moc mocna: sensor ma dzialac jak blok redstone, takze na przewod
        // polozony bezposrednio przy nim.
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
     * Sensor MUSI tykac - inaczej nigdy nie sprawdzi sieci.
     *
     * <p>Sprawdzenie jest rozlozone: raz na 10 tickow, a nie co tick. Skan
     * magazynow sieci jest drogi, a sensor nie musi reagowac szybciej niz
     * zareaguje maszyna po drugiej stronie redstone'a.
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
    // Wezel sieci - dokladnie jak pozostale maszyny
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
            InventoryCableNetwork n = InventoryCableNetwork.getNetwork(world);
            n.markNodeInvalid(pos);
            n.markNodeInvalid(neighbor);
        }
    }

    /** Sensor laczy sie z kazdej strony - nie ma przodu ani tylu. */
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
    protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(properties -> new VeloceThresholdSensorBlock());
    }
}
