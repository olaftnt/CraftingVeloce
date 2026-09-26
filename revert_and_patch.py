import re
with open("neoforge/src/main/java/com/craftingveloce/network/pipe/ConnectedEndpointInfo.java", "r") as f:
    content = f.read()

# Fix hasComponents()
content = content.replace("requested.hasComponents()", "(!requested.getComponents().isEmpty())")
with open("neoforge/src/main/java/com/craftingveloce/network/pipe/ConnectedEndpointInfo.java", "w") as f:
    f.write(content)

with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "r") as f:
    content = f.read()
content = content.replace("targetStack.hasComponents()", "(!targetStack.getComponents().isEmpty())")
# Fix line 466 requestedTarget -> new ItemStack(item)
content = content.replace("if (!plan(level, ctx.network, ctx.enabledItems, ctx.preferred, requestedTarget, missing, stock, plan, new HashSet<>(), 0)) {",
"if (!plan(level, ctx.network, ctx.enabledItems, ctx.preferred, new net.minecraft.world.item.ItemStack(item), missing, stock, plan, new HashSet<>(), 0)) {")
# Fix line 2734 Item -> ItemStack
content = content.replace("List<ProcessingEntry> recipes = orderRecipes(level, ctx.network, item, ctx.preferred,",
"List<ProcessingEntry> recipes = orderRecipes(level, ctx.network, new net.minecraft.world.item.ItemStack(item), ctx.preferred,")

with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "w") as f:
    f.write(content)

with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceTerminalBlockEntity.java", "r") as f:
    content = f.read()
content = content.replace("requested.hasComponents()", "(!requested.getComponents().isEmpty())")
with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceTerminalBlockEntity.java", "w") as f:
    f.write(content)

