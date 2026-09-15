package com.craftingveloce.block.entity;

import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity Veloce Threshold Sensor.
 *
 * <p><b>Co to robi.</b> Patrzy, ile sztuk FILTROWANEGO itemu jest fizycznie
 * w sieci, i porownuje to z progiem ustawionym przez gracza. Wynik wystawia
 * jako redstone. Czyli: "gdy w sieci jest mniej niz 64 zelaza - wlacz prad".
 *
 * <p><b>Dwa tryby.</b> {@link Mode#LOW} daje prad, gdy stan spadnie PONIZEJ
 * progu (typowo: uruchom fabryke, bo sie konczy). {@link Mode#HIGH} robi
 * odwrotnie - daje prad, gdy stan jest na progu albo wyzej (typowo: zatrzymaj
 * napelnianie, bo juz dosc). Jedno ustawienie pokrywa oba kierunki, wiec
 * gracz nie musi myslec o "negacji" - wybiera po prostu, co ma byc warunkiem.
 *
 * <p><b>Wyjscie.</b> Block trzyma wynik w stanie bloku ({@code POWERED}),
 * a nie tylko w sobie. To celowe: Minecraft rozglasza zmiane stanu bloku
 * sasiadom, wiec to wlasnie ta zmiana uruchamia maszyny obok. Trzymanie
 * wyniku wylacznie w block entity nie daloby zadnego powiadomienia.
 */
public class VeloceThresholdSensorBlockEntity extends BlockEntity
        implements MenuProvider, VeloceFilterHost {

    /** Co ma byc warunkiem wyjscia. */
    public enum Mode {
        /** Prad, gdy w sieci jest PONIZEJ progu ("za malo"). */
        LOW("gui.craftingveloce.sensor.mode.low"),
        /** Prad, gdy w sieci jest NA PROGU albo powyzej ("wystarczy"). */
        HIGH("gui.craftingveloce.sensor.mode.high");

        public final String key;

        Mode(String key) {
            this.key = key;
        }

        public Mode next() {
            return this == LOW ? HIGH : LOW;
        }
    }

    /** Ile itemow ma byc w sieci (prog). */
    public static final long DEFAULT_THRESHOLD = 64L;

    /** Gorna granica progu - chroni przed absurdalnymi wartosciami z pola tekstowego. */
    public static final long MAX_THRESHOLD = 1_000_000_000L;

    /**
     * Co ile tickow sprawdzamy siec. 20 tickow = raz na sekunde.
     *
     * <p>Raz na sekunde, a nie czesciej, z dwoch powodow:
     * <ul>
     *   <li>skan magazynow sieci jest drogi (przejscie po wszystkich
     *       endpointach), a agregat jest cache'owany wlasnie na 10 tickow -
     *       pytanie czesciej nie daloby swiezszych danych, tylko wiecej pracy,</li>
     *   <li>sensor sluzy do uruchamiania fabryki ("skonczylo sie zelazo"),
     *       a nie do taktowania - sekunda zwloki jest bez znaczenia, natomiast
     *       dziesiec sensorow pytajacych co pol sekundy to juz realne obciazenie.</li>
     * </ul>
     */
    private static final int CHECK_INTERVAL_TICKS = 20;

    private ItemStack filter = ItemStack.EMPTY;
    private long threshold = DEFAULT_THRESHOLD;
    private Mode mode = Mode.LOW;

    /** Ostatnio zmierzona liczba sztuk w sieci (-1 = jeszcze nie mierzono). */
    private long lastCount = -1L;

    /**
     * Ile sztuk bylo w sieci przy ostatnim doslaniu stanu klientowi.
     *
     * <p>Potrzebne, zeby nie wysylac blokowego pakietu co sekunde, gdy nic sie
     * nie zmienia - przy kilku sensorach to juz ruch bez powodu.
     */
    private long lastSyncedCount = Long.MIN_VALUE;

    private int checkCooldown = CHECK_INTERVAL_TICKS;

    public VeloceThresholdSensorBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.THRESHOLD_SENSOR_BE.get(), pos, state);
    }

    // ------------------------------------------------------------------
    // Filtr (przez wspolny interfejs - ten sam wybor itemu co w ekstraktorze)
    // ------------------------------------------------------------------

    @Override
    public int filterCount() {
        return 1;
    }

    @Override
    public ItemStack getFilterAt(int index) {
        return index == 0 ? filter : ItemStack.EMPTY;
    }

    @Override
    public void setFilterAt(int index, ItemStack stack) {
        if (index != 0) {
            return;
        }
        filter = stack == null ? ItemStack.EMPTY : stack.copyWithCount(1);
        setChanged();
        syncToClients();
    }

    public ItemStack getFilter() {
        return filter;
    }

    // ------------------------------------------------------------------
    // Prog i tryb
    // ------------------------------------------------------------------

    public long getThreshold() {
        return threshold;
    }

    /** Ustawia prog, przycinajac do sensownego zakresu. */
    public void setThreshold(long value) {
        long clamped = Math.max(0L, Math.min(MAX_THRESHOLD, value));
        if (clamped == threshold) {
            return;
        }
        threshold = clamped;
        setChanged();
        syncToClients();
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        if (mode == null || mode == this.mode) {
            return;
        }
        this.mode = mode;
        setChanged();
        syncToClients();
    }

    public long getLastCount() {
        return lastCount;
    }

    /**
     * Czy sensor wystawia teraz prad.
     *
     * <p>Czytamy to ze STANU BLOKU, a nie z wlasnego pola. Stan bloku jest
     * jedynym zrodlem prawdy dla wyjscia (bo tylko jego zmiana jest rozglaszana
     * sasiadom), wiec trzymanie drugiej kopii w block entity znaczyloby dwa
     * miejsca, ktore moga sie rozjesc - np. po wczytaniu swiata z NBT.
     */
    public boolean isPowered() {
        BlockState state = getBlockState();
        return state.hasProperty(com.craftingveloce.block.VeloceThresholdSensorBlock.POWERED)
                && state.getValue(com.craftingveloce.block.VeloceThresholdSensorBlock.POWERED);
    }

    /**
     * Czy warunek jest spelniony dla danej liczby sztuk.
     *
     * <p>Wydzielone, zeby GUI moglo pokazac DOKLADNIE to samo, co robi tick -
     * a nie wlasna, "podobna" ocene warunku.
     */
    public boolean conditionMet(long count) {
        return mode == Mode.LOW ? count < threshold : count >= threshold;
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    public void serverTick() {
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        // Timer odliczamy ZAWSZE, a mierzenie sieci dopiero po jego wyzerowaniu.
        // Skan sieci jest drogi (przejscie po magazynach), a sensor nie musi
        // reagowac co tick - 2 razy na sekunde to i tak szybciej, niz zareaguje
        // jakakolwiek maszyna po drugiej stronie redstone'a.
        if (--checkCooldown > 0) {
            return;
        }
        checkCooldown = CHECK_INTERVAL_TICKS;

        long count = measure(sl);
        lastCount = count;

        boolean shouldPower = !filter.isEmpty() && conditionMet(count);
        if (shouldPower != isPowered()) {
            applyPowerState(sl, shouldPower);
        }
        // Licznik jest pokazywany w GUI, wiec dosylamy go - ale TYLKO gdy
        // naprawde sie zmienil. Wysylanie co sekunde "na wszelki wypadek"
        // to ruch bez powodu przy stabilnym zapasie.
        //
        // UWAGA NA JEDNOSTKI: ten kod jest osiagany TYLKO raz na
        // CHECK_INTERVAL_TICKS (wyzej jest return), wiec kazdy licznik
        // dekrementowany w tym miejscu liczy "sprawdzenia", a nie ticki.
        // Poprzednia wersja trzymala tu osobny cooldown 20 - co dawalo
        // 20 * 20 = 400 tickow (20 SEKUND) zamiast sekundy, wiec licznik
        // w GUI odswiezal sie raz na 20 sekund. Samo porownanie z ostatnio
        // doslana wartoscia wystarcza: jestesmy tu najwyzej raz na sekunde.
        if (count != lastSyncedCount) {
            lastSyncedCount = count;
            syncToClients();
        }
    }

    /**
     * Liczba sztuk filtrowanego itemu w sieci.
     *
     * <p>Liczymy FIZYCZNY stan magazynow, a nie "ile da sie dorobic" - sensor
     * ma pilnowac zapasu, a nie produkcji. Craftowalnosc zmienia sie od samego
     * posiadania receptur, wiec nie ma tu nic do rzeczy.
     *
     * @return liczba sztuk; 0 gdy nie ma filtra albo sieci
     */
    private long measure(ServerLevel sl) {
        if (filter.isEmpty()) {
            return 0L;
        }
        VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                .getNetworkForTerminal(sl, worldPosition);
        if (net == null) {
            return 0L;
        }
        Item wanted = filter.getItem();
        return net.getAllItemCounts(sl).getOrDefault(wanted, 0L);
    }

    /** Ustawia stan bloku - to on rozglasza zmiane sasiadom. */
    private void applyPowerState(ServerLevel sl, boolean on) {
        BlockState state = getBlockState();
        if (!state.hasProperty(com.craftingveloce.block.VeloceThresholdSensorBlock.POWERED)) {
            return;
        }
        if (state.getValue(com.craftingveloce.block.VeloceThresholdSensorBlock.POWERED) == on) {
            return;
        }
        sl.setBlockAndUpdate(worldPosition, state.setValue(
                com.craftingveloce.block.VeloceThresholdSensorBlock.POWERED, on));
        setChanged();
    }

    private void syncToClients() {
        if (level instanceof ServerLevel sl) {
            sl.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ------------------------------------------------------------------
    // Zapis / odczyt
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!filter.isEmpty()) {
            tag.put("Filter", filter.save(registries));
        }
        tag.putLong("Threshold", threshold);
        tag.putString("Mode", mode.name());
        // UWAGA: wyjscia (POWERED) NIE zapisujemy tutaj. Zyje w stanie bloku,
        // ktory i tak jest zapisywany razem z chunkiem - druga kopia w NBT
        // mogla sie z nim rozjesc po wczytaniu swiata.
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        filter = tag.contains("Filter")
                ? ItemStack.parse(registries, tag.getCompound("Filter")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        threshold = tag.contains("Threshold")
                ? Math.max(0L, Math.min(MAX_THRESHOLD, tag.getLong("Threshold")))
                : DEFAULT_THRESHOLD;
        mode = tag.contains("Mode") && "HIGH".equals(tag.getString("Mode")) ? Mode.HIGH : Mode.LOW;
        // Pole tylko-synchronizacyjne: jest w pakiecie bloku, nie ma go
        // w zapisie swiata. Brak = jeszcze nie mierzono.
        lastCount = tag.contains("LastCount") ? tag.getLong("LastCount") : -1L;
    }

    /**
     * Dane wysylane klientowi.
     *
     * <p><b>Dlaczego osobno od {@link #saveAdditional}.</b> {@code lastCount}
     * jest wartoscia pochodna (wynik pomiaru sieci), wiec CELOWO nie trafia do
     * zapisu swiata - po wczytaniu i tak jest liczona od nowa. Ale GUI czyta
     * ja wlasnie z block entity po stronie klienta, a ta dostaje TYLKO to, co
     * zwroci ta metoda. Bez dopisania jej tutaj licznik nie docieral do
     * klienta NIGDY: GUI pokazywalo na stale "nieznane", a podpis stanu
     * wyjscia byl liczony z -1, czyli mogl pokazywac odwrotnosc prawdy.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        tag.putLong("LastCount", lastCount);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return net.minecraft.network.chat.Component.translatable(
                "block.craftingveloce.threshold_sensor");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new com.craftingveloce.inventory.VeloceThresholdSensorMenu(
                id, inv, getBlockPos(), this);
    }

    /** Pusty kontener na potrzeby menu (sensor nie trzyma przedmiotow). */
    public Container placeholderContainer() {
        return new SimpleContainer(1);
    }
}
