package com.craftingveloce.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Velocity Electric Furnace - zrodlo ciepla zasilane Forge Energy.
 *
 * <p><b>Roznica wobec paliwowego.</b> Nie ma filtra ani slotu paliwa i nie
 * pali sie w tle - energie trzyma w wewnetrznym akumulatorze, a kazde
 * instantowe przepalenie zabiera z niego stala porcje FE. Dzieki temu nie
 * zjada niczego, gdy nikt nie craftuje.
 *
 * <p><b>PRIORYTET.</b> {@link #heatPriority()} zwraca 0, czyli mniej niz
 * paliwowy (1) - crafter wybiera zrodlo o NAJNIZSZYM priorytecie, wiec
 * elektryczny jest uzywany PIERWSZY, a paliwowy jest fallbackiem. Dokladnie
 * tak, jak ustalono.
 *
 * <p><b>Liczby (ustalone z uzytkownikiem).</b>
 * <ul>
 *   <li>pojemnosc akumulatora: {@link #ENERGY_CAPACITY} = 25 000 000 FE</li>
 *   <li>koszt jednego przepalenia: {@link #FE_PER_SMELT} = 200 000 FE</li>
 * </ul>
 * Czyli pelny akumulator wystarcza na 125 przepalen, a stack (64 sztuki)
 * kosztuje 12 800 000 FE.
 */
public class VeloceElectricFurnaceBlockEntity extends BlockEntity
        implements MenuProvider, VeloceHeatSource, IEnergyStorage {

    /** Pojemnosc wewnetrznego akumulatora. */
    public static final int ENERGY_CAPACITY = 25_000_000;

    /** Koszt jednego instantowego przepalenia. */
    public static final int FE_PER_SMELT = 200_000;

    /** Ile FE jest teraz w akumulatorze. */
    private int energy;

    /**
     * Slot na ITEMEK Z ENERGIA (bateria, energy cube, tablet z innego moda).
     *
     * <p>Piec pobiera z niego prad jak z kabla - tylko w jedna strone. Item
     * zostaje w slocie, az gracz go wyjmie; nie da sie go tu "spalic".
     */
    private final net.minecraft.world.SimpleContainer batterySlot =
            new net.minecraft.world.SimpleContainer(1);

    /**
     * Ile FE na tick najwyzej wyciagamy z itemu.
     *
     * <p>Bez limitu pelna bateria wlalaby sie do akumulatora w jednym ticku.
     * Jedna sekunda (20 tickow) to 20 mln FE, czyli prawie pelny akumulator -
     * dosc szybko, a jednoczesnie widac, jak bateria sie oproznia.
     */
    public static final int MAX_ITEM_DRAIN_PER_TICK = 1_000_000;

    /**
     * Do kiedy (gameTime) pokazywac "swiezo zasilony" - do paska w GUI.
     *
     * <p>Na razie tylko diagnostyka przez czat; pole zostaje, zeby GUI
     * moglo pokazac ostatnia zmiane bez ciaglego synchronicowania.
     */
    private long lastEnergyChangeTick;

    public VeloceElectricFurnaceBlockEntity(BlockPos pos, BlockState state) {
        super(com.craftingveloce.init.VeloceRegistry.ELECTRIC_FURNACE_BE.get(), pos, state);
        // Kazda zmiana w slocie baterii musi trafic do zapisu.
        this.batterySlot.addListener(c -> setChanged());
    }

    /** Slot baterii - dla menu (i dla ekranu, ktory pokazuje podpowiedz). */
    public net.minecraft.world.Container getBatterySlot() {
        return batterySlot;
    }

    /**
     * Czy ten item da sie u nas "rozadowac" - czyli czy ma energie do oddania.
     *
     * <p>Pytamy o standardowa zdolnosc NeoForge (Forge Energy na itemie).
     * Tak wlasnie robia to inne mody: Energy Cube, baterie, tablety. Item,
     * ktory trzyma energie w NBT, ale NIE wystawia tej zdolnosci, nie zadziala -
     * i mowimy o tym wprost w podpowiedzi slotu, zamiast udawac, ze dziala.
     */
    public static boolean isEnergyItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        net.neoforged.neoforge.energy.IEnergyStorage st = stack.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);
        return st != null && st.canExtract() && st.getMaxEnergyStored() > 0;
    }

    /**
     * Bierze prad z itemu w slocie baterii i wlewa go do akumulatora.
     *
     * <p>Kolejnosc jest wazna: NAJPIERW sprawdzamy na sucho, ile item moze
     * oddac, potem wlewamy do akumulatora i DOPIERO z tego, co naprawde
     * weszlo, zabieramy z itemu. Odwrotna kolejnosc (zabierz, potem wlej)
     * zgubilaby energie, gdyby akumulator byl juz pelny.
     */
    private void chargeFromItem() {
        ItemStack stack = batterySlot.getItem(0);
        if (stack.isEmpty()) {
            return;
        }
        int space = ENERGY_CAPACITY - energy;
        if (space <= 0) {
            return;   // akumulator pelny - nie ruszamy itemu
        }
        net.neoforged.neoforge.energy.IEnergyStorage itemEnergy = stack.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);
        if (itemEnergy == null || !itemEnergy.canExtract()) {
            return;
        }
        int available = itemEnergy.extractEnergy(Math.min(space, MAX_ITEM_DRAIN_PER_TICK), true);
        if (available <= 0) {
            return;   // item jest pusty
        }
        // Zabieramy to, co item NAPRAWDE oddal - nie to, o co prosilismy.
        // Niektore implementacje potrafia oddac mniej, niz zadeklarowaly
        // w symulacji; wtedy wlanie "available" do akumulatora tworzyloby
        // energie z niczego.
        int taken = itemEnergy.extractEnergy(available, false);
        if (taken <= 0) {
            return;
        }
        int accepted = receiveEnergy(taken, false);
        if (accepted < taken) {
            // Akumulator nie przyjal calosci (np. zabraklo miejsca w trakcie) -
            // nadwyzke ODDajemy do itemu, zeby nic nie zginelo.
            itemEnergy.receiveEnergy(taken - accepted, false);
        }
        if (accepted > 0) {
            batterySlot.setChanged();
        }
    }

    // ------------------------------------------------------------------
    // VeloceHeatSource
    // ------------------------------------------------------------------

    @Override
    public long availableOperations() {
        return energy / FE_PER_SMELT;
    }

    @Override
    public void consumeOperations(long operations) {
        if (operations <= 0) {
            return;
        }
        long cost = operations * FE_PER_SMELT;
        energy = (int) Math.max(0L, energy - cost);
        if (level != null) {
            lastEnergyChangeTick = level.getGameTime();
        }
        setChanged();
    }

    @Override
    public boolean isPowered() {
        // "Zasilony" = stac go na co najmniej jedno przepalenie. Piec z resztka
        // energii ponizej kosztu nie odblokowuje receptur, bo i tak by ich
        // nie wykonal.
        return energy >= FE_PER_SMELT;
    }

    @Override
    public int heatPriority() {
        return 0;   // elektryczny wygrywa z paliwowym
    }

    @Override
    public String heatSourceName() {
        return "Velocity Electric Furnace";
    }

    // ------------------------------------------------------------------
    // IEnergyStorage - przyjmowanie energii z kabli
    // ------------------------------------------------------------------

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (toReceive <= 0) {
            return 0;
        }
        int space = ENERGY_CAPACITY - energy;
        int accepted = Math.min(space, toReceive);
        if (!simulate && accepted > 0) {
            energy += accepted;
            setChanged();
        }
        return accepted;
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        // Akumulator jest WEWNETRZNY - energia wychodzi tylko przez przepalanie
        // w crafterze, nie przez kabel. Inaczej kabel moglby "wyssac" piec.
        return 0;
    }

    @Override
    public int getEnergyStored() {
        return energy;
    }

    @Override
    public int getMaxEnergyStored() {
        return ENERGY_CAPACITY;
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public boolean canReceive() {
        return true;
    }

    // ------------------------------------------------------------------
    // Diagnostyka
    // ------------------------------------------------------------------

    /**
     * Dosyla stan akumulatora do klienta, gdy sie zmienil.
     *
     * <p>Piec nie ma wlasnej logiki per tick - tyka WYLACZNIE po to, zeby pasek
     * energii w GUI byl zywy. Dlatego wysylamy tylko przy realnej zmianie
     * (i najwyzej raz na pol sekundy): kabel z innego moda potrafi ladowac
     * co tick, a pakiet co tick bylby marnotrawstwem.
     */
    public void serverTick() {
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) {
            return;
        }
        // UWAGA: to musi byc PRZED wczesnym returnem od synchronizacji.
        // Inaczej ladowanie z itemu dzialaloby tylko raz na 10 tickow (czyli
        // 10x wolniej), bo cooldown przerywa te metode.
        chargeFromItem();
        if (--clientSyncCooldown > 0) {
            return;
        }
        clientSyncCooldown = CLIENT_SYNC_INTERVAL_TICKS;
        if (energy != lastSyncedEnergy) {
            lastSyncedEnergy = energy;
            sl.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** Co ile tickow najwyzej dosylamy stan energii do klienta. */
    private static final int CLIENT_SYNC_INTERVAL_TICKS = 10;

    private int clientSyncCooldown = CLIENT_SYNC_INTERVAL_TICKS;
    private int lastSyncedEnergy = -1;

    /** Ile FE jest w akumulatorze. */
    public int getEnergy() {
        return energy;
    }

    /** Tick ostatniej zmiany energii - do GUI. */
    public long getLastEnergyChangeTick() {
        return lastEnergyChangeTick;
    }

    // ------------------------------------------------------------------
    // Zapis / odczyt
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Energy", energy);
        ItemStack battery = batterySlot.getItem(0);
        if (!battery.isEmpty()) {
            // Zapisujemy CALY stos razem z jego danymi - energia itemu z innego
            // moda zyje wlasnie w nich, wiec nie mozemy zapisac "samego itemu".
            tag.put("Battery", battery.save(registries, new CompoundTag()));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = Math.max(0, Math.min(ENERGY_CAPACITY, tag.getInt("Energy")));
        batterySlot.setItem(0, tag.contains("Battery")
                ? ItemStack.parse(registries, tag.getCompound("Battery")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY);
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
        return Component.translatable("block.craftingveloce.electric_furnace");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new com.craftingveloce.inventory.VeloceElectricFurnaceMenu(
                id, inv, getBlockPos(), this);
    }
}
