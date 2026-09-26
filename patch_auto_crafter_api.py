import re

with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "r") as f:
    content = f.read()

content = content.replace(
"""    public static CraftResult ensureAvailable(ServerLevel level, VelocePipeNetwork network,
                                              Item item, int count, Context ctx) {
        return ensureAvailable(level, network, item, count, ctx, CRAFT_PLAN_BUDGET_NS);
    }""",
"""    public static CraftResult ensureAvailable(ServerLevel level, VelocePipeNetwork network,
                                              net.minecraft.world.item.ItemStack requestedTarget, int count, Context ctx) {
        return ensureAvailable(level, network, requestedTarget, count, ctx, CRAFT_PLAN_BUDGET_NS);
    }"""
)

content = content.replace(
"""    public static CraftResult ensureAvailable(ServerLevel level, VelocePipeNetwork network,
                                              Item item, int count, Context ctx, long timeLimitNs) {""",
"""    public static CraftResult ensureAvailable(ServerLevel level, VelocePipeNetwork network,
                                              net.minecraft.world.item.ItemStack requestedTarget, int count, Context ctx, long timeLimitNs) {
        Item item = requestedTarget.getItem();"""
)

content = content.replace(
"""        if (!plan(level, ctx.network, ctx.enabledItems, ctx.preferred, new net.minecraft.world.item.ItemStack(item), missing, stock, plan, new HashSet<>(), 0)) {""",
"""        if (!plan(level, ctx.network, ctx.enabledItems, ctx.preferred, requestedTarget, missing, stock, plan, new HashSet<>(), 0)) {"""
)

with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "w") as f:
    f.write(content)
