import re

with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "r") as f:
    content = f.read()

content = content.replace("String stackKey = item.toString() + targetStack.getComponents().hashCode();\n        if (!visiting.add(stackKey)) {",
                          "if (!visiting.add(stackKey)) {")

with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "w") as f:
    f.write(content)
