package com.craftingveloce.compat.jade;

import com.craftingveloce.compat.VeloceMods;

/**
 * Bramka integracji z Jade (podpowiedzi przy celowniku).
 *
 * <p><b>Izolacja.</b> Ladowana ZAWSZE, takze bez Jade - zero typow Jade w polach
 * i sygnaturach. Plugin ({@link VeloceJadePlugin}) ma obce typy w sygnaturach,
 * ale laduje go samo Jade, skanujac adnotacje {@code @WailaPlugin} - bez Jade
 * nie ma kto tego zrobic.
 *
 * <p>Wpis w {@link VeloceMods} sluzy do logu obecnosci i do kontroli w buildzie;
 * sam plugin jest odkrywany przez Jade, wiec rdzen go nie wola.
 */
public final class JadeCompat {

    private JadeCompat() {
    }

    /** Czy Jade jest obecne. Bezpieczne takze bez Jade. */
    public static boolean isPresent() {
        return VeloceMods.JADE.isLoaded();
    }
}
