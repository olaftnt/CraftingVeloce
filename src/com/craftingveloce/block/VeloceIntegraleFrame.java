package com.craftingveloce.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Stany ramy Veloce Integrale - wspolne dla DWÓCH blokow.
 *
 * <p><b>Dlaczego osobna klasa.</b> Te same szesc zaslepek (okno od strony, z
 * ktorej dochodzi rura, zamyka sie blacha) opisuja dzis dwa bloki:
 * <ul>
 *   <li>{@link VeloceIntegraleBlock} - ozdobna klatka (obudowa maszyny),</li>
 *   <li>{@link VeloceCraftingTableBlock} w stanie {@code facade} - prawdziwy
 *       stol craftingu stojacy w klatce (patrz {@code VeloceIntegraleBlock}
 *       "podmiana bloku").</li>
 * </ul>
 * Gdyby kazdy z nich liczyl zaslepki po swojemu, powstalyby dwa miejsca z ta
 * sama regula - a to w tym projekcie jest udokumentowanym zrodlem bledow
 * (kontroler nie byl wezlem sieci, bo lista wezlow byla zdublowana).
 *
 * <p>Uzywamy stanow waniliowego {@link PipeBlock} (te same nazwy: {@code north},
 * {@code east}, ...), bo sa dokladnie tym, czego potrzeba - jedna wartosc na
 * strone - i dzieki temu mapa kierunek -&gt; wlasciwosc istnieje juz w wanilii
 * ({@link PipeBlock#PROPERTY_BY_DIRECTION}). Wlasciwosc stanu mozna dzielic
 * miedzy blokami: to tylko deskryptor, nie rejestr.
 */
public final class VeloceIntegraleFrame {

    /** Szesc okien klatki: czy okno z danej strony jest ZABUDOWANE. */
    public static final BooleanProperty[] CLOSED_BY_DIRECTION = {
            PipeBlock.DOWN, PipeBlock.UP, PipeBlock.NORTH,
            PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.EAST,
    };

    private VeloceIntegraleFrame() {
    }

    /** Dodaje szesc zaslepek do definicji stanu bloku. */
    public static void addProperties(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CLOSED_BY_DIRECTION);
    }

    /**
     * Stan startowy: zaslepki zamkniete tam, gdzie juz stoi rura Veloce.
     *
     * <p>Dzieki temu postawienie bloku obok kabla od razu wyglada dobrze -
     * bez block entity, bez tickera i bez czekania na aktualizacje z serwera.
     */
    public static BlockState withPlacementClosures(BlockGetter level, BlockPos pos, BlockState state) {
        for (Direction direction : Direction.values()) {
            state = state.setValue(property(direction), isPipe(level.getBlockState(pos.relative(direction))));
        }
        return state;
    }

    /**
     * Przelicza TYLKO jedna strone (zmienil sie sasiad z tej strony).
     *
     * <p>Zwraca TEN SAM stan, gdy nic sie nie zmienilo - inaczej kazdy
     * neighbor update wysylalby pakiet aktualizacji bloku bez powodu.
     */
    public static BlockState withClosure(BlockState state, Direction facing, BlockState neighbor) {
        BooleanProperty property = property(facing);
        if (property == null) {
            return state;
        }
        boolean pipe = isPipe(neighbor);
        return state.getValue(property) == pipe ? state : state.setValue(property, pipe);
    }

    /** Czy okno z tej strony jest zakryte blacha. */
    public static boolean isClosed(BlockState state, Direction direction) {
        BooleanProperty property = property(direction);
        return property != null && state.getValue(property);
    }

    /** Liczba zakrytych okien (0..6) - diagnostyka i logi. */
    public static int coveredSides(BlockState state) {
        int covered = 0;
        for (Direction direction : Direction.values()) {
            if (isClosed(state, direction)) {
                covered++;
            }
        }
        return covered;
    }

    /**
     * Przepisuje zaslepki z jednego stanu na drugi - uzywane przy PODMIANIE
     * bloku (klatka -&gt; maszyna): blok, ktory ma rame, wyglada tak samo.
     *
     * <p>Blok docelowy bez ramy (zwykla maszyna Veloce) po prostu pomija te
     * wlasciwosci - dlatego metoda nie wymaga, by mial je wszystkie.
     */
    public static BlockState copyClosures(BlockState from, BlockState to) {
        BlockState result = to;
        for (Direction direction : Direction.values()) {
            BooleanProperty property = property(direction);
            if (property == null || !result.hasProperty(property)) {
                continue;
            }
            result = result.setValue(property, isClosed(from, direction));
        }
        return result;
    }

    private static BooleanProperty property(Direction direction) {
        return PipeBlock.PROPERTY_BY_DIRECTION.get(direction);
    }

    private static boolean isPipe(BlockState state) {
        return state.getBlock() instanceof VelocePipeBlock;
    }
}
