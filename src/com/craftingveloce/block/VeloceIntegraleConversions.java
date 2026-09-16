package com.craftingveloce.block;

import com.craftingveloce.init.VeloceRegistry;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Co klatka Veloce Integrale zamienia w co: tabela "waniliowy klocek -&gt; nasz blok".
 *
 * <p><b>Jak to dziala.</b> Gracz stawia klatke i prawym klikiem wklada do niej
 * odpowiedni waniliowy klocek. Klatka <b>podmienia sie</b> na nasza maszyne
 * (patrz {@link VeloceIntegraleBlock#convert}) - w swiecie stoi wtedy prawdziwy
 * blok Veloce, a nie atrapa ani "eksponat".
 *
 * <p><b>JEDNO miejsce z ta regula.</b> Mapowanie pochodzi ze starego projektu
 * (InventoryExchange), gdzie kontroler powstawal z pulpitu do czytania,
 * ekstraktor z dozownika, a sensor z obserwatora - i tam bylo rozsiane po
 * przepisach. Tutaj jest jedna tabela, wiec:
 * <ul>
 *   <li>dodanie maszyny to jeden wiersz (albo {@link #register} z modulu
 *       compat, gdy klocek-wejscie pochodzi z innego moda),</li>
 *   <li>podpowiedz itemu klatki jest generowana z tej samej tabeli
 *       ({@code VeloceIntegraleItem}), wiec nie moze sie z nia rozjechac.</li>
 * </ul>
 */
public final class VeloceIntegraleConversions {

    /**
     * Jedno przepisanie: co gracz wklada -&gt; jaki blok Veloce z tego powstaje.
     *
     * <p>{@code Supplier} (a nie sam blok) z dwoch powodow: rejestr blokow
     * rozstrzyga sie leniwie, a moduly z {@code compat/} dokladaja swoje
     * wiersze, zanim ich bloki istnieja.
     */
    public record Conversion(Block input, Supplier<Block> result) {

        /** Blok, ktory powstaje z tego wejscia. */
        public Block resultBlock() {
            return result.get();
        }
    }

    private static final List<Conversion> CONVERSIONS = new ArrayList<>();

    static {
        // Kolejnosc = kolejnosc w podpowiedzi itemu klatki.
        add(Blocks.CRAFTING_TABLE, () -> VeloceRegistry.VELOCE_CRAFTING_TABLE.get());
        add(Blocks.LECTERN, () -> VeloceRegistry.VELOCE_CONTROLLER.get());
        add(Blocks.DISPENSER, () -> VeloceRegistry.VELOCE_EXTRACTOR.get());
        add(Blocks.OBSERVER, () -> VeloceRegistry.THRESHOLD_SENSOR.get());
        add(Blocks.FURNACE, () -> VeloceRegistry.VELOCITY_FURNACE.get());
    }

    private VeloceIntegraleConversions() {
    }

    /**
     * Dokłada przepisanie do tabeli.
     *
     * <p>Dla modulow z {@code compat/}: klocek-wejscie moze pochodzic z obcego
     * moda, a wynik jest naszym blokiem - dzieki temu integracja nie zmienia
     * rdzenia (patrz {@code VeloceMods}).
     */
    public static void register(Block input, Supplier<Block> result) {
        CONVERSIONS.add(new Conversion(input, result));
    }

    private static void add(Block input, Supplier<Block> result) {
        register(input, result);
    }

    /** Przepisanie dla przedmiotu w rece, albo {@code null} (brak = nic sie nie dzieje). */
    public static Conversion forItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem ? forBlock(blockItem.getBlock()) : null;
    }

    /** Przepisanie dla bloku, albo {@code null}. */
    public static Conversion forBlock(Block block) {
        for (Conversion conversion : CONVERSIONS) {
            if (conversion.input() == block) {
                return conversion;
            }
        }
        return null;
    }

    /** Wszystkie przepisania (podpowiedz itemu, dokumentacja, testy). */
    public static List<Conversion> all() {
        return List.copyOf(CONVERSIONS);
    }
}
