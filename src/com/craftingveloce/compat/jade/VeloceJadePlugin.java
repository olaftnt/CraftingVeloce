package com.craftingveloce.compat.jade;

import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Plugin Jade: opis naszych maszyn w podpowiedzi przy celowniku.
 *
 * <p><b>Co to daje.</b> Gracz patrzy na maszyne i widzi to samo, co w oknie po
 * prawym kliku: predkosc aktualna/wymagana/maksymalna, pobor SU, liczbe
 * wklikanych elementow, stan akumulatora (dla maszyn na FE), stan sieci rur
 * i status - w tym <b>"za malo sily"</b>. Wczesniej ten status rysowalismy
 * wlasnym napisem na srodku ekranu; gracz kazal to wyrzucic i zrobic to przez
 * Jade ("ten mod, co pokazuje, na co sie patrzysz").
 *
 * <p><b>Rejestracja bez znajomosci innych modow.</b> Dane ida przez
 * {@code VeloceModuleInfoSource} (rdzeniowy interfejs), a nie przez typy
 * Create/Mekanism/Alchemistry:
 * <ul>
 *   <li>serwer: provider danych dla <b>wszystkich</b> block entity
 *       ({@code BlockEntity.class}, tak samo jak robi to sam Jade) i pyta
 *       o dane tylko wtedy, gdy BE implementuje nasz interfejs
 *       ({@code shouldRequestData}) - dlatego nie ma tu ani jednego typu
 *       z modulu maszyn,</li>
 *   <li>klient: komponent dla wszystkich blokow z tym samym filtrem.</li>
 * </ul>
 * Dzieki temu plugin dziala takze, gdy z tych modow nie ma NIC (wtedy po prostu
 * nie ma maszyn, ktore moglby opisac).
 */
@WailaPlugin
public class VeloceJadePlugin implements IWailaPlugin {

    /** Wspolny UID providerow (Jade wymaga unikalnego identyfikatora). */
    public static final ResourceLocation MODULE_INFO_UID =
            ResourceLocation.fromNamespaceAndPath("craftingveloce", "module_info");

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(VeloceModuleDataProvider.INSTANCE,
                net.minecraft.world.level.block.entity.BlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(VeloceModuleComponentProvider.INSTANCE,
                net.minecraft.world.level.block.Block.class);
    }
}
