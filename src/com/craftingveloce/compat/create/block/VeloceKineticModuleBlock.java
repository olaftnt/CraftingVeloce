package com.craftingveloce.compat.create.block;

import com.craftingveloce.compat.create.KineticModule;
import com.craftingveloce.compat.create.block.entity.VeloceKineticModuleBlockEntity;
import com.craftingveloce.network.pipe.VeloceNetworkNode;
import com.craftingveloce.network.pipe.VeloceNodeBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Maszyna kinetyczna Veloce - jeden blok dla wszystkich rodzin Create.
 *
 * <p><b>Napęd.</b> Blok jest maszyna obrotowa Create: wal napedowy wchodzi od
 * DOLU (jak w mlynku Create), a os obrotu jest pionowa (Y). Rury Veloce mozna
 * podlaczyc z KAZDEJ strony - to dwie niezalezne rzeczy (naped mechaniczny
 * i siec logistyczna).
 *
 * <p><b>Izolacja.</b> Klasa dziedziczy po {@code KineticBlock} z Create, wiec
 * moze istniec tylko w {@code compat/create} i jest tworzona wylacznie przez
 * bramke {@code CreateCompat} - rdzen nie wie o jej istnieniu.
 */
public class VeloceKineticModuleBlock extends KineticBlock
        implements EntityBlock, VeloceNetworkNode {

    /** Fabryka block entity - dostarczana przez modul (rdzen nie zna rejestrow). */
    @FunctionalInterface
    public interface BlockEntityFactory {
        VeloceKineticModuleBlockEntity create(BlockPos pos, BlockState state);
    }

    private final KineticModule module;
    private final BlockEntityFactory blockEntityFactory;

    public VeloceKineticModuleBlock(KineticModule module,
                                    BlockEntityFactory blockEntityFactory,
                                    Properties properties) {
        super(properties);
        this.module = module;
        this.blockEntityFactory = blockEntityFactory;
    }

    /** Opis maszyny (typ receptury, etykieta, SU). */
    public KineticModule module() {
        return module;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return blockEntityFactory.create(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /** Wal napedowy wchodzi od dolu - tak jak w mlynku Create. */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state,
                                   Direction side) {
        return side == Direction.DOWN;
    }

    /** Os obrotu pionowa (Y) - zgodnie z walem od dolu. */
    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return Direction.Axis.Y;
    }

    /**
     * Rury Veloce lacza sie z KAZDEJ strony - naped mechaniczny (od dolu) nie
     * ma z tym nic wspolnego. Gdyby ograniczyc to do osi obrotu, nie daloby
     * sie podlaczyc maszyny do sieci logistycznej z boku.
     */
    @Override
    public boolean canConnectFrom(BlockState state, Direction towardPipe) {
        return true;
    }

    @Override
    public void setPlacedBy(Level world, BlockPos pos, BlockState state,
                            @Nullable net.minecraft.world.entity.LivingEntity placer,
                            net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(world, pos, state, placer, stack);
        if (!world.isClientSide) {
            VeloceNodeBlocks.onNodePlaced(world, pos);
        }
    }

    @Override
    public void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState,
                         boolean moved) {
        super.onRemove(state, world, pos, newState, moved);
        VeloceNodeBlocks.onNodeRemoved(world, pos);
    }

    /** Blok nie ma wlasnego ksztaltu kolizji - pelny sześcian. */
    @Override
    public net.minecraft.world.phys.shapes.VoxelShape getShape(
            BlockState state, BlockGetter world, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        return net.minecraft.world.phys.shapes.Shapes.block();
    }
}
