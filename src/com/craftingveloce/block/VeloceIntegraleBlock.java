package com.craftingveloce.block;

import com.craftingveloce.network.pipe.VeloceNetworkNode;
import com.craftingveloce.network.pipe.VeloceNodeBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * Veloce Integrale - ozdobna KLATKA, ktora staje sie nasza maszyna.
 *
 * <p><b>Wyglad.</b> Model to dwanascie cienkich pretow biegnacych po krawedziach
 * szescianu plus fioletowa szyba w oknach, wiec blok wyglada jak narysowany
 * kwadrat - linie po rogach, szyba w srodku.
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
 * <p><b>Co robi.</b> To <b>obudowa</b>: prawy klik odpowiednim waniliowym
 * klockiem (patrz {@link VeloceIntegraleConversions}) <b>podmienia cala klatke
 * na nasza maszyne</b> - z pulpitu czytania powstaje kontroler, z dozownika
 * ekstraktor, z obserwatora sensor progu, ze stolu craftingu stol craftingu
 * (ten jeden zachowuje wyglad klatki). Inny klocek nie robi nic.
 *
 * <p><b>Dlaczego podmiana bloku, a nie block entity.</b> Pierwsza wersja
 * trzymala w klatce block entity stolu i udawala craftera, a druga "wystawiala
 * eksponat" (dowolny blok w srodku). Obie znaczylo, ze klatka NIE JEST maszyna:
 * mod od receptur nie mial czego rozpoznac, a siec musiala znac wyjatki.
 * Podmiana bloku usuwa te wyjatki - w swiecie stoi po prostu nasz blok.
 *
 * <p><b>Sieć.</b> Blok jest wezlem sieci rur Veloce ({@link VeloceNetworkNode}):
 * laczy sie z rura z kazdej strony. Chunk NIE jest utrzymywany
 * ({@link #keepChunkLoaded(BlockState)} = false), bo klatka nic nie robi -
 * trzymanie chunkow dla dekoracji to dokladnie ten rodzaj kosztu, ktory tego
 * projektu juz raz ugryzl (force-loady dla rur bez logiki). Maszyna, ktora
 * z klatki powstaje, chunk trzyma - bo ona pracuje.
 */
public class VeloceIntegraleBlock extends Block implements VeloceNetworkNode {

    public VeloceIntegraleBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
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
     * Prawy klik odpowiednim klockiem zamienia klatke na nasza maszyne.
     *
     * <p>Klocek spoza tabeli ({@link VeloceIntegraleConversions}) nie robi nic -
     * zadnego "wystawiania w srodku". To bylo zle: gracz wkladal piec i dostawal
     * "eksponat" zamiast maszyny.
     */
    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack, BlockState state, Level world, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        VeloceIntegraleConversions.Conversion conversion =
                VeloceIntegraleConversions.forItem(stack);
        if (conversion == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!world.isClientSide) {
            convert(world, pos, state, conversion);
            if (player == null || !player.isCreative()) {
                stack.shrink(1);
            }
        }
        return ItemInteractionResult.sidedSuccess(world.isClientSide);
    }

    /**
     * Podmienia cala klatke na blok z tabeli (np. pulpit -&gt; kontroler).
     *
     * <p>Zaslepki okien przepisujemy tylko wtedy, gdy blok docelowy ma rame
     * (czyli nasz stol craftingu w stanie {@code facade}) - zwykla maszyna ich
     * nie ma i nie potrzebuje. Po {@code setBlock} trzeba zglosic nowy blok do
     * sieci: {@code setBlock} wola najpierw {@code onRemove} starego bloku,
     * a ten (jako wezel) zglasza sie jako usuniety.
     */
    private static void convert(Level world, BlockPos pos, BlockState state,
                                VeloceIntegraleConversions.Conversion conversion) {
        BlockState result = conversion.resultBlock().defaultBlockState();
        result = VeloceIntegraleFrame.copyClosures(state, result);
        world.setBlock(pos, result, Block.UPDATE_ALL);
        // Wlozony klocek ZOSTAJE w srodku: obudowa buduje sie element po
        // elemencie (kolo mlynskie, oczko craftera), a nie "jest maszyna, bo
        // zostala postawiona".
        if (world.getBlockEntity(pos) instanceof VeloceCaseBuildable buildable) {
            buildable.addPart();
        }
        VeloceNodeBlocks.onNodePlaced(world, pos);
        world.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM,
                SoundSource.BLOCKS, 0.8F, 1.2F);
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
     * <p>Jest ozdobna: nic nie tyka i nic nie traci, gdy jej chunk wypadnie
     * z symulacji. Trzymanie chunkow dla dekoracji rozdmuchiwalo by liste
     * force-loadow i wypychalo z niej to, co naprawde pracuje (piec, crafter,
     * maszyny modulow).
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
        // Uwaga: przy PODMIANIE na maszyne (convert) ten hook zglasza wezel jako
        // usuniety - wolajacy musi go zaraz zglosic z powrotem.
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
