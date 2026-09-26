import re

with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "r") as f:
    content = f.read()

# 1. Add static method getKey
key_method = """    private static String getKey(net.minecraft.world.item.ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString() + ":" + stack.getComponents().hashCode();
    }"""
content = content.replace("public static final class Plan {", key_method + "\n\n    static final class Plan {")

# 2. Change failedAmounts to String
content = content.replace("final java.util.Map<net.minecraft.world.item.Item, FailedAttempt> failedAmounts = new java.util.HashMap<>();",
                          "final java.util.Map<String, FailedAttempt> failedAmounts = new java.util.HashMap<>();")

# 3. Change visiting type in plan method signatures
content = content.replace("Map<Item, Long> stock, Plan plan, Set<Item> visiting, int depth)",
                          "Map<Item, Long> stock, Plan plan, Set<String> visiting, int depth)")
# There might be multiple plan and planRecipe methods, replace broadly:
content = content.replace("Set<Item> visiting", "Set<String> visiting")

# 4. Change plan() implementation to use String
# From:
# Plan.FailedAttempt failure = plan.failedAmounts.get(item);
# ...
# if (!visiting.add(item)) {
# ...
# plan.failedAmounts.put(item, new Plan.FailedAttempt(amount, plan.currentStockId));
# ...
# visiting.remove(item);

plan_changes = [
    ("Plan.FailedAttempt failure = plan.failedAmounts.get(item);", 
     "String stackKey = getKey(targetStack);\n        Plan.FailedAttempt failure = plan.failedAmounts.get(stackKey);"),
    ("if (!visiting.add(item)) {", "if (!visiting.add(stackKey)) {"),
    ("plan.failedAmounts.put(item, new Plan.FailedAttempt(amount, plan.currentStockId));",
     "plan.failedAmounts.put(stackKey, new Plan.FailedAttempt(amount, plan.currentStockId));"),
    ("visiting.remove(item);", "visiting.remove(stackKey);")
]

for orig, new in plan_changes:
    content = content.replace(orig, new)


with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "w") as f:
    f.write(content)

