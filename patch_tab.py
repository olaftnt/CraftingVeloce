import re

with open("neoforge/src/main/java/com/craftingveloce/CraftingVeloceMod.java", "r") as f:
    content = f.read()

content = content.replace("output.accept(VeloceRegistry.VELOCE_EXTRACTOR_ITEM.get());",
                          "output.accept(VeloceRegistry.VELOCE_EXTRACTOR_ITEM.get());\n                    output.accept(VeloceRegistry.VELOCE_IMPORTER_ITEM.get());")

with open("neoforge/src/main/java/com/craftingveloce/CraftingVeloceMod.java", "w") as f:
    f.write(content)
