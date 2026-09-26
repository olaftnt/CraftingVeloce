import re

with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "r") as f:
    content = f.read()

# Replace Set<Item> visiting with Set<String> visiting everywhere
content = content.replace("Set<Item> visiting", "Set<String> visiting")

# In plan(...) method, we have `targetStack`
# We need to change how visiting works here
plan_sig = "plan(ServerLevel level, VelocePipeNetwork network,\n                                Set<Item> enabled,\n                                Map<Item, ResourceLocation> preferred,\n                                net.minecraft.world.item.ItemStack targetStack, long amount,\n                                Map<Item, Long> stock, Plan plan, Set<String> visiting, int depth)"

# Find the body of plan()
plan_body_start = content.find(plan_sig)
if plan_body_start != -1:
    body_end = content.find("private static boolean planRecipe(", plan_body_start)
    plan_body = content[plan_body_start:body_end]
    
    # We replace `if (!visiting.add(item))` with string key
    new_plan_body = plan_body.replace(
        "if (!visiting.add(item)) {",
        "String stackKey = item.toString() + targetStack.getComponents().hashCode();\n        if (!visiting.add(stackKey)) {"
    )
    new_plan_body = new_plan_body.replace("visiting.remove(item);", "visiting.remove(stackKey);")
    
    content = content[:plan_body_start] + new_plan_body + content[body_end:]


# In other methods (collectMissing, planSnapshot, etc.) they only have `Item item`.
# They can just use `item.toString()`
content = content.replace("!visiting.add(item)", "!visiting.add(item.toString())")
content = content.replace("visiting.remove(item)", "visiting.remove(item.toString())")

with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "w") as f:
    f.write(content)
