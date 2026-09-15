package com.craftingveloce.compat.mekanism.block;

import com.craftingveloce.compat.mekanism.block.entity.VeloceCrusherModuleBlockEntity;
import com.craftingveloce.network.pipe.VeloceNetworkNode;
import com.craftingveloce.network.pipe.VeloceNodeBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Veloce Crusher Module - maszyna Veloce dla receptur {@code mekanism:crushing}.
 *
 * <p><b>Czym to jest.</b> Wlasny blok Veloce (nie kopia maszyny Mekanism),
 * ktory stoi w sieci rur, przyjmuje FE kablem i pozwala auto-crafterowi
 * wykonywac receptury kruszenia. Mechanika jest natychmiastowa: operacje
 * rozlicza crafter, maszyna jest akumulatorem energii (jak Velocity Electric
 * Furnace) - dlatego nie ma tu GUI ani tickera z postepem.
 *
 * <p><b>Izolacja.</b> Ta klasa zyje w {@code compat/mekanism} i laduje sie
 * tylko przy obecnym Mekanism - mimo to nie zawiera ZADNEGO typu Mekanism:
 * maszyna jest w calosci nasza, a Mekanism dostarcza wylacznie receptury
 * (patrz {@code MekanismRecipeHarvest}).
 */
public class VeloceCrusherModuleBlock extends BaseEntityBlock
        implements EntityBlock, VeloceNetworkNode {

    public static final MapCodec<VeloceCrusherModuleBlock> CODEC =
            simpleCodec(VeloceCrusherModuleBlock::new);

    public VeloceCrusherModuleBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VeloceCrusherModuleBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /** Maszyna nie ma przodu ani tylu - laczy sie z rura z kazdej strony. */
    @Override
    public boolean canConnectFrom(BlockState state, Direction towardPipe) {
        return true;
    }

    /**
     * Klikniecie pokazuje stan akumulatora na pasku akcji.
     *
     * <p>Maszyna jest natychmiastowa i nie ma GUI (nie ma postepu ani slotow),
     * ale gracz musi moc sprawdzic, czy w ogole dochodzi do niej prad - bez
     * tego jedynym objawem "brak pradu" byloby to, ze automat nic nie robi.
     */
    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(
            BlockState state, Level world, BlockPos pos,
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.phys.BlockHitResult hit) {
        if (!world.isClientSide
                && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                && world.getBlockEntity(pos)
                instanceof com.craftingveloce.compat.mekanism.block.entity.VeloceCrusherModuleBlockEntity module) {
            module.sendStatus(serverPlayer);
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(world.isClientSide);
    }

    /**
     * Zgloszenie do sieci przy postawieniu.
     *
     * <p>Bez tego postawienie maszyny obok istniejacej rury nie odswiezyloby
     * sieci i crafter by jej nie widzial - dokladnie ten blad, ktory mialy
     * piece, zanim dostaly ten hook.
     */
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
    public void destroy(LevelAccessor world, BlockPos pos, BlockState state) {
        super.destroy(world, pos, state);
        VeloceNodeBlocks.onNodeRemoved(world, pos);
    }
}
