package com.craftingveloce.network.pipe;

import com.craftingveloce.util.VeloceLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tablica polaczen miedzy sieciami.
 *
 * <p><b>Problem, ktory to rozwiazuje.</b> Poprzednie podejscie bylo destrukcyjne:
 * gdy siec A stykala sie z siecia B, oba obiekty byly <b>kasowane</b>, a na ich
 * miejscu powstawal jeden nowy. Konsekwencje byly powazne i widoczne w grze:
 *
 * <ul>
 *   <li>cache i force-loady obu sieci przepadaly - terminal stojacy daleko
 *       tracil trzymanie swojego chunku,</li>
 *   <li>nie dalo sie ich rozlozyc z powrotem, bo informacja o tym, ze byly
 *       DWIE sieci, juz nie istniala - przy rozlaczeniu trzeba bylo zgadywac
 *       podzial,</li>
 *   <li>gdy obie sieci mialy terminal, jeden z nich tracil tozsamosc.</li>
 * </ul>
 *
 * <p><b>Rozwiazanie.</b> Sieci <b>nigdy nie znikaja</b>. Kazda zyje dalej jako
 * osobny obiekt z wlasnym UUID, cache i force-loadami. To, ze sie stykaja,
 * zapisujemy jako KRAWEDZ w tym grafie:
 *
 * <pre>
 *   A &lt;--&gt; B          // dwie sieci polaczone jednym stykiem
 *   A &lt;--&gt; B &lt;--&gt; C   // lancuch trzech sieci
 * </pre>
 *
 * <p>Dzieki temu:
 * <ul>
 *   <li><b>laczenie</b> to dodanie krawedzi - nic nie ginie,</li>
 *   <li><b>rozlaczenie</b> to usuniecie krawedzi - obie sieci wracaja
 *       dokladnie do stanu sprzed, bo nigdy nie zostaly zmienione,</li>
 *   <li><b>ponowne polaczenie</b> to znowu dodanie krawedzi,</li>
 *   <li>tozsamosc kazdej sieci (i jej force-loady) jest zachowana
 *       niezaleznie od tego, ile razy sie lacza i rozlaczaja.</li>
 * </ul>
 *
 * <p><b>Co widzi gracz.</b> Terminal w sieci A widzi itemy ze WSZYSTKICH sieci
 * osiagalnych przez krawedzie - czyli po polaczeniu widzi tez skrzynie z B.
 * Robi to {@link #findGroup}, ktory zwraca cala spojna grupe.
 *
 * <p><b>Zamknieta strona rury</b> (wrench) jest traktowana jako rozciecie
 * lacza - czyli brak krawedzi. To zgodne z tym, jak dziala wrench w innych
 * modach: zamykasz strone, siec sie rozdziela.
 */
public final class VeloceNetworkGraph {

    /** Tworzy pusty graf polaczen. */
    public VeloceNetworkGraph() {
    }

    /**
     * Krawedzie grafu: siec -> zbior sieci, z ktorymi sie styka.
     *
     * <p>Graf jest NIESKIEROWANY, wiec kazda krawedz wystepuje w obie strony.
     * Dzieki temu sasiedzi sa dostepni w O(1) bez przeszukiwania calosci.
     */
    private final Map<UUID, Set<UUID>> edges = new HashMap<>();

    /**
     * Gdzie dokladnie sieci sie stykaja - do diagnostyki i do usuwania
     * wlasciwej krawedzi przy zniszczeniu rury.
     *
     * <p>Klucz: para UUID (posortowana), wartosc: zbior pozycji styku.
     * Ta sama para sieci moze stykac sie w kilku miejscach naraz - wtedy
     * krawedz znika dopiero, gdy zniknie OSTATNI styk.
     */
    private final Map<String, Set<Long>> contacts = new HashMap<>();

    // ------------------------------------------------------------------
    // Laczenie i rozlaczanie
    // ------------------------------------------------------------------

    /**
     * Zapisuje, ze dwie sieci stykaja sie w danym miejscu.
     *
     * <p>Krawedz powstaje przy PIERWSZYM styku, a kolejne styki tylko sie do
     * niej dopisuja. Dzieki temu zniszczenie jednej rury nie rozcina sieci,
     * ktora styka sie w dwoch miejscach.
     *
     * @return true, jesli to nowe polaczenie (krawedz powstawala)
     */
    public boolean link(UUID a, UUID b, long contactPos) {
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        String key = pairKey(a, b);
        Set<Long> points = contacts.computeIfAbsent(key, k -> new HashSet<>());
        points.add(contactPos);

        if (edges.computeIfAbsent(a, k -> new HashSet<>()).add(b)) {
            edges.computeIfAbsent(b, k -> new HashSet<>()).add(a);
            VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                    "network graph: %s <-> %s (nowe polaczenie)",
                    shortId(a), shortId(b));
            return true;
        }
        return false;
    }

    /**
     * Usuwa styk w danym miejscu.
     *
     * <p>Krawedz znika dopiero, gdy zniknie OSTATNI styk tych dwoch sieci.
     * Bez tego zniszczenie jednej rury rozcinaloby siec, ktora laczy sie
     * w dwoch roznych miejscach.
     *
     * @return true, jesli krawedz zostala usunieta (sieci sie rozdzielily)
     */
    public boolean unlink(UUID a, UUID b, long contactPos) {
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        String key = pairKey(a, b);
        Set<Long> points = contacts.get(key);
        if (points == null) {
            return false;
        }
        points.remove(contactPos);
        if (!points.isEmpty()) {
            // Sieci stykaja sie jeszcze gdzie indziej - polaczenie zostaje.
            return false;
        }
        contacts.remove(key);
        removeEdge(a, b);
        removeEdge(b, a);
        VeloceLog.Network.detail(VeloceLog.Side.SERVER,
                "network graph: %s >-< %s (rozlaczone)", shortId(a), shortId(b));
        return true;
    }

    /** Usuwa wpis sieci z grafu (gdy siec przestaje istniec). */
    public void forget(UUID id) {
        if (id == null) {
            return;
        }
        Set<UUID> neighbours = edges.remove(id);
        if (neighbours != null) {
            for (UUID other : neighbours) {
                Set<UUID> back = edges.get(other);
                if (back != null) {
                    back.remove(id);
                    if (back.isEmpty()) {
                        edges.remove(other);
                    }
                }
                contacts.remove(pairKey(id, other));
            }
        }
        // Sprzatanie wpisow, w ktorych ta siec wystepowala.
        contacts.keySet().removeIf(key -> key.contains(id.toString()));
    }

    private void removeEdge(UUID from, UUID to) {
        Set<UUID> set = edges.get(from);
        if (set != null) {
            set.remove(to);
            if (set.isEmpty()) {
                edges.remove(from);
            }
        }
    }

    /**
     * Klucz pary - niezalezny od kolejnosci argumentow.
     *
     * <p>Porzadkujemy UUID, zeby (A,B) i (B,A) dawaly ten sam klucz. Bez tego
     * ten sam styk bylby zapisany dwa razy i nigdy nie zniknal w calosci.
     */
    public static String pairKey(UUID a, UUID b) {
        String x = a.toString();
        String y = b.toString();
        return x.compareTo(y) <= 0 ? x + "|" + y : y + "|" + x;
    }

    // ------------------------------------------------------------------
    // Zapytania
    // ------------------------------------------------------------------

    /**
     * Wszystkie sieci osiagalne z danej sieci, LACZNIE z nia sama.
     *
     * <p>To jest odpowiedz na "co widzi terminal": przegladamy cala spojna
     * grupe i sumujemy zawartosc wszystkich sieci w niej. Terminal w A po
     * polaczeniu z B widzi wiec takze skrzynie z B.
     *
     * <p>Przeszukiwanie jest iteracyjne (nie rekurencyjne), zeby dlugi lancuch
     * polaczonych sieci nie wyczerpal stosu. Zbior odwiedzonych chroni przed
     * zapetleniem przy cyklu A-B-C-A.
     */
    public Set<UUID> findGroup(UUID start) {
        Set<UUID> group = new LinkedHashSet<>();
        if (start == null) {
            return group;
        }
        List<UUID> stack = new ArrayList<>();
        group.add(start);
        stack.add(start);

        while (!stack.isEmpty()) {
            UUID current = stack.remove(stack.size() - 1);
            Set<UUID> neighbours = edges.get(current);
            if (neighbours == null) {
                continue;
            }
            for (UUID next : neighbours) {
                if (group.add(next)) {
                    stack.add(next);
                }
            }
        }
        return group;
    }

    /** Czy dwie sieci sa polaczone (bezposrednio albo posrednio). */
    public boolean connected(UUID a, UUID b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        return findGroup(a).contains(b);
    }

    /** Ilu sasiadow ma siec (bezposrednio). */
    public int degree(UUID id) {
        Set<UUID> set = edges.get(id);
        return set == null ? 0 : set.size();
    }

    /** Liczba krawedzi w grafie - do diagnostyki. */
    public int edgeCount() {
        int total = 0;
        for (Set<UUID> set : edges.values()) {
            total += set.size();
        }
        return total / 2;   // kazda krawedz liczona dwa razy
    }

    /** Wszystkie polaczenia - do raportu. */
    public Map<String, Integer> describeLinks() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Long>> e : contacts.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            if (parts.length == 2) {
                out.put(shortId(UUID.fromString(parts[0])) + " <-> "
                        + shortId(UUID.fromString(parts[1])), e.getValue().size());
            }
        }
        return out;
    }

    /** Wszystkie sieci obecne w grafie (majace jakakolwiek krawedz). */
    public Set<UUID> allNodes() {
        Set<UUID> out = new LinkedHashSet<>(edges.keySet());
        for (Set<UUID> set : edges.values()) {
            out.addAll(set);
        }
        return out;
    }

    /** Sasiadujace sieci (bezposrednio). */
    public Set<UUID> neighbours(UUID id) {
        Set<UUID> set = edges.get(id);
        return set == null ? Collections.emptySet() : Collections.unmodifiableSet(set);
    }

    /** Pozycje styku dwoch sieci - do usuniecia wlasciwej krawedzi. */
    public Set<Long> contactPoints(UUID a, UUID b) {
        Set<Long> set = contacts.get(pairKey(a, b));
        return set == null ? Collections.emptySet() : Collections.unmodifiableSet(set);
    }

    /** Krotki identyfikator do logow. */
    public static String shortId(UUID id) {
        return id == null ? "-" : id.toString().substring(0, 8);
    }
}
