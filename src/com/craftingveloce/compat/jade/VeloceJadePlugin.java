package com.craftingveloce.compat.jade;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import net.minecraft.world.level.block.Block;

/**
 * Plugin Jade dla Veloce - doklada do overlay linie z informacjami o naszych
 * blokach (nazwy i stan daje sama Jade; tu dokladamy to, czego w niej nie ma).
 *
 * <p><b>Jak to jest odkrywane.</b> Jade znajduje pluginy po adnotacji
 * {@link WailaPlugin} na klasie z naszego moda, wiec nie rejestrujemy go
 * w {@code CraftingVeloceMod} - i, co wazniejsze, ta klasa NIE jest ladowana,
 * gdy Jade nie ma (nikt jej nie dotyka). Dzieki temu Jade pozostaje miekka
 * zaleznoscia: bez niej mod dziala dokladnie jak wczesniej.
 *
 * <p><b>Jedna rejestracja na cala klase {@link Block}.</b> Lista naszych
 * blokow rosnie (moduly Create/Mekanism/Alchemistry), wiec rejestracja per
 * blok bylaby kolejnym spisem do rozjechania sie z rejestrem. Zamiast tego
 * rejestrujemy sie dla wszystkich blokow, a provider sam rozpoznaje "swoje"
 * po namespace - czyli dokladnie po tym, co widzi gracz.
 */
@WailaPlugin("craftingveloce")
public class VeloceJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        // Dane liczone raz na serwerze (energia, operacje, zawartosc sieci) -
        // klient nie ma ich aktualnych, a liczenie ich w tooltipie znaczyloby
        // skan sieci przy kazdej klatce.
        registration.registerBlockDataProvider(new VeloceBlockDataProvider(), Block.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(new VeloceBlockInfoProvider(), Block.class);
    }
}
