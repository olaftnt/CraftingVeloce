package com.craftingveloce.block;

import com.craftingveloce.block.entity.VeloceFeModuleBlockEntity;
import com.craftingveloce.crafting.FeModule;
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
import com.craftingveloce.block.VeloceIntegraleConversions;

/**
 * A FE-powered Mekanism module machine - one block for every family.
 *
 * <p><b>What this is.</b> A Veloce block of our own (not a copy of a Mekanism
 * machine) that stands in the pipe network, accepts FE through a cable
 * (the {@code EnergyStorage} capability) and lets the auto-crafter perform
 * recipes of its type. The mechanics are instantaneous - the crafter settles
 * the operations, and the machine is an energy accumulator, which is why it has
 * no GUI and no ticker with progress.
 *
 * <p><b>One class, four machines.</b> Crusher, enrichment, combining and sawing
 * differ only in data ({@link FeModule}), so they share the same block and the
 * same block entity. Adding another machine with the same shape (1-2 items ->
 * item) is one row in {@code FeModule.ALL}.
 *
 * <p><b>Isolation.</b> The class lives in {@code compat/mekanism} and loads only
 * when Mekanism is present - despite that it contains NO Mekanism type: the
 * machine is entirely ours, and Mekanism only supplies the recipes.
 */
public class VeloceFeModuleBlock extends BaseEntityBlock
        implements EntityBlock, VeloceNetworkNode {

    private final FeModule module;

    private final VeloceFeModuleBlockEntity.Factory blockEntityFactory;

    public VeloceFeModuleBlock(FeModule module,
                               VeloceFeModuleBlockEntity.Factory blockEntityFactory,
                               BlockBehaviour.Properties properties) {
        super(properties);
        this.module = module;
        this.blockEntityFactory = blockEntityFactory;
    }

    /** Machine description (recipe type, FE cost, label). */
    public FeModule module() {
        return module;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(properties ->
                new VeloceFeModuleBlock(module, blockEntityFactory, properties));
    }

    /** Server-side tick: drawing power from the item in the battery slot. */
    @Override
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
            Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof com.craftingveloce.block.entity.VeloceFeModuleBlockEntity module) {
                module.serverTick();
            }
        };
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return blockEntityFactory.create(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /** The machine has no front or back - it connects to a pipe on every side. */
    @Override
    public boolean canConnectFrom(BlockState state, Direction towardPipe) {
        return true;
    }

    /**
     * Clicking shows the accumulator status on the action bar.
     *
     * <p>The machine is instantaneous and has no GUI (no progress and no slots),
     * but the player has to be able to check whether power is reaching it -
     * without that, the only symptom of "no power" would be the automation doing
     * nothing.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos,
                                               net.minecraft.world.entity.player.Player player,
                                               net.minecraft.world.phys.BlockHitResult hit) {
        // Right-click opens an ordinary container window - EXACTLY as in a
        // furnace (menu + screen with the same texture). No packets.
        if (!world.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer) {
            player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                            (id, inv, p) -> new com.craftingveloce.inventory.VeloceModuleMenu(
                                    id, inv, pos),
                            state.getBlock().getName()),
                    buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.sidedSuccess(world.isClientSide);
    }

    /**
     * Reporting to the network on placement.
     *
     * <p>Without this, placing the machine next to an existing pipe would not
     * refresh the network and the crafter would not see it - exactly the bug the
     * furnaces had before they got this hook.
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

    /** Casing cap properties: one for each side of the world. */
    @Override
    protected void createBlockStateDefinition(
            net.minecraft.world.level.block.state.StateDefinition.Builder<
                    net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        com.craftingveloce.block.VeloceIntegraleFrame.addProperties(builder);
    }

    /** On placement we immediately close off the sides a cable arrives from. */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return com.craftingveloce.block.VeloceIntegraleFrame.withPlacementClosures(
                context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    /** Closing the sheet metal on the face a Veloce pipe stands against. */
    @Override
    protected BlockState updateShape(BlockState state, net.minecraft.core.Direction facing,
                                     BlockState facingState, net.minecraft.world.level.LevelAccessor world,
                                     BlockPos pos, BlockPos facingPos) {
        return com.craftingveloce.block.VeloceIntegraleFrame.withClosure(state, facing, facingState);
    }

    /**
     * What breaking this machine gives back: an EMPTY Integrale, and the block that was
     * put into it - never the module itself.
     *
     * <p><b>Why.</b> A module is no longer crafted; it EXISTS only as a block in the
     * world, made by right-clicking an Integrale with a foreign machine. Dropping "the
     * module" would hand the player an item they cannot otherwise obtain, and would eat
     * both the frame and the block they spent. So the drop is the reverse of the same
     * one table that builds the machine - which is why this lives here and not in 28
     * loot tables: two sources of truth for drops is how the two drift apart.
     *
     * <p>Hardness and tool are vanilla's furnace ({@code strength(3.5F)} +
     * {@code requiresCorrectToolForDrops()}), so a pickaxe is what is needed, exactly
     * as the player asked for.
     */
    @Override
    public java.util.List<net.minecraft.world.item.ItemStack> getDrops(
            BlockState state, net.minecraft.world.level.storage.loot.LootParams.Builder params) {
        java.util.List<net.minecraft.world.item.ItemStack> out = new java.util.ArrayList<>();
        out.add(new net.minecraft.world.item.ItemStack(
                com.craftingveloce.init.VeloceRegistry.VELOCE_INTEGRALE_ITEM.get()));
        VeloceIntegraleConversions.Conversion back = VeloceIntegraleConversions.forBlock(this);
        if (back != null) {
            net.minecraft.world.item.Item in =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(back.inputId());
            if (in != net.minecraft.world.item.Items.AIR) {
                out.add(new net.minecraft.world.item.ItemStack(in));
            }
        }
        return out;
    }
}
