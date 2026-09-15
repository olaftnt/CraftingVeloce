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

    /** Okna, o ktore mozna pytac. */
    public enum Window {
        MINUTE,
        HOUR
    }

    /** Tempo ponizej tej wartosci uznajemy za zero - nie pokazujemy szumu. */
    private static final float EPSILON = 0.01f;

    /**
     * Ruch jednego itemu w oknie, wszystko w sztukach na sekunde.
     *
     * @param net  roznica koncow: dodatnie = przybywa, ujemne = ubywa
     * @param gain ile sztuk DOSZLO na sekunde (zawsze &gt;= 0)
     * @param loss ile sztuk UBYLO na sekunde (zawsze &gt;= 0)
     */
    public record Movement(float net, float gain, float loss) {

        /**
         * Czy w oknie dzieje sie COKOLWIEK.
         *
         * <p>Sprawdzamy brutto, nie netto: item wkładany i wyciagany na
         * przemian ma netto zero, a jednak sie rusza i gracz ma to widziec.
         */
        public boolean isMoving() {
            return gain + loss >= EPSILON;
        }
    }

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
     * Ruch jednego itemu w oknie.
     *
     * @return {@code null}, gdy nie ma z czego liczyc - item ma mniej niz dwie
     *         probki (albo okno nie objelo jeszcze sekundy)
     */
    public Movement movement(Item item, Window window) {
        Series s = series.get(item);
        if (s == null) {
            return null;
        }
        if (window == Window.MINUTE) {
            int valid = Math.min(s.minuteWrites, SHORT_SLOTS);
            if (valid < 2) {
                return null;
            }
            // Okno minuty jest zamkniete: ostatnia probka JEST teraz.
            return rate(s.minute, minuteTicks, minuteCursor, valid, false, s);
        }
        int valid = Math.min(s.hourWrites, HOUR_SLOTS);
        if (valid >= 1) {
            return rate(s.hour, hourTicks, hourCursor, valid, true, s);
        }
        // Item pojawil sie PO ostatniej probce godzinowej. Zamiast milczec przez
        // cala minute, kotwiczymy na jego pierwszej probce minutowej - to jest
        // dokladnie moment, w ktorym item pojawil sie w sieci.
        int minuteValid = Math.min(s.minuteWrites, SHORT_SLOTS);
        if (minuteValid < 2) {
            return null;
        }
        return rate(s.minute, minuteTicks, minuteCursor, minuteValid, true, s);
    }

    /**
     * Tempo od NAJSTARSZEJ zapamietanej probki pierscienia do konca okna.
     *
     * <p>Idziemy parami po KOLEJNYCH probkach, a nie tylko po skrajnych:
     * tylko tak widac, ile sztuk przeszlo w kazda strone. Skrajne daja
     * wylacznie netto.
     *
     * <p>Czas okna bierzemy z PIERSciENIA TICKOW, a nie z liczby probek:
     * trzynascie probek to 60 s tylko wtedy, gdy zadnej nie wypadlo. Nie
     * zgadujemy i nie sciskamy czasu do okna - liczymy tempo z tylu sekund,
     * ile REALNIE obejmuja zapamietane probki. Przerwy wieksze niz
     * {@link #GAP_RESET_TICKS} i tak kasuja historie, wiec okno nie rozjedzie
     * sie daleko od swojej nazwy.
     *
     * @param live czy koncem okna jest BIEZACY stock; okno godzinowe musi
     *             patrzec na teraz, bo inaczej liczba zamarzalaby na minute
     *             po kazdym zapisie
     */
    private Movement rate(long[] values, long[] ticks, int cursor, int valid,
                          boolean live, Series s) {
        int oldestIdx = Math.floorMod(cursor - valid, values.length);
        long oldest = values[oldestIdx];
        long prev = oldest;
        long gain = 0;
        long loss = 0;
        for (int i = 1; i < valid; i++) {
            long v = values[Math.floorMod(oldestIdx + i, values.length)];
            gain += Math.max(0L, v - prev);
            loss += Math.max(0L, prev - v);
            prev = v;
        }
        long newest = live ? currentValue(s) : prev;
        if (live) {
            long step = newest - prev;
            gain += Math.max(0L, step);
            loss += Math.max(0L, -step);
        }
        float seconds = (lastSampleTick - ticks[oldestIdx]) / TICKS_PER_SECOND;
        if (seconds < 1f) {
            return null;
        }
        return new Movement((newest - oldest) / seconds, gain / seconds, loss / seconds);
    }

    /** Biezacy stock itemu: ostatnia zapisana probka minutowa. */
    private long currentValue(Series s) {
        return s.minute[Math.floorMod(minuteCursor - 1, SHORT_SLOTS)];
    }

    /**
     * Ruch tylko dla itemow, ktore REALNIE sie ruszaja.
     *
     * <p>Do wyslania na klienta. Celowo pomijamy stojace itemy: w typowej
     * sieci jest ich wiekszosc, a wysylanie ich wszystkich co sekunde byloby
     * marnowaniem pasma. Brak wpisu w mapie = "stoi".
     */
    public Map<Item, Movement> movements(Window window) {
        Map<Item, Movement> out = new HashMap<>();
        for (Item item : series.keySet()) {
            Movement m = movement(item, window);
            if (m != null && m.isMoving()) {
                out.put(item, m);
            }
        }
        return out;
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
