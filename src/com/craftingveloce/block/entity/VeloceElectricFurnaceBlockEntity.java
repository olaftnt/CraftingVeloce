package com.craftingveloce.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
     * Do kiedy (gameTime) pokazywac "swiezo zasilony" - do paska w GUI.
     *
     * <p>Na razie tylko diagnostyka przez czat; pole zostaje, zeby GUI
     * moglo pokazac ostatnia zmiane bez ciaglego synchronicowania.
     */
    private long lastEnergyChangeTick;

    public VeloceElectricFurnaceBlockEntity(BlockPos pos, BlockState state) {
        super(com.craftingveloce.init.VeloceRegistry.ELECTRIC_FURNACE_BE.get(), pos, state);
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
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = Math.max(0, Math.min(ENERGY_CAPACITY, tag.getInt("Energy")));
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
