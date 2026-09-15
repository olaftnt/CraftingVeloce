package com.craftingveloce.compat.mekanism.block;

import com.craftingveloce.compat.mekanism.FeModule;
import com.craftingveloce.compat.mekanism.block.entity.VeloceFeModuleBlockEntity;
import com.craftingveloce.network.pipe.VeloceNetworkNode;
import com.craftingveloce.network.pipe.VeloceNodeBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * Maszyna modulu Mekanism zasilana FE - jeden blok dla wszystkich rodzin.
 *
 * <p><b>Czym to jest.</b> Wlasny blok Veloce (nie kopia maszyny Mekanism),
 * ktory stoi w sieci rur, przyjmuje FE kablem (capability {@code EnergyStorage})
 * i pozwala auto-crafterowi wykonywac receptury swojego typu. Mechanika jest
 * natychmiastowa - operacje rozlicza crafter, a maszyna jest akumulatorem
 * energii, dlatego nie ma GUI ani tickera z postepem.
 *
 * <p><b>Jedna klasa, cztery maszyny.</b> Kruszarka, wzbogacanie, laczenie
 * i pilowanie roznia sie wylacznie danymi ({@link FeModule}), wiec dziela ten
 * sam blok i ten sam block entity. Dodanie kolejnej maszyny o tym samym
 * ksztalcie (1-2 itemy -> item) to jeden wiersz w {@code FeModule.ALL}.
 *
 * <p><b>Izolacja.</b> Klasa zyje w {@code compat/mekanism} i laduje sie tylko
 * przy obecnym Mekanism - mimo to nie zawiera ZADNEGO typu Mekanism: maszyna
 * jest w calosci nasza, a Mekanism dostarcza wylacznie receptury.
 */
public class VeloceFeModuleBlock extends BaseEntityBlock
        implements EntityBlock, VeloceNetworkNode {

    private final FeModule module;

    public VeloceFeModuleBlock(FeModule module, BlockBehaviour.Properties properties) {
        super(properties);
        this.module = module;
    }

    /** Opis maszyny (typ receptury, koszt FE, etykieta). */
    public FeModule module() {
        return module;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(properties -> new VeloceFeModuleBlock(module, properties));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return com.craftingveloce.compat.mekanism.MekanismBlockEntities
                .create(module, pos, state);
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
     * <p>Maszyna jest natychmiastowa i nie ma GUI (brak postepu i slotow), ale
     * gracz musi moc sprawdzic, czy dochodzi do niej prad - bez tego jedynym
     * objawem "brak pradu" byloby to, ze automat nic nie robi.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!world.isClientSide
                && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                && world.getBlockEntity(pos) instanceof VeloceFeModuleBlockEntity moduleEntity) {
            moduleEntity.sendStatus(serverPlayer);
        }
        return InteractionResult.sidedSuccess(world.isClientSide);
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
