package com.craftingveloce.block;

import com.craftingveloce.init.VeloceRegistry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Co jest W SRODKU obudowy Veloce Integrale - dla kazdego naszego klocka.
 *
 * <p><b>Zasada.</b> Kazdy nasz klocek (poza rura i terminalem) ma model
 * <b>obudowy Integrale</b> (rama z pretow + fioletowa szyba), a w srodku
 * renderuje sie model <b>klocka bazowego</b>: z pulpitu powstaje kontroler,
 * z dozownika ekstraktor, z obserwatora sensor progu, z pieca piec. Tak samo
 * zachowuje sie klatka zamieniana na maszyne
 * (patrz {@link VeloceIntegraleConversions}) - wyglad i przepisy pochodza
 * z jednej listy, wiec nie moga sie rozjechac.
 *
 * <p><b>Moduly z {@code compat/}</b> dokladaja swoje wiersze przez
 * {@link #register} (np. obudowa z kolkiem mlynskim Create w srodku), bez
 * zmiany rdzenia.
 */
public final class VeloceCaseContents {

    /**
     * Wiersz tabeli: nasz blok -&gt; co pokazac w jego obudowie.
     *
     * @param scale              wzgledny rozmiar zawartosci (1.0 = domyslny).
     *                           Maszyny wieksze od okna obudowy (kruszarka,
     *                           crafter, prasa, mixer, deployer) dostaja
     *                           mniejsza wartosc - inaczej przy animacji
     *                           wychodza gora ponad szybe.
     * @param pitch              dodatkowy obrot wokol osi X w stopniach
     *                           (0 = prosto). Piła i deployer maja patrzec
     *                           w DOL, wiec dostaja -90.
     * @param keepItemRotation   czy ZACHOWAC obrot z transformacji przedmiotu.
     *                           Domyslnie zerujemy przechyl (zawartosc stoi
     *                           prosto), ale kolo mlynskie wyglada lepiej
     *                           w swojej wlasnej orientacji.
     */
    public record Entry(Supplier<Block> machine, Supplier<Block> content, float scale,
                        float pitch, boolean keepItemRotation) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    static {
        add(() -> VeloceRegistry.VELOCE_CRAFTING_TABLE.get(), () -> Blocks.CRAFTING_TABLE);
        add(() -> VeloceRegistry.VELOCE_CONTROLLER.get(), () -> Blocks.LECTERN);
        add(() -> VeloceRegistry.VELOCE_EXTRACTOR.get(), () -> Blocks.DISPENSER);
        add(() -> VeloceRegistry.THRESHOLD_SENSOR.get(), () -> Blocks.OBSERVER);
        add(() -> VeloceRegistry.VELOCITY_FURNACE.get(), () -> Blocks.FURNACE);
        add(() -> VeloceRegistry.ELECTRIC_FURNACE.get(), () -> Blocks.BLAST_FURNACE);
        add(() -> VeloceRegistry.BREWING_STAND.get(), () -> Blocks.BREWING_STAND);
    }

    private VeloceCaseContents() {
    }

    /**
     * Dokłada wiersz tabeli (dla modulow z {@code compat/}).
     *
     * <p>Zarowno nasz blok, jak i zawartosc podajemy jako {@code Supplier}:
     * rejestr blokow rozstrzyga sie leniwie, a moduly dokladaja wiersze,
     * zanim ich bloki istnieja.
     */
    public static void register(Supplier<Block> machine, Supplier<Block> content) {
        register(machine, content, 1.0F, 0.0F, false);
    }

    /** Jak {@link #register(Supplier, Supplier)}, ale z wlasnym rozmiarem zawartosci. */
    public static void register(Supplier<Block> machine, Supplier<Block> content, float scale) {
        register(machine, content, scale, 0.0F, false);
    }

    /** Wariant z obrotem (piła i deployer patrzA w dol) i zachowaniem obrotu modelu. */
    public static void register(Supplier<Block> machine, Supplier<Block> content, float scale,
                                float pitch, boolean keepItemRotation) {
        ENTRIES.add(new Entry(machine, content, scale, pitch, keepItemRotation));
    }

    /** Wzgledny rozmiar zawartosci tej maszyny (1.0, gdy nikt nie ustawil inaczej). */
    public static float contentScale(BlockState state) {
        Entry entry = entryFor(state);
        return entry == null ? 1.0F : entry.scale();
    }

    /** Dodatkowy obrot zawartosci wokol osi X (0 = prosto). */
    public static float contentPitch(BlockState state) {
        Entry entry = entryFor(state);
        return entry == null ? 0.0F : entry.pitch();
    }

    /** Czy zawartosc ma zachowac obrot z transformacji przedmiotu. */
    public static boolean keepsItemRotation(BlockState state) {
        Entry entry = entryFor(state);
        return entry != null && entry.keepItemRotation();
    }

    private static Entry entryFor(BlockState state) {
        Block block = state.getBlock();
        for (Entry entry : ENTRIES) {
            if (entry.machine().get() == block) {
                return entry;
            }
        }
        return null;
    }

    private static void add(Supplier<Block> machine, Supplier<Block> content) {
        register(machine, content);
    }

    /**
     * Blok do pokazania w obudowie tego bloku, albo {@code null} (nie ma
     * obudowy - np. rura albo terminal).
     */
    public static Block contentFor(BlockState state) {
        return contentFor(state.getBlock());
    }

    /** To samo po samym bloku (uzywane m.in. przez recepture rozkladajaca). */
    public static Block contentFor(Block machine) {
        for (Entry entry : ENTRIES) {
            if (entry.machine().get() == machine) {
                return entry.content().get();
            }
        }
        return null;
    }

    /** Wszystkie wiersze (podpowiedzi, guardy, dokumentacja). */
    public static List<Entry> all() {
        return List.copyOf(ENTRIES);
    }
}
