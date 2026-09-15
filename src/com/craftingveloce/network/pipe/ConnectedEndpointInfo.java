package com.craftingveloce.network.pipe;

import com.craftingveloce.rs.RefinedStorageHelper;
import com.craftingveloce.util.VeloceLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.HashMap;
import java.util.Map;

public class ConnectedEndpointInfo {

    public enum Type {
        INVENTORY,
        REFINED_STORAGE,
        /**
         * Bufor auto-craftera - pamiec podreczna bloku, nie zasobnik w swiecie.
         * Rozpoznawany po typie, zeby po restarcie swiata odtworzyc wlasciwa
         * implementacje endpointu (patrz {@link CraftingBufferEndpoint}).
         */
        CRAFTING_BUFFER
    }

    private final BlockPos pos;
    private final Direction accessSide;
    private final ChunkPos chunkPos;
    private final Type type;
    private final Map<Item, Long> cachedCounts = new HashMap<>();

    /**
     * Tick, w ktorym ostatnio przeskanowalismy ten inwentarz.
     *
     * <p>Skanowanie polega na przejsciu WSZYSTKICH slotow i wywolaniu
     * {@code getStackInSlot} na kazdym. Dla moddowanych magazynow to potrafi
     * kopiowac stosy razem z NBT, wiec jest to operacja droga. Wczesniej
     * odswiezalismy sie przy KAZDYM zapytaniu o stan sieci (a to leci
     * kilka razy na sekunde), co zamulalo watek serwera.
     */
    private long lastScanTick = Long.MIN_VALUE;

    /** Minimalny odstep miedzy skanami tego samego inwentarza, w tickach. */
    public static final int SCAN_INTERVAL_TICKS = 10;

    /** Czy blad skanu zostal juz zaraportowany (zeby nie spamowac logu). */
    private boolean scanFailureLogged;

    public ConnectedEndpointInfo(BlockPos pos, Direction accessSide, Type type) {
        this.pos = pos;
        this.accessSide = accessSide;
        this.chunkPos = new ChunkPos(pos);
        this.type = type;
    }

    public BlockPos getPos() {
        return pos;
    }

    public Direction getAccessSide() {
        return accessSide;
    }

    public ChunkPos getChunkPos() {
        return chunkPos;
    }

    public Type getType() {
        return type;
    }

    public Map<Item, Long> getCachedCounts() {
        return cachedCounts;
    }

    /**
     * Odswieza liczniki, ale nie czesciej niz raz na
     * {@link #SCAN_INTERVAL_TICKS} tickow.
     *
     * <p>To wariant dla WSZYSTKICH odczytow tla (cache, GUI, wyswietlacze).
     * Stan starszy o pol sekundy jest dla nich w zupelnosci wystarczajacy,
     * a oszczedza skanowanie calej sieci kilka razy na sekunde.
     */
    public void refreshIfLoadedThrottled(ServerLevel level, long gameTime) {
        // `gameTime >= lastScanTick` nie jest zbedne: gdyby czas swiata cofnal sie
        // (wczytanie starszego save'a), roznica bylaby UJEMNA, a wiec mniejsza
        // od interwalu - i skan bylby pomijany bez konca.
        if (lastScanTick != Long.MIN_VALUE
                && gameTime >= lastScanTick
                && gameTime - lastScanTick < SCAN_INTERVAL_TICKS) {
            return;
        }
        lastScanTick = gameTime;
        refreshIfLoaded(level);
    }

    /** Wymusza skan teraz (do operacji, ktore musza widziec stan na zywo). */
    public void forceRefresh(ServerLevel level, long gameTime) {
        lastScanTick = gameTime;
        refreshIfLoaded(level);
    }

    /**
     * Uniewaznia zapamietane liczniki I pozwala na natychmiastowy ponowny skan.
     *
     * <p><b>To jest wazne.</b> Samo wyczyszczenie mapy nie wystarczy: throttle
     * w {@link #refreshIfLoadedThrottled} blokowal skan przez kolejne 10 tickow,
     * wiec przez pol sekundy endpoint raportowal ZERO itemow zamiast po prostu
     * nieaktualnych. A {@link VelocePipeNetwork#invalidateEndpointCache()} leci
     * przy kazdej zmianie sasiedztwa sieci.
     *
     * <p>Zerowanie {@code lastScanTick} mowi "ten wpis jest niewazny, zeskanuj
     * go przy nastepnym pytaniu".
     */
    public void invalidateCache() {
        cachedCounts.clear();
        lastScanTick = Long.MIN_VALUE;
    }

    public void refreshIfLoaded(ServerLevel level) {
        if (!level.isLoaded(pos)) {
            return;
        }

        if (type == Type.REFINED_STORAGE) {
            Map<Item, Long> rsCounts = RefinedStorageHelper.getRSItemCounts(level, pos, accessSide);
            cachedCounts.clear();
            cachedCounts.putAll(rsCounts);
            return;
        }

        // Standard INVENTORY
        Map<Item, Long> newCounts = new HashMap<>();
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(level, pos, state, be, accessSide);
            if (handler != null) {
                for (int i = 0; i < handler.getSlots(); i++) {
                    ItemStack stack = handler.getStackInSlot(i);
                    if (!stack.isEmpty()) {
                        newCounts.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
                    }
                }
            } else {
                if (be instanceof Container container) {
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        ItemStack stack = container.getItem(i);
                        if (!stack.isEmpty()) {
                            newCounts.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
                        }
                    }
                }
            }
            cachedCounts.clear();
            cachedCounts.putAll(newCounts);
            scanFailureLogged = false;
        } catch (Throwable t) {
            // NIE polykamy tego po cichu.
            //
            // Wczesniej byl tu `catch (Throwable ignored) {}`. Gdy skanowanie
            // inwentarza rzucalo (np. zepsuty magazyn z innego moda),
            // cachedCounts zostawalo ze STARYMI wartosciami - gracz widzial
            // nieaktualne liczby i nie mial jak zgadnac dlaczego. Teraz
            // przynajmniej raz na endpoint mowimy o tym w logu.
            if (!scanFailureLogged) {
                scanFailureLogged = true;
                VeloceLog.Network.failure(VeloceLog.Side.SERVER,
                        "endpoint scan failed at %s (type=%s) - counts stay stale: %s",
                        pos, type, t);
            }
        }
    }

    public ItemStack extractItem(ServerLevel level, Item item, int maxCount) {
        if (cachedCounts.getOrDefault(item, 0L) <= 0) {
            return ItemStack.EMPTY;
        }

        if (type == Type.REFINED_STORAGE) {
            ItemStack extracted = RefinedStorageHelper.extractItem(level, pos, accessSide, new ItemStack(item), maxCount);
            if (!extracted.isEmpty()) {
                refreshIfLoaded(level);
            }
            return extracted;
        }

        // INVENTORY
        boolean wasLoaded = level.isLoaded(pos);
        long chunkKey = ChunkPos.asLong(chunkPos.x, chunkPos.z);
        if (!wasLoaded) {
            // Podczas zapisu/zamykania swiata NIE wymuszamy chunku. Zrobienie
            // tego zawiesza zapis: Minecraft probuje chunk rozladowac, my go
            // znowu ladujemy, i tak w kolko (w logu: tysiace cykli
            // "loaded/unloaded" w trakcie "Saving worlds").
            if (VeloceChunkLoader.isFrozen()) {
                return ItemStack.EMPTY;
            }
            // Przez globalny loader: surowe setChunkForced(false) w finally
            // zabieralo chunk sieciom, ktore nadal go trzymaly - i napedzalo
            // petle load/unload.
            VeloceChunkLoader.retain(level, chunkKey);
            level.getChunkSource().getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true);
        }

        ItemStack result = ItemStack.EMPTY;
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(level, pos, state, be, accessSide);
            if (handler != null) {
                int needed = maxCount;
                for (int i = 0; i < handler.getSlots(); i++) {
                    ItemStack inSlot = handler.getStackInSlot(i);
                    if (!inSlot.isEmpty() && inSlot.getItem() == item) {
                        ItemStack extracted = handler.extractItem(i, needed, false);
                        if (!extracted.isEmpty()) {
                            if (result.isEmpty()) {
                                result = extracted.copy();
                            } else {
                                result.grow(extracted.getCount());
                            }
                            needed -= extracted.getCount();
                            if (needed <= 0) break;
                        }
                    }
                }
            } else {
                if (be instanceof Container container) {
                    int needed = maxCount;
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        ItemStack inSlot = container.getItem(i);
                        if (!inSlot.isEmpty() && inSlot.getItem() == item) {
                            int toTake = Math.min(needed, inSlot.getCount());
                            ItemStack taken = container.removeItem(i, toTake);
                            if (!taken.isEmpty()) {
                                if (result.isEmpty()) {
                                    result = taken.copy();
                                } else {
                                    result.grow(taken.getCount());
                                }
                                needed -= taken.getCount();
                                if (needed <= 0) break;
                            }
                        }
                    }
                }
            }

            refreshIfLoaded(level);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (!wasLoaded) {
                VeloceChunkLoader.release(level, chunkKey);
            }
        }

        return result;
    }

    /**
     * Wklada item do tego endpointu (odwrotnosc {@link #extractItem}).
     *
     * <p>Uzywane przez auto-crafter do odkładania wynikow craftowania.
     * Obsluguje te same trzy rodzaje magazynow co ekstrakcja: Refined Storage,
     * NeoForge ItemHandler oraz vanilla Container.
     *
     * @return true, jesli udalo sie wlozyc cala stacke
     */
    /**
     * Wklada ile sie da i zwraca RESZTE.
     *
     * <p><b>Po co zostaw pozostaly stos, a nie sam boolean.</b> Poprzednia
     * wersja zwracala {@code remaining.isEmpty()}, wiec przy CZESCIOWYM
     * przyjeciu (np. beczka prawie pelna) mowila "nie udalo sie" - mimo ze
     * czesc itemow juz fizycznie weszla. Wolajacy nie zabieral wtedy niczego
     * graczowi, a itemy byly juz w magazynie: DUPLIKACJA.
     *
     * @return to, czego NIE udalo sie wlozyc (EMPTY gdy wszystko przyjete)
     */
    public ItemStack insertItemLeftover(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        if (type == Type.REFINED_STORAGE) {
            ItemStack left = RefinedStorageHelper.insertItemLeftover(level, pos, accessSide, stack);
            if (left.getCount() != stack.getCount()) {
                refreshIfLoaded(level);
            }
            return left;
        }

        boolean wasLoaded = level.isLoaded(pos);
        long chunkKey = ChunkPos.asLong(chunkPos.x, chunkPos.z);
        if (!wasLoaded) {
            // Bez wymuszania przy zapisie swiata - patrz extractItem.
            if (VeloceChunkLoader.isFrozen()) {
                return stack;
            }
            VeloceChunkLoader.retain(level, chunkKey);
            level.getChunkSource().getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true);
        }

        ItemStack remaining = stack.copy();
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(level, pos, state, be, accessSide);
            if (handler != null) {
                for (int i = 0; i < handler.getSlots() && !remaining.isEmpty(); i++) {
                    remaining = handler.insertItem(i, remaining, false);
                }
            } else if (be instanceof Container container) {
                for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
                    ItemStack inSlot = container.getItem(i);
                    int max = Math.min(container.getMaxStackSize(), remaining.getMaxStackSize());
                    if (inSlot.isEmpty()) {
                        int move = Math.min(max, remaining.getCount());
                        container.setItem(i, remaining.split(move));
                    } else if (ItemStack.isSameItemSameComponents(inSlot, remaining)) {
                        int space = max - inSlot.getCount();
                        if (space > 0) {
                            int move = Math.min(space, remaining.getCount());
                            inSlot.grow(move);
                            remaining.shrink(move);
                            container.setChanged();
                        }
                    }
                }
            }

            refreshIfLoaded(level);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (!wasLoaded) {
                VeloceChunkLoader.release(level, chunkKey);
            }
        }
        return remaining;
    }

    /** Zgodnosc: true gdy wszystko przyjete. */
    public boolean insertItem(ServerLevel level, ItemStack stack) {
        return insertItemLeftover(level, stack).isEmpty();
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Pos", pos.asLong());
        tag.putInt("Side", accessSide.ordinal());
        tag.putString("Type", type.name());

        ListTag list = new ListTag();
        for (Map.Entry<Item, Long> entry : cachedCounts.entrySet()) {
            if (entry.getValue() <= 0) continue;
            CompoundTag itemTag = new CompoundTag();
            itemTag.putString("id", BuiltInRegistries.ITEM.getKey(entry.getKey()).toString());
            itemTag.putLong("cnt", entry.getValue());
            list.add(itemTag);
        }
        tag.put("Cache", list);
        return tag;
    }

    public static ConnectedEndpointInfo fromNbt(CompoundTag tag) {
        BlockPos pos = BlockPos.of(tag.getLong("Pos"));
        Direction side = Direction.values()[tag.getInt("Side")];
        Type type = Type.valueOf(tag.getString("Type"));

        // Bufor craftera ma wlasna implementacje (czyta z bufora bloku,
        // a nie z zasobnika w swiecie) - trzeba ją odtworzyc po restarcie.
        ConnectedEndpointInfo info = type == Type.CRAFTING_BUFFER
                ? new CraftingBufferEndpoint(pos, side)
                : new ConnectedEndpointInfo(pos, side, type);
        ListTag list = tag.getList("Cache", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag itemTag = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(itemTag.getString("id"));
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                Item item = BuiltInRegistries.ITEM.get(id);
                long count = itemTag.getLong("cnt");
                info.cachedCounts.put(item, count);
            }
        }
        return info;
    }
}
