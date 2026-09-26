import re

with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "r") as f:
    content = f.read()

content = content.replace("final java.util.Map<net.minecraft.world.item.Item, FailedAttempt> failedAmounts = new java.util.HashMap<>();",
                          "final java.util.Map<String, FailedAttempt> failedAmounts = new java.util.HashMap<>();")

with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "w") as f:
    f.write(content)
