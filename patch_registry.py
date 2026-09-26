import re

with open("neoforge/src/main/java/com/craftingveloce/init/VeloceRegistry.java", "r") as f:
    content = f.read()

content = content.replace('    public static final DeferredItem<net.minecraft.world.item.BlockItem> VELOCE_IMPORTER_ITEM = ITEMS.registerSimpleBlockItem(\n            "veloce_importer",\n            VELOCE_IMPORTER\n    );\n',
                          '')

with open("neoforge/src/main/java/com/craftingveloce/init/VeloceRegistry.java", "w") as f:
    f.write(content)

