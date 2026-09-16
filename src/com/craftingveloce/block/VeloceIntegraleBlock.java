package com.craftingveloce.block;

import com.craftingveloce.network.pipe.VeloceNetworkNode;
import com.craftingveloce.network.pipe.VeloceNodeBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Veloce Integrale - ozdobna KLATKA: widac tylko krawedzie i narozniki,
 * srodek jest pusty.
 *
 * <p><b>Wyglad.</b> Model to dwanascie cienkich pretow biegnacych po
 * krawedziach szescianu (zadnego wypelnienia scian), wiec blok wyglada jak
 * narysowany kwadrat - linie po rogach, pusty srodek. Poniewaz srodek NIE jest
 * niczym wypelniony, nie potrzebujemy przezroczystych tekstur ani
 * {@code render_type} (patrz decyzja o wylaczeniu przezroczystosci w tym
 * projekcie): brak geometrii = brak renderowania.
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
 * <p><b>Sieć.</b> Blok jest wezlem sieci rur Veloce ({@link VeloceNetworkNode}):
 * laczy sie z rura z kazdej strony. Chunk NIE jest jednak utrzymywany
 * ({@link #keepChunkLoaded()} = false), bo to element ozdobny - trzymanie
 * chunkow dla dekoracji to dokladnie ten rodzaj kosztu, ktory tego projektu
 * juz raz ugryzl (force-loady dla rur bez logiki).
 */
public class VeloceIntegraleBlock extends Block implements VeloceNetworkNode {

    /**
     * Szesc stanow: czy okno z danej strony jest ZABUDOWANE.
     *
     * <p>Uzywamy stanow waniliowego {@link PipeBlock} (te same nazwy:
     * {@code north}, {@code east}, ...), bo sa dokladnie tym, czego potrzeba -
     * jedna wartosc na strone - i dzieki temu mapa kierunek -&gt; wlasciwosc
     * istnieje juz w wanilii ({@link PipeBlock#PROPERTY_BY_DIRECTION}).
     *
     * <p><b>Po co.</b> Gracz chce widziec, z ktorej strony dochodzi kabel:
     * okno od tej strony zamyka sie metalowa zaslepka. To czysto wizualne -
     * polaczenie sieci jest rozpoznawane po bloku, nie po stanie.
     */
    public static final BooleanProperty[] CLOSED_BY_DIRECTION = {
            PipeBlock.DOWN, PipeBlock.UP, PipeBlock.NORTH,
            PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.EAST,
    };

    public VeloceIntegraleBlock(Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any();
        for (Direction direction : Direction.values()) {
            state = state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(direction), false);
        }
        registerDefaultState(state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CLOSED_BY_DIRECTION);
    }

    /** Przy postawieniu od razu zamykamy strony, z ktorych dochodzi kabel. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        net.minecraft.world.level.Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        for (Direction direction : Direction.values()) {
            boolean cable = level.getBlockState(pos.relative(direction)).getBlock()
                    instanceof VelocePipeBlock;
            state = state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(direction), cable);
        }
        return state;
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
                                     net.minecraft.world.level.LevelAccessor world, BlockPos pos,
                                     BlockPos facingPos) {
        BooleanProperty property = PipeBlock.PROPERTY_BY_DIRECTION.get(facing);
        if (property == null) {
            return state;
        }
        boolean cable = facingState.getBlock() instanceof VelocePipeBlock;
        return state.getValue(property) == cable ? state : state.setValue(property, cable);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
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
     * <p>Jest ozdobna: nie ma block entity, nic nie tyka i nic nie traci, gdy
     * jej chunk wypadnie z symulacji. Trzymanie chunkow dla dekoracji
     * rozdmuchiwalo by liste force-loadow i wypychalo z niej to, co naprawde
     * pracuje (piec, crafter, maszyny modulow).
     */
    @Override
    public boolean keepChunkLoaded() {
        return false;
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
    protected void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState,
                            boolean moved) {
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
