import re

with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceTerminalBlockEntity.java", "r") as f:
    content = f.read()

# Replace in storeFromPlayer
content = content.replace(
    "for (int i = 0; i < inv.getContainerSize(); i++) {",
    "for (int i = 0; i < 36; i++) {"
)

# Replace in stacksToStore
content = content.replace(
    "for (int i = includeHotbar ? 0 : 9; i < inv.getContainerSize(); i++) {",
    "for (int i = includeHotbar ? 0 : 9; i < 36; i++) {"
)

with open("neoforge/src/main/java/com/craftingveloce/block/entity/VeloceTerminalBlockEntity.java", "w") as f:
    f.write(content)
