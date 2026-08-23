package net.minecraft.client.renderer.feature;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class BlockModelFeatureRenderer extends RenderTypeFeatureRenderer<BlockModelFeatureRenderer.Submit> {
    public static final FeatureRendererType<BlockModelFeatureRenderer.Submit> TYPE = FeatureRendererType.create("Block Model");
    private static final Direction[] DIRECTIONS = Direction.values();
    private final QuadInstance quadInstance = new QuadInstance();

    @Override
    protected void buildGroup(final FeatureFrameContext context, final List<BlockModelFeatureRenderer.Submit> submits) {
        for (BlockModelFeatureRenderer.Submit submit : submits) {
            // MODIFIED for porting: was iris's entity_render_context MixinBlockModelFeatureRenderer#iris$set (@Inject at the
            // INVOKE of getVertexBuilder, with @Local Submit)
            if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                ((net.irisshaders.iris.mixinterface.ModelStorage)(Object)submit).iris$set();
            }

            VertexConsumer buffer = this.getVertexBuilder(submit.renderType());
            VertexConsumer wrappedBuffer = submit.sheetedDecalPose() != null
                ? new SheetedDecalTextureGenerator(buffer, submit.sheetedDecalPose(), 1.0F)
                : buffer;
            this.quadInstance.setLightCoords(submit.lightCoords());
            this.quadInstance.setOverlayCoords(submit.overlayCoords());

            for (BlockStateModelPart part : submit.modelParts()) {
                putPartQuads(part, submit.pose(), this.quadInstance, submit.tintColor(), submit.tintLayers(), wrappedBuffer);
            }
        }

        // MODIFIED for porting: was iris's entity_render_context MixinBlockModelFeatureRenderer#iris$unset (@Inject RETURN)
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentRenderedItem(0);
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentEntity(0);
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentBlockEntity(0);
        }
    }

    private static void putPartQuads(
        final BlockStateModelPart part,
        final PoseStack.Pose pose,
        final QuadInstance quadInstance,
        final int baseTintColor,
        final int[] tintLayers,
        final VertexConsumer buffer
    ) {
        for (Direction direction : DIRECTIONS) {
            for (BakedQuad quad : part.getQuads(direction)) {
                putQuad(pose, quad, quadInstance, baseTintColor, tintLayers, buffer);
            }
        }

        for (BakedQuad quad : part.getQuads(null)) {
            putQuad(pose, quad, quadInstance, baseTintColor, tintLayers, buffer);
        }
    }

    private static void putQuad(
        final PoseStack.Pose pose,
        final BakedQuad quad,
        final QuadInstance instance,
        final int baseTintColor,
        final int[] tintLayers,
        final VertexConsumer buffer
    ) {
        int tintIndex = quad.materialInfo().tintIndex();
        boolean useTintLayer = tintIndex != -1 && tintIndex < tintLayers.length;
        instance.setColor(useTintLayer ? ARGB.multiply(baseTintColor, tintLayers[tintIndex]) : baseTintColor);
        buffer.putBakedQuad(pose, quad, instance);
    }

    @OnlyIn(Dist.CLIENT)
    /**
     * MODIFIED for porting: iris's entity_render_context MixinBlockModelSubmit adds four mutable {@code @Unique} fields to this record
     * (see the ModelStorage block below), which a record cannot have. It was therefore rewritten as a plain final class with
     * the same components and accessors; {@code equals}, {@code hashCode} and {@code toString} are implemented exactly as
     * the record's generated ones (the captured ids are set after construction and are deliberately not part of them).
     */
    public static final class Submit implements TranslucentSubmit, net.irisshaders.iris.mixinterface.ModelStorage {
        private final PoseStack.Pose pose;
        private final RenderType renderType;
        private final List<BlockStateModelPart> modelParts;
        private final int[] tintLayers;
        private final int lightCoords;
        private final int overlayCoords;
        private final int tintColor;
        private final PoseStack.@Nullable Pose sheetedDecalPose;

        public Submit(
            final PoseStack.Pose pose,
            final RenderType renderType,
            final List<BlockStateModelPart> modelParts,
            final int[] tintLayers,
            final int lightCoords,
            final int overlayCoords,
            final int tintColor,
            final PoseStack.@Nullable Pose sheetedDecalPose
        ) {
            this.pose = pose;
            this.renderType = renderType;
            this.modelParts = modelParts;
            this.tintLayers = tintLayers;
            this.lightCoords = lightCoords;
            this.overlayCoords = overlayCoords;
            this.tintColor = tintColor;
            this.sheetedDecalPose = sheetedDecalPose;
        }

        public PoseStack.Pose pose() {
            return this.pose;
        }

        public RenderType renderType() {
            return this.renderType;
        }

        public List<BlockStateModelPart> modelParts() {
            return this.modelParts;
        }

        public int[] tintLayers() {
            return this.tintLayers;
        }

        public int lightCoords() {
            return this.lightCoords;
        }

        public int overlayCoords() {
            return this.overlayCoords;
        }

        public int tintColor() {
            return this.tintColor;
        }

        public PoseStack.@Nullable Pose sheetedDecalPose() {
            return this.sheetedDecalPose;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof Submit other)) {
                return false;
            }

            return java.util.Objects.equals(this.pose, other.pose)
                && java.util.Objects.equals(this.renderType, other.renderType)
                && java.util.Objects.equals(this.modelParts, other.modelParts)
                && java.util.Arrays.equals(this.tintLayers, other.tintLayers)
                && this.lightCoords == other.lightCoords
                && this.overlayCoords == other.overlayCoords
                && this.tintColor == other.tintColor
                && java.util.Objects.equals(this.sheetedDecalPose, other.sheetedDecalPose);
        }

        @Override
        public int hashCode() {
            return java.util.Objects
                .hash(
                this.pose,
                this.renderType,
                this.modelParts,
                java.util.Arrays.hashCode(this.tintLayers),
                this.lightCoords,
                this.overlayCoords,
                this.tintColor,
                this.sheetedDecalPose
                );
        }

        @Override
        public String toString() {
            return "Submit[pose="
                + this.pose
                + ", renderType="
                + this.renderType
                + ", modelParts="
                + this.modelParts
                + ", tintLayers="
                + java.util.Arrays.toString(this.tintLayers)
                + ", lightCoords="
                + this.lightCoords
                + ", overlayCoords="
                + this.overlayCoords
                + ", tintColor="
                + this.tintColor
                + ", sheetedDecalPose="
                + this.sheetedDecalPose
                + "]";
        }

        /**
         * MODIFIED for porting: iris entity_render_context MixinBlockModelSubmit @Unique fields (its ModelStorage implementation) - each
         * submit remembers the entity / block-entity / item id that was current when it was created, so the ids can be restored
         * when the submit is actually built (which happens much later, in a different order).
         */
        private int iris$entityId;

        private int iris$beId;

        private int iris$itemId;

        private boolean iris$isRenderingBEs;

        @Override
        public void iris$capture() {
            this.iris$entityId = net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentRenderedEntity();
            this.iris$beId = net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentRenderedBlockEntity();
            this.iris$itemId = net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentRenderedItem();
            this.iris$isRenderingBEs = net.irisshaders.iris.vertices.ImmediateState.isRenderingBEs;
        }

        @Override
        public void iris$set() {
            net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentEntity(this.iris$entityId);
            net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentBlockEntity(this.iris$beId);
            net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentRenderedItem(this.iris$itemId);
        }

        @Override
        public boolean iris$wasBE() {
            return this.iris$isRenderingBEs;
        }

        @Override
        public float distanceToCameraSq() {
            return TranslucentSubmit.computeDistanceToCameraSq(this.pose.pose(), 0.5F, 0.5F, 0.5F);
        }

        @Override
        public FeatureRendererType<BlockModelFeatureRenderer.Submit> featureType() {
            return BlockModelFeatureRenderer.TYPE;
        }
    }
}