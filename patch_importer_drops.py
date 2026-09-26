import re

with open("neoforge/src/main/java/com/craftingveloce/block/VeloceImporterBlock.java", "r") as f:
    content = f.read()

get_drops = """
    @Override
    public java.util.List<net.minecraft.world.item.ItemStack> getDrops(
            BlockState state, net.minecraft.world.level.storage.loot.LootParams.Builder params) {
        java.util.List<net.minecraft.world.item.ItemStack> out = new java.util.ArrayList<>();
        out.add(new net.minecraft.world.item.ItemStack(
                com.craftingveloce.init.VeloceRegistry.VELOCE_INTEGRALE_ITEM.get()));
        com.craftingveloce.block.VeloceIntegraleConversions.Conversion back = com.craftingveloce.block.VeloceIntegraleConversions.forResult(this);
        if (back != null) {
            net.minecraft.world.item.Item in =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(back.inputId());
            if (in != net.minecraft.world.item.Items.AIR) {
                out.add(new net.minecraft.world.item.ItemStack(in));
            }
        }
        return out;
    }
"""

content = content.replace("public class VeloceImporterBlock extends BaseEntityBlock implements VeloceNetworkNode {",
                          "public class VeloceImporterBlock extends BaseEntityBlock implements VeloceNetworkNode {" + get_drops)

with open("neoforge/src/main/java/com/craftingveloce/block/VeloceImporterBlock.java", "w") as f:
    f.write(content)

