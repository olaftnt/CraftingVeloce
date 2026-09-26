import re

with open("neoforge/src/main/java/com/craftingveloce/block/VeloceImporterBlock.java", "r") as f:
    content = f.read()

# Replace constructor properties
old_props = """super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GRAY)
                .requiresCorrectToolForDrops()
                .strength(3.0F, 1200.0F)
                .sound(SoundType.METAL));"""
new_props = """super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .sound(SoundType.METAL)
                .strength(3.5F)
                .requiresCorrectToolForDrops()
                .noOcclusion()
                .isViewBlocking((state, world, pos) -> false)
                .isSuffocating((state, world, pos) -> false)
                .lightLevel(s -> 7));"""
content = content.replace(old_props, new_props)

# Add the 3 methods
methods = """
    /** Casing cap properties: one per each side of the world. */
    @Override
    protected void createBlockStateDefinition(
            net.minecraft.world.level.block.state.StateDefinition.Builder<
                    net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        com.craftingveloce.block.VeloceIntegraleFrame.addProperties(builder);
    }

    /** On placement we immediately close the sides a cable comes in from. */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return com.craftingveloce.block.VeloceIntegraleFrame.withPlacementClosures(
                context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    /** Closing the panel on the wall where a Veloce pipe stands. */
    @Override
    protected BlockState updateShape(BlockState state, net.minecraft.core.Direction facing,
                                     BlockState facingState, net.minecraft.world.level.LevelAccessor world,
                                     BlockPos pos, BlockPos facingPos) {
        return com.craftingveloce.block.VeloceIntegraleFrame.withClosure(state, facing, facingState);
    }
"""
content = content.replace("public class VeloceImporterBlock extends BaseEntityBlock implements VeloceNetworkNode {",
                          "public class VeloceImporterBlock extends BaseEntityBlock implements VeloceNetworkNode {" + methods)

with open("neoforge/src/main/java/com/craftingveloce/block/VeloceImporterBlock.java", "w") as f:
    f.write(content)
