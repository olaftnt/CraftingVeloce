package com.craftingveloce.network.pipe;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Blok, ktory jest wezlem sieci rur Veloce.
 *
 * <p><b>Po co ten interfejs.</b> Rdzen rozpoznawal wezly recznie pisanym
 * lancuchem {@code instanceof} w dwoch miejscach ({@link VeloceNodeBlocks} oraz
 * petla BFS w {@code VelocePipeNetworkManager}). Ten lancuch mial dwa koszty:
 * <ul>
 *   <li>dodanie wezla wymagalo dopisania sie w KAZDYM z tych miejsc (i juz raz
 *       sie rozjechalo - kontroler nie byl rozpoznawany jako wezel),</li>
 *   <li>wezel spoza rdzenia (np. blok z {@code com.craftingveloce.compat.create})
 *       wymagalby importu obcego moda w rdzeniu, a wtedy mod nie wstaje bez
 *       tamtego moda ({@code NoClassDefFoundError} przy linkowaniu klasy).</li>
 * </ul>
 *
 * <p>Teraz rdzen zna WYLACZNIE ten interfejs. Blok z {@code compat/*} moze byc
 * pelnoprawnym wezlem, nie wnoszac do rdzenia ani jednego obcego importu.
 *
 * <p><b>Izolacja.</b> Ten interfejs celowo nie ma zadnych typow obcych modow -
 * wolno mu uzywac tylko klas wanilii i rdzenia Veloce.
 */
public interface VeloceNetworkNode {

    /**
     * Czy wezel laczy sie z rura stojaca po stronie {@code towardPipe}.
     *
     * <p><b>Konwencja kierunku jest tu kluczowa</b> (i juz raz byla odwrocona,
     * co dawalo objaw "rura widzi terminal, ale terminal nie widzi rury"):
     * {@code towardPipe} to kierunek OD WEZLA DO RURY, a nie od rury do wezla.
     * Wolajacy stojacy przy rurze musi wiec przekazac {@code d.getOpposite()}.
     *
     * @param state      stan bloku wezla
     * @param towardPipe kierunek od wezla w strone rury
     */
    boolean canConnectFrom(BlockState state, Direction towardPipe);

    /**
     * Czy ten wezel wystawia swoj bufor craftingu jako endpoint sieci.
     *
     * <p>Dotyczy craftera: jego bufor jest miejscem, w ktorym ląduje nadwyzka
     * produkcji, wiec cala siec (terminal, rury, hoppery) ma do niego dostep.
     * Kazdy inny wezel zwraca {@code false} - dlatego to metoda domyslna,
     * a nie obowiazek kazdego bloku.
     */
    default boolean exposesCraftingBuffer() {
        return false;
    }

    /**
     * Czy chunk z tym wezlem ma byc utrzymywany w pamieci (force-load).
     *
     * <p>Domyslnie tak, bo wezel to zwykle maszyna albo terminal: bez symulacji
     * przestaje pracowac (piec nie pali, crafter nie craftuje, sensor nie
     * pilnuje progow). Bloki OZDOBNE (np. klatka {@code veloce_integrale})
     * zwracaja {@code false} - nie maja block entity i nic nie traca, gdy ich
     * chunk wypadnie, a trzymanie ich kosztowaloby miejsce na liscie
     * force-loadow, wypychajac z niej to, co naprawde pracuje.
     */
    default boolean keepChunkLoaded() {
        return true;
    }

    /** Opcjonalna etykieta do diagnostyki i logow. */
    default String nodeName() {
        return getClass().getSimpleName();
    }
}
