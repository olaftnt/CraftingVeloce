package com.craftingveloce.block.entity;

import com.craftingveloce.init.VeloceRegistry;
import com.craftingveloce.inventory.VeloceExtractorMenu;
import com.craftingveloce.network.SyncExtractorFiltersPKT;
import com.craftingveloce.network.pipe.VelocePipeNetwork;
import com.craftingveloce.network.pipe.VelocePipeNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainer;

public class VeloceExtractorBlockEntity extends BlockEntity implements MenuProvider, WorldlyContainer {

    private static final int[] OUTPUT_SLOTS = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8};

    private final NonNullList<ItemStack> filterSlots = NonNullList.withSize(9, ItemStack.EMPTY);
    private final SimpleContainer outputInventory = new SimpleContainer(9);

    private int tickCounter = 0;

    /**
     * Czy dany slot filtra moze korzystac z auto-craftingu.
     *
     * <p>Domyslnie true (extractor sam dorabia brakujacy item, jesli jakis
     * crafter w sieci ma go wlaczonego). Prawy klik na zajetym slocie to
     * przelacza: wylaczony slot dostaje TYLKO to, co juz jest w sieci.
     */
    private final boolean[] allowCrafting = new boolean[]{true, true, true, true, true, true, true, true, true};

    public boolean isCraftingAllowed(int index) {
        return index >= 0 && index < 9 && allowCrafting[index];
    }

    public void setCraftingAllowed(int index, boolean allowed) {
        if (index >= 0 && index < 9) {
            allowCrafting[index] = allowed;
            setChanged();
            syncFiltersToWatchers();
        }
    }

    /** Przelacza auto-crafting dla slotu i zwraca nowy stan. */
    public boolean toggleCraftingAllowed(int index) {
        boolean next = !isCraftingAllowed(index);
        setCraftingAllowed(index, next);
        return next;
    }

    public VeloceExtractorBlockEntity(BlockPos pos, BlockState state) {
        super(VeloceRegistry.VELOCE_EXTRACTOR_BE.get(), pos, state);
        outputInventory.addListener(c -> setChanged());
    }

    public Container getOutputInventory() {
        return outputInventory;
    }

    public NonNullList<ItemStack> getFilterSlots() {
        return filterSlots;
    }

    public ItemStack getFilter(int index) {
        if (index >= 0 && index < 9) {
            return filterSlots.get(index);
        }
        return ItemStack.EMPTY;
    }

    public void setFilter(int index, ItemStack filterItem) {
        if (index >= 0 && index < 9) {
            if (filterItem.isEmpty()) {
                filterSlots.set(index, ItemStack.EMPTY);
            } else {
                ItemStack copy = filterItem.copy();
                copy.setCount(1);
                filterSlots.set(index, copy);
            }
            setChanged();
            syncFiltersToWatchers();
        }
    }

    public void syncFiltersToWatchers() {
        if (level != null && !level.isClientSide && level instanceof ServerLevel sl) {
            SyncExtractorFiltersPKT pkt = buildFilterPacket();
            for (ServerPlayer player : sl.players()) {
                if (player.containerMenu instanceof VeloceExtractorMenu menu && menu.getPos().equals(worldPosition)) {
                    PacketDistributor.sendToPlayer(player, pkt);
                }
            }
        }
    }

    public void syncFiltersToPlayer(ServerPlayer player) {
        if (level != null && !level.isClientSide) {
            PacketDistributor.sendToPlayer(player, buildFilterPacket());
        }
    }

    /** Filtry + flagi auto-craftingu w jednym pakiecie. */
    private SyncExtractorFiltersPKT buildFilterPacket() {
        List<Boolean> flags = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            flags.add(allowCrafting[i]);
        }
        return new SyncExtractorFiltersPKT(worldPosition, new ArrayList<>(filterSlots), flags);
    }

    public void serverTick() {
        tickCounter++;
        // Attempt extraction from network every 10 ticks (0.5s)
        if (tickCounter % 10 == 0) {
            pullFilteredItemsFromNetwork();
        }
    }

    private void pullFilteredItemsFromNetwork() {
        if (level == null || level.isClientSide || !(level instanceof ServerLevel sl)) return;

        VelocePipeNetworkManager manager = VelocePipeNetworkManager.get(sl);
        VelocePipeNetwork net = manager.getNetworkForTerminal(sl, worldPosition);
        if (net == null) return;

        // FAZA 1: samo pobranie z sieci - tanie, dla wszystkich 9 slotow.
        //
        // Kolejnosc ma znaczenie. Wczesniej kazdy slot szedl od razu pelna
        // sciezka "pobierz albo wycraftuj", a to znaczylo do 9 planowan drzewa
        // receptur na jeden cykl (co 10 tickow, na kazdy extractor) plus 9
        // wymuszonych skanow sieci. Serwer nie mial szans.
        boolean[] needsCraft = new boolean[9];
        for (int i = 0; i < 9; i++) {
            ItemStack filter = filterSlots.get(i);
            if (filter.isEmpty()) continue;

            ItemStack currentOutput = outputInventory.getItem(i);
            int maxStack = filter.getMaxStackSize();
            int needed;

            if (currentOutput.isEmpty()) {
                needed = maxStack;
            } else if (ItemStack.isSameItemSameComponents(currentOutput, filter)) {
                needed = maxStack - currentOutput.getCount();
            } else {
                continue;   // slot zajety czyms innym
            }
            if (needed <= 0) continue;

            ItemStack direct = net.extractItem(sl, filter.getItem(), needed);
            if (!direct.isEmpty()) {
                depositIntoSlot(i, currentOutput, direct);
            } else {
                needsCraft[i] = true;
            }
        }

        // FAZA 2: craftowanie tylko dla slotow, ktorym naprawde czegos braklo,
        // i tylko w ramach JEDNEGO wspolnego budzetu na caly cykl. Jeden wolny
        // craft nie moze zjesc czasu przeznaczonego na pozostale sloty.
        long deadline = System.nanoTime() + PULL_CRAFT_BUDGET_NS;
        for (int i = 0; i < 9; i++) {
            if (!needsCraft[i]) continue;
            // Slot z wylaczonym auto-craftingiem dostaje wylacznie to, co juz
            // jest w sieci - nigdy nie zamawiamy dla niego craftu.
            if (!allowCrafting[i]) continue;
            if (System.nanoTime() > deadline) {
                break;   // reszta w nastepnym cyklu
            }
            ItemStack filter = filterSlots.get(i);
            ItemStack currentOutput = outputInventory.getItem(i);
            int maxStack = filter.getMaxStackSize();
            // Ten sam warunek co w fazie 1: slot moze byc zajety innym itemem
            // (nic innego go nie zapisuje, ale nie zakladamy tego na zapas).
            int needed;
            if (currentOutput.isEmpty()) {
                needed = maxStack;
            } else if (ItemStack.isSameItemSameComponents(currentOutput, filter)) {
                needed = maxStack - currentOutput.getCount();
            } else {
                continue;
            }
            if (needed <= 0) continue;

            ItemStack crafted = craftFromNetwork(sl, net, filter.getItem(), needed);
            if (!crafted.isEmpty()) {
                depositIntoSlot(i, currentOutput, crafted);
            }
        }
    }

    /** Wklada pobrany stos do slotu wyjsciowego (nowy albo doglebia istniejacy). */
    private void depositIntoSlot(int slot, ItemStack currentOutput, ItemStack pulled) {
        if (currentOutput.isEmpty()) {
            outputInventory.setItem(slot, pulled);
        } else {
            currentOutput.grow(pulled.getCount());
            outputInventory.setItem(slot, currentOutput);
        }
        setChanged();
    }

    /**
     * Ile laczenie moze trwac auto-craftowanie w jednym cyklu extractora.
     *
     * <p>Cykl leci co 10 tickow na kazdy extractor, wiec 10 ms to juz 20%
     * budzetu ticku przy jednym urzadzeniu. Reszta czeka na kolejny cykl.
     */
    private static final long PULL_CRAFT_BUDGET_NS = 10_000_000L;

    /**
     * Auto-wycraftowuje brakujacy item i wyjmuje go z bufora/sieci.
     *
     * <p>Wolane TYLKO gdy bezposrednie pobranie sie nie powiodlo (patrz faza 2
     * w {@link #pullFilteredItemsFromNetwork}) i tylko w ramach wspolnego
     * budzetu cyklu. Wczesniej kazdy slot szedl ta sciezka bezwarunkowo.
     */
    private ItemStack craftFromNetwork(ServerLevel sl, VelocePipeNetwork net, Item item, int count) {
        // Auto-crafting: tylko jesli jakis crafter ma wlaczona recepture dla itemu.
        var crafter = com.craftingveloce.crafting.VeloceCraftingRegistry
                .findEnabledCrafter(sl, net, item);
        if (crafter == null) {
            return ItemStack.EMPTY;
        }

        var enabled = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getAllEnabledItems(sl, net);
        var preferred = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getPreferredRecipes(sl, net);
        var buffers = com.craftingveloce.crafting.VeloceCraftingRegistry
                .getBuffers(sl, net);
        var ctx = new com.craftingveloce.crafting.VeloceAutoCrafter.Context(
                sl, net, enabled, preferred, null, buffers);
        // Maly budzet planowania: to tlo, nie zadanie gracza. Reszta poczeka
        // na kolejny cykl, zamiast blokowac watek serwera.
        var result = com.craftingveloce.crafting.VeloceAutoCrafter
                .ensureAvailable(sl, net, item, count, ctx, CRAFT_PLAN_BUDGET_NS);
        if (!result.success()) {
            return ItemStack.EMPTY;
        }

        // Bierzemy DOKLADNIE tyle, ile realnie powstalo - nie tyle, o ile
        // prosilismy. Gdy materialu starczylo na 12 z 64, ensureAvailable
        // robi 12 i tyle ma trafic do slotu; zadanie 64 wyciagneloby przy
        // okazji itemy, ktore lezaly w sieci z innych powodow.
        int got = Math.max(1, Math.min(count, result.produced()));

        // Wynik trafia najpierw do bufora craftera, ktory nie jest endpointem
        // sieci - dlatego najpierw wyciagamy z buforow, potem z sieci.
        ItemStack fromBuffer = extractFromBuffers(buffers, item, got);
        if (!fromBuffer.isEmpty()) {
            return fromBuffer;
        }
        return net.extractItem(sl, item, got);
    }

    /** Budzet planowania dla jednego brakujacego itemu w cyklu extractora. */
    private static final long CRAFT_PLAN_BUDGET_NS = 3_000_000L;

    /** Wyciaga item z buforow auto-crafterow. */
    private static ItemStack extractFromBuffers(
            java.util.List<com.craftingveloce.inventory.VeloceCraftingBuffer> buffers,
            Item item, int count) {
        ItemStack result = ItemStack.EMPTY;
        int remaining = count;
        for (var buf : buffers) {
            if (remaining <= 0) {
                break;
            }
            for (int slot = 0; slot < buf.getContainerSize() && remaining > 0; slot++) {
                ItemStack inSlot = buf.getItem(slot);
                if (inSlot.isEmpty() || inSlot.getItem() != item) {
                    continue;
                }
                int take = Math.min(remaining, inSlot.getCount());
                ItemStack taken = buf.removeItem(slot, take);
                if (taken.isEmpty()) {
                    continue;
                }
                if (result.isEmpty()) {
                    result = taken.copy();
                } else {
                    result.grow(taken.getCount());
                }
                remaining -= taken.getCount();
            }
        }
        return result;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag filterList = new ListTag();
        for (int i = 0; i < 9; i++) {
            if (!filterSlots.get(i).isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putByte("Slot", (byte) i);
                filterSlots.get(i).save(registries, itemTag);
                filterList.add(itemTag);
            }
        }
        tag.put("Filters", filterList);

        // Flagi auto-craftingu per slot. Zapisujemy liste indeksow WYLACZONYCH,
        // zeby stare swiaty (bez tego tagu) dostawaly domyslne "wlaczone".
        ListTag noCraft = new ListTag();
        for (int i = 0; i < 9; i++) {
            if (!allowCrafting[i]) {
                noCraft.add(net.minecraft.nbt.IntTag.valueOf(i));
            }
        }
        tag.put("NoCraft", noCraft);

        ListTag outputList = new ListTag();
        for (int i = 0; i < outputInventory.getContainerSize(); i++) {
            ItemStack stack = outputInventory.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putByte("Slot", (byte) i);
                stack.save(registries, itemTag);
                outputList.add(itemTag);
            }
        }
        tag.put("Outputs", outputList);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        java.util.Collections.fill(filterSlots, ItemStack.EMPTY);
        ListTag filterList = tag.getList("Filters", Tag.TAG_COMPOUND);
        for (int i = 0; i < filterList.size(); i++) {
            CompoundTag itemTag = filterList.getCompound(i);
            int slot = itemTag.getByte("Slot") & 255;
            if (slot < 9) {
                ItemStack.parse(registries, itemTag).ifPresent(s -> filterSlots.set(slot, s));
            }
        }

        java.util.Arrays.fill(allowCrafting, true);
        ListTag noCraft = tag.getList("NoCraft", Tag.TAG_INT);
        for (int i = 0; i < noCraft.size(); i++) {
            int slot = noCraft.getInt(i);
            if (slot >= 0 && slot < 9) {
                allowCrafting[slot] = false;
            }
        }

        outputInventory.clearContent();
        ListTag outputList = tag.getList("Outputs", Tag.TAG_COMPOUND);
        for (int i = 0; i < outputList.size(); i++) {
            CompoundTag itemTag = outputList.getCompound(i);
            int slot = itemTag.getByte("Slot") & 255;
            if (slot < outputInventory.getContainerSize()) {
                ItemStack.parse(registries, itemTag).ifPresent(s -> outputInventory.setItem(slot, s));
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Veloce Extractor");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new VeloceExtractorMenu(containerId, playerInventory, worldPosition, this);
    }

    // --- WorldlyContainer implementation (for Hoppers & vanilla extraction) ---

    @Override
    public int[] getSlotsForFace(Direction side) {
        return OUTPUT_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction dir) {
        return false; // Prevent external insertion into extractor
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction dir) {
        return true; // Allow hoppers and pipes to extract items
    }

    @Override
    public int getContainerSize() {
        return outputInventory.getContainerSize();
    }

    @Override
    public boolean isEmpty() {
        return outputInventory.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return outputInventory.getItem(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack res = outputInventory.removeItem(slot, amount);
        setChanged();
        return res;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack res = outputInventory.removeItemNoUpdate(slot);
        setChanged();
        return res;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        outputInventory.setItem(slot, stack);
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return outputInventory.stillValid(player);
    }

    @Override
    public void clearContent() {
        outputInventory.clearContent();
        setChanged();
    }
}
