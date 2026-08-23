package net.minecraft.client.renderer.feature;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.submit.BatchableSubmit;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class CustomFeatureRenderer extends RenderTypeFeatureRenderer<CustomFeatureRenderer.Submit> {
    public static final FeatureRendererType<CustomFeatureRenderer.Submit> TYPE = FeatureRendererType.create("Custom");

    @Override
    protected void buildGroup(final FeatureFrameContext context, final List<CustomFeatureRenderer.Submit> submits) {
        try {
            for (CustomFeatureRenderer.Submit submit : submits) {
                // MODIFIED for porting: was iris's entity_render_context MixinCustomFeatureRenderer#iris$set (@Inject at the
                // INVOKE of getVertexBuilder, with @Local Submit)
                if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                    ((net.irisshaders.iris.mixinterface.ModelStorage)submit).iris$set();
                }

                VertexConsumer builder = this.getVertexBuilder(submit.renderType());
                submit.customGeometryRenderer().render(submit.pose(), builder);
            }
        } finally {
            // MODIFIED for porting: was iris's entity_render_context MixinCustomFeatureRenderer#iris$unset (@Inject RETURN)
            if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentRenderedItem(0);
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentEntity(0);
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentBlockEntity(0);
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    /**
     * MODIFIED for porting: iris's entity_render_context MixinCustomGeometrySubmit adds four mutable {@code @Unique} fields to this record
     * (see the ModelStorage block below), which a record cannot have. It was therefore rewritten as a plain final class with
     * the same components and accessors; {@code equals}, {@code hashCode} and {@code toString} are implemented exactly as
     * the record's generated ones (the captured ids are set after construction and are deliberately not part of them).
     */
    public static final class Submit implements BatchableSubmit, net.irisshaders.iris.mixinterface.ModelStorage {
        private final PoseStack.Pose pose;
        private final RenderType renderType;
        private final SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer;

        public Submit(
            final PoseStack.Pose pose,
            final RenderType renderType,
            final SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer
        ) {
            this.pose = pose;
            this.renderType = renderType;
            this.customGeometryRenderer = customGeometryRenderer;
            // MODIFIED for porting: was iris's entity_render_context MixinCustomGeometrySubmit#iris$capture2
            // (@Inject into <init> at RETURN)
            if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                this.iris$capture();
            }
        }

        public PoseStack.Pose pose() {
            return this.pose;
        }

        public RenderType renderType() {
            return this.renderType;
        }

        public SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer() {
            return this.customGeometryRenderer;
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
                && java.util.Objects.equals(this.customGeometryRenderer, other.customGeometryRenderer);
        }

        @Override
        public int hashCode() {
            return java.util.Objects
                .hash(
                this.pose,
                this.renderType,
                this.customGeometryRenderer
                );
        }

        @Override
        public String toString() {
            return "Submit[pose="
                + this.pose
                + ", renderType="
                + this.renderType
                + ", customGeometryRenderer="
                + this.customGeometryRenderer
                + "]";
        }

        /**
         * MODIFIED for porting: iris entity_render_context MixinCustomGeometrySubmit @Unique fields (its ModelStorage implementation) - each
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
        public FeatureRendererType<CustomFeatureRenderer.Submit> featureType() {
            return CustomFeatureRenderer.TYPE;
        }
    }
}