package com.craftingveloce.crafting;

import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Mierzy, ile sztuk kazdego itemu na sekunde PRZYBYWA albo UBYWA w sieci.
 *
 * <p><b>Po co to jest.</b> Sam stan magazynu nie odpowiada na pytanie "czy to
 * ucieka?". Przy dzialajacej fabryce stock ciagle sie zmienia, a gracz widzi
 * tylko jedna liczbe i nie wie, czy to zapas, ktory rosnie, czy tasma, ktora
 * go zaraz oprozni. Ten tracker zamienia kolejne migawki w TEMPO.
 *
 * <p><b>Jak liczy.</b> Co {@link #SAMPLE_INTERVAL_TICKS} zapisujemy migawke
 * stocku do dwoch pierscieni: okna minuty (probki co 5 s) i okna godziny
 * (probki co 60 s). Tempo w oknie to roznica miedzy najnowsza i najstarsza
 * probka podzielona przez RZECZYWISTY czas, jaki te probki obejmuja.
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
 * "nie wiem" ({@code NaN}), a nie zero i nie zgadywanie.
 *
 * <p><b>Pamiec.</b> Jedna mapa {@code Item -> Series} zamiast czterech
 * rownoleglych map, i usuwanie itemow, ktore zniknely z sieci na cale okno.
 */
public final class VeloceFlowTracker {

    /** Co ile tickow robimy migawke. 100 tickow = 5 sekund. */
    public static final int SAMPLE_INTERVAL_TICKS = 100;

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

    /** Okna, o ktore mozna pytac. */
    public enum Window {
        MINUTE,
        HOUR
    }

    /** Tempo ponizej tej wartosci uznajemy za zero - nie pokazujemy szumu. */
    private static final float EPSILON = 0.01f;

    /** Historia jednego itemu: oba pierscienie i liczniki wlasnych zapisow. */
    private static final class Series {
        final long[] minute = new long[SHORT_SLOTS];
        final long[] hour = new long[HOUR_SLOTS];
        /** Ile razy TEN item trafil do danego pierscienia. */
        int minuteWrites;
        int hourWrites;
    }

    private final Map<Item, Series> series = new HashMap<>();

    private int minuteCursor;
    private int hourCursor;
    /** Ile migawek zrobiono w sumie (globalnie, do liczenia pokrycia okna). */
    private int totalSamples;
    /** Ile probek trafilo do pierscienia godzinowego (globalnie). */
    private int hourSamples;

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
        lastSampleTick = gameTime;
        totalSamples++;

        // Probka godzinowa wypada przy 1., 13., 25. migawce - czyli co
        // MINUTE_INTERVALS krotkich probek, licząc od pierwszej.
        boolean hourTick = (totalSamples - 1) % MINUTE_INTERVALS == 0;

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
            hourSamples++;
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
     * Tempo zmiany w sztukach na sekunde.
     *
     * @return dodatnie = przybywa, ujemne = ubywa, {@code 0} = stoi,
     *         {@code NaN} = jeszcze nie wiemy (ten item ma mniej niz 2 probki)
     */
    public float ratePerSecond(Item item, Window window) {
        Trend trend = trend(item, window);
        if (trend == null || trend.seconds <= 0f) {
            return Float.NaN;
        }
        return (trend.newest - trend.oldest) / trend.seconds;
    }

    /**
     * Ile sekund REALNIE obejmuje okno.
     *
     * <p>Moze byc mniej niz pelne okno, gdy serwer stoi krotko - i wtedy
     * wlasnie tak to raportujemy, zamiast udawac pelna godzine z trzech minut
     * danych. {@code 0} oznacza brak danych.
     */
    public float coveredSeconds(Window window) {
        int writes = window == Window.MINUTE ? totalSamples : hourSamples;
        int slots = window == Window.MINUTE ? SHORT_SLOTS : HOUR_SLOTS;
        if (writes < 2) {
            return 0f;
        }
        int intervals = Math.min(writes, slots) - 1;
        return (float) intervals * (window == Window.MINUTE ? SHORT_SECONDS : 60);
    }

    /** Czy mamy juz co najmniej dwie probki - czyli czy tempo jest policzalne. */
    public boolean isReady(Window window) {
        return coveredSeconds(window) > 0f;
    }

    /** Najstarsza i najnowsza probka okna oraz czas, ktory obejmuja. */
    private record Trend(long oldest, long newest, float seconds) {
    }

    private Trend trend(Item item, Window window) {
        Series s = series.get(item);
        if (s == null) {
            return null;
        }
        boolean minute = window == Window.MINUTE;
        long[] arr = minute ? s.minute : s.hour;
        int slots = minute ? SHORT_SLOTS : HOUR_SLOTS;
        // PER ITEM: item z jedna probka nie ma z czego liczyc tempa.
        int writes = minute ? s.minuteWrites : s.hourWrites;
        int cursor = minute ? minuteCursor : hourCursor;

        if (writes < 2) {
            return null;
        }
        int valid = Math.min(writes, slots);
        // Kursor wskazuje miejsce NASTEPNEGO zapisu, wiec najnowsza probka
        // lezy tuz przed nim, a najstarsza - `valid - 1` krokow w tyl.
        int newestIdx = Math.floorMod(cursor - 1, slots);
        int oldestIdx = Math.floorMod(cursor - valid, slots);
        float secondsPerSample = minute ? SHORT_SECONDS : 60f;
        return new Trend(arr[oldestIdx], arr[newestIdx], (valid - 1) * secondsPerSample);
    }

    /**
     * Tempa tylko dla itemow, ktore REALNIE sie ruszaja.
     *
     * <p>Do wyslania na klienta. Celowo pomijamy zera: w typowej sieci
     * wiekszosc itemow stoi, a wysylanie ich wszystkich co sekunde byloby
     * marnowaniem pasma. Brak wpisu w mapie = "stoi".
     */
    public Map<Item, Float> movingItems(Window window) {
        Map<Item, Float> out = new HashMap<>();
        for (Item item : series.keySet()) {
            float rate = ratePerSecond(item, window);
            if (!Float.isNaN(rate) && Math.abs(rate) >= EPSILON) {
                out.put(item, rate);
            }
        }
        return out;
    }

    /** Wszystko do kosza - np. gdy kontroler zostal przestawiony na inna siec. */
    public void reset() {
        series.clear();
        minuteCursor = 0;
        hourCursor = 0;
        totalSamples = 0;
        hourSamples = 0;
        lastSampleTick = Long.MIN_VALUE;
    }
}
