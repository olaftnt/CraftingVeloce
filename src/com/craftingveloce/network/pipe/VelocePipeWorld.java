package com.craftingveloce.network.pipe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plaska struktura wszystkich rur w swiecie - zrodlo prawdy o polaczeniach.
 *
 * <p><b>Dlaczego tak, a nie "sieci".</b> Poprzednie podejscie traktowalo siec
 * jako OBIEKT, ktory sie scala i dzieli. To bylo zrodlem wszystkich problemow:
 * przy laczeniu trzeba bylo dwa obiekty skasowac i zbudowac nowy (trace
 * tozsamosci, cache i force-loady), a przy rozcinaniu trzeba bylo ZGADYWAC,
 * na jakie kawalki sie rozpadla - a do tego potrzebny byl BFS po zaladowanych
 * chunkach, wiec wynik zalezal od tego, gdzie stoi gracz.
 *
 * <p>Tutaj nie ma zadnych "sieci" do scalania. Jest jedna plaska mapa:
 * <b>pozycja rury -> zbior sasiadow, z ktorymi jest realnie polaczona</b>.
 * Wszystko inne (czy dwie rury sa w jednej sieci, co widzi terminal, ktore
 * chunki trzymac) jest WYNIKIEM ZAPYTANIA na tej strukturze, a nie stanem,
 * ktory trzeba utrzymywac w spójnosci.
 *
 * <p><b>Dzieki temu:</b>
 * <ul>
 *   <li>polaczenie dwoch sieci to po prostu dwie rury, ktore staja sie
 *       sasiadami - nic nie trzeba scalac,</li>
 *   <li>rozciecie to usuniecie rury (albo zamkniecie strony) - komponenty
 *       rozdzielaja sie SAME, bez zgadywania,</li>
 *   <li>dlugosc rury nie ma znaczenia: dane sa w tej mapie, a nie w swiecie,</li>
 *   <li>niezalezne od chunkow: rura w niezaladowanym chunku nadal tu jest.</li>
 * </ul>
 *
 * <p><b>Wydajnosc.</b> Komponent (zbior rur polaczonych ze soba) liczymy BFS-em
 * po samych koordynatach - bez dotykania swiata, bez chunkow. Wynik jest
 * CACHE'OWANY, wiec przeszukanie idzie raz na zmiane ukladu, a nie przy kazdym
 * odczycie. Dla 1000 rur to ~6000 tanich operacji - i tylko wtedy, gdy
 * cokolwiek sie w ukladzie zmienilo.
 */
public final class VelocePipeWorld {

    /**
     * Sasiedzi kazdej rury.
     *
     * <p>Trzymamy TYLKO realne polaczenia: kierunek, w ktorym rura laczy sie
     * z inna rura. Jesli strona jest zamknieta (wrench) albo nie ma tam rury,
     * nie ma wpisu. Dzieki temu BFS nie musi niczego filtrowac.
     */
    private final Map<BlockPos, Set<BlockPos>> links = new HashMap<>();

    /** Rury nalezace do moda - niezaleznie od tego, czy maja polaczenia. */
    private final Set<BlockPos> allPipes = new HashSet<>();

    /**
     * Cache komponentow: rura -> identyfikator komponentu (pozycja "korzenia").
     *
     * <p>Komponent to zbior rur polaczonych ze soba. Identyfikujemy go pozycja
     * jednej z jego rur (deterministycznie najmniejsza), zeby wynik byl
     * powtarzalny miedzy uruchomieniami.
     */
    private final Map<BlockPos, BlockPos> componentOf = new HashMap<>();

    /** Rury danego komponentu - odwrotnosc {@link #componentOf}. */
    private final Map<BlockPos, Set<BlockPos>> componentMembers = new HashMap<>();

    /** Czy cache komponentow jest aktualny. */
    private boolean componentsDirty = true;

    /**
     * Opis komponentu: co stoi wokol jego rur.
     *
     * <p><b>Po co drugi poziom cache.</b> Sam zbior rur to za malo - maszyny
     * potrzebuja jeszcze wiedziec, jakie wezly (terminal, crafter, extractor)
     * i magazyny (skrzynie, beczki) sa do nich podlaczone. Odczytanie tego
     * wymaga siegniecia do swiata ({@code getBlockState}, {@code getBlockEntity})
     * dla kazdego sasiada kazdej rury.
     *
     * <p>Bez tego cache'u ekstraktor robil to od zera co 10 tickow: przy 999
     * rurach i trzech maszynach dawalo to ok. 36 000 odwolan do swiata na
     * sekunde. Teraz opis liczymy RAZ na zmiane ukladu, a odczyt to jedno
     * lookup w mapie.
     *
     * <p>Wpisy dla magazynow w niezaladowanych chunkach ZOSTAJA - razem
     * z ostatnia znana zawartoscia. Skoro chunk nie jest symulowany, nikt tych
     * itemow nie ruszyl, wiec zapamietana liczba jest nadal prawdziwa.
     */
    public static final class Component {
        /** Rury nalezace do komponentu. */
        public final Set<BlockPos> pipes = new LinkedHashSet<>();
        /** Wezly: terminale, craftery, extractory. Trzymaja chunk na stale. */
        public final Set<BlockPos> nodes = new LinkedHashSet<>();
        /** Magazyny: skrzynie, beczki, RS. Doladowywane na czas operacji. */
        public final Set<BlockPos> storages = new LinkedHashSet<>();
        /** Craftery - dodatkowo jako bufory produkcji. */
        public final Set<BlockPos> crafters = new LinkedHashSet<>();
        /** Kiedy opis powstal (gameTime) - do diagnostyki. */
        public long builtAtTick;
    }

    /** Cache opisow: reprezentant komponentu -> opis. */
    private final Map<BlockPos, Component> componentCache = new HashMap<>();

    // ------------------------------------------------------------------
    // Budowa struktury
    // ------------------------------------------------------------------

    /**
     * Dodaje rure. Bez polaczen - te ustala {@link #setNeighbours}.
     *
     * <p>UWAGA: brudzi cache komponentow TYLKO gdy rura naprawde doszla.
     * Wolane jest to przy kazdej synchronizacji, takze dla rur juz znanych.
     */
    public void addPipe(BlockPos pos) {
        if (allPipes.add(pos.immutable())) {
            componentsDirty = true;
            version++;
        }
    }

    /**
     * Usuwa rure wraz ze wszystkimi jej polaczeniami.
     *
     * <p>Brudzi cache TYLKO gdy rura naprawde byla - inaczej kazde sprawdzenie
     * pustego sasiada (a {@code syncAround} sprawdza ich szesc) kasowaloby
     * wszystkie policzone komponenty, mimo ze uklad sie nie zmienil.
     */
    public void removePipe(BlockPos pos) {
        boolean had = allPipes.remove(pos);
        boolean hadLinks = links.remove(pos) != null;
        // Usuwamy tez odwolania z sasiadow - inaczej zostalyby "wiszace"
        // krawedzie do rury, ktorej juz nie ma.
        boolean removedFromOthers = false;
        for (Set<BlockPos> neighbours : links.values()) {
            if (neighbours.remove(pos)) {
                removedFromOthers = true;
            }
        }
        if (had || hadLinks || removedFromOthers) {
            componentsDirty = true;
            version++;
        }
    }

    /** Czy ta rura jest znana strukturze. */
    public boolean hasPipe(BlockPos pos) {
        return allPipes.contains(pos);
    }

    /**
     * Ustawia sasiadow rury - JEDNO miejsce, w ktorym powstaje polaczenie.
     *
     * <p>Sasiadami sa wylacznie rury, ktore sa znane strukturze i ktore lacza
     * sie w danym kierunku (strona nie jest zamknieta). Wywolujacy ustala to
     * na podstawie stanu bloku.
     */
    public void setNeighbours(BlockPos pos, Set<BlockPos> neighbours) {
        if (!allPipes.contains(pos)) {
            return;
        }
        Set<BlockPos> filtered = new HashSet<>();
        for (BlockPos n : neighbours) {
            if (allPipes.contains(n)) {
                filtered.add(n.immutable());
            }
        }
        Set<BlockPos> previous = links.get(pos);
        if (previous != null && previous.equals(filtered)) {
            return;   // bez zmian - nie uniewazniamy cache bez potrzeby
        }
        links.put(pos.immutable(), filtered);
        componentsDirty = true;
        version++;
    }

    /** Sasiadujace rury (bezposrednio polaczone). */
    public Set<BlockPos> neighbours(BlockPos pos) {
        Set<BlockPos> set = links.get(pos);
        return set == null ? Set.of() : set;
    }

    /** Wszystkie znane rury. */
    public Set<BlockPos> allPipes() {
        return java.util.Collections.unmodifiableSet(allPipes);
    }

    /** Liczba rur. */
    public int pipeCount() {
        return allPipes.size();
    }

    // ------------------------------------------------------------------
    // Komponenty (spojne grupy rur)
    // ------------------------------------------------------------------

    /**
     * Identyfikator komponentu, do ktorego nalezy ta rura.
     *
     * <p>To jest odpowiedz na pytanie "w jakiej sieci jest ta rura". Zwracamy
     * pozycje reprezentanta, ktora jest stabilna dopoki uklad sie nie zmieni.
     *
     * @return reprezentant komponentu albo {@code null}, gdy rura nie istnieje
     */
    public BlockPos componentOf(BlockPos pos) {
        if (!allPipes.contains(pos)) {
            return null;
        }
        rebuildIfDirty();
        return componentOf.get(pos);
    }

    /** Wszystkie rury w tym samym komponencie co podana (lacznie z nia). */
    public Set<BlockPos> componentMembers(BlockPos pos) {
        BlockPos root = componentOf(pos);
        if (root == null) {
            return Set.of();
        }
        Set<BlockPos> members = componentMembers.get(root);
        return members == null ? Set.of() : members;
    }

    /**
     * Opis komponentu zawierajacego te rure - z cache.
     *
     * <p>Zwraca gotowy opis albo {@code null}, gdy trzeba go policzyc.
     * Wywolujacy ({@code VelocePipeNetworkManager}) decyduje, jak go zbudowac -
     * ta klasa nie dotyka swiata, bo jest od niego niezalezna.
     */
    public Component cachedComponent(BlockPos pipe) {
        rebuildIfDirty();
        BlockPos root = componentOf(pipe);
        return root == null ? null : componentCache.get(root);
    }

    /** Zapisuje policzony opis komponentu. */
    public void storeComponent(BlockPos pipe, Component component) {
        rebuildIfDirty();
        BlockPos root = componentOf(pipe);
        if (root != null && component != null) {
            componentCache.put(root, component);
        }
    }

    /** Usuwa opis komponentu (gdy zmienil sie uklad). */
    public void invalidateComponent(BlockPos pipe) {
        rebuildIfDirty();
        BlockPos root = componentOf(pipe);
        if (root != null) {
            componentCache.remove(root);
        }
    }

    /** Liczba zcache'owanych opisow - do diagnostyki. */
    public int cachedComponentCount() {
        rebuildIfDirty();
        return componentCache.size();
    }

    /**
     * Reprezentant kazdego komponentu - do przejscia po wszystkich sieciach.
     *
     * <p>Zwraca po jednej rurze z kazdej spojnej grupy, zeby dalo sie zbudowac
     * opis kazdej sieci bez powtarzania tych samych rur.
     */
    public java.util.Collection<BlockPos> componentRoots() {
        rebuildIfDirty();
        return java.util.Collections.unmodifiableSet(componentMembers.keySet());
    }

    /** Liczba komponentow - do diagnostyki. */
    public int componentCount() {
        rebuildIfDirty();
        return componentMembers.size();
    }

    /**
     * Przelicza komponenty, jesli uklad sie zmienil.
     *
     * <p>To jest JEDYNE miejsce, w ktorym chodzimy po grafie - i robimy to
     * dopiero wtedy, gdy ktos naprawde pyta o komponent. Dzieki temu
     * postawienie 100 rur pod rzad nie uruchamia 100 przeszukiwan.
     */
    private void rebuildIfDirty() {
        if (!componentsDirty) {
            return;
        }
        componentsDirty = false;
        componentOf.clear();
        componentMembers.clear();
        componentCache.clear();

        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos start : allPipes) {
            if (visited.contains(start)) {
                continue;
            }
            // BFS po samych koordynatach - bez dotykania swiata.
            Set<BlockPos> group = new LinkedHashSet<>();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);
            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                group.add(current);
                for (BlockPos next : neighbours(current)) {
                    if (visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
            // Reprezentant: deterministycznie najmniejsza pozycja w grupie.
            // Dzieki temu identyfikator jest stabilny miedzy uruchomieniami.
            BlockPos root = group.stream().min(VelocePipeWorld::comparePositions).orElse(start);
            componentMembers.put(root, group);
            for (BlockPos p : group) {
                componentOf.put(p, root);
            }
        }
    }

    /**
     * Identyfikator sieci dla komponentu o danym reprezentancie.
     *
     * <p>JEDNO miejsce liczenia UUID - uzywane i przez budowanie sieci, i przy
     * uzgadnianiu cache'y. Gdyby te dwa miejsca liczby inaczej, cache nie
     * zostalby dopasowany do sieci.
     */
    public static java.util.UUID componentId(BlockPos root) {
        return java.util.UUID.nameUUIDFromBytes(
                root.toShortString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Numer wersji ukladu komponentow.
     *
     * <p>Rosnie przy KAZDEJ zmianie ukladu polaczen. Menedzer porownuje go,
     * zeby wiedziec, kiedy uzgodnic cache sieci z zywymi komponentami -
     * bez tego przy kazdym podziale sieci powstawal nowy identyfikator,
     * a stary cache trzymal swoje force-loady na zawsze.
     */
    public long version() {
        rebuildIfDirty();
        return version;
    }

    private long version = 0L;

    /** Stala kolejnosc pozycji - do wyboru reprezentanta. */
    private static int comparePositions(BlockPos a, BlockPos b) {
        int c = Integer.compare(a.getY(), b.getY());
        if (c != 0) {
            return c;
        }
        c = Integer.compare(a.getX(), b.getX());
        if (c != 0) {
            return c;
        }
        return Integer.compare(a.getZ(), b.getZ());
    }

    /**
     * Sasiadujace pozycje, ktore MOGLYBY byc rura - do skanowania swiata.
     *
     * <p>Uzywane przy wykrywaniu, co stoi obok danej rury. Nie wymaga
     * zaladowanego chunku, jesli pytamy o rury juz znane strukturze.
     */
    public static List<BlockPos> neighbourPositions(BlockPos pos) {
        List<BlockPos> out = new ArrayList<>(6);
        for (Direction d : Direction.values()) {
            out.add(pos.relative(d));
        }
        return out;
    }

    /** Czysci wszystko (rozladowanie swiata). */
    public void clear() {
        links.clear();
        allPipes.clear();
        componentOf.clear();
        componentMembers.clear();
        componentCache.clear();
        componentsDirty = true;
        version++;
    }

    /** Wymusza przeliczenie komponentow (po zmianie, ktora nie ustawila flagi). */
    public void invalidate() {
        componentsDirty = true;
        version++;
    }

    /** Czy struktura jest pusta. */
    public boolean isEmpty() {
        return allPipes.isEmpty();
    }
}
