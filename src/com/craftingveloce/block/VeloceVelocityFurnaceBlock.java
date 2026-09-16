package com.craftingveloce.block;

import com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity;
import com.craftingveloce.network.pipe.VeloceNetworkNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import javax.annotation.Nullable;
import com.craftingveloce.network.pipe.VeloceNodeBlocks;

/**
 * Velocity Furnace - blok zrodla ciepla dla auto-craftera.
 *
 * <p>Blok jest WEZLEM sieci (tak jak crafter czy extractor): musi byc
 * postawiony obok rury, wtedy siec go widzi i utrzymuje jego chunk, zeby
 * piec mogl palic sie CALY CZAS - nawet gdy gracz jest daleko.
 */
public class VeloceVelocityFurnaceBlock extends BaseEntityBlock
        implements EntityBlock, VeloceNetworkNode {

    public VeloceVelocityFurnaceBlock(Properties properties) {
        super(properties);
    }

    /**
     * Piec paliwowy nie ma przodu ani tylu, wiec laczy sie z rura z kazdej
     * strony - tak samo jak pozostale maszyny w modzie.
     */
    @Override
    public boolean canConnectFrom(BlockState state, Direction towardPipe) {
        return true;
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

    /**
     * Zglasza piec do sieci rur przy postawieniu.
     *
     * <p><b>BUG, ktory to naprawia.</b> Piec byl zarejestrowany jako wezel
     * (VeloceNodeBlocks) i potrafil dzialac jako zrodlo ciepla, ale NIE
     * zglaszal sie przy postawieniu - w przeciwienstwie do terminala,
     * kontrolera, craftera i ekstraktora. Skutek: postawienie pieca obok
     * istniejacej rury nie odswiezalo sieci, wiec piec nie byl w niej widziany
     * (a bez tego crafter go nie znajdowal i nie mial czym przepalac).
     *
     * <p>Dzialalo tylko w jedna strone: gdy rura byla stawiana PO piecu,
     * skan rury sam go odkrywal. Odwrotna kolejnosc - i nic.
     */
    @Override
    public void setPlacedBy(Level world, BlockPos pos, BlockState state,
                            @Nullable net.minecraft.world.entity.LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(world, pos, state, placer, stack);
        if (!world.isClientSide) {
            VeloceNodeBlocks.onNodePlaced(world, pos);
        }
    }

    @Override
    public void destroy(net.minecraft.world.level.LevelAccessor world, BlockPos pos,
                        BlockState state) {
        // Paliwo lezace w piecu to PRAWDZIWE przedmioty (piec sam je dociaga
        // z sieci), wiec musza wypasc - inaczej zburzenie pieca je gubi.
        //
        // Uwaga: filtry paliwa sa WIDMOWE (klikniecie tylko kopiuje item do
        // filtra, nie zabiera go graczowi). Ich oddanie tworzyloby przedmioty
        // z niczego, wiec oddajemy WYLACZNIE realny slot paliwa.
        if (world instanceof net.minecraft.server.level.ServerLevel sl
                && sl.getBlockEntity(pos) instanceof com.craftingveloce.block.entity.VeloceVelocityFurnaceBlockEntity be) {
            net.minecraft.world.Container fuel = be.getFuelSlot();
            net.minecraft.world.item.ItemStack inFuel = fuel.getItem(0);
            if (!inFuel.isEmpty()) {
                net.minecraft.world.Containers.dropItemStack(sl,
                        pos.getX(), pos.getY(), pos.getZ(), inFuel.copy());
                fuel.setItem(0, net.minecraft.world.item.ItemStack.EMPTY);
            }
        }
        super.destroy(world, pos, state);
        VeloceNodeBlocks.onNodeRemoved(world, pos);
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(VeloceVelocityFurnaceBlock::new);
    }


    /** Wlasciwosci zaslepek obudowy: po jednej na kazda strone swiata. */
    @Override
    protected void createBlockStateDefinition(
            net.minecraft.world.level.block.state.StateDefinition.Builder<
                    net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        com.craftingveloce.block.VeloceIntegraleFrame.addProperties(builder);
    }

    /** Przy postawieniu od razu zamykamy strony, z ktorych dochodzi kabel. */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return com.craftingveloce.block.VeloceIntegraleFrame.withPlacementClosures(
                context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    /** Domkniecie blachy na scianie, przy ktorej stoi rura Veloce. */
    @Override
    protected BlockState updateShape(BlockState state, net.minecraft.core.Direction facing,
                                     BlockState facingState, net.minecraft.world.level.LevelAccessor world,
                                     BlockPos pos, BlockPos facingPos) {
        return com.craftingveloce.block.VeloceIntegraleFrame.withClosure(state, facing, facingState);
    }
}
