package com.craftingveloce.client.render;

import com.craftingveloce.block.VeloceCaseContents;
import com.craftingveloce.block.VeloceCaseSpin;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.joml.Quaternionf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders the CONTENTS of the Veloce Integrale casing - for each of our blocks.
 *
 * <p><b>What it shows.</b> The base block's model from the
 * {@link VeloceCaseContents} table: inside the controller's casing stands a
 * lectern, in the extractor a dispenser, in the sensor an observer, in the
 * crafting table a table, and in the Create module a millstone / saw /
 * water wheel / crafter.
 *
 * <p><b>Why the ITEM model, and not the block model.</b> A finding from the
 * Create JAR: the BLOCK model of those machines is trimmed down -
 * {@code block/millstone/block} has no centre stone,
 * {@code block/mechanical_saw/block} has no blade - because those parts are
 * drawn by a separate renderer (in Create: Flywheel). The ITEM model is their
 * COMPLETE, static representation ({@code block/millstone/item} has Gear5, and
 * {@code crushing_wheel/item} is an OBJ model). That is why an item dropped on
 * the ground looks fine, while a casing with the block model was "half
 * wrecked". So we always draw the item model - also for vanilla, where it is
 * the same model as the block.
 *
 * <p><b>Scale and centre.</b> The item model renders with its own transform
 * (FIXED: translation + shrink), which is why it previously came out small and
 * in a corner. We read the transform from the model ({@code getTransforms}) and
 * compensate for it in our own pose, so the content is always the same size and
 * exactly in the centre of the casing - regardless of the mod and its model.
 *
 * <p><b>No server-side work.</b> The content follows from the TYPE of the
 * block, so nothing has to be saved or synchronized - the client reads the
 * block state itself.
 */
public class VeloceCaseRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {

    /** Size of the content: the casing window is 12 px (0.75 of a block). */
    private static final float CONTENT_SCALE = 0.45F;

    /** Rotation speed of the whole content in degrees per tick (full turn ~10 s). */
    private static final float SPIN_DEGREES_PER_TICK = 0.6F;

    /**
     * Size of the whole GRID of elements in the casing window.
     *
     * <p>The window is 12 px (0.75 of a block), the content additionally sways
     * up and down, and each element has half its thickness. A player reported
     * that at the highest frame of the animation the crafters stuck out beyond
     * the model - which is why the grid occupies only 0.62 of a block instead
     * of 0.72: the rest is headroom for the movement and for the element's
     * thickness.
     */
    private static final float GRID_EXTENT = 0.62F;

    /** Item stack for the base block (no allocation per frame). */
    private static final Map<Block, ItemStack> CONTENT_STACKS = new ConcurrentHashMap<>();

    /** FIXED transform of the item model - needed to compensate the size. */
    private static final Map<Block, ItemTransform> CONTENT_TRANSFORMS = new ConcurrentHashMap<>();

    private static int cachedGeneration = 0;

    /**
     * Order of cells in the square: from the CENTRE outwards.
     *
     * <p>Player: "it is supposed to expand from the centre outwards in a
     * square". Thanks to that the first cells are in the middle of the casing,
     * and the following ones add themselves as rings around them - instead of
     * growing in one corner. Sorting by distance from the centre (rather than
     * walking a spiral) always terminates.
     */
    private static final Map<Integer, int[][]> CENTRE_ORDER = new ConcurrentHashMap<>();

    public VeloceCaseRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(T be, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        if (be.getLevel() == null) {
            return;
        }
        Block content = VeloceCaseContents.contentFor(be.getBlockState());
        if (content == null) {
            return;   // pipe, terminal or an ordinary block - we render nothing
        }
        float contentScale = VeloceCaseContents.contentScale(be.getBlockState());
        float contentPitch = VeloceCaseContents.contentPitch(be.getBlockState());
        boolean keepRotation = VeloceCaseContents.keepsItemRotation(be.getBlockState());
        float time = be.getLevel().getGameTime() + partialTick;

        if (be instanceof VeloceCaseSpin spin) {
            if (spin.caseParts() > 0) {
                renderParts(spin, content, contentScale, contentPitch, keepRotation, pose, buffers, packedLight, packedOverlay, time);
                return;
            }
            if (spin.caseBuiltFromParts()) {
                // Crusher/crafter with no elements clicked in: EMPTY casing.
                return;
            }
        }
        renderStandard(content, contentScale, contentPitch, keepRotation, pose, buffers, packedLight, packedOverlay, time);
    }

    /** Ordinary content animation: rotation around the vertical axis + swaying. */
    private void renderStandard(Block content, float contentScale, float contentPitch,
                                boolean keepRotation, PoseStack pose,
                                MultiBufferSource buffers, int packedLight, int packedOverlay,
                                float time) {
        pose.pushPose();
        beginStandardAnimation(pose, time);
        renderContent(content, contentScale, contentPitch, keepRotation, pose, buffers,
                packedLight, packedOverlay);
        pose.popPose();
    }

    /** Moving to the centre of the casing + rotation + swaying (shared by whole layouts). */
    private static void beginStandardAnimation(PoseStack pose, float time) {
        pose.translate(0.5D, 0.5D, 0.5D);
        pose.mulPose(Axis.YP.rotationDegrees((time * SPIN_DEGREES_PER_TICK) % 360.0F));
        pose.translate(Math.sin(time * 0.05F) * 0.030D,
                Math.sin(time * 0.08F) * 0.040D,
                Math.cos(time * 0.045F) * 0.030D);
    }

    /**
     * Machine elements (crafter cells, millstones) in the layout given by the
     * machine.
     *
     * <p>The layout as a WHOLE rotates around the centre of the casing with the
     * ordinary animation, and only the millstones each spin around themselves
     * and at the drive speed (that is how they mesh). The element size follows
     * from the grid density, so 81 cells are correspondingly smaller than one.
     */
    private void renderParts(VeloceCaseSpin spin, Block content, float contentScale,
                             float contentPitch, boolean keepRotation, PoseStack pose,
                             MultiBufferSource buffers, int packedLight, int packedOverlay,
                             float time) {
        int parts = spin.caseParts();
        int cols = Math.max(1, spin.caseGridColumns());
        int rows = Math.max(1, spin.caseGridRows());
        float spacing = Math.min(GRID_EXTENT / cols, GRID_EXTENT / rows);
        float scale = Math.min(CONTENT_SCALE * contentScale, spacing * 0.9F);
        boolean individually = spin.casePartsSpinIndividually();
        float speed = spin.caseSpinDegreesPerTick();
        int[][] order = centreOrder(Math.max(cols, rows));

        pose.pushPose();
        if (!individually) {
            beginStandardAnimation(pose, time);
        } else {
            pose.translate(0.5D, 0.5D, 0.5D);
        }
        for (int i = 0; i < parts; i++) {
            int col = order[i][0];
            int row = order[i][1];
            pose.pushPose();
            pose.translate((col - (cols - 1) / 2.0F) * spacing,
                    -(row - (rows - 1) / 2.0F) * spacing, 0.0D);
            if (individually && speed != 0.0F) {
                float direction = (i % 2 == 0) ? 1.0F : -1.0F;
                pose.mulPose(Axis.ZP.rotationDegrees((time * speed * direction) % 360.0F));
            }
            // The content draws itself at CONTENT_SCALE, so we scale the grid
            // element relative to it (and not a second time from scratch).
            float factor = scale / (CONTENT_SCALE * contentScale);
            pose.scale(factor, factor, factor);
            renderContent(content, contentScale, contentPitch, keepRotation, pose, buffers,
                    packedLight, packedOverlay);
            pose.popPose();
        }
        pose.popPose();
    }

    /**
     * Draws the base block as an ITEM - the way it looks as an entity on the
     * ground, but rescaled and centred in the casing.
     */

    private void renderContent(Block content, float contentScale, float contentPitch,
                               boolean keepRotation, PoseStack pose,
                               MultiBufferSource buffers, int packedLight, int packedOverlay) {


        ItemStack stack = CONTENT_STACKS.computeIfAbsent(content,


                block -> new ItemStack(block.asItem()));
        ItemRenderer renderer = Minecraft.getInstance().getItemRenderer();
        if (stack.isEmpty() || renderer == null) {
            return;
        }
        ItemTransform transform = contentTransform(content, stack);
        pose.pushPose();
        // Per-machine tilt (the saw and the deployer are supposed to face DOWN) -
        // the outermost one, so it turns the whole content around the centre of
        // the casing, and not around its corner.
        if (contentPitch != 0.0F) {
            pose.mulPose(Axis.XP.rotationDegrees(contentPitch));
        }
        // Compensation of the item transform: the item model draws itself with
        // its own translation and shrink (FIXED), so without this the content
        // comes out small and offset. We scale to CONTENT_SCALE and zero the
        // translation.
        float factor = (CONTENT_SCALE * contentScale) / Math.max(0.01F, transform.scale.x);
        pose.scale(factor, factor, factor);
        // WE ZERO the model's TILT (the FIXED transform tips the item over as in
        // the inventory: 30/225 degrees). Player: "the saw and the deployer face
        // sideways, but they are supposed to face up". The inverse rotation =
        // rotationZYX with minuses, because JOML builds it as Rx * Ry * Rz.
        if (!keepRotation) {
            float deg = (float) (Math.PI / 180.0);
            pose.mulPose(new Quaternionf().rotationZYX(-transform.rotation.z * deg,
                    -transform.rotation.y * deg, -transform.rotation.x * deg));
        }
        pose.translate(-transform.translation.x, -transform.translation.y,
                -transform.translation.z);
        renderer.renderStatic(stack, ItemDisplayContext.FIXED, packedLight, packedOverlay,
                pose, buffers, Minecraft.getInstance().level, 0);
        pose.popPose();
    }

    /** FIXED transform of the item model (computed once per model generation). */
    private static ItemTransform contentTransform(Block block, ItemStack stack) {
        ModelManager manager = Minecraft.getInstance().getModelManager();
        int generation = System.identityHashCode(manager);
        if (generation != cachedGeneration) {
            cachedGeneration = generation;
            CONTENT_TRANSFORMS.clear();
        }
        return CONTENT_TRANSFORMS.computeIfAbsent(block, b -> Minecraft.getInstance()
                .getItemRenderer()
                .getModel(stack, Minecraft.getInstance().level, null, 0)
                .getTransforms()
                .getTransform(ItemDisplayContext.FIXED));
    }

    /** Cells of the square in order from the centre outwards. */
    private static int[][] centreOrder(int side) {
        return CENTRE_ORDER.computeIfAbsent(side, s -> {
            double centre = (s - 1) / 2.0;
            List<int[]> cells = new ArrayList<>(s * s);
            for (int x = 0; x < s; x++) {
                for (int y = 0; y < s; y++) {
                    cells.add(new int[]{x, y});
                }
            }
            cells.sort(Comparator
                    .comparingDouble((int[] c) -> Math.max(Math.abs(c[0] - centre),
                            Math.abs(c[1] - centre)))
                    .thenComparingDouble(c -> Math.pow(c[0] - centre, 2)
                            + Math.pow(c[1] - centre, 2))
                    .thenComparingInt(c -> c[1])
                    .thenComparingInt(c -> c[0]));
            return cells.toArray(new int[0][]);
        });
    }
}
