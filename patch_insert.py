import re

with open("neoforge/src/main/java/com/craftingveloce/network/pipe/VelocePipeNetwork.java", "r") as f:
    content = f.read()

original_insert = """    public ItemStack insertIntoStorage(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        for (ConnectedEndpointInfo endpoint : endpoints.values()) {
            if (remaining.isEmpty()) {
                break;
            }
            if (endpoint.getType() == ConnectedEndpointInfo.Type.CRAFTING_BUFFER) {
                continue;   // a crafter buffer is not a storage
            }
            remaining = endpoint.insertItemLeftover(level, remaining);
        }
        if (remaining.getCount() < stack.getCount()) {
            invalidateAggregateCache();
        }
        return remaining;
    }"""

new_insert = """    public ItemStack insertIntoStorage(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        
        // Pass 1: Try to insert into endpoints that already contain this item (by proxy)
        Item proxyItem = com.craftingveloce.util.VelocePotionMapper.getProxy(stack);
        for (ConnectedEndpointInfo endpoint : endpoints.values()) {
            if (remaining.isEmpty()) break;
            if (endpoint.getType() == ConnectedEndpointInfo.Type.CRAFTING_BUFFER) continue;
            if (endpoint.getCachedCounts().getOrDefault(proxyItem, 0L) > 0) {
                remaining = endpoint.insertItemLeftover(level, remaining);
            }
        }
        
        // Pass 2: Insert into any available endpoint
        for (ConnectedEndpointInfo endpoint : endpoints.values()) {
            if (remaining.isEmpty()) break;
            if (endpoint.getType() == ConnectedEndpointInfo.Type.CRAFTING_BUFFER) continue;
            remaining = endpoint.insertItemLeftover(level, remaining);
        }
        
        if (remaining.getCount() < stack.getCount()) {
            invalidateAggregateCache();
        }
        return remaining;
    }"""

content = content.replace(original_insert, new_insert)

with open("neoforge/src/main/java/com/craftingveloce/network/pipe/VelocePipeNetwork.java", "w") as f:
    f.write(content)

