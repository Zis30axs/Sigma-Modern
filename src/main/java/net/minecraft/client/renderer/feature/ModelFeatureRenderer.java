package net.minecraft.client.renderer.feature;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.submit.BatchableSubmit;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class ModelFeatureRenderer extends RenderTypeFeatureRenderer<ModelFeatureRenderer.Submit<?>> {
    public static final FeatureRendererType<ModelFeatureRenderer.Submit<?>> TYPE = FeatureRendererType.create("Entity Model");
    private final PoseStack poseStack = new PoseStack();

    @Override
    protected void buildGroup(final FeatureFrameContext context, final List<ModelFeatureRenderer.Submit<?>> submits) {
        for (ModelFeatureRenderer.Submit<?> submit : submits) {
            this.prepareModel(submit);
        }
    }

    /**
     * MODIFIED for porting: was iris's entity_render_context MixinModelFeatureRenderer#iris$set (@WrapMethod).
     */
    private <S> void prepareModel(final ModelFeatureRenderer.Submit<S> submit) {
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            ((net.irisshaders.iris.mixinterface.ModelStorage)(Object)submit).iris$set();

            try {
                this.iris$prepareModel(submit);
            } finally {
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentRenderedItem(0);
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentEntity(0);
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentBlockEntity(0);
            }

            return;
        }

        this.iris$prepareModel(submit);
    }

    // MODIFIED for porting: original vanilla body of prepareModel
    private <S> void iris$prepareModel(final ModelFeatureRenderer.Submit<S> submit) {
        this.poseStack.last().set(submit.pose());
        VertexConsumer buffer = this.getVertexBuilder(submit.renderType());
        if (submit.sheetedDecalPose() != null) {
            buffer = new SheetedDecalTextureGenerator(buffer, submit.sheetedDecalPose(), 1.0F);
        } else if (submit.sprite() != null) {
            buffer = submit.sprite().wrap(buffer);
        }

        Model<? super S> model = submit.model();
        model.setupAnim(submit.state());
        model.renderToBuffer(this.poseStack, buffer, submit.lightCoords(), submit.overlayCoords(), submit.tintedColor());
    }

    @OnlyIn(Dist.CLIENT)
    public record CrumblingOverlay(int progress, PoseStack.Pose cameraPose) {
    }

    @OnlyIn(Dist.CLIENT)
    /**
     * MODIFIED for porting: iris's entity_render_context MixinModelSubmit adds four mutable {@code @Unique} fields to this record
     * (see the ModelStorage block below), which a record cannot have. It was therefore rewritten as a plain final class with
     * the same components and accessors; {@code equals}, {@code hashCode} and {@code toString} are implemented exactly as
     * the record's generated ones (the captured ids are set after construction and are deliberately not part of them).
     */
    public static final class Submit<S> implements BatchableSubmit, TranslucentSubmit, net.irisshaders.iris.mixinterface.ModelStorage {
        private final RenderType renderType;
        private final PoseStack.Pose pose;
        private final Model<? super S> model;
        private final S state;
        private final int lightCoords;
        private final int overlayCoords;
        private final int tintedColor;
        private final @Nullable TextureAtlasSprite sprite;
        private final PoseStack.@Nullable Pose sheetedDecalPose;

        public Submit(
            final RenderType renderType,
            final PoseStack.Pose pose,
            final Model<? super S> model,
            final S state,
            final int lightCoords,
            final int overlayCoords,
            final int tintedColor,
            final @Nullable TextureAtlasSprite sprite,
            final PoseStack.@Nullable Pose sheetedDecalPose
        ) {
            this.renderType = renderType;
            this.pose = pose;
            this.model = model;
            this.state = state;
            this.lightCoords = lightCoords;
            this.overlayCoords = overlayCoords;
            this.tintedColor = tintedColor;
            this.sprite = sprite;
            this.sheetedDecalPose = sheetedDecalPose;
        }

        public RenderType renderType() {
            return this.renderType;
        }

        public PoseStack.Pose pose() {
            return this.pose;
        }

        public Model<? super S> model() {
            return this.model;
        }

        public S state() {
            return this.state;
        }

        public int lightCoords() {
            return this.lightCoords;
        }

        public int overlayCoords() {
            return this.overlayCoords;
        }

        public int tintedColor() {
            return this.tintedColor;
        }

        public @Nullable TextureAtlasSprite sprite() {
            return this.sprite;
        }

        public PoseStack.@Nullable Pose sheetedDecalPose() {
            return this.sheetedDecalPose;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof Submit<?> other)) {
                return false;
            }

            return java.util.Objects.equals(this.renderType, other.renderType)
                && java.util.Objects.equals(this.pose, other.pose)
                && java.util.Objects.equals(this.model, other.model)
                && java.util.Objects.equals(this.state, other.state)
                && this.lightCoords == other.lightCoords
                && this.overlayCoords == other.overlayCoords
                && this.tintedColor == other.tintedColor
                && java.util.Objects.equals(this.sprite, other.sprite)
                && java.util.Objects.equals(this.sheetedDecalPose, other.sheetedDecalPose);
        }

        @Override
        public int hashCode() {
            return java.util.Objects
                .hash(
                this.renderType,
                this.pose,
                this.model,
                this.state,
                this.lightCoords,
                this.overlayCoords,
                this.tintedColor,
                this.sprite,
                this.sheetedDecalPose
                );
        }

        @Override
        public String toString() {
            return "Submit[renderType="
                + this.renderType
                + ", pose="
                + this.pose
                + ", model="
                + this.model
                + ", state="
                + this.state
                + ", lightCoords="
                + this.lightCoords
                + ", overlayCoords="
                + this.overlayCoords
                + ", tintedColor="
                + this.tintedColor
                + ", sprite="
                + this.sprite
                + ", sheetedDecalPose="
                + this.sheetedDecalPose
                + "]";
        }

        /**
         * MODIFIED for porting: iris entity_render_context MixinModelSubmit @Unique fields (its ModelStorage implementation) - each
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
        public Object batchKey() {
            return this.renderType;
        }

        @Override
        public float distanceToCameraSq() {
            return TranslucentSubmit.computeDistanceToCameraSq(this.pose.pose());
        }

        @Override
        public FeatureRendererType<ModelFeatureRenderer.Submit<S>> featureType() {
            return (FeatureRendererType<ModelFeatureRenderer.Submit<S>>)(FeatureRendererType)ModelFeatureRenderer.TYPE;
        }
    }
}
