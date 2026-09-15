package com.craftingveloce.network.pipe;

import com.craftingveloce.block.VeloceControllerBlock;
import com.craftingveloce.block.VeloceCraftingTableBlock;
import com.craftingveloce.block.VeloceElectricFurnaceBlock;
import com.craftingveloce.block.VeloceExtractorBlock;
import com.craftingveloce.block.VeloceTomTerminalBlock;
import com.craftingveloce.block.VeloceVelocityFurnaceBlock;
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
 * <p>Teraz oba miejsca pytaja TE KLASE. Dodanie nowego wezla to jedna linia
 * w {@link #isNode} - i nie da sie juz zapomniec o drugim miejscu.
 */
public final class VeloceNodeBlocks {

    private VeloceNodeBlocks() {
    }

    /**
     * Czy ten blok jest wezlem sieci rur.
     *
     * <p>Wezel to blok z wlasnym block entity, ktory musi byc symulowany
     * (dlatego jego chunk jest force-loadowany) albo ktory dostarcza sieci
     * funkcji: terminal, kontroler, crafter, ekstraktor, piece.
     */
    public static boolean isNode(Block block) {
        return block instanceof VeloceTomTerminalBlock
                || block instanceof VeloceControllerBlock
                || block instanceof VeloceCraftingTableBlock
                || block instanceof VeloceExtractorBlock
                || block instanceof VeloceVelocityFurnaceBlock
                || block instanceof VeloceElectricFurnaceBlock;
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
        if (block instanceof VeloceTomTerminalBlock terminal) {
            return terminal.canConnectFrom(state, towardPipe);
        }
        if (block instanceof VeloceControllerBlock controller) {
            return controller.canConnectFrom(state, towardPipe);
        }
        if (block instanceof VeloceCraftingTableBlock crafter) {
            return crafter.canConnectFrom(state, towardPipe);
        }
        if (block instanceof VeloceExtractorBlock extractor) {
            return extractor.canConnectFrom(state, towardPipe);
        }
        // Piece nie maja wlasciwosci kierunku (nie maja FACING), wiec lacza sie
        // kazda strona - tak samo jak pozostale maszyny w modzie.
        if (block instanceof VeloceVelocityFurnaceBlock
                || block instanceof VeloceElectricFurnaceBlock) {
            return true;
        }
        return false;
    }
}
