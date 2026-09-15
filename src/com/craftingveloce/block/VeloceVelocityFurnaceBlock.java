package com.craftingveloce.block;

import com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Velocity Furnace - blok zrodla ciepla dla auto-craftera.
 *
 * <p>Blok jest WEZLEM sieci (tak jak crafter czy extractor): musi byc
 * postawiony obok rury, wtedy siec go widzi i utrzymuje jego chunk, zeby
 * piec mogl palic sie CALY CZAS - nawet gdy gracz jest daleko.
 */
public class VeloceVelocityFurnaceBlock extends BaseEntityBlock implements EntityBlock {

    public VeloceVelocityFurnaceBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VeloceVelocityFurnaceBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (world.isClientSide) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof VeloceVelocityFurnaceBlockEntity furnace) {
                furnace.serverTick();
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!world.isClientSide && player instanceof ServerPlayer serverPlayer) {
            // Otwieramy normalne menu. Ekran (plomyk + 6 filtrow) jest
            // zarejestrowany w RegisterMenuScreensEvent - bez tego wpisu
            // otwarcie menu wysypaloby klienta.
            //
            // Block entity moze byc chwilowo niedostepne (chunk w trakcie
            // ladowania) - wtedy po prostu nic nie otwieramy.
            if (world.getBlockEntity(pos) instanceof MenuProvider provider) {
                serverPlayer.openMenu(provider, pos);
            }
        }
        return InteractionResult.sidedSuccess(world.isClientSide);
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(VeloceVelocityFurnaceBlock::new);
    }

}
