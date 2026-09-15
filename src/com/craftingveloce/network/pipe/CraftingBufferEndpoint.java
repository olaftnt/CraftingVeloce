package com.craftingveloce.network.pipe;

import com.craftingveloce.block.entity.VeloceCraftingTableBlockEntity;
import com.craftingveloce.inventory.VeloceCraftingBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Endpoint sieci reprezentujacy bufor auto-craftera.
 *
 * <p>Bufor nie jest zwyklym inventory w swiecie (to pamiec podreczna bloku),
 * wiec standardowe skanowanie go nie znajdzie. Ten endpoint sprawia, ze
 * nadwyzka produkcji jest <b>normalnie czescia sieci</b>:
 * <ul>
 *   <li>widac ja w terminalu (licznik stocku),</li>
 *   <li>mozna ja wyciagnac terminalem, rura, hopperem, extractorem.</li>
 * </ul>
 *
 * <p>Dziedziczy po {@link ConnectedEndpointInfo}, zeby korzystac z gotowego
 * cache'owania licznikow - nadpisujemy tylko odczyt i ekstrakcje.
 */
public class CraftingBufferEndpoint extends ConnectedEndpointInfo {

    public CraftingBufferEndpoint(BlockPos pos, Direction accessSide) {
        super(pos, accessSide, Type.CRAFTING_BUFFER);
    }

    /** Bufor spod tego endpointu, albo null gdy blok zniknal. */
    private VeloceCraftingBuffer buffer(ServerLevel level) {
        BlockEntity be = level.getBlockEntity(getPos());
        if (be instanceof VeloceCraftingTableBlockEntity crafter) {
            return crafter.getBuffer();
        }
        return null;
    }

    @Override
    public void refreshIfLoaded(ServerLevel level) {
        if (!level.isLoaded(getPos())) {
            return;
        }
        VeloceCraftingBuffer buf = buffer(level);
        if (buf == null) {
            return;
        }
        var counts = getCachedCounts();
        counts.clear();
        for (int i = 0; i < buf.getContainerSize(); i++) {
            ItemStack s = buf.getItem(i);
            if (!s.isEmpty()) {
                counts.merge(s.getItem(), (long) s.getCount(), Long::sum);
            }
        }
    }

    @Override
    public ItemStack extractItem(ServerLevel level, Item item, int maxCount) {
        VeloceCraftingBuffer buf = buffer(level);
        if (buf == null) {
            return ItemStack.EMPTY;
        }
        ItemStack result = ItemStack.EMPTY;
        int remaining = maxCount;
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
        refreshIfLoaded(level);
        return result;
    }

    @Override
    public ItemStack insertItemLeftover(ServerLevel level, ItemStack stack) {
        VeloceCraftingBuffer buf = buffer(level);
        if (buf == null) {
            return stack;   // brak bufora - nic nie przyjeto
        }
        ItemStack leftover = buf.insert(stack);
        refreshIfLoaded(level);
        return leftover;
    }
}
