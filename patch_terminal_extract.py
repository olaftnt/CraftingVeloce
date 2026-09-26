import re

with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceTerminalBlockEntity.java", "r") as f:
    content = f.read()

content = content.replace(
"""        ItemStack fromBuffer = extractFromBuffers(buffers, item, count);""",
"""        ItemStack fromBuffer = extractFromBuffers(buffers, requested, count);"""
)

content = content.replace(
"""    private ItemStack extractFromBuffers(List<VeloceCrafterBlockEntity> buffers, Item item, int count) {
        ItemStack result = ItemStack.EMPTY;
        int needed = count;
        for (VeloceCrafterBlockEntity crafter : buffers) {
            ItemStack extracted = crafter.extractResult(item, needed);""",
"""    private ItemStack extractFromBuffers(List<VeloceCrafterBlockEntity> buffers, net.minecraft.world.item.ItemStack requested, int count) {
        ItemStack result = ItemStack.EMPTY;
        int needed = count;
        Item item = com.craftingveloce.util.VelocePotionMapper.getProxy(requested);
        for (VeloceCrafterBlockEntity crafter : buffers) {
            ItemStack extracted = crafter.extractResult(requested, needed);"""
)

with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceTerminalBlockEntity.java", "w") as f:
    f.write(content)

with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceCrafterBlockEntity.java", "r") as f:
    content = f.read()

content = content.replace(
"""    public ItemStack extractResult(Item item, int maxCount) {
        ItemStack result = ItemStack.EMPTY;
        int needed = maxCount;
        for (int i = 0; i < OUTPUT_SLOTS && needed > 0; i++) {
            ItemStack inSlot = inventory.getItem(i);
            if (!inSlot.isEmpty() && com.craftingveloce.util.VelocePotionMapper.getProxy(inSlot) == item) {""",
"""    public ItemStack extractResult(net.minecraft.world.item.ItemStack requested, int maxCount) {
        ItemStack result = ItemStack.EMPTY;
        int needed = maxCount;
        Item item = com.craftingveloce.util.VelocePotionMapper.getProxy(requested);
        for (int i = 0; i < OUTPUT_SLOTS && needed > 0; i++) {
            ItemStack inSlot = inventory.getItem(i);
            if (!inSlot.isEmpty() && com.craftingveloce.util.VelocePotionMapper.getProxy(inSlot) == item) {
                if (requested.hasComponents() && !net.minecraft.world.item.ItemStack.isSameItemSameComponents(inSlot, requested)) continue;"""
)

with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceCrafterBlockEntity.java", "w") as f:
    f.write(content)
