package com.craftingveloce.block.entity;

import com.craftingveloce.crafting.FeModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
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
        implements VeloceProcessingSource, IEnergyStorage, VeloceModuleInfoSource,
        VeloceModuleDisplay {

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

    /**
     * Dane do okna modulu: energia, koszt operacji i sieć rur.
     *
     * <p>Gracz: "ma byc widoczny wskaznik naladowania (bateryjka) pokazujacy
     * aktualny stan zmagazynowanego pradu". Okno liczy sie na serwerze (tylko
     * tam sa prawdziwe liczby), a klient rysuje pasek baterii z tych pol.
     */
    /** Pola okna u KLIENTA (energia jest synchronizowana przez block entity). */
    @Override
    public net.minecraft.nbt.CompoundTag moduleDisplay() {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putLong("energy", energy);
        tag.putLong("energyCapacity", module.capacity());
        tag.putLong("fePerOperation", module.fePerOperation());
        tag.putLong("operations", module.fePerOperation() <= 0 ? 0L
                : energy / module.fePerOperation());
        tag.putBoolean("powered", isPowered());
        return tag;
    }

    @Override
    public net.minecraft.nbt.CompoundTag moduleInfo(net.minecraft.server.level.ServerLevel level) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putLong("energy", energy);
        tag.putLong("energyCapacity", module.capacity());
        tag.putLong("fePerOperation", module.fePerOperation());
        tag.putLong("operations", module.fePerOperation() <= 0 ? 0L
                : energy / module.fePerOperation());
        tag.putBoolean("powered", energy >= module.fePerOperation());
        tag.putString("moduleLabel", module.label());
        var pipes = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(level)
                .getNetworkForTerminal(level, worldPosition);
        if (pipes != null) {
            tag.putInt("networkNodes", pipes.getTerminals().size());
            tag.putInt("networkStorages", pipes.getEndpoints().size());
            tag.putInt("networkItems", pipes.getAllItemCounts(level).size());
        }
        return tag;
    }

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

    // ------------------------------------------------------------------
    // Slot baterii (jak w Velocity Electric Furnace)
    // ------------------------------------------------------------------

    /** Item z energia (bateria, energy cube, tablet) - jedyne, co tu wejdzie. */
    private final net.minecraft.world.SimpleContainer batterySlot =
            new net.minecraft.world.SimpleContainer(1);

    /** Ile FE na tick najwyzej wyciagamy z itemu. */
    public static final int MAX_ITEM_DRAIN_PER_TICK = 1_000_000;

    /** Slot baterii - dla menu i dla ekranu. */
    public net.minecraft.world.Container getBatterySlot() {
        return batterySlot;
    }

    /** Czy item ma energie do oddania (standardowa zdolnosc NeoForge). */
    public static boolean isEnergyItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        net.neoforged.neoforge.energy.IEnergyStorage st = stack.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);
        return st != null && st.canExtract() && st.getMaxEnergyStored() > 0;
    }

    /** Co tick (serwer): dobierz prad z itemu w slocie baterii i z sieci. */

    /**
     * Siec, do ktorej NAPRAWDE nalezy ta maszyna.
     *
     * <p>BUG z logu: piec pytal o siec przez {@code getNetworkForTerminal}
     * i dostawal CUDZA siec - w logu jej jedyna rura sasiadowala trawie
     * i powietrzu, wiec Energy Cube ani pieca tam nie bylo i pobor nie mial
     * z czego dzialac. Teraz najpierw szukamy rury OBOK maszyny i pytamy
     * o siec tej rury; dopiero gdy takiej nie ma, wracamy do starej sciezki.
     */
    private com.craftingveloce.network.pipe.VelocePipeNetwork networkFor(net.minecraft.server.level.ServerLevel sl) {
        var manager = com.craftingveloce.network.pipe.VelocePipeNetworkManager.get(sl);
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            net.minecraft.core.BlockPos side = worldPosition.relative(dir);
            if (sl.isLoaded(side)
                    && sl.getBlockState(side).getBlock() instanceof com.craftingveloce.block.VelocePipeBlock) {
                var net = manager.getNetworkForPipe(sl, side);
                if (net != null) {
                    return net;
                }
            }
        }
        return manager.getNetworkForTerminal(sl, worldPosition);
    }

    private int clientSyncCooldown = 10;
    private int lastSyncedEnergy = -1;

    public void serverTick() {
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) {
            return;
        }
        chargeFromItem();
        pullFromNetwork();
        
        if (--clientSyncCooldown > 0) {
            return;
        }
        clientSyncCooldown = 10;
        if (energy != lastSyncedEnergy) {
            lastSyncedEnergy = energy;
            sl.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * Sciaga prad z OBCYCH zrodel podpietych do sieci rur (Energy Cube,
     * generator). Tylko nasza maszyna sciaga - rura nie przewodzi pradu dla
     * innych modow, a maszyny nie sa dla siebie zrodlem (skan sieci pomija
     * nasze bloki). Pelny akumulator = zero prob (patrz VeloceEnergyPull).
     */
    private void pullFromNetwork() {
        if (level == null || level.isClientSide) {
            return;
        }
        var serverLevel = (net.minecraft.server.level.ServerLevel) level;
        var network = networkFor(serverLevel);
        com.craftingveloce.network.pipe.VeloceEnergyPull.pull(
                serverLevel, network, this, MAX_PULL_PER_TICK);
    }

    /** Ile FE na tick najwyzej przyjmujemy z sieci (obok limitu zrodla). */
    public static final int MAX_PULL_PER_TICK = 1_000_000;

    /**
     * Bierze prad z itemu i wlewa do akumulatora.
     *
     * <p>Kolejnosc jak w piecu: NAJPIERW symulacja, potem wlew do akumulatora,
     * a z itemu zabieramy tylko to, co naprawde weszlo - inaczej przy pelnym
     * akumulatorze energia znikalaby z itemu.
     */
    private void chargeFromItem() {
        ItemStack stack = batterySlot.getItem(0);
        if (stack.isEmpty()) {
            return;
        }
        int space = module.capacity() - energy;
        if (space <= 0) {
            return;
        }
        net.neoforged.neoforge.energy.IEnergyStorage itemEnergy = stack.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);
        if (itemEnergy == null || !itemEnergy.canExtract()) {
            return;
        }
        int available = itemEnergy.extractEnergy(Math.min(space, MAX_ITEM_DRAIN_PER_TICK), true);
        if (available <= 0) {
            return;
        }
        int taken = itemEnergy.extractEnergy(available, false);
        if (taken <= 0) {
            return;
        }
        int accepted = receiveEnergy(taken, false);
        if (accepted < taken) {
            itemEnergy.receiveEnergy(taken - accepted, false);
        }
        if (accepted > 0) {
            batterySlot.setChanged();
            syncEnergy();
        }
    }

    /**
     * Wysyla energie na KLIENTA.
     *
     * <p>BUG, ktory to naprawia (zgloszenie gracza): pasek baterii w oknie
     * modulu pokazywal ciagle pusty akumulator. Okno czyta energie z block
     * entity u siebie (jak piec), ale modul NIE wysylal jej na klienta -
     * piec robi to od poczatku ({@code sendBlockUpdated} + pakiet z danymi).
     */
    private void syncEnergy() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Energy", energy);
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection connection,
                             net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket pkt,
                             HolderLookup.Provider registries) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            energy = Math.max(0, Math.min(module.capacity(), tag.getInt("Energy")));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Energy", energy);
        ItemStack battery = batterySlot.getItem(0);
        if (!battery.isEmpty()) {
            tag.put("Battery", battery.save(registries, new CompoundTag()));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = Math.max(0, Math.min(module.capacity(), tag.getInt("Energy")));
        batterySlot.setItem(0, tag.contains("Battery")
                ? ItemStack.parse(registries, tag.getCompound("Battery")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY);
    }
}
