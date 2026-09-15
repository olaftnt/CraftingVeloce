package com.craftingveloce.block.entity;

import com.craftingveloce.crafting.FeModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.Set;

/**
 * Akumulator energii JEDNEJ maszyny modulu Mekanism.
 *
 * <p><b>Jedna klasa na cztery maszyny.</b> Maszyny itemowe Mekanism roznia sie
 * tylko typem receptury i etykieta, wiec nie ma powodu pisac czterech kopii
 * tej samej logiki energii - rozni je pole {@link FeModule} podane
 * w konstruktorze.
 *
 * <p><b>Model pracy.</b> Jak Velocity Electric Furnace: brak wlasnego tickera
 * i postepu. Auto-crafter pyta o {@link #availableOperations()} ("ile operacji
 * jeszcze uciagniesz"), zabiera operacje przy wykonaniu receptury
 * ({@link #consumeOperations(long)}) i sam wklada wyniki do sieci. Dzieki temu
 * energia jest rozliczana dokladnie raz na wykonana recepture.
 */
public class VeloceFeModuleBlockEntity extends BlockEntity
        implements VeloceProcessingSource, IEnergyStorage {

    /**
     * Fabryka block entity - dostarczana przez modul.
     *
     * <p>Kazdy mod ma wlasne typy block entity, a rdzen nie moze znac ich
     * rejestrow - dlatego blok dostaje fabryke w konstruktorze. Dzieki temu
     * jeden blok rdzenia obsluguje maszyny z roznych modow.
     */
    @FunctionalInterface
    public interface Factory {
        VeloceFeModuleBlockEntity create(BlockPos pos, BlockState state);
    }

    private final FeModule module;
    private final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<VeloceFeModuleBlockEntity>> typeHolder;

    private int energy;

    public VeloceFeModuleBlockEntity(
            FeModule module,
            DeferredHolder<BlockEntityType<?>, BlockEntityType<VeloceFeModuleBlockEntity>> typeHolder,
            BlockPos pos, BlockState state) {
        super(typeHolder.get(), pos, state);
        this.module = module;
        this.typeHolder = typeHolder;
    }

    /** Opis maszyny (typ receptury, koszt, etykieta) - do diagnostyki. */
    public FeModule module() {
        return module;
    }

    /** Typ block entity, do ktorego ta maszyna jest zarejestrowana. */
    public BlockEntityType<?> registeredType() {
        return typeHolder.get();
    }

    // ------------------------------------------------------------------
    // VeloceProcessingSource
    // ------------------------------------------------------------------

    @Override
    public String moduleId() {
        return module.id();
    }

    @Override
    public Set<RecipeType<?>> recipeTypes() {
        // Typ receptury rozwiazujemy DOPIERO tutaj (nie przy ladowaniu klasy):
        // DeferredHolder obcego moda jest wiazany po zdarzeniach rejestracji.
        return Set.of(module.recipeType().get());
    }

    @Override
    public long availableOperations() {
        return energy / module.fePerOperation();
    }

    @Override
    public void consumeOperations(long operations) {
        if (operations <= 0) {
            return;
        }
        energy = (int) Math.max(0L, energy - operations * (long) module.fePerOperation());
        setChanged();
    }

    @Override
    public boolean isPowered() {
        return energy >= module.fePerOperation();
    }

    @Override
    public String sourceName() {
        return module.label();
    }

    // ------------------------------------------------------------------
    // IEnergyStorage - wkladanie z kabli, wyciaganie zabronione
    // ------------------------------------------------------------------

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (toReceive <= 0) {
            return 0;
        }
        int accepted = Math.min(toReceive, module.capacity() - energy);
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
        return module.capacity();
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
    // Pomoc dla gracza - ile pradu zostalo (maszyna nie ma GUI)
    // ------------------------------------------------------------------

    /** Wysyla graczowi stan akumulatora na pasek akcji. */
    public void sendStatus(ServerPlayer player) {
        player.displayClientMessage(Component.translatable(
                "gui.craftingveloce.module.energy",
                energy, module.capacity(),
                energy / module.fePerOperation(), module.fePerOperation()), true);
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
        energy = Math.max(0, Math.min(module.capacity(), tag.getInt("Energy")));
    }
}
