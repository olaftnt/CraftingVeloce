package com.craftingveloce.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.AxisAngle4f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The casing's ITEM model: the real icon of the represented block, standing inside the
 * frame.
 *
 * <p><b>What this replaces.</b> Every casing item model carried a small cube at
 * {@code [4,4,4]..[12,12,12]} with all six faces pointing at {@code #content}. One wall of
 * the machine was therefore stretched over all six sides of that cube - including the top
 * and the bottom - so a crafting table wore its front panel on its lid. The measurement
 * was uniform across all 44 models: no {@code uv} anywhere, the same element, the same
 * six faces.
 *
 * <p><b>Why composition instead of a better cube.</b> A texture on a cube is still not the
 * machine's icon. The player asked for exactly what the hotbar shows for that block, and
 * that is the block's own ITEM model - which is already baked and sitting in the same map
 * at this point, so this is a lookup and not a second bake.
 *
 * <p><b>ITEM model, not BLOCK model.</b> Create's block models are not a complete picture
 * of the machine: {@code create:block/millstone/block} has 6 elements against 12 in its
 * item model, and {@code create:block/mechanical_saw/block} does not exist at all - those
 * parts are drawn by Flywheel. The item model is the complete, static one.
 *
 * <p><b>No FIXED-transform compensation, unlike the world renderer.</b>
 * {@link VeloceCaseRenderer} draws the content through {@code ItemRenderer}, which applies
 * the content model's own inventory tilt, so the renderer has to undo it. Here the quads
 * are taken BEFORE any display transform and placed into the casing's model space; the
 * only transform applied to them afterwards is the casing's own, which tilts the content
 * together with the frame - which is what an item icon should do.
 */
public final class VeloceCaseItemModel implements BakedModel {

    /**
     * Bytes per vertex in a baked quad's int array.
     *
     * <p>Position (3 floats), colour (1 int), UV (2 floats), light (1 int), normal (1 int).
     * Only the first three are read or written here - the rest is copied through untouched,
     * because a quad's colour, UVs and light are not ours to recompute.
     */
    private static final int VERTEX_STRIDE = 8;

    /** The casing is a full block in model space: 0..16. */
    private static final float MODEL_CENTRE = 8.0F;

    private final BakedModel caseModel;
    private final BakedModel contentModel;

    /** Content-space to casing-space, built once: this runs per quad, not per frame. */
    private final Matrix4f contentTransform;

    public VeloceCaseItemModel(BakedModel caseModel, BakedModel contentModel,
                               float contentScale, float contentPitch) {
        this.caseModel = caseModel;
        this.contentModel = contentModel;

        // The same placement the world renderer uses, expressed in model space instead of
        // in blocks: scale about the centre, with the per-machine tilt the saw and the
        // deployer need. Compose order matters - JOML post-multiplies, so this reads
        // right to left as "centre it, turn it, shrink it, put it back".
        Matrix4f transform = new Matrix4f();
        transform.translate(MODEL_CENTRE, MODEL_CENTRE, MODEL_CENTRE);
        float scale = VeloceCaseRenderer.CONTENT_SCALE * contentScale;
        transform.scale(scale, scale, scale);
        if (contentPitch != 0.0F) {
            transform.rotate(new AxisAngle4f((float) Math.toRadians(contentPitch), 1.0F, 0.0F, 0.0F));
        }
        transform.translate(-MODEL_CENTRE, -MODEL_CENTRE, -MODEL_CENTRE);
        this.contentTransform = transform;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction,
                                    RandomSource random) {
        List<BakedQuad> content = contentModel.getQuads(state, direction, random);
        List<BakedQuad> casing = caseModel.getQuads(state, direction, random);
        if (content.isEmpty()) {
            return casing;
        }
        if (casing.isEmpty()) {
            return content;
        }
        List<BakedQuad> out = new ArrayList<>(content.size() + casing.size());
        for (BakedQuad quad : content) {
            out.add(transformed(quad));
        }
        // The casing goes LAST. Its glass is translucent, and blending is order-dependent:
        // drawn first, the content would end up behind an unblended pane.
        out.addAll(casing);
        return out;
    }

    /** Moves one quad's four positions into casing space, leaving colour, UV and light alone. */
    private BakedQuad transformed(BakedQuad quad) {
        int[] source = quad.getVertices();
        int[] moved = source.clone();
        Vector3f vertex = new Vector3f();
        for (int i = 0; i < 4; i++) {
            int offset = i * VERTEX_STRIDE;
            vertex.set(Float.intBitsToFloat(source[offset]),
                    Float.intBitsToFloat(source[offset + 1]),
                    Float.intBitsToFloat(source[offset + 2]));
            contentTransform.transformPosition(vertex);
            moved[offset] = Float.floatToIntBits(vertex.x());
            moved[offset + 1] = Float.floatToIntBits(vertex.y());
            moved[offset + 2] = Float.floatToIntBits(vertex.z());
        }
        return new BakedQuad(moved, quad.getTintIndex(), quad.getDirection(),
                quad.getSprite(), quad.isShade());
    }

    // Everything that is not geometry belongs to the casing: it decides the particle, the
    // inventory tilt and whether the model is drawn in 3D. Delegating keeps the icon
    // sitting the way the casing always sat - only its inside changed.

    @Override
    public boolean useAmbientOcclusion() {
        return caseModel.useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return caseModel.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return caseModel.usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return caseModel.isCustomRenderer();
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return caseModel.getParticleIcon();
    }

    @Override
    public ItemTransforms getTransforms() {
        return caseModel.getTransforms();
    }

    @Override
    public ItemOverrides getOverrides() {
        return caseModel.getOverrides();
    }
}
