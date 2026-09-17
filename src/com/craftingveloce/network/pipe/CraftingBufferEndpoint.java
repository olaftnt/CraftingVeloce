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
 * A network endpoint representing the auto-crafter's buffer.
 *
 * <p>The buffer is not an ordinary inventory in the world (it is the block's
 * scratch memory), so the standard scan will not find it. This endpoint makes
 * the production surplus <b>a normal part of the network</b>:
 * <ul>
 *   <li>it is visible in the terminal (the stock counter),</li>
 *   <li>it can be pulled out with the terminal, a pipe, a hopper, an extractor.</li>
 * </ul>
 *
 * <p>It extends {@link ConnectedEndpointInfo} in order to reuse the ready-made
 * counter caching - we only override the read and the extraction.
 */
public class CraftingBufferEndpoint extends ConnectedEndpointInfo {

    public CraftingBufferEndpoint(BlockPos pos, Direction accessSide) {
        super(pos, accessSide, Type.CRAFTING_BUFFER);
    }

    /** The buffer behind this endpoint, or null when the block is gone. */
    private VeloceCraftingBuffer buffer(ServerLevel level) {
        // Without loading the chunk: this buffer tends to be called on EVERY
        // insert/extract attempt, so getBlockEntity on an unloaded chunk would
        // create a load/unload loop (see VeloceChunkLoader.blockEntityIfLoaded).
        BlockEntity be = com.craftingveloce.network.pipe.VeloceChunkLoader
                .blockEntityIfLoaded(level, getPos());
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
            return stack;   // no buffer - nothing was accepted
        }
        ItemStack leftover = buf.insert(stack);
        refreshIfLoaded(level);
        return leftover;
    }
}
