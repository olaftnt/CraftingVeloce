package com.craftingveloce.inventory;

import com.craftingveloce.block.entity.VeloceThresholdSensorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Menu Veloce Threshold Sensor.
 *
 * <p><b>Uklad.</b> JEDEN wysrodkowany wiersz: slot itemu, pole liczby, "+",
 * "-" i guzik trybu. Ponizej ekwipunek gracza (siatka jak w ekstraktorze:
 * ekwipunek od y=84, hotbar na y=142).
 *
 * <p>Wszystkie wspolrzedne GUI zyja TUTAJ - ekran czyta je z tej klasy, a
 * generator tekstury maluje ramke slotu pod ta sama liczba. Dzieki temu nie ma
 * trzech kopii, ktore moga sie rozjechac (build.py pilnuje pary
 * menu &lt;-&gt; generator).
 *
 * <p>Menu nie trzyma progu ani trybu - one zyja w block entity. Dzieki temu
 * dokladnie ta sama wartosc jest widziana przez serwer (ktory na jej podstawie
 * wystawia redstone) i przez ekran, bez drugiego zrodla prawdy.
 */
public class VeloceThresholdSensorMenu extends AbstractContainerMenu {

    /** Panel - ta sama szerokosc co tekstura i ekran (imageWidth). */
    public static final int PANEL_WIDTH = 212;
    public static final int PANEL_HEIGHT = 166;

    // ---------------- wiersz glowny (wysrodkowany) ----------------
    //
    //  slot 16 | 4 | pole 38 | 4 | "+" 20 | 4 | "-" 20 | 4 | tryb 20
    //
    // Szerokosc wiersza = 16 + 4 + 38 + 4 + 20 + 4 + 20 + 4 + 20 = 130, a
    // (212 - 130) / 2 = 41 - dokladnie tyle marginesu z KAZDEJ strony. Pole
    // liczby jest o POLOWE krotsze, niz bylo (bylo 76 px).
    /** Gorny brzeg wiersza; slot 16 px jest w nim wysrodkowany (2 px zapasu). */
    public static final int ROW_Y = 26;
    public static final int ROW_H = 20;
    public static final int GAP = 4;
    public static final int SLOT_SIZE = 16;
    public static final int FIELD_X = 61;
    public static final int FIELD_W = 38;
    public static final int FIELD_H = 20;
    public static final int BTN_W = 20;
    public static final int STEP_PLUS_X = 103;
    public static final int STEP_MINUS_X = 127;
    /** Guzik trybu (pochodnia) - trzeci guzik w wierszu. */
    public static final int MODE_X = 151;

    /** Slot itemu: pierwszy element wiersza. Generator maluje tu ramke. */
    public static final int FILTER_SLOT_X = 41;
    public static final int FILTER_SLOT_Y = 28;

    private static final int PLAYER_X = 26;
    private static final int PLAYER_Y = 84;

    private final VeloceThresholdSensorBlockEntity sensor;
    private final BlockPos pos;

    public VeloceThresholdSensorMenu(int id, Inventory playerInv, BlockPos pos) {
        this(id, playerInv, pos, playerInv.player.level().getBlockEntity(pos));
    }

    public VeloceThresholdSensorMenu(int id, Inventory playerInv, BlockPos pos, BlockEntity be) {
        this(id, playerInv, pos, be instanceof VeloceThresholdSensorBlockEntity s ? s : null);
    }

    public VeloceThresholdSensorMenu(int id, Inventory playerInv, BlockPos pos,
                                     VeloceThresholdSensorBlockEntity sensor) {
        super(com.craftingveloce.init.VeloceRegistry.THRESHOLD_SENSOR_MENU.get(), id);
        this.sensor = sensor;
        this.pos = pos;

        // Slot filtra: widmo. Blokujemy wkladanie i wyciaganie, ale slot
        // zostaje AKTYWNY - inaczej getSlotUnderMouse() by go pomijal
        // i klikniecie w filtr nie robiloby nic.
        Container placeholder = new SimpleContainer(1);
        this.addSlot(new Slot(placeholder, 0, FILTER_SLOT_X, FILTER_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public boolean mayPickup(Player player) {
                return false;
            }
        });

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9,
                        PLAYER_X + col * 18, PLAYER_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, PLAYER_X + col * 18, PLAYER_Y + 58));
        }
    }

    public VeloceThresholdSensorBlockEntity getSensor() {
        return sensor;
    }

    public BlockPos getPos() {
        return sensor != null ? sensor.getBlockPos() : pos;
    }

    /** Aktualny filtr (z block entity po stronie klienta). */
    public ItemStack getFilter() {
        return sensor == null ? ItemStack.EMPTY : sensor.getFilter();
    }

    public long getThreshold() {
        return sensor == null ? VeloceThresholdSensorBlockEntity.DEFAULT_THRESHOLD : sensor.getThreshold();
    }

    public VeloceThresholdSensorBlockEntity.Mode getMode() {
        return sensor == null ? VeloceThresholdSensorBlockEntity.Mode.LOW : sensor.getMode();
    }

    public long getLastCount() {
        return sensor == null ? -1L : sensor.getLastCount();
    }

    /**
     * Czy sensor wystawia teraz prad.
     *
     * <p>Bierzemy to ze STANU BLOKU, a nie z przeliczonego warunku. Stan bloku
     * jest rozglaszany przez serwer i to on jest prawda; licznik po stronie
     * klienta jest tylko wartoscia do wyswietlenia. Przeliczanie warunku
     * z licznika pokazywalo odwrotny stan, gdy licznik byl jeszcze nieznany.
     */
    public boolean isPowered() {
        return sensor != null && sensor.isPowered();
    }

    /**
     * Shift-klik nic nie przenosi.
     *
     * <p>Czujnik nie ma wlasnych slotow na przedmioty - jedyny slot to widmo
     * filtra, ktorego i tak nie da sie wypelnic. Poprzednia wersja przenosila
     * stos "w obrebie ekwipunku gracza", co w GUI maszyny jest zaskakujace:
     * gracz oczekuje przeniesienia do bloku, a nie przestawiania rzeczy
     * w plecaku. Zachowanie zgodne z piecem elektrycznym.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return sensor == null
                || player.distanceToSqr(sensor.getBlockPos().getX() + 0.5,
                sensor.getBlockPos().getY() + 0.5,
                sensor.getBlockPos().getZ() + 0.5) <= 64.0;
    }
}
