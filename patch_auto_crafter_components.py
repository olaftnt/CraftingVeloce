import re

with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "r") as f:
    content = f.read()

# 1. Change plan signature to take ItemStack targetStack
content = content.replace(
"""    private static boolean plan(ServerLevel level, VelocePipeNetwork network,
                                Set<Item> enabled,
                                Map<Item, ResourceLocation> preferred,
                                Item item, long amount,
                                Map<Item, Long> stock, Plan plan, Set<Item> visiting, int depth) {""",
"""    private static boolean plan(ServerLevel level, VelocePipeNetwork network,
                                Set<Item> enabled,
                                Map<Item, ResourceLocation> preferred,
                                net.minecraft.world.item.ItemStack targetStack, long amount,
                                Map<Item, Long> stock, Plan plan, Set<Item> visiting, int depth) {
        Item item = targetStack.getItem();"""
)

# 2. Update callers of plan in VeloceAutoCrafter.java

content = content.replace(
"""        if (!plan(level, ctx.network, ctx.enabledItems, ctx.preferred, item, missing, stock, plan, new HashSet<>(), 0)) {""",
"""        if (!plan(level, ctx.network, ctx.enabledItems, ctx.preferred, new net.minecraft.world.item.ItemStack(item), missing, stock, plan, new HashSet<>(), 0)) {"""
)

content = content.replace(
"""                    if (plan(level, network, enabled, preferred, optItem, lacking, stock, plan, visiting, depth + 1)) {""",
"""                    if (plan(level, network, enabled, preferred, opt, lacking, stock, plan, visiting, depth + 1)) {"""
)

content = content.replace(
"""            boolean ok = plan(level, network, enabled, preferred, item, amount, copy, candidate,""",
"""            boolean ok = plan(level, network, enabled, preferred, new net.minecraft.world.item.ItemStack(item), amount, copy, candidate,"""
)

content = content.replace(
"""            if (plan(level, network, enabled, preferred, item, mid, copy, candidate,""",
"""            if (plan(level, network, enabled, preferred, new net.minecraft.world.item.ItemStack(item), mid, copy, candidate,"""
)

# 3. Change orderRecipes to take targetStack
content = content.replace(
"""                    orderRecipes(level, network, item, preferred, plan.heatRemaining > 0,
                            network.prefersFurnace(item));""",
"""                    orderRecipes(level, network, targetStack, preferred, plan.heatRemaining > 0,
                            network.prefersFurnace(item));"""
)

content = content.replace(
"""    private static List<ProcessingEntry> orderRecipes(
            ServerLevel level, VelocePipeNetwork network, Item item,
            Map<Item, ResourceLocation> preferred,
            boolean heatAvailable, boolean furnaceFirst) {
        List<ProcessingEntry> all = allRecipesFor(level, network, item, heatAvailable);""",
"""    private static List<ProcessingEntry> orderRecipes(
            ServerLevel level, VelocePipeNetwork network, net.minecraft.world.item.ItemStack targetStack,
            Map<Item, ResourceLocation> preferred,
            boolean heatAvailable, boolean furnaceFirst) {
        Item item = targetStack.getItem();
        List<ProcessingEntry> allUnfiltered = allRecipesFor(level, network, item, heatAvailable);
        List<ProcessingEntry> all = new ArrayList<>();
        for (ProcessingEntry e : allUnfiltered) {
            ItemStack primary = e.primaryResult();
            if (targetStack.hasComponents()) {
                if (ItemStack.isSameItemSameComponents(primary, targetStack)) {
                    all.add(e);
                }
            } else {
                all.add(e);
            }
        }"""
)

with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "w") as f:
    f.write(content)
