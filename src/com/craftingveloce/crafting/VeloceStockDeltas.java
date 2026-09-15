package com.craftingveloce.crafting;

import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Roznica stocku dla klienta: co sie ZMIENILO, a nie caly stan sieci.
 *
 * <p><b>Problem, ktory to rozwiazuje.</b> Kontroler odpytywal serwer raz na
 * sekunde, a odpowiedzia byl CALY stock sieci. Przy sieci z tysiacami roznych
 * itemow to tysiace wpisow na sekunde, mimo ze stock prawie nigdy sie nie
 * zmienia: gracz stojacy przy kontrolerze nie przenosi itemow, a mimo to
 * serwer za kazdym razem serializowal i wysylal ten sam obraz. Im wieksza
 * siec, tym wiekszy ruch - dokladnie w stylu "im dluzej gram, tym gorzej".
 *
 * <p><b>Rozwiazanie.</b> Wysylamy tylko wpisy, ktore roznia sie od poprzednio
 * wyslanych, plus liste itemow, ktore zniknely. Wartosci sa BEZWZGLEDNE
 * (a nie "o ile wzroslo"), wiec zastosowanie tej samej roznicy dwa razy nic
 * nie psuje - to wazne, bo kilku graczy moze patrzec w ten sam kontroler,
 * a kazdy z nich ma wlasna kopie stocku.
 *
 * <p><b>Pelny zrzut co minute.</b> Gdyby cokolwiek kiedys rozjechalo klienta
 * z serwerem (blad w kodzie, przeladowanie), sama roznica nie pozwolilaby mu
 * wrocic do zgodnosci - dlatego co {@link #FULL_RESYNC_TICKS} tickow leci
 * pelny stock. To ubezpieczenie kosztuje jeden wiekszy pakiet na minute.
 */
public final class VeloceStockDeltas {

    /** Co tyle tickow pelny zrzut stocku (60 s) - zabezpieczenie przed rozjazdem. */
    private static final long FULL_RESYNC_TICKS = 1200L;

    /**
     * Roznica do wyslania.
     *
     * @param changed wpisy nowe albo o zmienionej liczbie (wartosci bezwzgledne)
     * @param removed itemy, ktorych w sieci juz nie ma
     * @param full    czy to pelny zrzut (klient ma zastapic stock, a nie scalac)
     */
    public record Delta(Map<Item, Long> changed, Set<Item> removed, boolean full) {
    }

    private final Map<Item, Long> last = new HashMap<>();
    private long lastFullTick = Long.MIN_VALUE;

    /**
     * Liczy roznice wobec poprzedniej wysylki i zapamietuje nowy stan.
     *
     * @param current  aktualny stock sieci
     * @param gameTime czas gry (do okresowego pelnego zrzutu)
     */
    public Delta diff(Map<Item, Long> current, long gameTime) {
        boolean full = lastFullTick == Long.MIN_VALUE
                || gameTime - lastFullTick >= FULL_RESYNC_TICKS;

        Map<Item, Long> changed = new HashMap<>();
        for (Map.Entry<Item, Long> entry : current.entrySet()) {
            Long previous = last.get(entry.getKey());
            if (full || previous == null || !previous.equals(entry.getValue())) {
                changed.put(entry.getKey(), entry.getValue());
            }
        }
        Set<Item> removed = new HashSet<>();
        for (Item item : last.keySet()) {
            if (!current.containsKey(item)) {
                removed.add(item);
            }
        }

        last.clear();
        last.putAll(current);
        if (full) {
            lastFullTick = gameTime;
        }
        return new Delta(changed, removed, full);
    }

    /** Zapomina zapamietany stan - nastepna roznica bedzie pelnym zrzutem. */
    public void reset() {
        last.clear();
        lastFullTick = Long.MIN_VALUE;
    }
}
