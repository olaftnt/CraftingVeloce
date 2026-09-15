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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import javax.annotation.Nullable;
import com.craftingveloce.network.pipe.VeloceNodeBlocks;

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

    /**
     * Piec elektryczny tyka TYLKO po to, zeby odswiezac pasek energii w GUI.
     *
     * <p>Nie ma wlasnej logiki per tick: przetapianie jest natychmiastowe i
     * rozliczane przez craftera, ktory zabiera cieplo z tego akumulatora.
     * Bez tickera pasek w GUI zamarzlby na wartosci z chwili otwarcia okna.
     */
    @Override
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
            Level world, BlockState state,
            net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        if (world.isClientSide) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof VeloceElectricFurnaceBlockEntity furnace) {
                furnace.serverTick();
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        // Menu z paskiem energii. Piec nie ma slotow na przedmioty - to bufor
        // pradu dla craftera, wiec ekran pokazuje tylko akumulator.
        if (!world.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer sp
                && world.getBlockEntity(pos) instanceof net.minecraft.world.MenuProvider provider) {
            sp.openMenu(provider, pos);
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
        super.destroy(world, pos, state);
        VeloceNodeBlocks.onNodeRemoved(world, pos);
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(VeloceElectricFurnaceBlock::new);
    }
}
