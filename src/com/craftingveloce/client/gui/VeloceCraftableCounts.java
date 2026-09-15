package com.craftingveloce.client.gui;

import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Liczby "ile da sie jeszcze dorobic" dla WIDOCZNYCH itemow.
 *
 * <p><b>Po co osobna klasa.</b> Terminal mial cala te mechanike w sobie:
 * zbieranie widocznych itemow, sygnature strony (zeby nie pytac co tick),
 * i sklejanie odpowiedzi z dwoch zrodel (natychmiastowej odpowiedzi i tla).
 * Kontroler potrzebuje dokladnie tego samego, a kopiowanie tego drugi raz
 * gwarantowaloby, ze po kilku zmianach liczby zaczna sie w obu ekranach
 * roznic. Trzymamy to wiec w jednym miejscu i oba ekrany maja wlasna
 * instancje.
 *
 * <p><b>Skad te liczby.</b> Serwer liczy je na zadanie, dla podanej listy
 * itemow, jednym wspoldzielonym budzetem czasu ({@code countCraftableBatch})
 * i odsyla gotowe wartosci. Tlo (cache) przelicza reszte sieci, ale gracz nie
 * musi na to czekac, zeby zobaczyc aktualne liczby tam, gdzie patrzy.
 */
public final class VeloceCraftableCounts {

    /** item -> ile sztuk da sie dorobic (zolta liczba "+N"). */
    private final Map<Item, Long> counts = new HashMap<>();

    /** Itemy z ostatniego zadania - zeby wiedziec, ktore wpisy odswiezyc. */
    private final Set<Item> lastRequested = new HashSet<>();

    /**
     * Sygnatura ostatnio zamowionej strony.
     *
     * <p>Pozwala wykryc zmiane zawartosci ekranu (inna zakladka, przewiniecie)
     * i zamowic liczby dla nowej strony, bez wysylania zadania co tick.
     */
    private int lastSignature;

    /** Czy juz zamowiono liczby po otwarciu ekranu. */
    private boolean initialRequestSent;

    /**
     * Czy ostatnia odpowiedz byla NIEPELNA.
     *
     * <p>Serwer liczy partie we wspolnym budzecie czasu i przerywa, gdy sie
     * skonczy - w logu widac to jako "45 item(s) -> 3 result(s) (complete=false)".
     * Przy takiej odpowiedzi czesc itemow nie dostala nowej liczby i trzymala
     * stara. Bez ponowienia dzialo sie to Az DO ZMIANY WIDOKU, bo zapytanie
     * wychodzi tylko przy zmianie sygnatury - czyli liczba mogla zostac
     * zamrozona na wartosci z poczatku sesji (zgloszenie gracza: "wyjme
     * polowe piasku, a dalej pokazuje 25").
     */
    private boolean partial;

    /** Co ile tickow ponawiac, gdy odpowiedz byla niepelna. */
    private static final int RETRY_INTERVAL_TICKS = 10;
    private int retryCooldown;

    /**
     * Czy zamowic liczby JESZCZE RAZ w pierwszym ticku po otwarciu ekranu.
     *
     * <p><b>Po co.</b> {@code init()} leci, zanim wanilia zdazy wypelnic sloty
     * siatki (przy przywracanej frazie widzielismy to w logu: zadanie na 45
     * pozycji, a liczby pojawialy sie tylko na czesci). Pierwsze zadanie idzie
     * wiec od razu (zeby gracz nie czekal), a drugie - juz po wypelnieniu
     * slotow - w najblizszym ticku.
     */
    private boolean repeatFirstRequest;

    /** Probka nazw itemow do logu diagnostycznego (max {@code limit}). */
    private static String sample(List<Item> items, int limit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size() && i < limit; i++) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(items.get(i)).getPath());
        }
        if (items.size() > limit) {
            sb.append(", ...");
        }
        return sb.toString();
    }

    /** Liczba do dorobienia dla tego itemu; 0 gdy brak wpisu. */
    public long get(Item item) {
        return counts.getOrDefault(item, 0L);
    }

    /** Wpisuje wartosci policzone gdzie indziej (np. migawka tla z serwera). */
    public void putAll(Map<Item, Long> craftable) {
        if (craftable != null && !craftable.isEmpty()) {
            counts.putAll(craftable);
        }
    }

    /**
     * Zamawia liczby dla itemow widocznych na ekranie.
     *
     * @param pos          pozycja bloku, ktorego siec ma byc przeliczona
     * @param slots        sloty ekranu
     * @param isPlayerSlot czy dany slot nalezy do gracza (pomijamy takie)
     * @param force        true = wyslij nawet gdy sygnatura sie nie zmienila
     */
    public void request(BlockPos pos, List<Slot> slots,
                        Predicate<Slot> isPlayerSlot, boolean force) {
        if (pos == null || slots == null) {
            return;
        }
        // HashSet do wykrywania duplikatow: to leci co tick, a contains() na
        // liscie bylby skanem O(n) przy kazdym slocie - czyli O(n^2) na tick.
        List<Item> visible = new ArrayList<>();
        Set<Item> seen = new HashSet<>();
        int signature = 1;
        // Liczniki do logu: bez nich nie da sie stwierdzic, CZY i CO klient
        // w ogole objal zadaniem (gracz podejrzewal, ze request nie leci).
        int totalSlots = 0;
        int playerSlots = 0;
        int emptySlots = 0;
        int duplicates = 0;
        for (Slot slot : slots) {
            totalSlots++;
            if (slot == null || !slot.hasItem() || isPlayerSlot.test(slot)) {
                if (slot == null || !slot.hasItem()) {
                    emptySlots++;
                } else {
                    playerSlots++;
                }
                continue;
            }
            Item item = slot.getItem().getItem();
            if (seen.add(item)) {
                visible.add(item);
                // Sygnatura: sklad i kolejnosc widocznych itemow.
                signature = signature * 31 + item.hashCode();
            }
            // Twardy limit: serwer odrzuca zadania wieksze niz MAX_ITEMS.
            // Widoczna strona to kilkadziesiat pozycji, ale przy nietypowym
            // ukladzie slotow moze byc wiecej - lepiej wyslac obcieta liste
            // niz taka, ktora serwer odrzuci w calosci.
            if (visible.size() >= com.craftingveloce.network.RequestCraftableCountsPKT.MAX_ITEMS) {
                break;
            }
        }
        if (visible.isEmpty()) {
            com.craftingveloce.util.VeloceLog.Gui.detail(
                    com.craftingveloce.util.VeloceLog.Side.CLIENT,
                    "craftable counts: NIC do policzenia (slots=%d, gracza=%d, puste=%d)",
                    totalSlots, playerSlots, emptySlots);
            return;
        }
        // Ponawiamy, dopoki poprzednia odpowiedz byla niepelna - inaczej itemy
        // z ogona listy nigdy nie doczekalyby sie przeliczenia. Z throttlem,
        // bo kazde zadanie kosztuje serwer do 25 ms.
        boolean retry = this.partial && --this.retryCooldown <= 0;
        boolean firstRepeat = this.repeatFirstRequest;
        this.repeatFirstRequest = false;
        if (!force && !retry && !firstRepeat && initialRequestSent && signature == lastSignature) {
            return;
        }
        this.retryCooldown = RETRY_INTERVAL_TICKS;
        lastSignature = signature;
        initialRequestSent = true;
        lastRequested.clear();
        lastRequested.addAll(visible);
        PacketDistributor.sendToServer(
                new com.craftingveloce.network.RequestCraftableCountsPKT(pos, visible));
    }

    /** Po otwarciu ekranu sygnatura startuje od nowa. */
    public void resetRequestState() {
        initialRequestSent = false;
        partial = false;
        retryCooldown = 0;
        repeatFirstRequest = true;
    }

    /**
     * Odpowiedz serwera z liczbami dla widocznych itemow.
     *
     * <p>Aktualizujemy TYLKO te itemy, o ktore pytalismy. Gdybysmy nadpisali
     * cala mape, tlo (cache) i natychmiastowa odpowiedz nadpisywalyby sie
     * nawzajem i liczby by migotaly.
     *
     * <p>Usuwamy tez wpisy dla pytanych itemow, ktorych nie ma w wyniku.
     * Serwer od pewnego czasu przysyla takze ZERA (konkretna odpowiedz "nie da
     * sie juz nic zrobic"), ale czyszczenie zostaje jako zabezpieczenie dla
     * odpowiedzi bez tych zer.
     */
    public void update(Map<Item, Long> craftable, boolean complete) {
        // Niepelna odpowiedz = trzeba dopytac (patrz pole partial).
        this.partial = !complete;

        if (complete) {
            for (Item it : lastRequested) {
                counts.remove(it);
            }
            counts.putAll(craftable);
        } else {
            // Serwer nie przezyl wszystkiego (siec jeszcze nie gotowa albo
            // budzet sie skonczyl). Tylko dokladamy to, co przyszlo - NIE
            // kasujemy poprzednich wartosci. Bez tego liczby znikaly i nie
            // wracaly.
            counts.putAll(craftable);
        }
        reportState(complete);
    }

    /**
     * Log stanu PO scaleniu odpowiedzi: ile ZADANYCH itemow ma wartosc, ile ma
     * zero, a ilu brakuje.
     *
     * <p>Rozroznia dwie zupelnie rozne przyczyny "brak liczby": brak wpisu =
     * item nie zostal policzony (zadanie go nie objelo), zero = policzony
     * i naprawde nie da sie go zrobic. Wczesniej log byl PRZED scaleniem, wiec
     * pokazywal stan sprzed odpowiedzi i wprowadzal w blad.
     */
    private void reportState(boolean complete) {
        int withValue = 0;
        int zero = 0;
        java.util.List<Item> absent = new java.util.ArrayList<>();
        for (Item it : lastRequested) {
            Long v = counts.get(it);
            if (v == null) {
                absent.add(it);
            } else if (v == 0L) {
                zero++;
            } else {
                withValue++;
            }
        }
        com.craftingveloce.util.VeloceLog.Gui.detail(
                com.craftingveloce.util.VeloceLog.Side.CLIENT,
                "counts AFTER merge for %d requested item(s): %d with a value, "
                        + "%d with ZERO, %d without any entry (complete=%s) -> %s",
                lastRequested.size(), withValue, zero, absent.size(), complete,
                sample(absent, 8));
    }
}
