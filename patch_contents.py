import re

with open("neoforge/src/main/java/com/craftingveloce/block/VeloceCaseContents.java", "r") as f:
    content = f.read()

content = content.replace("add(() -> VeloceRegistry.VELOCE_EXTRACTOR.get(), () -> Blocks.DISPENSER);",
                          "add(() -> VeloceRegistry.VELOCE_EXTRACTOR.get(), () -> Blocks.DISPENSER);\n        add(() -> VeloceRegistry.VELOCE_IMPORTER.get(), () -> Blocks.HOPPER);")

with open("neoforge/src/main/java/com/craftingveloce/block/VeloceCaseContents.java", "w") as f:
    f.write(content)
