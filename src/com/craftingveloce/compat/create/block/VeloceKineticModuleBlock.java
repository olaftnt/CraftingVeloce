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

/**
 * Maszyna kinetyczna Veloce - jeden blok dla wszystkich rodzin Create.
 *
 * <p><b>Naped.</b> Maszyna jest kinetyczna: os obrotu trzyma w STANIE bloku
 * (`axis`) i dopasowuje ja automatycznie do sasiada z napedem, a wal
 * przyjmujemy z obu koncow tej osi oraz z kazdej strony, gdzie stoi maszyna
 * kinetyczna o zgodnej osi. Bok, z ktorego dochodzi naped (albo rura Veloce),
 * zamyka sie blacha w obudowie.
 *
 * <p><b>Izolacja.</b> Klasa dziedziczy po {@code KineticBlock} z Create, wiec
 * moze istniec tylko w {@code compat/create} i jest tworzona wylacznie przez
 * bramke {@code CreateCompat} - rdzen nie wie o jej istnieniu.
 */
public class VeloceKineticModuleBlock extends KineticBlock
        implements EntityBlock, VeloceNetworkNode, IBE<VeloceKineticModuleBlockEntity> {

    /** Fabryka block entity - dostarczana przez modul (rdzen nie zna rejestrow). */
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
     * Os obrotu bierze sie ze sciany, w ktora celuje gracz.
     *
     * <p>Gracz: "powinno przyjmowac power krecenia z KAZDEJ strony". Krecenie
     * przenosi sie tylko miedzy maszynami o ZGODNEJ osi, wiec maszyna musi
     * przyjmowac os z miejsca postawienia (jak wal Create) - wtedy naped
     * z kazdej strony wystarczy podlaczyc walem w tej samej osi.
     */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(BlockStateProperties.AXIS,
                context.getClickedFace().getAxis());
        for (Direction direction : Direction.values()) {
            state = VeloceIntegraleFrame.withClosure(state, direction,
                    covered(context.getLevel(), context.getClickedPos().relative(direction),
                            context.getLevel().getBlockState(context.getClickedPos().relative(direction))));
        }
        return state;
    }

    /**
     * Sasiedni blok zakrywa bok obudowy blacha.
     *
     * <p>Zakryte sa DWIE rzeczy tej samej wagi: rura Veloce i NAPED Create.
     * Gracz: "ten bok ma sie zachowywac tak, jakby byl kabel podlaczony
     * z tej strony" - czyli od razu widac, skad maszyna dostaje krecenie.
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

    /** Obrot konstrukcji obraca os napedu (X &lt;-&gt; Z), jak w walku Create. */
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
     * Typ block entity tej maszyny.
     *
     * <p><b>Krytyczne dla dzialania.</b> Create tickuje swoje maszyny przez
     * domyslny {@code getTicker} z {@link IBE} - bez implementacji tego
     * interfejsu block entity NIGDY nie bylby tickowany, {@code getSpeed()}
     * zostaloby zerem i maszyna na zawsze bylaby "bez napedu" (cichy blad:
     * blok stoi, nic nie robi, a w logach nie ma ani sladu).
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

    /** Opis maszyny (typ receptury, etykieta, SU). */
    public KineticModule module() {
        return module;
    }

    /**
     * Prawy klik itemem bazowym dokłada element maszyny (kolo mlynskie, oczko
     * craftera). Jeden klik = jeden element - tak gracz to opisal.
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
                // Powiadomienie na pasku akcji: SAM uklad siatki ("1x2", "9x9"),
                // bez zadnego tekstu - gracz chce widziec, ile pol ma maszyna.
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(be.gridLabel()), true);
            }
            return net.minecraft.world.ItemInteractionResult.sidedSuccess(false);
        }
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(world.isClientSide);
    }

    /** Item bazowy z Create, ktory dokladamy do tej maszyny (albo null). */
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
     * Zbicie maszyny oddaje ja z ZAPISANA liczba elementow.
     *
     * <p>Gracz: "niech dany blok ma w tagu NBT zapamietane, ile dokladnie
     * crafterow zawiera w srodku - po zniszczeniu konstrukcji dokladnie ta sama
     * liczba zostaje w NBT dropnietego itemu, a po postawieniu ma zachowac te
     * sama wartosc". Zapisujemy wiec licznik w danych block entity itemu
     * (BlockItem.setBlockEntityData), a przy postawieniu odtwarza go zwykla
     * sciezka Minecrafta (updateCustomBlockEntityTag -> read).
     */
    @Override
    protected java.util.List<ItemStack> getDrops(BlockState state,
                                                 net.minecraft.world.level.storage.loot.LootParams.Builder params) {
        ItemStack stack = new ItemStack(this);
        if (params.getOptionalParameter(
                net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY)
                instanceof VeloceKineticModuleBlockEntity be && be.caseParts() > 0) {
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.putInt("VeloceParts", be.caseParts());
            net.minecraft.world.item.BlockItem.setBlockEntityData(stack,
                    blockEntityType.get(), tag);
        }
        return java.util.List.of(stack);
    }

    /**
     * Ile elementow ma miec przedmiot tej maszyny "z pudełka".
     *
     * <p>Gracz: "jak wezmę ten itemek middle clickiem albo z ekwipunku
     * kreatywnego, to dostaję pusty, a nie chcę pustego - crushing wheel ma
     * mieć dwa koła, a crafter grid 3x3". Maszyny bez elementow zwracaja 0.
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
     * Przedmiot tej maszyny z ZAPISANYMI elementami (kreatywnosc, middle click).
     *
     * <p>Dzieki temu postawiony egzemplarz od razu ma komplet i wyglada jak
     * maszyna, a nie pusta obudowa. Licznik jedzie w tych samych danych block
     * entity, ktore zapisuje zbicie maszyny, wiec mechanizm jest jeden.
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
     * Middle click (pick block) daje przedmiot WYPELNIONY, nie pusty.
     *
     * <p>Gracz chce od razu dostac maszyne z elementami - inaczej kazdy
     * egzemplarz z kreatywnosci trzeba by klikac od zera.
     */
    @Override
    public ItemStack getCloneItemStack(BlockState state, net.minecraft.world.phys.HitResult target,
                                       net.minecraft.world.level.LevelReader level, BlockPos pos,
                                       net.minecraft.world.entity.player.Player player) {
        return filledStack();
    }

    /** Czy ta maszyna ma ignorowac moc w MODELU (crafter: zero reakcji na naped). */
    public boolean ignoresPowerInModel() {
        return module == com.craftingveloce.compat.create.CreateKineticModules.MECHANICAL_CRAFTING;
    }

    /**
     * Prawy klik bez itemu otwiera okno modulu (predkosc, SU, sieć).
     *
     * <p>Gracz: "jak klikne na nie prawym guzikiem myszy, to otwiera sie GUI,
     * ktore pokazuje aktualna predkosc / maksymalna, minimalna wymagana,
     * aktualnie ile dostaje SU / ile jest potrzebne, no i informacje o sieci".
     */
    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(
            BlockState state, Level world, BlockPos pos,
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.phys.BlockHitResult hit) {
        if (!world.isClientSide
                && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                && world.getBlockEntity(pos)
                instanceof com.craftingveloce.block.entity.VeloceModuleInfoSource source
                && world instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer,
                    new com.craftingveloce.network.OpenModuleInfoPKT(pos,
                            source.moduleInfo(serverLevel)));
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
     * Wal napedowy przyjmujemy z KAZDEJ strony.
     *
     * <p>Krecenie przenosi sie tylko miedzy zgodnymi osiami, wiec poza dwoma
     * koncami wlasnej osi przyjmujemy wal takze tam, gdzie stoi maszyna
     * kinetyczna o tej samej osi - a os maszyny dopasowuje sie do sasiada
     * automatycznie (patrz {@link #neighborChanged}).
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
     * Os maszyny dopasowuje sie do sasiada z napedem.
     *
     * <p>Bez tego maszyna postawiona obok poziomego walu zostawala z pionowa
     * osia i "nie przyjmowala mocy z tej strony" - a gracz oczekuje, ze
     * wystarczy dostawic ja do napedu.
     */
    @Override
    protected void neighborChanged(BlockState state, Level world, BlockPos pos, Block fromBlock,
                                   BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, world, pos, fromBlock, fromPos, isMoving);
        if (world.isClientSide) {
            return;
        }
        if (ignoresPowerInModel()) {
            // Crafter mechaniczny nie moze zmieniac stanu (a wiec i modelu)
            // pod wplywem podlaczonego napedu - gracz: "niech w zadnym sposob
            // nie reaguje na dostarczana moc".
            return;
        }
        Direction.Axis axis = neighbourAxis(world, fromPos, world.getBlockState(fromPos));
        if (axis != null && axis != state.getValue(BlockStateProperties.AXIS)) {
            world.setBlock(pos, state.setValue(BlockStateProperties.AXIS, axis), Block.UPDATE_ALL);
        }
    }

    /** Os obrotu sasiada, gdy jest nim maszyna kinetyczna Create. */
    private static Direction.Axis neighbourAxis(net.minecraft.world.level.BlockGetter world,
                                                BlockPos pos, BlockState neighbour) {
        if (neighbour.getBlock() instanceof KineticBlock kinetic) {
            return kinetic.getRotationAxis(neighbour);
        }
        return null;
    }

    /** Os obrotu maszyny - taka, jaka wybral gracz przy postawieniu. */
    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(BlockStateProperties.AXIS);
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
