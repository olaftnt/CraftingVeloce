package com.craftingveloce.crafting;

import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Mierzy, ile sztuk kazdego itemu na sekunde PRZYBYWA i UBYWA w sieci.
 *
 * <p><b>Po co to jest.</b> Sam stan magazynu nie odpowiada na pytanie "czy to
 * ucieka?". Przy dzialajacej fabryce stock ciagle sie zmienia, a gracz widzi
 * tylko jedna liczbe i nie wie, czy to zapas, ktory rosnie, czy tasma, ktora
 * go zaraz oprozni. Ten tracker zamienia kolejne migawki w TEMPO.
 *
 * <p><b>Jak czesto probkuje.</b> Co {@link #SAMPLE_INTERVAL_TICKS} tickow
 * (5 s) zapisujemy jedna migawke stocku calej sieci. Z tych migawek powstaja
 * DWA okna: minuta (13 probek co 5 s) i godzina (61 probek co 60 s). Okno
 * minuty mowi "co sie dzieje TERAZ", okno godziny - "jaki jest dlugofalowy
 * bilans".
 *
 * <p><b>Kiedy okno godzinowe ma dane.</b> Nie czeka godzine. Kotwica jest
 * NAJSTARSZA zapamietana probka godzinowa (na starcie ta z t=0, po godzinie
 * ta sprzed godziny), a koncem okna jest BIEZACY stock - dlatego liczba jest
 * od drugiej migawki (5 s), a nie od 60. Item, ktory pojawil sie w sieci po
 * ostatniej probce godzinowej, kotwiczy sie na swojej pierwszej probce
 * minutowej, wiec nie milczy przez minute. Wczesniejsza wersja wymagala dwoch
 * probek godzinowych i przez pierwsza minute pokazywala "zbieram dane";
 * gracz zglosil to jako blad ("dane sie nie pokazywaly"), i mial racje.
 *
 * <p><b>Netto i brutto.</b> Sama roznica koncow daje NETTO, ktore przy
 * wkładaniu i wyciaganiu tych samych itemow wychodzi zero - i gracz widzi
 * "bez zmian", mimo ze tasma pracuje. Dlatego liczymy takze BRUTTO: ile
 * sztuk doszlo i ile ubyło, z osobna. Netto odpowiada "czy zapas rosnie",
 * brutto - "ile przez to przeplywa".
 *
 * <p><b>Dlaczego pierscien ma {@code odstepy + 1} slotow.</b> To nie jest
 * przeoczenie. Zeby objac pelne 60 s probkami co 5 s, potrzebujemy 13 probek -
 * bo 13 punktow wyznacza 12 odstepow. Wersja z 12 slotami obejmowala tylko
 * 55 s i cicho zanizala kazde tempo o 1/12.
 *
 * <p><b>Licznik zapisow jest PER ITEM, nie globalny.</b> To tez nie jest
 * szczegol: item, ktory wlasnie pojawil sie w sieci, ma jedna probke, a
 * tracker ma ich za soba setki. Globalny licznik kazalby mu policzyc tempo
 * z jednej probki i zer wypelniajacych pierscien - czyli pokazac liczbe
 * z sufitu. Per item rozwiazuje to uczciwie: mniej niz dwie probki to
 * "nie wiem" ({@code null}), a nie zero i nie zgadywanie.
 *
 * <p><b>Pamiec.</b> Jedna mapa {@code Item -> Series} zamiast czterech
 * rownoleglych map, i usuwanie itemow, ktore zniknely z sieci na cale okno.
 */
public final class VeloceFlowTracker {

    /** Co ile tickow robimy migawke. 100 tickow = 5 sekund. */
    public static final int SAMPLE_INTERVAL_TICKS = 100;

    /**
     * Przerwa dluzsza niz tyle tickow kasuje historie.
     *
     * <p>Kontroler tyka tylko wtedy, gdy jego chunk jest zaladowany. Gdy gracz
     * odejdzie, probek nie ma - a po powrocie pierscien nadal trzyma wartosci
     * sprzed przerwy. Bez tego resetu tempo liczone byloby z roznicy, ktora
     * obejmuje czas, gdy nikt nie patrzyl (a czesc tego czasu mogl zjesc
     * dowolny proces), czyli z liczby wyssanej z palca. 20 s tolerancji
     * zostawiamy na zwykle zacięcie serwera.
     */
    private static final int GAP_RESET_TICKS = SAMPLE_INTERVAL_TICKS * 4;

    /** Sekund na probke w oknie minuty (100 tickow / 20). */
    private static final int SHORT_SECONDS = SAMPLE_INTERVAL_TICKS / 20;

    /** Ile ODSTEPOW miesci sie w oknie minuty (60 s / 5 s). */
    private static final int MINUTE_INTERVALS = 60 / SHORT_SECONDS;

    /** Ile probek tworzy okno minuty: odstepy + 1 (patrz opis klasy). */
    private static final int SHORT_SLOTS = MINUTE_INTERVALS + 1;

    /** Ile ODSTEPOW miesci sie w oknie godziny (3600 s / 60 s). */
    private static final int HOUR_INTERVALS = 60;

    /** Ile probek tworzy okno godziny: odstepy + 1. */
    private static final int HOUR_SLOTS = HOUR_INTERVALS + 1;

    /** Sekund na probke w oknie godziny. */
    private static final int HOUR_SECONDS = 60;

    /** Tickow na sekunde - do zamiany roznicy tickow na sekundy. */
    private static final float TICKS_PER_SECOND = 20f;

    /**
     * Minimalny udzial netto w calym ruchu, zeby uznac trend za JEDNOKIERUNKOWY.
     *
     * <p>0.8 = "co najmniej 80% ruchu idzie w jedna strone". Dzieki temu item
     * wkladany i wyciagany na przemian (netto zero, brutto duze) NIE jest
     * pokazywany jako trend - gracz nie chce widziec szarpania, tylko produkcje.
     */
    private static final float STEADY_SHARE = 0.8f;

    /**
     * Ile odstepow musialo sie ruszyc, zeby to byl TREND, a nie jednorazowy skok.
     *
     * <p>Dwa, bo jedno wlozenie itemu do skrzynki to nie "staly przyrost".
     */
    private static final int STEADY_MIN_STEPS = 2;

    /** Tempo ponizej tej wartosci uznajemy za zero - nie pokazujemy szumu. */
    private static final float EPSILON = 0.01f;

    /** Historia jednego itemu: oba pierscienie i liczniki wlasnych zapisow. */
    private static final class Series {
        final long[] minute = new long[SHORT_SLOTS];
        /** Ile razy TEN item trafil do okna minuty. */
        int minuteWrites;
        final long[] hour = new long[HOUR_SLOTS];
        /** Ile razy TEN item trafil do okna godziny. */
        int hourWrites;
    }

    private final Map<Item, Series> series = new HashMap<>();

    /**
     * Tick gry kazdej probki - wspolny dla wszystkich itemow.
     *
     * <p>Trzymamy je OSOBNO od wartosci, bo czas okna liczymy z rzeczywistych
     * tickow, a nie z liczby probek: gdy serwer stoi albo chunk sie rozladuje,
     * probek ubywa i "13 probek" nie znaczy juz 60 sekund.
     */
    private final long[] minuteTicks = new long[SHORT_SLOTS];
    private final long[] hourTicks = new long[HOUR_SLOTS];

    private int minuteCursor;
    private int hourCursor;
    /** Ile migawek zrobiono w sumie (globalnie). */
    private int totalSamples;

    private long lastSampleTick = Long.MIN_VALUE;

    /** Czy wypada zrobic kolejna migawke. */
    public boolean due(long gameTime) {
        return lastSampleTick == Long.MIN_VALUE
                || gameTime - lastSampleTick >= SAMPLE_INTERVAL_TICKS;
    }

    /**
     * Zapisuje migawke stocku.
     *
     * <p>Iterujemy po UNII: wszystko, co juz pamietamy, plus wszystko, co jest
     * teraz. Dzieki temu item, ktory wlasnie zniknal z sieci, dostaje zero
     * (a nie zostaje z ostatnia znana wartoscia i nie udaje, ze stoi), a kursory
     * pierscieni pozostaja zsynchronizowane dla wszystkich itemow.
     */
    public void sample(long gameTime, Map<Item, Long> stock) {
        if (lastSampleTick != Long.MIN_VALUE
                && gameTime - lastSampleTick > GAP_RESET_TICKS) {
            reset();   // przerwa w tykaniu - stara historia klamie, zaczynamy od nowa
        }
        lastSampleTick = gameTime;
        totalSamples++;

        // Probka godzinowa wypada przy 1., 13., 25. migawce - czyli co
        // MINUTE_INTERVALS krotkich probek, licząc od pierwszej.
        boolean hourTick = (totalSamples - 1) % MINUTE_INTERVALS == 0;
        minuteTicks[minuteCursor] = gameTime;
        if (hourTick) {
            hourTicks[hourCursor] = gameTime;
        }

        Set<Item> items = new HashSet<>(series.keySet());
        items.addAll(stock.keySet());

        for (Item item : items) {
            Series s = series.computeIfAbsent(item, k -> new Series());
            long now = stock.getOrDefault(item, 0L);
            s.minute[minuteCursor] = now;
            s.minuteWrites++;
            if (hourTick) {
                s.hour[hourCursor] = now;
                s.hourWrites++;
            }
        }

        minuteCursor = (minuteCursor + 1) % SHORT_SLOTS;
        if (hourTick) {
            hourCursor = (hourCursor + 1) % HOUR_SLOTS;
            pruneEmpty();
        }
    }

    /**
     * Usuwa itemy, ktore maja zera w CALYCH obu oknach.
     *
     * <p>Bez tego serwer, na ktorym itemy kraza (pojawiaja sie i znikaja),
     * trzymalby tablice po kazdym itemie, jaki kiedykolwiek przez niego przeszedl.
     */
    private void pruneEmpty() {
        series.values().removeIf(s -> allZero(s.minute) && allZero(s.hour));
    }

    private static boolean allZero(long[] arr) {
        for (long v : arr) {
            if (v != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tempo itemow o STAŁYM trendzie - w sztukach na SEKUNDE.
     *
     * <p>Jedna liczba: klient przelicza ja na minute i godzine i pokazuje
     * w jednej linii ("+2.0/min, +120/h"). Item, ktory stoi albo sie szarpie,
     * NIE trafia do mapy - i wtedy w tooltipie nie ma o nim zadnej linii.
     */
    public Map<Item, Float> steadyRates() {
        Map<Item, Float> out = new HashMap<>();
        for (Item item : series.keySet()) {
            float rate = steadyRate(item);
            if (!Float.isNaN(rate)) {
                out.put(item, rate);
            }
        }
        return out;
    }

    /** @return netto (szt./s) gdy trend jest jednokierunkowy, inaczej {@code NaN} */
    private float steadyRate(Item item) {
        Series s = series.get(item);
        if (s == null) {
            return Float.NaN;
        }
        // TRYB WCZESNY: dopoki okno godzinowe ma mniej niz 3 probki (pierwsze
        // ~2 minuty po zaladowaniu kontrolera), trend oceniamy na oknie MINUTY -
        // probki co 5 s, wiec linia pojawia sie po kilkunastu sekundach, a nie
        // po dwoch minutach. Potem ocene przejmuje okno godzinowe, odporne na
        // chwilowe szarpniecia.
        long now = currentValue(s);
        if (s.hourWrites < 3) {
            int valid = Math.min(s.minuteWrites, SHORT_SLOTS);
            return valid < 3 ? Float.NaN
                    : steadyRate(s.minute, minuteTicks, minuteCursor, valid, now);
        }
        int valid = Math.min(s.hourWrites, HOUR_SLOTS);
        return steadyRate(s.hour, hourTicks, hourCursor, valid, now);
    }

    /**
     * Trend z pierscienia probek: tempo netto albo {@code NaN}.
     *
     * <p>Kolejnosc jest istotna dla WYDAJNOSCI: najpierw tani test (roznica
     * skrajnych probek), a spacer po probkach tylko dla itemow, ktore sie
     * ruszaja - bo on sluzy juz tylko do rozbicia ruchu na kierunki. W sieci ze
     * 100 tys. typow itemow to roznica miedzy "przejdz liste" a "przejdz liste
     * i dodatkowo 61 probek dla kazdego itemu".
     */
    private float steadyRate(long[] values, long[] ticks, int cursor, int valid, long now) {
        int oldestIdx = Math.floorMod(cursor - valid, values.length);
        float seconds = (lastSampleTick - ticks[oldestIdx]) / TICKS_PER_SECOND;
        if (seconds < 1f) {
            return Float.NaN;
        }
        long oldest = values[oldestIdx];

        // 1) TANI TEST: czy w oknie jest w ogole ruch netto.
        float net = (now - oldest) / seconds;
        if (Math.abs(net) < EPSILON) {
            return Float.NaN;
        }

        // 2) Spacer po probkach - tylko dla ruchomych itemow.
        long prev = oldest;
        long gain = 0;
        long loss = 0;
        int up = 0;
        int down = 0;
        for (int i = 1; i < valid; i++) {
            long v = values[Math.floorMod(oldestIdx + i, values.length)];
            long d = v - prev;
            if (d > 0) {
                gain += d;
                up++;
            } else if (d < 0) {
                loss += -d;
                down++;
            }
            prev = v;
        }
        long step = now - prev;
        if (step > 0) {
            gain += step;
            up++;
        } else if (step < 0) {
            loss += -step;
            down++;
        }

        if ((net > 0 ? up : down) < STEADY_MIN_STEPS) {
            return Float.NaN;   // jednorazowy skok to nie trend
        }
        float gross = (gain + loss) / seconds;
        if (gross <= 0f || Math.abs(net) < STEADY_SHARE * gross) {
            return Float.NaN;   // za duzo ruchu w druga strone - to nie trend
        }
        return net;
    }

    /** Biezacy stock itemu: ostatnia zapisana probka minutowa. */
    private long currentValue(Series s) {
        return s.minute[Math.floorMod(minuteCursor - 1, SHORT_SLOTS)];
    }

    /** Wszystko do kosza - np. gdy kontroler zostal przestawiony na inna siec. */
    public void reset() {
        series.clear();
        java.util.Arrays.fill(minuteTicks, 0L);
        java.util.Arrays.fill(hourTicks, 0L);
        minuteCursor = 0;
        hourCursor = 0;
        totalSamples = 0;
        lastSampleTick = Long.MIN_VALUE;
    }
}
