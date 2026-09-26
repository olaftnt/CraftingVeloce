import re

with open("neoforge/src/main/java/com/craftingveloce/CraftingVeloceMod.java", "r") as f:
    content = f.read()

content = content.replace("event.registerBlockEntityRenderer(VeloceRegistry.VELOCE_EXTRACTOR_BE.get(),\n                            com.craftingveloce.client.render.VeloceCaseRenderer::new);",
                          "event.registerBlockEntityRenderer(VeloceRegistry.VELOCE_EXTRACTOR_BE.get(),\n                            com.craftingveloce.client.render.VeloceCaseRenderer::new);\n                    event.registerBlockEntityRenderer(VeloceRegistry.VELOCE_IMPORTER_BE.get(),\n                            com.craftingveloce.client.render.VeloceCaseRenderer::new);")

with open("neoforge/src/main/java/com/craftingveloce/CraftingVeloceMod.java", "w") as f:
    f.write(content)
