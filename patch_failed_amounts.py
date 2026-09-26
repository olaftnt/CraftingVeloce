import re

with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "r") as f:
    content = f.read()

# We need to find the `Plan.FailedAttempt failure = plan.failedAmounts.get(item);`
# and move `String stackKey = item.toString() + targetStack.getComponents().hashCode();` before it,
# then use stackKey.

plan_sig = "plan(ServerLevel level, VelocePipeNetwork network,\n                                Set<Item> enabled,\n                                Map<Item, ResourceLocation> preferred,\n                                net.minecraft.world.item.ItemStack targetStack, long amount,\n                                Map<Item, Long> stock, Plan plan, Set<String> visiting, int depth)"

plan_body_start = content.find("Item item = targetStack.getItem();")

if plan_body_start != -1:
    body_end = content.find("private static boolean planRecipe(", plan_body_start)
    plan_body = content[plan_body_start:body_end]
    
    new_plan_body = plan_body.replace(
        "Item item = targetStack.getItem();\n        if (amount <= 0) {\n            return true;\n        }\n        \n        Plan.FailedAttempt failure = plan.failedAmounts.get(item);",
        "Item item = targetStack.getItem();\n        if (amount <= 0) {\n            return true;\n        }\n        String stackKey = item.toString() + targetStack.getComponents().hashCode();\n        Plan.FailedAttempt failure = plan.failedAmounts.get(stackKey);"
    )
    
    new_plan_body = new_plan_body.replace(
        "plan.failedAmounts.put(item, new Plan.FailedAttempt(amount, plan.currentStockId));",
        "plan.failedAmounts.put(stackKey, new Plan.FailedAttempt(amount, plan.currentStockId));"
    )
    
    content = content[:plan_body_start] + new_plan_body + content[body_end:]

with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "w") as f:
    f.write(content)
