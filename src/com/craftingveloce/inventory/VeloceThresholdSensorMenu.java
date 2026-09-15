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
 * <p><b>Uklad.</b> Jeden slot FILTRA (widmo - ikone rysuje ekran, wartosc trzyma
 * block entity), pole na prog i trzy przyciski rysuje ekran, a ponizej jest
 * ekwipunek gracza. Siatka jest ta sama co w ekstraktorze: sloty od x=26,
 * ekwipunek od y=84, hotbar na y=142.
 *
 * <p>Menu nie trzyma progu ani trybu - one zyja w block entity. Dzieki temu
 * dokladnie ta sama wartosc jest widziana przez serwer (ktory na jej podstawie
 * wystawia redstone) i przez ekran, bez drugiego zrodla prawdy.
 */
public class VeloceThresholdSensorMenu extends AbstractContainerMenu {

    /** Pozycja slotu filtra - musi sie zgadzac z rysowaniem i klikaniem. */
    public static final int FILTER_SLOT_X = 26;
    public static final int FILTER_SLOT_Y = 18;

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
