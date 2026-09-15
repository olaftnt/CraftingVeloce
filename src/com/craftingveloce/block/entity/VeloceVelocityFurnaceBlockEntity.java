package com.craftingveloce.block.entity;

import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Velocity Furnace - piec "instant" dla auto-craftera.
 *
 * <p><b>Jak dziala.</b> To NIE jest piec, ktory fizycznie przepala surowiec.
 * To <b>zrodlo ciepla</b> dla craftera:
 * <ol>
 *   <li>Pali sie CALY CZAS, niezaleznie od tego, czy cos jest craftowane.
 *       To jest koszt, ktory gracz placi za instant przepalanie.</li>
 *   <li>Paliwo zaciaga z sieci wedlug 6 filtrow, w kolejnosci priorytetu:
 *       najpierw wyczerpuje filtr 1, potem 2, potem 3...</li>
 *   <li>Crafter, ktory znajdzie ten piec w sieci i zobaczy, ze sie pali,
 *       moze uzywac receptur pieca (smelting/blasting/smoking) INSTANT.</li>
 *   <li>Kazde takie przepalenie zabiera piecowi {@link #SMELT_HEAT_COST}
 *       jednostek ciepla. Gdy cieplo zejdzie do zera, piec musi pobrac
 *       kolejny item paliwa - czyli im wiecej crafter przepala, tym szybciej
 *       piec zre paliwo.</li>
 * </ol>
 *
 * <p><b>Koszt paliwa.</b> Vanilla przepala 1 przedmiot za 200 tickow, wiec
 * węgiel (1600 t) starcza na 8 sztuk. Tutaj przepalenie kosztuje
 * {@link #SMELT_HEAT_COST} = 300 tickow, czyli <b>1.5x wiecej paliwa</b>:
 * węgiel starcza na 5 sztuk. Mnoznik siedzi w koszcie, a nie w wartosci
 * opalowej - dzieki temu dziala jednolicie dla kazdego paliwa (takze modowego),
 * bo wartosci opalowe bierzemy z vanilla.
 */
public class VeloceVelocityFurnaceBlockEntity extends BlockEntity
        implements MenuProvider, VeloceHeatSource {

    /** Ile filtrow paliwa (priorytet od 0 w gore). */
    public static final int FUEL_FILTERS = 6;

    /**
     * Koszt jednego instantowego przepalenia, w tickach palenia.
     *
     * <p>Vanilla: 200 tickow na przedmiot. Tutaj 300 = mnoznik 1.5x.
     */
    public static final long SMELT_HEAT_COST = 300L;

    /** Co ile tickow probujemy dociagnac paliwo z sieci. */
    private static final int PULL_INTERVAL_TICKS = 10;

    /** Ile sztuk paliwa zaciagamy naraz (zapas, zeby nie ciagnac co tick). */
    private static final int PULL_BATCH = 8;

    /** Filtry paliwa - ktore itemy piec moze palic i w jakiej kolejnosci. */
    private final NonNullList<ItemStack> fuelFilters =
            NonNullList.withSize(FUEL_FILTERS, ItemStack.EMPTY);

    /** Slot z paliwem, ktore piec aktualnie przetwarza. */
    private final SimpleContainer fuelSlot = new SimpleContainer(1);

    /** Ile tickow palenia zostalo w buforze ciepla. */
    private long burnTicksRemaining;

    /**
     * Ile tickow palenia dawal ostatnio spalony item.
     *
     * <p>Potrzebne tylko do paska/plomienia - pokazuje pelny zakres.
     */
    private long burnTicksTotal;

    private int pullCooldown;

    public VeloceVelocityFurnaceBlockEntity(BlockPos pos, BlockState state) {
        super(com.craftingveloce.init.VeloceRegistry.VELOCITY_FURNACE_BE.get(), pos, state);
        // Kazda zmiana w slocie paliwa musi trafic do zapisu.
        this.fuelSlot.addListener(c -> setChanged());
    }

    // ------------------------------------------------------------------
    // VeloceHeatSource - to widzi crafter
    // ------------------------------------------------------------------

    @Override
    public long availableOperations() {
        return burnTicksRemaining / SMELT_HEAT_COST;
    }

    @Override
    public void consumeOperations(long operations) {
        if (operations <= 0) {
            return;
        }
        burnTicksRemaining = Math.max(0L, burnTicksRemaining - operations * SMELT_HEAT_COST);
        setChanged();
    }

    @Override
    public boolean isPowered() {
        // "Zasilony" = plomien sie pali. Piec z pustym buforem ciepla nie
        // odblokowuje receptur, nawet jesli ma paliwo w slocie - musi je
        // najpierw spalic.
        return burnTicksRemaining > 0;
    }

    @Override
    public int heatPriority() {
        return 1;   // paliwowy - fallback po elektrycznym
    }

    @Override
    public String heatSourceName() {
        return "Velocity Furnace";
    }

    // ------------------------------------------------------------------
    // Dostep do slotow (menu i GUI)
    // ------------------------------------------------------------------

    /** Slot, w ktorym lezy paliwo do spalenia. */
    public Container getFuelSlot() {
        return fuelSlot;
    }

    /** Filtr paliwa o danym indeksie (0..5). */
    public ItemStack getFuelFilter(int index) {
        return index >= 0 && index < FUEL_FILTERS ? fuelFilters.get(index) : ItemStack.EMPTY;
    }

    /** Ustawia filtr paliwa. Zmiana trafia do zapisu. */
    public void setFuelFilter(int index, ItemStack stack) {
        if (index < 0 || index >= FUEL_FILTERS) {
            return;
        }
        fuelFilters.set(index, stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        setChanged();
    }

    /** Czy plomien sie pali - do rysowania ikony. */
    public boolean isLit() {
        return burnTicksRemaining > 0;
    }

    /** Ile ciepla zostalo (tickow palenia). */
    public long getBurnTicksRemaining() {
        return burnTicksRemaining;
    }

    /** Pelny zakres ostatniego palenia - do paska. */
    public long getBurnTicksTotal() {
        return burnTicksTotal;
    }

    // ------------------------------------------------------------------
    // Praca w tle
    // ------------------------------------------------------------------

    public void serverTick() {
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        boolean wasLit = isLit();

        // 1. Piec pali sie CALY CZAS - to jest koszt instant craftowania.
        //    Bufor ciepla schodzi niezaleznie od tego, czy ktokolwiek craftuje.
        if (burnTicksRemaining > 0) {
            burnTicksRemaining--;
        }

        // 2. Bufor pusty -> spal jeden item z paliwa.
        if (burnTicksRemaining <= 0) {
            tryBurnFuel();
        }

        // 3. Zapas paliwa niski -> dociagnij z sieci wedlug filtrow.
        if (pullCooldown > 0) {
            pullCooldown--;
        } else {
            pullCooldown = PULL_INTERVAL_TICKS;
            pullFuelFromNetwork(sl);
        }

        if (wasLit != isLit()) {
            // Zmiana stanu plomienia - klient musi to zobaczyc.
            setChanged();
            syncToClients();
        }
    }

    /** Spala jeden item z zapasu paliwa, jesli to w ogole paliwo. */
    private void tryBurnFuel() {
        ItemStack fuel = fuelSlot.getItem(0);
        if (fuel.isEmpty()) {
            return;
        }
        long burn = burnTicksOf(fuel);
        if (burn <= 0) {
            // Nie jest paliwem - nie spalamy go i nie blokujemy slotu.
            return;
        }
        fuelSlot.removeItem(0, 1);
        burnTicksTotal = burn;
        burnTicksRemaining = burn;
    }

    /** Ile tickow palenia daje ten stos (0 = nie jest paliwem). */
    public static long burnTicksOf(ItemStack stack) {
        if (stack.isEmpty() || !AbstractFurnaceBlockEntity.isFuel(stack)) {
            return 0L;
        }
        Integer ticks = AbstractFurnaceBlockEntity.getFuel().get(stack.getItem());
        // Paliwo z tagu moze nie miec wpisu per-item - wtedy bierzemy
        // standardowa wartosc, zeby nie odrzucic poprawnego paliwa.
        return ticks == null ? 200L : ticks.longValue();
    }

    /**
     * Zaciaga paliwo z sieci wedlug filtrow, w kolejnosci priorytetu.
     *
     * <p>Najpierw wyczerpujemy filtr 1, potem 2, potem 3... - tak jak ustalono.
     * Pusty filtr jest pomijany, wiec gracz moze ustawic tylko te, ktorych chce.
     */
    private void pullFuelFromNetwork(ServerLevel sl) {
        ItemStack current = fuelSlot.getItem(0);
        int space = 64 - current.getCount();
        if (space <= 0) {
            return;   // zapas pelny
        }
        VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                .getNetworkForTerminal(sl, worldPosition);
        if (net == null) {
            return;
        }
        int wanted = Math.min(space, PULL_BATCH);

        // BRAK FILTROW = paliwo dowolne.
        //
        // Dzieki temu piec dziala od razu po postawieniu, bez konfiguracji -
        // a filtry sluza do ZAWEZENIA wyboru, nie do jego umozliwienia.
        boolean anyFilter = false;
        for (int i = 0; i < FUEL_FILTERS; i++) {
            if (!fuelFilters.get(i).isEmpty()) {
                anyFilter = true;
                break;
            }
        }

        if (!anyFilter) {
            pullAnyFuel(sl, net, wanted);
            return;
        }

        for (int i = 0; i < FUEL_FILTERS && wanted > 0; i++) {
            ItemStack filter = fuelFilters.get(i);
            if (filter.isEmpty()) {
                continue;
            }
            if (!canMergeIntoFuelSlot(filter)) {
                continue;
            }
            ItemStack got = net.extractItem(sl, filter.getItem(), wanted);
            if (got.isEmpty()) {
                continue;   // ten filtr sie skonczyl - nastepny w kolejnosci
            }
            wanted -= got.getCount();
            mergeIntoFuelSlot(got);
        }
    }

    /** Tryb bez filtrow: bierze cokolwiek, co jest paliwem. */
    private void pullAnyFuel(ServerLevel sl, VelocePipeNetwork net, int wanted) {
        for (var entry : net.getAllItemCounts(sl).entrySet()) {
            if (wanted <= 0) {
                break;
            }
            ItemStack probe = new ItemStack(entry.getKey());
            if (burnTicksOf(probe) <= 0) {
                continue;
            }
            if (!canMergeIntoFuelSlot(probe)) {
                continue;
            }
            ItemStack got = net.extractItem(sl, entry.getKey(), wanted);
            if (!got.isEmpty()) {
                wanted -= got.getCount();
                mergeIntoFuelSlot(got);
            }
        }
    }

    /**
     * Czy to paliwo zmiesci sie do slotu - sprawdzane PRZED pobraniem.
     *
     * <p><b>BUG, ktory to naprawia (petla pobierz-oddaj).</b> Wczesniej piec
     * pobieral paliwo, a dopiero potem {@link #mergeIntoFuelSlot} odkrywal, ze
     * w slocie lezy INNY rodzaj paliwa i nie da sie ich polaczyc - wiec
     * oddawal je z powrotem do sieci przez {@code depositBack}.
     *
     * <p>Wygladalo to tak, co PULL_INTERVAL_TICKS, w nieskonczonosc:
     * <pre>
     *   pobierz charcoal z sieci -> nie pasuje do wegla w slocie -> oddaj charcoal
     *   pobierz charcoal z sieci -> nie pasuje do wegla w slocie -> oddaj charcoal
     *   ...
     * </pre>
     * A ze oddawanie do magazynu w niezaladowanym chunku wczytuje ten chunk,
     * kazdy obrot petli to kolejne wczytanie chunku - czyli dokladnie ta
     * petla load/unload, ktora widac bylo w grze.
     *
     * <p>Teraz po prostu NIE pobieramy tego, czego nie mozemy przyjac: piec
     * najpierw wypala to, co ma w slocie, a dopiero potem siega po kolejny
     * rodzaj paliwa.
     */
    private boolean canMergeIntoFuelSlot(ItemStack fuel) {
        ItemStack current = fuelSlot.getItem(0);
        if (current.isEmpty()) {
            return true;   // pusty slot przyjmie wszystko
        }
        return ItemStack.isSameItemSameComponents(current, fuel);
    }

    /** Doklada pobrane paliwo do slotu (albo je zostawia, gdy sie nie zmiesci). */
    private void mergeIntoFuelSlot(ItemStack got) {
        ItemStack current = fuelSlot.getItem(0);
        if (current.isEmpty()) {
            fuelSlot.setItem(0, got);
            return;
        }
        if (ItemStack.isSameItemSameComponents(current, got)) {
            int space = current.getMaxStackSize() - current.getCount();
            int move = Math.min(space, got.getCount());
            current.grow(move);
            // Nadwyzka wraca do sieci - nie gubimy itemow.
            if (move < got.getCount()) {
                ItemStack rest = got.copyWithCount(got.getCount() - move);
                depositBack(rest);
            }
            fuelSlot.setChanged();
        } else {
            depositBack(got);
        }
    }

    /** Oddaje nadwyzke do sieci (gdy piec nie mial gdzie jej zmiescic). */
    private void depositBack(ItemStack stack) {
        if (stack.isEmpty() || !(level instanceof ServerLevel sl)) {
            return;
        }
        VelocePipeNetwork net = VelocePipeNetworkManager.get(sl)
                .getNetworkForTerminal(sl, worldPosition);
        if (net != null) {
            net.insertIntoStorage(sl, stack);
        }
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

        net.minecraft.nbt.ListTag filters = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < FUEL_FILTERS; i++) {
            ItemStack f = fuelFilters.get(i);
            if (!f.isEmpty()) {
                CompoundTag e = new CompoundTag();
                e.putByte("Slot", (byte) i);
                // UWAGA: save() ZWRACA nowy tag, nie mutuje przekazanego.
                CompoundTag saved = (CompoundTag) f.save(registries, new CompoundTag());
                saved.putByte("Slot", (byte) i);
                filters.add(saved);
            }
        }
        tag.put("FuelFilters", filters);

        ItemStack fuel = fuelSlot.getItem(0);
        if (!fuel.isEmpty()) {
            CompoundTag saved = (CompoundTag) fuel.save(registries, new CompoundTag());
            tag.put("Fuel", saved);
        }
        tag.putLong("Heat", burnTicksRemaining);
        tag.putLong("HeatTotal", burnTicksTotal);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        for (int i = 0; i < FUEL_FILTERS; i++) {
            fuelFilters.set(i, ItemStack.EMPTY);
        }
        net.minecraft.nbt.ListTag filters =
                tag.getList("FuelFilters", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < filters.size(); i++) {
            CompoundTag e = filters.getCompound(i);
            int slot = e.getByte("Slot") & 255;
            if (slot >= 0 && slot < FUEL_FILTERS) {
                CompoundTag clean = e.copy();
                clean.remove("Slot");
                ItemStack.parse(registries, clean).ifPresent(s -> fuelFilters.set(slot, s));
            }
        }

        fuelSlot.setItem(0, tag.contains("Fuel")
                ? ItemStack.parse(registries, tag.getCompound("Fuel")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY);
        burnTicksRemaining = tag.getLong("Heat");
        burnTicksTotal = tag.getLong("HeatTotal");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.craftingveloce.velocity_furnace");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new com.craftingveloce.inventory.VeloceVelocityFurnaceMenu(id, inv, this);
    }
}
