import re

with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceTerminalBlockEntity.java", "r") as f:
    content = f.read()

content = content.replace(
"""        var result = com.craftingveloce.crafting.VeloceAutoCrafter
                .ensureAvailable(sl, net, item, count, ctx);""",
"""        var result = com.craftingveloce.crafting.VeloceAutoCrafter
                .ensureAvailable(sl, net, requested, count, ctx);"""
)
with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceTerminalBlockEntity.java", "w") as f:
    f.write(content)

with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceExtractorBlockEntity.java", "r") as f:
    content = f.read()

content = content.replace(
"""        var result = com.craftingveloce.crafting.VeloceAutoCrafter
                .ensureAvailable(sl, net, item, missing, ctx);""",
"""        var result = com.craftingveloce.crafting.VeloceAutoCrafter
                .ensureAvailable(sl, net, new net.minecraft.world.item.ItemStack(item), missing, ctx);"""
)
with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceExtractorBlockEntity.java", "w") as f:
    f.write(content)
