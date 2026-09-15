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
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import javax.annotation.Nullable;

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
            com.tom.storagemod.inventory.InventoryCableNetwork n =
                    com.tom.storagemod.inventory.InventoryCableNetwork.getNetwork(world);
            n.markNodeInvalid(pos);
            if (world instanceof net.minecraft.server.level.ServerLevel sl) {
                VelocePipeNetworkManager.get(sl).onTerminalPlaced(sl, pos);
            }
        }
    }

    @Override
    public void destroy(net.minecraft.world.level.LevelAccessor world, BlockPos pos,
                        BlockState state) {
        super.destroy(world, pos, state);
        if (world instanceof net.minecraft.server.level.ServerLevel l) {
            com.tom.storagemod.inventory.InventoryCableNetwork.getNetwork(l).markNodeInvalid(pos);
            VelocePipeNetworkManager.get(l).onTerminalRemoved(l, pos);
        }
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(VeloceVelocityFurnaceBlock::new);
    }

}
