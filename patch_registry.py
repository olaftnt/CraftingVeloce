import re

with open("neoforge/src/main/java/com/craftingveloce/init/VeloceRegistry.java", "r") as f:
    content = f.read()

importer_block = """    public static final DeferredBlock<com.craftingveloce.block.VeloceImporterBlock> VELOCE_IMPORTER = BLOCKS.register(
            "veloce_importer",
            com.craftingveloce.block.VeloceImporterBlock::new
    );

    public static final DeferredItem<net.minecraft.world.item.BlockItem> VELOCE_IMPORTER_ITEM = ITEMS.registerSimpleBlockItem(
            "veloce_importer",
            VELOCE_IMPORTER
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.craftingveloce.block.entity.VeloceImporterBlockEntity>> VELOCE_IMPORTER_BE =
            BLOCK_ENTITY_TYPES.register("veloce_importer", () -> createBEType(
                    (pos, state) -> new com.craftingveloce.block.entity.VeloceImporterBlockEntity(pos, state),
                    VELOCE_IMPORTER.get()
            ));

    public static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<com.craftingveloce.inventory.VeloceImporterMenu>> VELOCE_IMPORTER_MENU =
            MENU_TYPES.register("veloce_importer_menu", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                    (windowId, inv, data) -> new com.craftingveloce.inventory.VeloceImporterMenu(windowId, inv)
            ));

"""

content = content.replace("    // 3. Veloce Extractor (Cube terminal node with 3x3 filter and 3x3 output)",
                          importer_block + "    // 3. Veloce Extractor (Cube terminal node with 3x3 filter and 3x3 output)")

with open("neoforge/src/main/java/com/craftingveloce/init/VeloceRegistry.java", "w") as f:
    f.write(content)
