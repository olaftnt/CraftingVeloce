package com.craftingveloce.network.pipe;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * JEDNO zrodlo prawdy o tym, co jest wezlem sieci rur.
 *
 * <p><b>Dlaczego ta klasa istnieje.</b> Ta wiedza byla rozsiana po dwoch
 * miejscach: {@code VelocePipeNetworkManager.nodeConnectsToPipe} (ktore pyta
 * "czy ten wezel laczy sie z rura z tej strony?") oraz
 * {@code collectNeighbours} (ktore zbiera wezly przy budowie sieci). Oba
 * miejsca wymienialy typy blokow RECZNIE - i sie rozjechaly.
 *
 * <p>Skutek byl widoczny golym okiem: kontroler nie byl rozpoznawany przez
 * zadne z tych miejsc jako wezel, wiec
 * {@link VelocePipeNetworkManager#getNetworkForTerminal} zwracal dla niego
 * {@code null}. Kontroler dostawal wtedy PUSTY stock i PUSTY zbior itemow
 * z wlaczonym auto-craftingiem - wiec kazdy item (np. deski) pokazywal sie
 * jako "auto-crafting wylaczony", mimo ze crafter w sieci mial go wlaczonego.
 *
 * <p>Dodatkowo kontroler nie trafial do {@code network.getTerminals()}, wiec
 * jego chunk nie byl utrzymywany w pamieci.
 *
 * <p>Teraz oba miejsca pytaja TE KLASE, a ta pyta JEDEN interfejs
 * ({@link VeloceNetworkNode}). Dodanie nowego wezla to implementacja tego
 * interfejsu w jego bloku - bez dotykania rdzenia i bez importu obcego moda.
 */
public final class VeloceNodeBlocks {

    private VeloceNodeBlocks() {
    }

    /**
     * Czy ten blok jest wezlem sieci rur.
     *
     * <p>Wezel to blok z wlasnym block entity, ktory musi byc symulowany
     * (dlatego jego chunk jest force-loadowany) albo ktory dostarcza sieci
     * funkcji: terminal, kontroler, crafter, ekstraktor, sensor, piece.
     */
    public static boolean isNode(Block block) {
        return block instanceof VeloceNetworkNode;
    }

    /**
     * Czy wezel laczy sie z rura stojaca po stronie {@code towardPipe}.
     *
     * <p><b>Konwencja kierunku jest tu kluczowa</b> (i juz raz byla odwrocona,
     * co dawalo objaw "rura widzi terminal, ale terminal nie widzi rury"):
     * {@code towardPipe} to kierunek OD WEZLA DO RURY, a nie od rury do wezla.
     * Wolajacy stojacy przy rurze musi wiec przekazac {@code d.getOpposite()}.
     *
     * @param state      stan bloku wezla
     * @param block      blok wezla (ten sam co {@code state.getBlock()})
     * @param towardPipe kierunek od wezla w strone rury
     */
    public static boolean connectsFrom(BlockState state, Block block, Direction towardPipe) {
        return block instanceof VeloceNetworkNode node
                && node.canConnectFrom(state, towardPipe);
    }
    /**
     * Wspolny hook: wezel wlasnie stanal w swiecie.
     *
     * <p><b>Po co wydzielone.</b> Ta sama sekwencja (uniewaznij wezel w sieci
     * Toma, potem zglos go naszemu menedzerowi) byla skopiowana w SIEDMIU
     * klasach blokow-wezlow. To nie jest kosmetyka: gdy dodawalismy piece,
     * jeden z nich nie dostal tego hooka i nie byl rozpoznawany przez siec,
     * dopoki czegos innego nie ruszylo. Nowy wezel ma teraz JEDNO miejsce do
     * wywolania, a nie piec linii do przepisania z pamieci.
     */

    /**
     * Domkniecie scianek obudowy u KAZDEJ naszej maszyny - takze u tych, ktore
     * nie mialy tego w swoim kodzie.
     *
     * <p>Zgloszenie gracza: "kabel sie przelacza, ale scianki sie nie zamykaja
     * - na crushing wheelu dziala, a na crafting table i piecyku elektrycznym
     * nie". Przyczyna: domykanie mialy tylko VeloceIntegraleBlock i modul
     * kinetyczny Create. Teraz robi to to jedno miejsce: przy postawieniu i przy
     * usunieciu wezla przeliczamy zaslepki u SASIADOW tego wezla oraz u NIEGO
     * SAMEGO (postawienie maszyny obok istniejacej rury to druga strona tej
     * samej sytuacji).
     *
     * <p>{@code withClosure} sam sprawdza, czy blok ma wlasciwosci zaslepek
     * i zwraca ten sam stan, gdy nie ma - dlatego wolno to wolac dla kazdego
     * sasiada bez pytania o typ bloku.
     */
    private static void refreshClosures(net.minecraft.server.level.ServerLevel level,
                                        net.minecraft.core.BlockPos pos) {
        BlockState pipe = level.getBlockState(pos);
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            net.minecraft.core.BlockPos side = pos.relative(dir);
            if (!level.isLoaded(side)) {
                continue;
            }
            BlockState state = level.getBlockState(side);
            BlockState updated = com.craftingveloce.block.VeloceIntegraleFrame
                    .withClosure(state, dir.getOpposite(), pipe);
            if (updated != state) {
                level.setBlock(side, updated, Block.UPDATE_ALL);
            }
        }
        BlockState own = level.getBlockState(pos);
        BlockState ownUpdated = own;
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            net.minecraft.core.BlockPos side = pos.relative(dir);
            if (!level.isLoaded(side)) {
                continue;
            }
            ownUpdated = com.craftingveloce.block.VeloceIntegraleFrame
                    .withClosure(ownUpdated, dir, level.getBlockState(side));
        }
        if (ownUpdated != own) {
            level.setBlock(pos, ownUpdated, Block.UPDATE_ALL);
        }
    }

    public static void onNodePlaced(net.minecraft.world.level.Level world, net.minecraft.core.BlockPos pos) {
        if (world.isClientSide) {
            return;
        }
        com.tom.storagemod.inventory.InventoryCableNetwork.getNetwork(world).markNodeInvalid(pos);
        if (world instanceof net.minecraft.server.level.ServerLevel sl) {
            VelocePipeNetworkManager.get(sl).onTerminalPlaced(sl, pos);
            refreshClosures(sl, pos);
            // Nowy wezel = nowe mozliwosci: cache liczb przestaje byc aktualny.
            VelocePipeNetworkManager.get(sl).clearCraftableMemo(sl, pos);
        }
    }

    /**
     * Wspolny hook: wezel zniknal ze swiata.
     *
     * <p>Bez tego siec trzymalaby wpis o wezle, ktorego juz nie ma (widmo
     * w terminalu) - a przy ponownym postawieniu bloku powstalby drugi wpis.
     */
    public static void onNodeRemoved(net.minecraft.world.level.LevelAccessor world,
                                     net.minecraft.core.BlockPos pos) {
        if (world instanceof net.minecraft.server.level.ServerLevel sl) {
            com.tom.storagemod.inventory.InventoryCableNetwork.getNetwork(sl).markNodeInvalid(pos);
            VelocePipeNetworkManager.get(sl).onTerminalRemoved(sl, pos);
            refreshClosures(sl, pos);
            VelocePipeNetworkManager.get(sl).clearCraftableMemo(sl, pos);
        }
    }
}
