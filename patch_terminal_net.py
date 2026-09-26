import re
with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceTerminalBlockEntity.java", "r") as f:
    content = f.read()

content = content.replace(
"""            Item extractProxy = com.craftingveloce.util.VelocePotionMapper.getProxy(requested);
            ItemStack extracted = net.extractItem(sl, extractProxy, count);""",
"""            Item extractProxy = com.craftingveloce.util.VelocePotionMapper.getProxy(requested);
            ItemStack extracted = net.extractItem(sl, requested, count);"""
)

content = content.replace(
"""        ItemStack extracted = net.extractItem(sl, item, count);""",
"""        ItemStack extracted = net.extractItem(sl, requested, count);"""
)

with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceTerminalBlockEntity.java", "w") as f:
    f.write(content)
