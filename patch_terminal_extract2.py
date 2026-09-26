import re

with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceTerminalBlockEntity.java", "r") as f:
    content = f.read()

content = content.replace(
"""    private static ItemStack extractFromBuffers(
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
                }""",
"""    private static ItemStack extractFromBuffers(
            java.util.List<com.craftingveloce.inventory.VeloceCraftingBuffer> buffers,
            net.minecraft.world.item.ItemStack requested, int count) {
        Item item = requested.getItem();
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
                if (requested.hasComponents() && !net.minecraft.world.item.ItemStack.isSameItemSameComponents(inSlot, requested)) {
                    continue;
                }"""
)

with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceTerminalBlockEntity.java", "w") as f:
    f.write(content)
