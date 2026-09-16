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

    /**
     * Szesc okien klatki: czy okno z danej strony jest ZABUDOWANE.
     *
     * <p><b>WLASNE wlasciwosci, nie pozyczone z {@code PipeBlock}.</b>
     * Zgloszenie gracza: "niektore bloki jako defaultowy state maja to, ze sa
     * jakby zamkniete, mimo ze nic nie jest podlaczone". Pozyczone
     * {@code PipeBlock.*} mialy domyslna wartosc TRUE, wiec KAZDA postawiona
     * maszyna startowala z wszystkimi scianami zamknietymi (potwierdzone
     * w grze: pusty piecyk pokazywal `down=true, east=true, ... west=true`).
     * Wlasne wlasciwosci maja domyslne FALSE: maszyna startuje otwarta,
     * a zaslepki zamyka dopiero sasiad-rura (patrz withClosure).
     *
     * <p>Nazwy zostaja te same (north/east/south/west/up/down), bo na nich
     * opieraja sie blockstate'y (multipart) i zapisane stany w swiecie.
     */
    public static final BooleanProperty DOWN = BooleanProperty.create("down");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty EAST = BooleanProperty.create("east");

    /** Szesc okien klatki w kolejnosci: dol, gora, polnoc, poludnie, zachod, wschod. */
    public static final BooleanProperty[] CLOSED_BY_DIRECTION = {
            DOWN, UP, NORTH, SOUTH, WEST, EAST,
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

    /**
     * Ustawia zaslepke jednej strony, gdy WOLAJACY wie, czy jest zakryta.
     *
     * <p>Klatka pyta o rury Veloce, ale maszyna kinetyczna Create zakrywa bok
     * takze wtedy, gdy dochodzi z niego NAPED (gracz: "ten bok ma sie
     * zachowywac tak, jakby byl kabel podlaczony z tej strony"). Rdzen nie
     * moze znac Create, wiec decyzje podejmuje wolajacy.
     */
    public static BlockState withClosure(BlockState state, Direction facing, boolean covered) {
        BooleanProperty property = property(facing);
        if (property == null || !state.hasProperty(property)) {
            return state;
        }
        return state.getValue(property) == covered ? state : state.setValue(property, covered);
    }

    /** Czy okno z tej strony jest zakryte blacha. */
    public static boolean isClosed(BlockState state, Direction direction) {
        BooleanProperty property = property(direction);
        return property != null && state.getValue(property);
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
        return switch (direction) {
            case DOWN -> DOWN;
            case UP -> UP;
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
        };
    }

    private static boolean isPipe(BlockState state) {
        return state.getBlock() instanceof VelocePipeBlock;
    }
}
