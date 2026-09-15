package com.craftingveloce.block;

import com.craftingveloce.block.entity.VeloceElectricFurnaceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Velocity Electric Furnace - zrodlo ciepla zasilane Forge Energy.
 *
 * <p>Blok jest WEZLEM sieci (jak crafter czy extractor), wiec moze stac
 * wszedzie tam, gdzie rura - a crafter go znajdzie i uzyje z PRIORYTETEM
 * przed piecem paliwowym.
 *
 * <p>Nie ma tickera: energia przychodzi z sieci kablowej przez capability
 * {@code EnergyStorage}, a zuzywa sie wylacznie przy przepalaniu w crafterze.
 * Dzieki temu piec nic nie robi, gdy nikt nie craftuje.
 */
public class VeloceElectricFurnaceBlock extends BaseEntityBlock implements EntityBlock {

    public VeloceElectricFurnaceBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VeloceElectricFurnaceBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        // GUI jest w budowie - nie otwieramy menu bez ekranu klienta (crash).
        // Na razie pokazujemy stan, zeby dalo sie sprawdzic, ze piec dziala.
        if (!world.isClientSide && world.getBlockEntity(pos)
                instanceof VeloceElectricFurnaceBlockEntity furnace) {
            player.displayClientMessage(Component.literal(
                    "Velocity Electric Furnace: FE = " + furnace.getEnergy()
                            + " / " + VeloceElectricFurnaceBlockEntity.ENERGY_CAPACITY
                            + ", operacje = " + furnace.availableOperations()
                            + ", zasilony = " + furnace.isPowered()), false);
        }
        return InteractionResult.sidedSuccess(world.isClientSide);
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(VeloceElectricFurnaceBlock::new);
    }
}
