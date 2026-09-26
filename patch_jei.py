import re

with open("neoforge/src/main/java/com/craftingveloce/compat/alchemistry/AlchemistryJeiCatalysts.java", "r") as f:
    content = f.read()

# Remove the dissolver catalyst
content = content.replace('VeloceJeiCatalysts.register("alchemistry:dissolver",\n                () -> AlchemistryBlocks.VELOCE_DISSOLVER_MODULE_ITEM.get());', '// Removed because JEI does not support filtering catalysts per-recipe, so it would falsely advertise that the Veloce module can dissolve everything.')

with open("neoforge/src/main/java/com/craftingveloce/compat/alchemistry/AlchemistryJeiCatalysts.java", "w") as f:
    f.write(content)
