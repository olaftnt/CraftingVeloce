package com.craftingveloce.compat.mekanism.block.entity;

import com.craftingveloce.block.entity.VeloceProcessingSource;
import com.craftingveloce.compat.mekanism.MekanismRecipeFamily;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.Set;

/**
 * Akumulator energii maszyny kruszacej (receptury {@code mekanism:crushing}).
 *
 * <p><b>Model pracy.</b> Jak Velocity Electric Furnace: maszyna nie ma
 * wlasnego tickera ani postepu. Crafter pyta ja o {@link #availableOperations()}
 * ("ile operacji jeszcze uciagniesz"), zabiera operacje przy wykonaniu receptury
 * ({@link #consumeOperations(long)}) i sam wklada wyniki do sieci. Dzięki temu
 * jedna maszyna obsluguje wiele rownoleglych receptur, a energia jest
 * rozliczana dokladnie raz na operacje.
 *
 * <p><b>Koszt.</b> Mekanism liczy 20 FE/t przez 200 tickow = 4000 FE na
 * operacje. Przyjmujemy ta sama cene, zeby modul nie byl ani okazja, ani
 * pułapka wzgledem maszyn Mekanism. Bufor jest wiekszy niz w oryginale
 * (40 000 FE = 10 operacji) - instant maszyna nie ma wlasnego postepu, wiec
 * kilka operacji zapasu chroni automatyzacje przed przerwami, gdy kabel
 * dostarcza prad z opoznieniem.
 */
public class VeloceCrusherModuleBlockEntity extends BlockEntity
        implements VeloceProcessingSource, IEnergyStorage {

    /** Koszt jednej operacji w FE (Mekanism: 20 FE/t * 200 t). */
    public static final int FE_PER_OPERATION = 4_000;

    /** Pojemnosc akumulatora (10 operacji zapasu). */
    public static final int ENERGY_CAPACITY = 40_000;

    private int energy;

    public VeloceCrusherModuleBlockEntity(BlockPos pos, BlockState state) {
        super(com.craftingveloce.compat.mekanism.MekanismBlockEntities.CRUSHER_MODULE.get(),
                pos, state);
    }

    // ------------------------------------------------------------------
    // VeloceProcessingSource
    // ------------------------------------------------------------------

    @Override
    public String moduleId() {
        return "mekanism:crusher";
    }

    @Override
    public Set<RecipeType<?>> recipeTypes() {
        return Set.of(MekanismRecipeFamily.crushing());
    }

    @Override
    public long availableOperations() {
        return energy / FE_PER_OPERATION;
    }

    @Override
    public void consumeOperations(long operations) {
        if (operations <= 0) {
            return;
        }
        energy = (int) Math.max(0L, energy - operations * (long) FE_PER_OPERATION);
        setChanged();
    }

    @Override
    public boolean isPowered() {
        return energy >= FE_PER_OPERATION;
    }

    @Override
    public String sourceName() {
        return "Veloce Crusher Module";
    }

    // ------------------------------------------------------------------
    // IEnergyStorage - wkladanie z kabli, wyciaganie zabronione
    // ------------------------------------------------------------------

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (toReceive <= 0) {
            return 0;
        }
        int accepted = Math.min(toReceive, ENERGY_CAPACITY - energy);
        if (!simulate && accepted > 0) {
            energy += accepted;
            setChanged();
        }
        return accepted;
    }

    /**
     * Wyciaganie jest CELOWO zablokowane.
     *
     * <p>Akumulator maszyny nie jest magazynem dla sieci - kabel nie moze
     * "wyssac" energii, ktora maszyna ma do wykonania operacji. Ta sama zasada
     * co w Velocity Electric Furnace.
     */
    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
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
    // Pomoc dla gracza - ile pradu zostalo (bez GUI)
    // ------------------------------------------------------------------

    /** Wysyla graczowi stan akumulatora na pasek akcji. */
    public void sendStatus(ServerPlayer player) {
        player.displayClientMessage(Component.translatable(
                "gui.craftingveloce.module.energy",
                energy, ENERGY_CAPACITY,
                energy / FE_PER_OPERATION, FE_PER_OPERATION), true);
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
}
