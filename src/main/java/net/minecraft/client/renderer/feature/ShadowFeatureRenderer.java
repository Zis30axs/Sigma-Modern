package net.minecraft.client.renderer.feature;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

@OnlyIn(Dist.CLIENT)
public class ShadowFeatureRenderer extends RenderTypeFeatureRenderer<ShadowFeatureRenderer.Submit> {
    public static final FeatureRendererType<ShadowFeatureRenderer.Submit> TYPE = FeatureRendererType.create("Shadow");
    private static final RenderType SHADOW_RENDER_TYPE = RenderTypes.entityShadow(Identifier.withDefaultNamespace("textures/misc/shadow.png"));

    @Override
    protected void buildGroup(final FeatureFrameContext context, final List<ShadowFeatureRenderer.Submit> submits) {
        VertexConsumer builder = this.getVertexBuilder(SHADOW_RENDER_TYPE);

        for (ShadowFeatureRenderer.Submit submit : submits) {
            this.prepare(submit, builder);
        }
    }

    private void prepare(final ShadowFeatureRenderer.Submit submit, final VertexConsumer builder) {
        // MODIFIED for porting: sodium features.render.entity.shadows ShadowFeatureRendererMixin#renderShadowPartFast
        // (HEAD, cancellable) - reduces the vertex assembly overhead for shadow rendering.
        net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter sodium$writer =
            net.caffeinemc.mods.sodium.client.render.vertex.VertexConsumerUtils.convertOrLog(builder);
        if (sodium$writer != null) {
            sodium$renderShadowPieces(submit, sodium$writer);
            return;
        }

        for (EntityRenderState.ShadowPiece piece : submit.pieces()) {
            AABB aabb = piece.shapeBelow().bounds();
            float x01 = piece.relativeX() + (float)aabb.minX;
            float x11 = piece.relativeX() + (float)aabb.maxX;
            float y01 = piece.relativeY() + (float)aabb.minY;
            float z01 = piece.relativeZ() + (float)aabb.minZ;
            float z11 = piece.relativeZ() + (float)aabb.maxZ;
            float radius = submit.radius();
            float u0 = -x01 / 2.0F / radius + 0.5F;
            float u1 = -x11 / 2.0F / radius + 0.5F;
            float v0 = -z01 / 2.0F / radius + 0.5F;
            float v1 = -z11 / 2.0F / radius + 0.5F;
            int color = ARGB.white(piece.alpha());
            shadowVertex(submit.pose(), builder, color, x01, y01, z01, u0, v0);
            shadowVertex(submit.pose(), builder, color, x01, y01, z11, u0, v1);
            shadowVertex(submit.pose(), builder, color, x11, y01, z11, u1, v1);
            shadowVertex(submit.pose(), builder, color, x11, y01, z01, u1, v0);
        }
    }

    // MODIFIED for porting: sodium features.render.entity.shadows ShadowFeatureRendererMixin @Unique fields
    private static final int SODIUM_DEFAULT_NORMAL = net.caffeinemc.mods.sodium.api.util.NormI8.pack(0.0F, 1.0F, 0.0F);

    private static final int SODIUM_SHADOW_COLOR = net.caffeinemc.mods.sodium.api.util.ColorABGR.pack(1.0F, 1.0F, 1.0F);

    // MODIFIED for porting: was sodium's features.render.entity.shadows ShadowFeatureRendererMixin#renderShadowPartFast
    private static void sodium$renderShadowPieces(
        final ShadowFeatureRenderer.Submit shadows, final net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter writer
    ) {
        Matrix4fc matrices = shadows.pose();

        for (int i = 0; i < shadows.pieces().size(); i++) {
            EntityRenderState.ShadowPiece shadowPiece = shadows.pieces().get(i);
            float alpha = shadowPiece.alpha();
            if (alpha >= 0.0F) {
                if (alpha > 1.0F) {
                    alpha = 1.0F;
                }

                AABB box = shadowPiece.shapeBelow().bounds();
                float minX = (float)(shadowPiece.relativeX() + box.minX);
                float maxX = (float)(shadowPiece.relativeX() + box.maxX);
                float minY = (float)(shadowPiece.relativeY() + box.minY);
                float minZ = (float)(shadowPiece.relativeZ() + box.minZ);
                float maxZ = (float)(shadowPiece.relativeZ() + box.maxZ);
                sodium$renderShadowPart(matrices, writer, shadows.radius(), alpha, minX, maxX, minY, minZ, maxZ);
            }
        }
    }

    // MODIFIED for porting: was sodium's features.render.entity.shadows ShadowFeatureRendererMixin#renderShadowPart
    private static void sodium$renderShadowPart(
        final Matrix4fc matPosition,
        final net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter writer,
        final float radius,
        final float alpha,
        final float minX,
        final float maxX,
        final float minY,
        final float minZ,
        final float maxZ
    ) {
        float size = 0.5F * (1.0F / radius);
        float u1 = -minX * size + 0.5F;
        float u2 = -maxX * size + 0.5F;
        float v1 = -minZ * size + 0.5F;
        float v2 = -maxZ * size + 0.5F;
        int color = net.caffeinemc.mods.sodium.api.util.ColorABGR.withAlpha(SODIUM_SHADOW_COLOR, alpha);
        // This seems wrong, but it is identical to vanilla's handling.
        int normal = SODIUM_DEFAULT_NORMAL;

        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            long buffer = stack.nmalloc(4 * net.caffeinemc.mods.sodium.api.vertex.format.common.EntityVertex.STRIDE);
            long ptr = buffer;
            sodium$writeShadowVertex(ptr, matPosition, minX, minY, minZ, u1, v1, color, normal);
            ptr += net.caffeinemc.mods.sodium.api.vertex.format.common.EntityVertex.STRIDE;
            sodium$writeShadowVertex(ptr, matPosition, minX, minY, maxZ, u1, v2, color, normal);
            ptr += net.caffeinemc.mods.sodium.api.vertex.format.common.EntityVertex.STRIDE;
            sodium$writeShadowVertex(ptr, matPosition, maxX, minY, maxZ, u2, v2, color, normal);
            ptr += net.caffeinemc.mods.sodium.api.vertex.format.common.EntityVertex.STRIDE;
            sodium$writeShadowVertex(ptr, matPosition, maxX, minY, minZ, u2, v1, color, normal);
            writer.push(stack, buffer, 4, net.caffeinemc.mods.sodium.api.vertex.format.common.EntityVertex.FORMAT);
        }
    }

    // MODIFIED for porting: was sodium's features.render.entity.shadows ShadowFeatureRendererMixin#writeShadowVertex
    private static void sodium$writeShadowVertex(
        final long ptr,
        final Matrix4fc matPosition,
        final float x,
        final float y,
        final float z,
        final float u,
        final float v,
        final int color,
        final int normal
    ) {
        net.caffeinemc.mods.sodium.api.vertex.format.common.EntityVertex
            .write(
                ptr,
                net.caffeinemc.mods.sodium.api.math.MatrixHelper.transformPositionX(matPosition, x, y, z),
                net.caffeinemc.mods.sodium.api.math.MatrixHelper.transformPositionY(matPosition, x, y, z),
                net.caffeinemc.mods.sodium.api.math.MatrixHelper.transformPositionZ(matPosition, x, y, z),
                color,
                u,
                v,
                net.minecraft.util.LightCoordsUtil.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY,
                normal
            );
    }

    private static void shadowVertex(
        final Matrix4fc pose, final VertexConsumer buffer, final int color, final float x, final float y, final float z, final float u, final float v
    ) {
        Vector3f position = pose.transformPosition(x, y, z, new Vector3f());
        buffer.addVertex(position.x(), position.y(), position.z(), color, u, v, OverlayTexture.NO_OVERLAY, 15728880, 0.0F, 1.0F, 0.0F);
    }

    @OnlyIn(Dist.CLIENT)
    public record Submit(Matrix4fc pose, float radius, List<EntityRenderState.ShadowPiece> pieces) implements SubmitNode {
        @Override
        public FeatureRendererType<ShadowFeatureRenderer.Submit> featureType() {
            return ShadowFeatureRenderer.TYPE;
        }
    }
}