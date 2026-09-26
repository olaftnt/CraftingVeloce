import re

with open("neoforge/src/main/java/com/craftingveloce/network/pipe/VelocePipeNetwork.java", "r") as f:
    content = f.read()

content = content.replace(
"""    public ItemStack extractItem(ServerLevel level, Item item, int maxCount) {""",
"""    public ItemStack extractItem(ServerLevel level, net.minecraft.world.item.ItemStack requested, int maxCount) {
        Item item = requested.getItem();
        for (ConnectedEndpointInfo endpoint : endpoints.values()) {
            if (endpoint.getCachedCounts().getOrDefault(item, 0L) > 0) {
                ItemStack extracted = endpoint.extractItem(level, requested, maxCount);
                if (!extracted.isEmpty()) {
                    invalidateAggregateCache();
                    return extracted;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    public ItemStack extractItem(ServerLevel level, Item item, int maxCount) {"""
)

with open("neoforge/src/main/java/com/craftingveloce/network/pipe/VelocePipeNetwork.java", "w") as f:
    f.write(content)

with open("neoforge/src/main/java/com/craftingveloce/network/pipe/ConnectedEndpointInfo.java", "r") as f:
    content = f.read()

content = content.replace(
"""    public ItemStack extractItem(ServerLevel level, Item item, int maxCount) {""",
"""    public ItemStack extractItem(ServerLevel level, net.minecraft.world.item.ItemStack requested, int maxCount) {
        Item item = requested.getItem();
        if (cachedCounts.getOrDefault(item, 0L) <= 0) {
            return ItemStack.EMPTY;
        }
        if (type == Type.REFINED_STORAGE) {
            ItemStack extracted = extractNow(level, requested, maxCount);
            if (!extracted.isEmpty()) {
                applyCachedDelta(item, -extracted.getCount());
                refreshIfLoaded(level);
            }
            return extracted;
        }
        boolean wasLoaded = level.isLoaded(pos);
        long chunkKey = ChunkPos.asLong(chunkPos.x, chunkPos.z);
        if (!wasLoaded) {
            if (VeloceChunkLoader.canExtract(level, chunkKey)) {
                VeloceChunkLoader.forceTicket(level, chunkKey);
                try {
                    ItemStack extracted = extractNow(level, requested, maxCount);
                    if (!extracted.isEmpty()) {
                        applyCachedDelta(item, -extracted.getCount());
                        refreshIfLoaded(level);
                    }
                    return extracted;
                } finally {
                    VeloceChunkLoader.releaseTicket(level, chunkKey);
                }
            } else {
                return ItemStack.EMPTY;
            }
        }
        ItemStack extracted = extractNow(level, requested, maxCount);
        if (!extracted.isEmpty()) {
            applyCachedDelta(item, -extracted.getCount());
            refreshIfLoaded(level);
        }
        return extracted;
    }

    public ItemStack extractItem(ServerLevel level, Item item, int maxCount) {"""
)

content = content.replace(
"""    public ItemStack extractNow(ServerLevel level, Item item, int maxCount) {""",
"""    public ItemStack extractNow(ServerLevel level, net.minecraft.world.item.ItemStack requested, int maxCount) {
        Item item = requested.getItem();
        if (type == Type.REFINED_STORAGE) {
            return RefinedStorageHelper.extractItem(level, pos, accessSide,
                    com.craftingveloce.util.VelocePotionMapper.toRealPotion(item, 1), maxCount);
        }
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(
                    level, pos, state, be, accessSide);
            if (handler != null) {
                ItemStack result = ItemStack.EMPTY;
                int needed = maxCount;
                for (int i = 0; i < handler.getSlots() && needed > 0; i++) {
                    ItemStack inSlot = handler.getStackInSlot(i);
                    if (!inSlot.isEmpty() && com.craftingveloce.util.VelocePotionMapper.getProxy(inSlot) == item) {
                        if (requested.hasComponents() && !net.minecraft.world.item.ItemStack.isSameItemSameComponents(inSlot, requested)) continue;
                        if (!canMergeInto(result, inSlot)) {
                            continue;   // a DIFFERENT stack of the same item - leave it alone
                        }
                        ItemStack extracted = handler.extractItem(i, needed, false);
                        if (!extracted.isEmpty()) {
                            result = result.isEmpty() ? extracted.copy() : grow(result, extracted);
                            needed -= extracted.getCount();
                        }
                    }
                }
                return result;
            }
            if (be instanceof Container container) {
                ItemStack result = ItemStack.EMPTY;
                int needed = maxCount;
                for (int i = 0; i < container.getContainerSize() && needed > 0; i++) {
                    ItemStack inSlot = container.getItem(i);
                    if (!inSlot.isEmpty() && com.craftingveloce.util.VelocePotionMapper.getProxy(inSlot) == item) {
                        if (requested.hasComponents() && !net.minecraft.world.item.ItemStack.isSameItemSameComponents(inSlot, requested)) continue;
                        if (!canMergeInto(result, inSlot)) {
                            continue;   // a DIFFERENT stack of the same item - leave it alone
                        }
                        int toTake = Math.min(needed, inSlot.getCount());
                        ItemStack taken = container.removeItem(i, toTake);
                        if (!taken.isEmpty()) {
                            result = result.isEmpty() ? taken.copy() : grow(result, taken);
                            needed -= taken.getCount();
                        }
                    }
                }
                if (!result.isEmpty()) {
                    container.setChanged();
                }
                return result;
            }
        } catch (Throwable t) {
            com.craftingveloce.util.VeloceLog.Network.error(com.craftingveloce.util.VeloceLog.Side.SERVER, t,
                    "failed to extract %s from endpoint %s", item, pos);
        }
        return ItemStack.EMPTY;
    }

    public ItemStack extractNow(ServerLevel level, Item item, int maxCount) {"""
)

with open("neoforge/src/main/java/com/craftingveloce/network/pipe/ConnectedEndpointInfo.java", "w") as f:
    f.write(content)

