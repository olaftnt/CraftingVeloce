import re

with open("neoforge/src/main/java/com/craftingveloce/network/pipe/ConnectedEndpointInfo.java", "r") as f:
    content = f.read()

# 1. Add extractItem(ServerLevel, ItemStack, int) right before extractItem(ServerLevel, Item, int)
original_extract = """    public ItemStack extractItem(ServerLevel level, Item item, int maxCount) {"""
new_extract = """    public ItemStack extractItem(ServerLevel level, net.minecraft.world.item.ItemStack requested, int maxCount) {
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
            if (level.getGameTime() < opLoadBlockedUntilTick) {
                return ItemStack.EMPTY;
            }
            if (VeloceChunkLoader.isFrozen()) {
                return ItemStack.EMPTY;
            }
            if (!VeloceChunkLoader.tryReserveOpLoad(level)) {
                return ItemStack.EMPTY;
            }
            VeloceChunkLoader.noteOpLoad(level, chunkKey, pos, "extraction");
        }

        ItemStack extracted = extractNow(level, requested, maxCount);
        if (!extracted.isEmpty()) {
            applyCachedDelta(item, -extracted.getCount());
            if (!wasLoaded) {
                refreshIfLoaded(level);
            }
        }
        return extracted;
    }

    public ItemStack extractItem(ServerLevel level, Item item, int maxCount) {"""
content = content.replace(original_extract, new_extract)


# 2. Add extractNow(ServerLevel, ItemStack, int) right before extractNow(ServerLevel, Item, int)
original_extract_now = """    public ItemStack extractNow(ServerLevel level, Item item, int maxCount) {"""
new_extract_now = """    public ItemStack extractNow(ServerLevel level, net.minecraft.world.item.ItemStack requested, int maxCount) {
        Item item = requested.getItem();
        if (type == Type.REFINED_STORAGE) {
            return RefinedStorageHelper.extractItem(level, pos, accessSide,
                    requested, maxCount);
        }
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            net.neoforged.neoforge.items.IItemHandler handler = level.getCapability(
                    net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, state, be, accessSide);
            if (handler != null) {
                ItemStack result = ItemStack.EMPTY;
                int needed = maxCount;
                for (int i = 0; i < handler.getSlots() && needed > 0; i++) {
                    ItemStack inSlot = handler.getStackInSlot(i);
                    if (!inSlot.isEmpty() && com.craftingveloce.util.VelocePotionMapper.getProxy(inSlot) == item) {
                        if (!requested.getComponents().isEmpty() && !net.minecraft.world.item.ItemStack.isSameItemSameComponents(inSlot, requested)) continue;
                        if (!canMergeInto(result, inSlot)) {
                            continue;
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
            if (be instanceof net.minecraft.world.Container container) {
                ItemStack result = ItemStack.EMPTY;
                int needed = maxCount;
                for (int i = 0; i < container.getContainerSize() && needed > 0; i++) {
                    ItemStack inSlot = container.getItem(i);
                    if (!inSlot.isEmpty() && com.craftingveloce.util.VelocePotionMapper.getProxy(inSlot) == item) {
                        if (!requested.getComponents().isEmpty() && !net.minecraft.world.item.ItemStack.isSameItemSameComponents(inSlot, requested)) continue;
                        if (!canMergeInto(result, inSlot)) {
                            continue;
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
content = content.replace(original_extract_now, new_extract_now)

with open("neoforge/src/main/java/com/craftingveloce/network/pipe/ConnectedEndpointInfo.java", "w") as f:
    f.write(content)


# Also fix ExtractorBlockEntity
with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceExtractorBlockEntity.java", "r") as f:
    content = f.read()
content = content.replace(
"""        var result = com.craftingveloce.crafting.VeloceAutoCrafter
                .ensureAvailable(sl, net, item, count, ctx, CRAFT_PLAN_BUDGET_NS);""",
"""        var result = com.craftingveloce.crafting.VeloceAutoCrafter
                .ensureAvailable(sl, net, new net.minecraft.world.item.ItemStack(item), count, ctx, CRAFT_PLAN_BUDGET_NS);"""
)
with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceExtractorBlockEntity.java", "w") as f:
    f.write(content)

