import re

with open("neoforge/src/main/java/com/craftingveloce/network/pipe/ConnectedEndpointInfo.java", "r") as f:
    content = f.read()

# I need to completely remove the bad extractItem(ItemStack) I added, and add a correct one.
# First, remove the bad one:
bad_extract = """    public ItemStack extractItem(ServerLevel level, net.minecraft.world.item.ItemStack requested, int maxCount) {
        Item item = requested.getItem();
        if (cachedCounts.getOrDefault(item, 0L) <= 0) {
            return ItemStack.EMPTY;
        }
        if (type == Type.REFINED_STORAGE) {
            return RefinedStorageHelper.extractItem(level, pos, accessSide,
                    requested, maxCount);
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
    }"""
content = content.replace(bad_extract, "")

# Now read the original extractItem(ServerLevel level, Item item, int maxCount) and clone it to take ItemStack
original_extract_pattern = re.compile(r"    public ItemStack extractItem\(ServerLevel level, Item item, int maxCount\) \{(.*?)\n    }", re.DOTALL)
match = original_extract_pattern.search(content)

original_body = match.group(1)
new_body = original_body.replace("extractNow(level, item, maxCount)", "extractNow(level, requested, maxCount)")

new_extract = f"""    public ItemStack extractItem(ServerLevel level, net.minecraft.world.item.ItemStack requested, int maxCount) {{
        Item item = requested.getItem();{new_body}
    }}"""

# Add it right before the original one
content = content.replace("    public ItemStack extractItem(ServerLevel level, Item item, int maxCount) {",
                          new_extract + "\n\n    public ItemStack extractItem(ServerLevel level, Item item, int maxCount) {")

with open("neoforge/src/main/java/com/craftingveloce/network/pipe/ConnectedEndpointInfo.java", "w") as f:
    f.write(content)
