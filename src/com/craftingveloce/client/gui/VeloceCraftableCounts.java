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
        for (Slot slot : slots) {
            if (slot == null || !slot.hasItem() || isPlayerSlot.test(slot)) {
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
            return;
        }
        if (!force && initialRequestSent && signature == lastSignature) {
            return;
        }
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
    }
}
