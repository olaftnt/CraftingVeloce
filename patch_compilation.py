import re

# 1. Fix VeloceImporterBlock
with open("neoforge/src/main/java/com/craftingveloce/block/VeloceImporterBlock.java", "r") as f:
    content = f.read()

content = content.replace("public class VeloceImporterBlock extends BaseEntityBlock implements VeloceNetworkNode {",
                          "public class VeloceImporterBlock extends BaseEntityBlock implements VeloceNetworkNode {\n    @Override\n    public boolean canConnectFrom(BlockState state, net.minecraft.core.Direction side) {\n        return true;\n    }")

with open("neoforge/src/main/java/com/craftingveloce/block/VeloceImporterBlock.java", "w") as f:
    f.write(content)

# 2. Fix VeloceRegistry
with open("neoforge/src/main/java/com/craftingveloce/init/VeloceRegistry.java", "r") as f:
    content = f.read()

content = content.replace("com.craftingveloce.block.VeloceImporterBlock::new\n    );",
                          "() -> new com.craftingveloce.block.VeloceImporterBlock()\n    );")

with open("neoforge/src/main/java/com/craftingveloce/init/VeloceRegistry.java", "w") as f:
    f.write(content)
