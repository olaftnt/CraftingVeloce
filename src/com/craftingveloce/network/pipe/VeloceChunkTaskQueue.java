package com.craftingveloce.network.pipe;

import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Kolejka zadan na zawartosci chunkow poza symulacja.
 *
 * <p><b>Po co to istnieje.</b> Bez tego siegniecie do skrzyni stojacej
 * w niezaladowanym chunku robilo {@code level.getChunkSource().getChunk(..., true)}
 * - czyli <b>blokujace, synchroniczne</b> wczytanie chunku z dysku w srodku
 * ticku serwera. Jedno wyciagniecie itemu z odleglej skrzyni to kilka do
 * kilkudziesieciu milisekund zamulenia; przy serii operacji (auto-crafting,
 * extractor w petli) tick sie rozjezdzal.
 *
 * <p><b>Jak dziala.</b> Zadanie nigdy nie laduje chunku natychmiast. Zamiast
 * tego:
 * <ol>
 *   <li>Operacja czyta z <b>cache</b> endpointu - dokladnie tak, jak ustalono:
 *       skoro chunk jest rozladowany, jego zawartosc sie nie zmienila, wiec
 *       ostatnio zapamietane liczby sa nadal prawdziwe.</li>
 *   <li>Fizyczna czynnosc jest <b>kolejkowana</b> i wykona sie w jednym
 *       z najbliszych tickow, w ramach budzetu czasu.</li>
 *   <li>Chunk jest wymuszany na czas operacji i <b>zwalniany od razu</b> po
 *       jej zakonczeniu (bilet "op:*"), zeby gra mogla go sobie rozladowac.</li>
 * </ol>
 *
 * <p><b>Dlaczego jedno zadanie na tick.</b> Wczytanie chunku jest kosztowne
 * i nie da sie go rozlozyc na czesci - albo chunk jest, albo go nie ma. Wiec
 * ograniczamy liczbe takich operacji na tick, a nie ich dlugosc. Kolejka
 * mowi tez, ile zadan czeka, co widac w {@code /cv chunk status}.
 */
public final class VeloceChunkTaskQueue {

    private VeloceChunkTaskQueue() {
    }

    /** Rodzaj zadania - do raportu i statystyk. */
    public enum Kind {
        /** Zabranie itemow z magazynu. */
        EXTRACT,
        /** Wlozenie itemow do magazynu. */
        INSERT
    }

    /**
     * Zadanie do wykonania na zawartosci chunku.
     *
     * <p>Interfejs, a nie konkretna klasa, bo zadania roznia sie tym, CO robia
     * z magazynem - a mechanika kolejki, budzetu i zwalniania chunku jest
     * wspolna dla wszystkich.
     */
    public interface Task {
        /** Chunk, ktory trzeba na czas zadania zaladowac. */
        long chunkKey();

        /** Pozycja bloku - do raportu i teleportu. */
        BlockPos pos();

        Kind kind();

        /** Krotki opis do logu (np. "1x minecraft:oak_log"). */
        String describe();

        /**
         * Wykonuje zadanie. Wolane TYLKO gdy chunk jest juz zaladowany.
         *
         * @return wynik (np. zabrany stos), albo EMPTY
         */
        ItemStack run(ServerLevel level);
    }

    /**
     * Ile zadan wykonujemy na tick.
     *
     * <p>Jedno. Wczytanie chunku z dysku to operacja, ktorej nie da sie
     * podzielic - wiec jesli mamy ich wykonac kilka, rozkladamy je na kolejne
     * ticki. Jeden chunk na tick to maksymalnie ~50 ms budzetu, a typowo
     * duzo mniej (zwykle kilka ms).
     */
    private static final int TASKS_PER_TICK = 1;

    /**
     * Ile zadan wolno trzymac w kolejce.
     *
     * <p>Bezpiecznik: gdyby cos sypalo zadaniami szybciej, niz jestesmy je
     * wykonac (np. auto-crafting w petli na odleglym magazynie), kolejka nie
     * moze rosnac bez konca. Nadmiar odrzucamy - operacja po prostu sie nie
     * uda, a gracz zobaczy niezmieniony stan.
     */
    private static final int MAX_QUEUE = 256;

    private static final Deque<Task> PENDING = new ArrayDeque<>();

    /** Liczniki do raportu. */
    private static int completed;
    private static int dropped;
    private static long lastRunNanos;

    /**
     * Dodaje zadanie do kolejki.
     *
     * @return true gdy przyjeto; false gdy kolejka pelna
     */
    public static boolean submit(Task task) {
        if (task == null) {
            return false;
        }
        if (PENDING.size() >= MAX_QUEUE) {
            dropped++;
            VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                    "chunk task queue full (%d) - dropped %s at %s",
                    MAX_QUEUE, task.describe(), task.pos());
            return false;
        }
        PENDING.addLast(task);
        return true;
    }

    /** Ile zadan czeka. */
    public static int pending() {
        return PENDING.size();
    }

    /** Ile zadan wykonano lacznie. */
    public static int completed() {
        return completed;
    }

    /** Ile zadan odrzucono z powodu pelnej kolejki. */
    public static int dropped() {
        return dropped;
    }

    /**
     * Wykonuje zadania z kolejki w ramach budzetu.
     *
     * <p>Wolane z ticku serwera. Dla kazdego zadania:
     * <ol>
     *   <li>sprawdza, czy chunk jest juz zaladowany - jesli tak, nie trzeba
     *       go wymuszac (i nie dotykamy biletu),</li>
     *   <li>w przeciwnym razie wymusza chunk i ZAPAMIETUJE, ze zrobil to sam,</li>
     *   <li>wykonuje zadanie,</li>
     *   <li>zwalnia chunk, jesli sam go wymusil.</li>
     * </ol>
     */
    public static void tick(ServerLevel level) {
        if (PENDING.isEmpty()) {
            return;
        }
        // Przy zapisie/zamknieciu swiata nie ruszamy chunkow - to zawiesza
        // zapis swiata (patrz VeloceChunkLoader.freeze).
        if (VeloceChunkLoader.isFrozen()) {
            return;
        }

        for (int i = 0; i < TASKS_PER_TICK && !PENDING.isEmpty(); i++) {
            Task task = PENDING.pollFirst();
            if (task == null) {
                return;
            }
            runOne(level, task);
        }
    }

    /** Wykonuje jedno zadanie, dbajac o bilans wymuszen chunku. */
    private static void runOne(ServerLevel level, Task task) {
        long chunkKey = task.chunkKey();
        boolean wasLoaded = isChunkLoaded(level, chunkKey);
        boolean weForced = false;

        long start = System.nanoTime();
        boolean ok = false;
        String outcome = "";
        try {
            if (!wasLoaded) {
                // Bilet operacyjny - zwolnimy go w finally, zeby gra mogla
                // chunk rozladowac zaraz po zakonczeniu zadania.
                com.craftingveloce.debug.ChunkTrace.event("QUEUE",
                        "wymuszam chunk do zadania: %s %s @%s",
                        task.kind(), task.describe(), task.pos().toShortString());
                weForced = VeloceChunkLoader.retain(level, chunkKey, ownerFor(task),
                        VeloceChunkLoader.Reason.OPERATION, task.pos());
                level.getChunkSource().getChunk(
                        net.minecraft.world.level.ChunkPos.getX(chunkKey),
                        net.minecraft.world.level.ChunkPos.getZ(chunkKey),
                        net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
            } else {
                com.craftingveloce.debug.ChunkTrace.event("QUEUE",
                        "chunk byl juz zaladowany - zadanie bez wymuszania: %s %s",
                        task.kind(), task.describe());
            }

            ItemStack result = task.run(level);
            completed++;
            ok = true;
            outcome = "wynik=" + (result.isEmpty() ? "nic" : result.toString());
        } catch (Throwable t) {
            // Zadanie nie moze wywalic ticku - logujemy i idziemy dalej.
            outcome = "wyjatek=" + t;
            VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                    "chunk task %s at %s failed: %s", task.kind(), task.pos(), t);
        } finally {
            if (weForced) {
                VeloceChunkLoader.release(level, chunkKey, ownerFor(task));
            }
            lastRunNanos = System.nanoTime() - start;
            // DRUKUJEMY WERDYKT - to jest odpowiedz na pytanie "czy zadzialalo".
            String finalOutcome = outcome;
            boolean finalOk = ok;
            com.craftingveloce.debug.ChunkTrace.result(
                    "QUEUE:" + task.kind(), finalOk, !wasLoaded, wasLoaded,
                    "%s | %s | trwalo=%d ms | zostalo=%d",
                    task.describe(), finalOutcome,
                    lastRunNanos / 1_000_000L, PENDING.size());
        }
    }

    /** Nazwa wlasciciela biletu - rozna dla wyciagania i wkladania. */
    private static String ownerFor(Task task) {
        return task.kind() == Kind.EXTRACT ? "task:extract" : "task:insert";
    }

    /** Czy chunk jest zaladowany - po kluczu, bez tworzenia obiektow. */
    private static boolean isChunkLoaded(ServerLevel level, long chunkKey) {
        return level.getChunkSource().hasChunk(
                net.minecraft.world.level.ChunkPos.getX(chunkKey),
                net.minecraft.world.level.ChunkPos.getZ(chunkKey));
    }

    /** Czas ostatniego zadania w nanosekundach - do raportu. */
    public static long lastTaskNanos() {
        return lastRunNanos;
    }

    /** Czysci kolejke (rozladowanie swiata / zamkniecie serwera). */
    public static void clear() {
        PENDING.clear();
    }

    /** Ostatnio wykonane zadania - diagnostyka. */
    public static List<String> describePending(int limit) {
        List<String> out = new ArrayList<>();
        int i = 0;
        for (Task t : PENDING) {
            if (i++ >= limit) {
                out.add("... (+" + (PENDING.size() - limit) + " more)");
                break;
            }
            out.add(t.kind() + " " + t.describe() + " @ " + t.pos().toShortString());
        }
        return out;
    }
}
