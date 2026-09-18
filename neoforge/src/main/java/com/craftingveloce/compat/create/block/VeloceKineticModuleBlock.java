package com.craftingveloce.compat.create.block;

import com.craftingveloce.block.VeloceIntegraleFrame;
import com.craftingveloce.compat.create.KineticModule;
import com.craftingveloce.compat.create.block.entity.VeloceKineticModuleBlockEntity;
import com.craftingveloce.network.pipe.VeloceNetworkNode;
import com.craftingveloce.network.pipe.VeloceNodeBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import javax.annotation.Nullable;
import com.craftingveloce.block.VeloceIntegraleConversions;

/**
 * Veloce kinetic machine - one block for every Create family.
 *
 * <p><b>Drive.</b> The machine is kinetic: it keeps the rotation axis in the
 * block STATE (`axis`) and matches it automatically to a neighbour with a
 * drive, and we accept a shaft from both ends of that axis and from every side
 * where a kinetic machine with a matching axis stands. The side the drive
 * (or a Veloce pipe) arrives from is closed off with sheet metal in the casing.
 *
 * <p><b>Isolation.</b> The class extends Create's {@code KineticBlock}, so it
 * may only exist in {@code compat/create} and is created exclusively by the
 * {@code CreateCompat} gate - the core knows nothing about its existence.
 */
public class VeloceKineticModuleBlock extends KineticBlock
        implements EntityBlock, VeloceNetworkNode, IBE<VeloceKineticModuleBlockEntity> {

    /** Block entity factory - supplied by the module (the core does not know the registries). */
    @FunctionalInterface
    public interface BlockEntityFactory {
        VeloceKineticModuleBlockEntity create(BlockPos pos, BlockState state);
    }

    private final KineticModule module;
    private final BlockEntityFactory blockEntityFactory;
    private final java.util.function.Supplier<BlockEntityType<?>> blockEntityType;

    public VeloceKineticModuleBlock(KineticModule module,
                                    BlockEntityFactory blockEntityFactory,
                                    java.util.function.Supplier<BlockEntityType<?>> blockEntityType,
                                    Properties properties) {
        super(properties);
        this.module = module;
        this.blockEntityFactory = blockEntityFactory;
        this.blockEntityType = blockEntityType;
        registerDefaultState(stateDefinition.any()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.AXIS);
        VeloceIntegraleFrame.addProperties(builder);
    }

    /**
     * The rotation axis comes from the face the player is aiming at.
     *
     * <p>Player: "it should accept rotational power from EVERY side". Rotation
     * is only transferred between machines with a MATCHING axis, so the machine
     * has to take its axis from the placement position (like a Create shaft) -
     * then a drive from any side only needs a shaft connected along the same
     * axis.
     */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        Direction.Axis preferredAxis = context.getClickedFace().getAxis();
        
        // Before we place it, we check whether a drive is already nearby -
        // if so, we aim straight at its axis (thanks to this the network updates
        // automatically when the block is placed).
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = context.getClickedPos().relative(direction);
            BlockState neighbor = context.getLevel().getBlockState(neighborPos);
            Direction.Axis nAxis = neighbourAxis(context.getLevel(), neighborPos, neighbor);
            if (nAxis != null) {
                preferredAxis = nAxis;
                break;
            }
        }

        BlockState state = defaultBlockState().setValue(BlockStateProperties.AXIS, preferredAxis);
        for (Direction direction : Direction.values()) {
            state = VeloceIntegraleFrame.withClosure(state, direction,
                    covered(context.getLevel(), context.getClickedPos().relative(direction),
                            context.getLevel().getBlockState(context.getClickedPos().relative(direction))));
        }
        return state;
    }

    /**
     * A neighbouring block covers the casing side with sheet metal.
     *
     * <p>TWO things of the same weight are covered: a Veloce pipe and a Create
     * DRIVE. Player: "this side should behave as if a cable were connected on
     * this side" - that is, you can see at a glance where the machine gets its
     * rotation from.
     */
    private static boolean covered(net.minecraft.world.level.BlockGetter world,
                                   BlockPos neighborPos, BlockState neighbor) {
        if (neighbor.getBlock() instanceof com.craftingveloce.block.VelocePipeBlock) {
            return true;
        }
        return world.getBlockEntity(neighborPos)
                instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction facing, BlockState facingState,
                                     LevelAccessor world, BlockPos pos, BlockPos facingPos) {
        return VeloceIntegraleFrame.withClosure(state, facing,
                covered(world, facingPos, facingState));
    }

    /** Rotating the structure rotates the drive axis (X &lt;-&gt; Z), like a Create shaft. */
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        if (rotation == Rotation.NONE) {
            return state;
        }
        Direction.Axis axis = state.getValue(BlockStateProperties.AXIS);
        if (axis == Direction.Axis.Y) {
            return state;
        }
        return state.setValue(BlockStateProperties.AXIS,
                axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state;
    }

    /**
     * The block entity type of this machine.
     *
     * <p><b>Critical for operation.</b> Create ticks its machines through the
     * default {@code getTicker} of {@link IBE} - without implementing this
     * interface the block entity would NEVER be ticked, {@code getSpeed()}
     * would be zero and the machine would forever be "without a drive" (a silent
     * bug: the block stands there, does nothing, and the logs show not a trace).
     */
    @Override
    public Class<VeloceKineticModuleBlockEntity> getBlockEntityClass() {
        return VeloceKineticModuleBlockEntity.class;
    }

    @Override
    @SuppressWarnings("unchecked")
    public BlockEntityType<? extends VeloceKineticModuleBlockEntity> getBlockEntityType() {
        return (BlockEntityType<? extends VeloceKineticModuleBlockEntity>)
                (BlockEntityType<?>) blockEntityType.get();
    }

    /** Machine description (recipe type, label, SU). */
    public KineticModule module() {
        return module;
    }

    /**
     * Right-clicking with the base item adds an element of the machine (a
     * crushing wheel, a crafter grid slot). One click = one element - that is
     * how the player described it.
     */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(
            net.minecraft.world.item.ItemStack stack, BlockState state, Level world, BlockPos pos,
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.InteractionHand hand,
            net.minecraft.world.phys.BlockHitResult hit) {
        net.minecraft.world.item.Item part = partItem();
        if (part == null || !stack.is(part)) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!world.isClientSide
                && world.getBlockEntity(pos) instanceof VeloceKineticModuleBlockEntity be
                && be.addPart()) {
            if (player == null || !player.isCreative()) {
                stack.shrink(1);
            }
            world.playSound(null, pos, net.minecraft.sounds.SoundEvents.ITEM_FRAME_ADD_ITEM,
                    net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1.2F);
            if (player != null) {
                // Action bar notification: the grid layout ITSELF ("1x2", "9x9"),
                // with no other text - the player wants to see how many slots the
                // machine has.
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(be.gridLabel()), true);
            }
            return net.minecraft.world.ItemInteractionResult.sidedSuccess(false);
        }
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(world.isClientSide);
    }

    /** The Create base item that we add to this machine (or null). */
    private net.minecraft.world.item.Item partItem() {
        String id = module.id();
        if ("create:crushing".equals(id)) {
            return item("crushing_wheel");
        }
        if ("create:mechanical_crafting".equals(id)) {
            return item("mechanical_crafter");
        }
        return null;
    }

    private static net.minecraft.world.item.Item item(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", id));
    }

    /**
     * Breaking the machine returns it with the SAVED number of elements.
     *
     * <p>Player: "let a given block have remembered in its NBT tag exactly how
     * many crafters it contains inside - after the structure is destroyed
     * exactly the same number stays in the NBT of the dropped item, and after
     * placing it should keep that same value". So we save the counter in the
     * block entity data of the item (BlockItem.setBlockEntityData), and on
     * placement the ordinary Minecraft path restores it
     * (updateCustomBlockEntityTag -> read).
     */
    @Override
    protected java.util.List<ItemStack> getDrops(BlockState state,
                                                 net.minecraft.world.level.storage.loot.LootParams.Builder params) {
        // RULE: breaking a module gives back an EMPTY Integrale and the block that was
        // put into it - NEVER the module. A module is not an item a player can hold any
        // more; dropping "itself" would hand out something unobtainable AND eat the frame
        // and the block that were spent. This replaces the old drop, which was the block.
        //
        // The casing elements are returned as REAL BLOCKS, one per part, and that is why
        // the NBT counter is gone: the counter existed to carry "how many crafters are
        // inside" through an item, and the frame has no block entity to carry it on. The
        // blocks themselves cannot be lost that way, and the player gets back exactly
        // what they clicked in.
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        out.add(new ItemStack(
                com.craftingveloce.init.VeloceRegistry.VELOCE_INTEGRALE_ITEM.get()));
        int parts = 1;
        if (params.getOptionalParameter(
                net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY)
                instanceof VeloceKineticModuleBlockEntity be && be.caseParts() > 0) {
            parts = be.caseParts();
        }
        VeloceIntegraleConversions.Conversion back = VeloceIntegraleConversions.forBlock(this);
        if (back != null) {
            net.minecraft.world.item.Item in =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(back.inputId());
            if (in != net.minecraft.world.item.Items.AIR) {
                out.add(new ItemStack(in, parts));
            }
        }
        return out;
    }

    /**
     * How many elements the item of this machine should have "out of the box".
     *
     * <p>Player: "when I take this item with middle click or from the creative
     * inventory, I get an empty one, and I don't want an empty one - the
     * crushing wheel should have two wheels, and the crafter grid 3x3".
     * Machines without elements return 0.
     */
    public int defaultParts() {
        if (module == com.craftingveloce.compat.create.CreateKineticModules.CRUSHING) {
            return 2;
        }
        if (module == com.craftingveloce.compat.create.CreateKineticModules.MECHANICAL_CRAFTING) {
            return 9;   // 3x3
        }
        return 0;
    }

    /**
     * The item of this machine with the elements SAVED (creative, middle click).
     *
     * <p>Thanks to this a placed copy has the full set right away and looks like
     * a machine instead of an empty casing. The counter travels in the same
     * block entity data that breaking the machine writes, so there is only one
     * mechanism.
     */
    public ItemStack filledStack() {
        ItemStack stack = new ItemStack(this);
        int parts = defaultParts();
        if (parts > 0) {
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.putInt("VeloceParts", parts);
            net.minecraft.world.item.BlockItem.setBlockEntityData(stack,
                    blockEntityType.get(), tag);
        }
        return stack;
    }

    /**
     * Middle click (pick block) gives an assembled item, not an empty one.
     *
     * <p>The player wants to get a machine with elements right away - otherwise
     * every creative copy would have to be clicked up from scratch.
     */
    @Override
    public ItemStack getCloneItemStack(BlockState state, net.minecraft.world.phys.HitResult target,
                                       net.minecraft.world.level.LevelReader level, BlockPos pos,
                                       net.minecraft.world.entity.player.Player player) {
        return filledStack();
    }

    /** Whether this machine should ignore power in the MODEL (crafter: no reaction to a drive). */
    public boolean ignoresPowerInModel() {
        return module == com.craftingveloce.compat.create.CreateKineticModules.MECHANICAL_CRAFTING;
    }

    /**
     * Right-clicking without an item opens the module window (speed, SU, network).
     *
     * <p>Player: "when I right-click it, a GUI opens that shows the current
     * speed / maximum, minimum required, currently how much SU it gets / how
     * much is needed, and information about the network".
     */
    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(
            BlockState state, Level world, BlockPos pos,
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.phys.BlockHitResult hit) {
        // An ordinary container window - like in a furnace (menu + the same texture).
        if (!world.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer) {
            player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                            (id, inv, p) -> new com.craftingveloce.inventory.VeloceKineticMenu(
                                    id, inv, pos),
                            state.getBlock().getName()),
                    buf -> buf.writeBlockPos(pos));
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(world.isClientSide);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return blockEntityFactory.create(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * We accept a drive shaft from EVERY side.
     *
     * <p>Rotation is only transferred between matching axes, so besides the two
     * ends of its own axis we also accept a shaft where a kinetic machine with
     * the same axis stands - and the machine's axis matches the neighbour
     * automatically (see {@link #neighborChanged}).
     */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state,
                                   Direction side) {
        Direction.Axis own = state.getValue(BlockStateProperties.AXIS);
        if (side.getAxis() == own) {
            return true;
        }
        return neighbourAxis(world, pos.relative(side), world.getBlockState(pos.relative(side))) == own;
    }

    /**
     * The machine's axis matches a neighbour with a drive.
     *
     * <p>Without this, a machine placed next to a horizontal shaft was left with
     * a vertical axis and "did not accept power from that side" - while the
     * player expects it to be enough to put it next to a drive.
     */
    @Override
    protected void neighborChanged(BlockState state, Level world, BlockPos pos, Block fromBlock,
                                   BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, world, pos, fromBlock, fromPos, isMoving);
        if (world.isClientSide) {
            return;
        }
        // The axis matches the drive from EVERY side - INCLUDING in the crafter.
        //
        // The BUG this fixes: previously the crafter (ignoresPowerInModel)
        // returned here right away, so it was left with the axis from the moment
        // of placement (e.g. vertical) and did not accept rotation from the
        // side. Player: "I want our Create blocks to accept power from every
        // side". The crafter's model still does NOT react to power - it does not
        // spin, and that is a separate condition (caseSpinDegreesPerTick checks
        // ignoresPowerInModel).
        Direction.Axis axis = neighbourAxis(world, fromPos, world.getBlockState(fromPos));
        if (axis != null && axis != state.getValue(BlockStateProperties.AXIS)) {
            world.setBlock(pos, state.setValue(BlockStateProperties.AXIS, axis), Block.UPDATE_ALL);
        }
    }

    /** The rotation axis of a neighbour, when that neighbour is a Create kinetic machine. */
    private static Direction.Axis neighbourAxis(net.minecraft.world.level.BlockGetter world,
                                                BlockPos pos, BlockState neighbour) {
        if (neighbour.getBlock() instanceof KineticBlock kinetic) {
            return kinetic.getRotationAxis(neighbour);
        }
        return null;
    }

    /** The machine's rotation axis - the one the player chose when placing it. */
    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(BlockStateProperties.AXIS);
    }

    /**
     * Veloce pipes connect on EVERY side - the mechanical drive (from below) has
     * nothing to do with it. If we restricted this to the rotation axis, it
     * would be impossible to connect the machine to the logistics network from
     * the side.
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

    /** The block has no shape of its own for collision - a full cube. */
    @Override
    public net.minecraft.world.phys.shapes.VoxelShape getShape(
            BlockState state, BlockGetter world, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        return net.minecraft.world.phys.shapes.Shapes.block();
    }
}
