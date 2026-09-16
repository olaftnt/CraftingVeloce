package com.craftingveloce.block;

import com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity;
import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.network.pipe.VeloceNetworkNode;
import com.craftingveloce.network.pipe.VeloceNodeBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * Veloce Integrale - ozdobna KLATKA: widac tylko krawedzie i narozniki,
 * srodek jest pusty.
 *
 * <p><b>Wyglad.</b> Model to dwanascie cienkich pretow biegnacych po
 * krawedziach szescianu plus fioletowa szyba w oknach, wiec blok wyglada jak
 * narysowany kwadrat - linie po rogach, szyba w srodku.
 *
 * <p><b>Kolizja.</b> ZWYKLY PELNY BLOK - gracz tak wlasnie chcial: klatka
 * wyglada jak szkielet, ale zachowuje sie jak normalny klocek (można po niej
 * chodzic, nie da sie przez nia przejsc, nie wypada z niej nic). Nietypowy
 * ksztalt kolizji byl bledem: utrudnial stawianie blokow obok i wygladal jak
 * zepsuty model.
 *
 * <p><b>Swiatlo i widocznosc.</b> Blok nie zaslania sasiednich scian
 * ({@code noOcclusion}) i przepuszcza swiatlo dzienne, wiec stojaca obok
 * maszyna renderuje sie normalnie, a nie "w ciemnej dziurze".
 *
 * <p><b>Dwa zastosowania.</b>
 * <ol>
 *   <li><b>Gablota</b> - prawy klik dowolnym blokiem wklada go do srodka
 *       (klient renderuje go w klatce), prawy klik z pusta reka oddaje
 *       eksponat.</li>
 *   <li><b>Stol craftingu</b> - prawy klik STOLEM CRAFTINGU podmienia caly
 *       blok na prawdziwy {@link VeloceCraftingTableBlock} w stanie
 *       {@code facade} (patrz {@link #swapIntoCraftingStation}). Dzieki temu
 *       w klatce stoi PRAWDZIWY stol: auto-crafter, GUI, bufor i ten sam blok,
 *       ktory widza mody od receptur.</li>
 * </ol>
 *
 * <p><b>Dlaczego podmiana bloku, a nie block entity.</b> Pierwsza wersja
 * trzymala w klatce block entity stolu i udawala craftera. To dzialalo, ale
 * znaczylo, ze klatka NIE JEST stolem: mod od receptur (JEI/EMI) nie mial
 * czego rozpoznac, a siec musiala znac wyjatek "klatka bywa crafterem".
 * Podmiana bloku usuwa oba te wyjatki - w swiecie stoi po prostu nasz stol,
 * tylko w innym stanie wizualnym.
 *
 * <p><b>Sieć.</b> Blok jest wezlem sieci rur Veloce ({@link VeloceNetworkNode}):
 * laczy sie z rura z kazdej strony. Chunk NIE jest utrzymywany
 * ({@link #keepChunkLoaded(BlockState)} = false), bo gablota nic nie robi -
 * trzymanie chunkow dla dekoracji to dokladnie ten rodzaj kosztu, ktory tego
 * projektu juz raz ugryzl (force-loady dla rur bez logiki). Prawdziwy stol,
 * ktory powstaje z podmiany, chunek trzyma - bo on pracuje.
 */
public class VeloceIntegraleBlock extends Block
        implements VeloceNetworkNode, EntityBlock {

    /** Czy w klatce jest EKSPONAT (gablota) - wtedy nie przyjmuje kolejnych. */
    public static final BooleanProperty FILLED = BooleanProperty.create("filled");

    public VeloceIntegraleBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FILLED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FILLED);
        VeloceIntegraleFrame.addProperties(builder);
    }

    /** Przy postawieniu od razu zamykamy strony, z ktorych dochodzi kabel. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return VeloceIntegraleFrame.withPlacementClosures(
                context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    /**
     * Zmiana sasiada przelicza TYLKO te strone.
     *
     * <p>Dzieki temu postawienie albo zburzenie kabla obok natychmiast zamyka
     * lub otwiera okno - bez block entity, bez tickera i bez odswiezania
     * z serwera (stan bloku jedzie normalnym sync pakietem).
     */
    @Override
    protected BlockState updateShape(BlockState state, Direction facing, BlockState facingState,
                                     LevelAccessor world, BlockPos pos, BlockPos facingPos) {
        return VeloceIntegraleFrame.withClosure(state, facing, facingState);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * Czy z tego przedmiotu ma powstac STACJA (podmiana bloku), czy eksponat.
     *
     * <p><b>JEDNO miejsce z ta regula.</b> Stol craftingu jest jedynym
     * przedmiotem, ktory daje klatce funkcje - reszta blokow to dekoracja
     * w gablocie. Trzymanie tego razem zapobiega sytuacji, w ktorej klient
     * pokazuje cos, czego serwer nie przyjmie (albo odwrotnie).
     */
    public static boolean isCraftingStation(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        Block block = blockItem.getBlock();
        return block == Blocks.CRAFTING_TABLE
                || block == VeloceRegistry.VELOCE_CRAFTING_TABLE.get();
    }

    /**
     * Czy ten przedmiot mozna wystawic w gablocie.
     *
     * <p>Tylko bloki: klient renderuje w srodku model bloku (patrz
     * {@code VeloceDisplayRenderer}). Zwykly przedmiot (np. sztabka) nie ma
     * modelu 3D, wiec jego wyswietlenie wymagaloby drugiej sciezki renderowania
     * - dodamy ja, gdy gracz o to poprosi.
     */
    public static boolean isDisplayable(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem;
    }

    /** Czy klatka jest wypelniona (ma eksponat). */
    public static boolean isFilled(BlockState state) {
        return state.getValue(FILLED);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // Klatka uzywa block entity stolu craftingu tylko po to, zeby PAMIETAC
        // EKSPONAT (stan bloku nie uniesie dowolnego przedmiotu). Crafterem ta
        // klatka nie jest - patrz isActiveCrafter w block entity.
        return new VeloceCraftingTableBlockEntity(pos, state);
    }

    /**
     * Prawy klik: stol craftingu podmienia blok, inny blok staje sie eksponatem.
     */
    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack, BlockState state, Level world, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (isFilled(state)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (isCraftingStation(stack)) {
            if (!world.isClientSide) {
                swapIntoCraftingStation(world, pos, state);
                if (player == null || !player.isCreative()) {
                    stack.shrink(1);
                }
            }
            return ItemInteractionResult.sidedSuccess(world.isClientSide);
        }
        if (isDisplayable(stack)) {
            if (!world.isClientSide) {
                putOnDisplay(world, pos, state, stack, player);
            }
            return ItemInteractionResult.sidedSuccess(world.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /**
     * Podmienia cala klatke na PRAWDZIWY stol craftingu w stanie {@code facade}.
     *
     * <p>Zaslepki okien przepisujemy ze starego stanu, zeby klatka nie
     * "otworzyla sie" w chwili podmiany. Po {@code setBlock} trzeba zglosic
     * nowy blok do sieci: {@code setBlock} wola najpierw {@code onRemove}
     * starego bloku, a ten (jako wezel) zglasza sie jako usuniety.
     */
    private static void swapIntoCraftingStation(Level world, BlockPos pos, BlockState state) {
        BlockState station = VeloceIntegraleFrame.copyClosures(state,
                VeloceRegistry.VELOCE_CRAFTING_TABLE.get().defaultBlockState()
                        .setValue(VeloceCraftingTableBlock.FACADE, true));
        world.setBlock(pos, station, Block.UPDATE_ALL);
        VeloceNodeBlocks.onNodePlaced(world, pos);
        world.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM,
                SoundSource.BLOCKS, 0.8F, 1.2F);
    }

    /** Wklada przedmiot do srodka klatki (gablota). */
    private static void putOnDisplay(Level world, BlockPos pos, BlockState state,
                                     ItemStack stack, @Nullable Player player) {
        if (world.getBlockEntity(pos) instanceof VeloceCraftingTableBlockEntity be) {
            be.setDisplayItem(stack);
        }
        world.setBlock(pos, state.setValue(FILLED, true), Block.UPDATE_ALL);
        if (player == null || !player.isCreative()) {
            stack.shrink(1);
        }
        world.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM,
                SoundSource.BLOCKS, 0.8F, 1.2F);
    }

    /**
     * Prawy klik z pusta reka: zabiera eksponat z gabloty.
     *
     * <p>Wczesniej otwieralo to GUI craftera - ale klatka crafterem nie jest
     * (crafterem jest stol, ktory powstaje z podmiany). Pusty klik musi wiec
     * robic to, co w ramce na przedmioty: oddawac to, co w srodku.
     */
    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level world, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (!isFilled(state)) {
            return InteractionResult.PASS;
        }
        if (!world.isClientSide
                && world.getBlockEntity(pos) instanceof VeloceCraftingTableBlockEntity be) {
            takeOffDisplay(world, pos, state, be, player);
        }
        return InteractionResult.sidedSuccess(world.isClientSide);
    }

    /** Oddaje eksponat graczowi (a gdy plecak pelny - rzuca go pod nogi). */
    private static void takeOffDisplay(Level world, BlockPos pos, BlockState state,
                                       VeloceCraftingTableBlockEntity be,
                                       @Nullable Player player) {
        ItemStack display = be.getDisplayItem().copy();
        be.setDisplayItem(ItemStack.EMPTY);
        world.setBlock(pos, state.setValue(FILLED, false), Block.UPDATE_ALL);
        world.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM,
                SoundSource.BLOCKS, 0.8F, 1.2F);
        if (display.isEmpty()) {
            return;
        }
        // Inventory.add zabiera z tej samej instancji to, co sie zmiescilo -
        // wiec to, co zostalo, jest reszta do wyrzucenia (a nie duplikatem).
        if (player == null || !player.getInventory().add(display)) {
            Block.popResource(world, pos, display);
        }
    }

    /**
     * Zbicie klatki z eksponatem wypuszcza go.
     *
     * <p>Gracz wlozyl cos do srodka, wiec musi to odzyskac - inaczej "schowek"
     * zjadalby przedmioty. Sama klatke wypuszcza loot table.
     */
    @Override
    public void playerDestroy(Level world, Player player, BlockPos pos, BlockState state,
                              @Nullable BlockEntity be, ItemStack tool) {
        if (!world.isClientSide && be instanceof VeloceCraftingTableBlockEntity table) {
            ItemStack display = table.getDisplayItem();
            if (!display.isEmpty()) {
                Block.popResource(world, pos, display.copy());
            }
        }
        super.playerDestroy(world, player, pos, state, be, tool);
    }

    /**
     * Klatka laczy sie z rura z KAZDEJ strony - jak pozostale maszyny w modzie
     * (nie ma przodu ani tylu).
     */
    @Override
    public boolean canConnectFrom(BlockState state, Direction towardPipe) {
        return true;
    }

    /**
     * KLATKA NIE UTRZYMUJE CHUNKU.
     *
     * <p>Jest ozdobna: nawet z eksponatem w srodku nic nie tyka i nic nie
     * traci, gdy jej chunk wypadnie z symulacji. Trzymanie chunkow dla
     * dekoracji rozdmuchiwalo by liste force-loadow i wypychalo z niej to, co
     * naprawde pracuje (piec, crafter, maszyny modulow).
     */
    @Override
    public boolean keepChunkLoaded(BlockState state) {
        return false;
    }

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
    protected void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState,
                            boolean moved) {
        // Uwaga: przy PODMIANIE na stol (swapIntoCraftingStation) ten hook
        // zglosza wezel jako usuniety - wolajacy musi go zaraz zglosic z powrotem.
        if (!state.is(newState.getBlock())) {
            VeloceNodeBlocks.onNodeRemoved(world, pos);
        }
        super.onRemove(state, world, pos, newState, moved);
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter world, BlockPos pos) {
        return true;
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter world, BlockPos pos) {
        return 1.0F;
    }

}
