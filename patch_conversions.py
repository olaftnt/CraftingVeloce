import re

with open("neoforge/src/main/java/com/craftingveloce/block/VeloceIntegraleConversions.java", "r") as f:
    content = f.read()

content = content.replace("add(Blocks.DISPENSER, () -> VeloceRegistry.VELOCE_EXTRACTOR.get());",
                          "add(Blocks.DISPENSER, () -> VeloceRegistry.VELOCE_EXTRACTOR.get());\n        add(Blocks.HOPPER, () -> VeloceRegistry.VELOCE_IMPORTER.get());")

with open("neoforge/src/main/java/com/craftingveloce/block/VeloceIntegraleConversions.java", "w") as f:
    f.write(content)
