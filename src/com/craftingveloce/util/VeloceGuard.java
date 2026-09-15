package com.craftingveloce.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Osłona dla okresowej pracy moda wolanej z ticku poziomu.
 *
 * <p><b>Po co.</b> Trzy rzeczy dzieja sie w kazdym ticku swiata: krok sieci rur,
 * rozglaszanie stanow crafterow i utrzymanie force-loadow. Kazda z nich siega
 * do kodu, ktory nie jest nasz: block entity Toma, Refined Storage, kontenery
 * wanilii. Wystarczy jeden wyjatek z obcej biblioteki (zly capability, kontener
 * zwracajacy null), zeby przerwac tick poziomu - a wtedy Minecraft konczy sie
 * crashem "Exception ticking world", bez wskazania, ze to nasza robota.
 *
 * <p><b>Dlaczego to nie ukrywa bledow.</b> Wyjatek NIE jest polykany po cichu:
 * leci do loga ZAWSZE (takze przy wylogowaniu moda w configu) i to z pelnym
 * stack trace, z nazwa operacji i z informacja, ze bez tej osłony serwer by
 * polegl. Tlumimy tylko POWTARZANIE tego samego bledu - inaczej zawieszona
 * operacja zapchalaby log tysiacami identycznych wpisow i ukryla prawdziwa
 * przyczyne.
 *
 * <p><b>Dlaczego tylko tick poziomu.</b> Dla ticku block entity wanilia sama
 * robi czytelny raport ("Ticking block entity" + nazwa bloku), wiec jest tam
 * lepszy mechanizm niz nasz. Dla ticku poziomu nie ma - i dlatego ta osłona
 * istnieje dokladnie tutaj.
 */
public final class VeloceGuard {

    /** Kanalu nie filtrujemy configiem: to sytuacja, w ktorej serwer mialby paść. */
    private static final Logger LOGGER = LoggerFactory.getLogger("craftingveloce");

    /** Minimalny odstep (w tickach) miedzy logami tego samego bledu. */
    private static final long LOG_THROTTLE_TICKS = 200L;

    /**
     * Ostatni tick, w ktorym logowalismy dany blad.
     *
     * <p>Mapa jest ograniczona Z KONSTRUKCJI: klucze to kilka stalych nazw
     * operacji podawanych w kodzie, nie dane ze swiata.
     */
    private static final Map<String, Long> LAST_LOG = new HashMap<>();

    private VeloceGuard() {
    }

    /** Uruchamia prace; wyjatek loguje i tlumi, zeby nie zabic ticku swiata. */
    public static void run(String what, long gameTime, Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            Long last = LAST_LOG.get(what);
            boolean log = last == null || gameTime < last || gameTime - last >= LOG_THROTTLE_TICKS;
            if (!log) {
                return;
            }
            LAST_LOG.put(what, gameTime);
            LOGGER.error("[Veloce] operacja '{}' zakonczyla sie wyjatkiem - pomijam ja w tym "
                    + "ticku, zeby nie zabic swiata. Zglos to jako blad w modzie.", what, t);
            LOGGER.error("[Veloce] (ten sam blad nie bedzie powtarzany czesciej niz raz "
                    + "na {} tickow)", LOG_THROTTLE_TICKS);
        }
    }
}
