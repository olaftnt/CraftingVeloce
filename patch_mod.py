import re

with open("neoforge/src/main/java/com/craftingveloce/CraftingVeloceMod.java", "r") as f:
    content = f.read()

content = content.replace("event.register(VeloceRegistry.VELOCE_EXTRACTOR_MENU.get(), com.craftingveloce.client.gui.VeloceExtractorScreen::new);",
                          "event.register(VeloceRegistry.VELOCE_EXTRACTOR_MENU.get(), com.craftingveloce.client.gui.VeloceExtractorScreen::new);\n            event.register(VeloceRegistry.VELOCE_IMPORTER_MENU.get(), com.craftingveloce.client.gui.VeloceImporterScreen::new);")

with open("neoforge/src/main/java/com/craftingveloce/CraftingVeloceMod.java", "w") as f:
    f.write(content)
