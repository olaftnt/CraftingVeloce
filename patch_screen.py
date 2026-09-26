import re
with open("neoforge/src/main/java/com/craftingveloce/client/gui/VeloceImporterScreen.java", "r") as f:
    content = f.read()

content = content.replace('ResourceLocation.fromNamespaceAndPath(CraftingVeloceMod.MODID, "textures/gui/importer.png");',
    'ResourceLocation.withDefaultNamespace("textures/gui/container/dispenser.png");')

with open("neoforge/src/main/java/com/craftingveloce/client/gui/VeloceImporterScreen.java", "w") as f:
    f.write(content)
