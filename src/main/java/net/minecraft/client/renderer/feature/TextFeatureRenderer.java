package net.minecraft.client.renderer.feature;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

@OnlyIn(Dist.CLIENT)
public class TextFeatureRenderer extends RenderTypeFeatureRenderer<TextFeatureRenderer.Submit> {
    public static final FeatureRendererType<TextFeatureRenderer.Submit> TYPE = FeatureRendererType.create("Text");

    @Override
    protected void buildGroup(final FeatureFrameContext context, final List<TextFeatureRenderer.Submit> submits) {
        // MODIFIED for porting: iris entity_render_context MixinTextFeatureRenderer @Unique field - upstream keeps it on the
        // renderer, but it is only read and written inside this one call, so it is a local here.
        boolean iris$hasBE = false;
        Font font = context.font();
        TextFeatureRenderer.GlyphRenderer glyphRenderer = new TextFeatureRenderer.GlyphRenderer();

        try {
        for (TextFeatureRenderer.Submit submit : submits) {
            // MODIFIED for porting: was iris's entity_render_context MixinTextFeatureRenderer#iris$set (@Inject at the first
            // INVOKE of TextFeatureRenderer$Submit#pose, with @Local Submit) - restores the ids captured at submit time, and
            // turns block-entity tagging on for text that belongs to a block entity.
            if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                ((net.irisshaders.iris.mixinterface.ModelStorage)submit).iris$set();
                if (((net.irisshaders.iris.mixinterface.ModelStorage)submit).iris$wasBE()) {
                    iris$hasBE = true;
                    net.irisshaders.iris.vertices.ImmediateState.isRenderingBEs = net.irisshaders.iris.Iris.isPackInUseQuick();
                } else if (iris$hasBE) {
                    iris$hasBE = false;
                    net.irisshaders.iris.vertices.ImmediateState.isRenderingBEs = false;
                }
            }

            glyphRenderer.pose.set(submit.pose());
            glyphRenderer.lightCoords = submit.lightCoords();
            glyphRenderer.displayMode = submit.displayMode();
            if (submit.outlineColor() == 0) {
                Font.PreparedText text = font.prepareText(
                    submit.string(), submit.x(), submit.y(), submit.color(), submit.dropShadow(), false, submit.backgroundColor()
                );
                text.visit(glyphRenderer);
            } else {
                Font.PreparedText outline = font.prepare8xTextOutline(submit.string(), submit.x(), submit.y(), submit.outlineColor());
                Font.PreparedText text = font.prepareText(submit.string(), submit.x(), submit.y(), submit.color(), false, false, 0);
                glyphRenderer.displayMode = Font.DisplayMode.NORMAL;
                outline.visit(glyphRenderer);
                glyphRenderer.displayMode = Font.DisplayMode.POLYGON_OFFSET;
                text.visit(glyphRenderer);
            }
        }
        } finally {
            // MODIFIED for porting: was iris's entity_render_context MixinTextFeatureRenderer#iris$clear (@Inject RETURN)
            if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentRenderedItem(0);
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentEntity(0);
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentBlockEntity(0);
                net.irisshaders.iris.vertices.ImmediateState.isRenderingBEs = false;
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    private class GlyphRenderer implements Font.GlyphVisitor {
        private final Matrix4f pose = new Matrix4f();
        private int lightCoords = 15728880;
        private Font.DisplayMode displayMode = Font.DisplayMode.NORMAL;

        @Override
        public void acceptRenderable(final TextRenderable renderable) {
            VertexConsumer builder = TextFeatureRenderer.this.getVertexBuilder(renderable.renderType(this.displayMode));
            renderable.render(this.pose, builder, this.lightCoords, false);
        }
    }

    @OnlyIn(Dist.CLIENT)
    /**
     * MODIFIED for porting: iris's entity_render_context MixinTextSubmit adds four mutable {@code @Unique} fields to this record
     * (see the ModelStorage block below), which a record cannot have. It was therefore rewritten as a plain final class with
     * the same components and accessors; {@code equals}, {@code hashCode} and {@code toString} are implemented exactly as
     * the record's generated ones (the captured ids are set after construction and are deliberately not part of them).
     */
    public static final class Submit implements SubmitNode, net.irisshaders.iris.mixinterface.ModelStorage {
        private final Matrix4fc pose;
        private final float x;
        private final float y;
        private final FormattedCharSequence string;
        private final boolean dropShadow;
        private final Font.DisplayMode displayMode;
        private final int lightCoords;
        private final int color;
        private final int backgroundColor;
        private final int outlineColor;

        public Submit(
            final Matrix4fc pose,
            final float x,
            final float y,
            final FormattedCharSequence string,
            final boolean dropShadow,
            final Font.DisplayMode displayMode,
            final int lightCoords,
            final int color,
            final int backgroundColor,
            final int outlineColor
        ) {
            this.pose = pose;
            this.x = x;
            this.y = y;
            this.string = string;
            this.dropShadow = dropShadow;
            this.displayMode = displayMode;
            this.lightCoords = lightCoords;
            this.color = color;
            this.backgroundColor = backgroundColor;
            this.outlineColor = outlineColor;
        }

        public Matrix4fc pose() {
            return this.pose;
        }

        public float x() {
            return this.x;
        }

        public float y() {
            return this.y;
        }

        public FormattedCharSequence string() {
            return this.string;
        }

        public boolean dropShadow() {
            return this.dropShadow;
        }

        public Font.DisplayMode displayMode() {
            return this.displayMode;
        }

        public int lightCoords() {
            return this.lightCoords;
        }

        public int color() {
            return this.color;
        }

        public int backgroundColor() {
            return this.backgroundColor;
        }

        public int outlineColor() {
            return this.outlineColor;
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
                && this.x == other.x
                && this.y == other.y
                && java.util.Objects.equals(this.string, other.string)
                && this.dropShadow == other.dropShadow
                && java.util.Objects.equals(this.displayMode, other.displayMode)
                && this.lightCoords == other.lightCoords
                && this.color == other.color
                && this.backgroundColor == other.backgroundColor
                && this.outlineColor == other.outlineColor;
        }

        @Override
        public int hashCode() {
            return java.util.Objects
                .hash(
                this.pose,
                this.x,
                this.y,
                this.string,
                this.dropShadow,
                this.displayMode,
                this.lightCoords,
                this.color,
                this.backgroundColor,
                this.outlineColor
                );
        }

        @Override
        public String toString() {
            return "Submit[pose="
                + this.pose
                + ", x="
                + this.x
                + ", y="
                + this.y
                + ", string="
                + this.string
                + ", dropShadow="
                + this.dropShadow
                + ", displayMode="
                + this.displayMode
                + ", lightCoords="
                + this.lightCoords
                + ", color="
                + this.color
                + ", backgroundColor="
                + this.backgroundColor
                + ", outlineColor="
                + this.outlineColor
                + "]";
        }

        /**
         * MODIFIED for porting: iris entity_render_context MixinTextSubmit @Unique fields (its ModelStorage implementation) - each
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
        public FeatureRendererType<TextFeatureRenderer.Submit> featureType() {
            return TextFeatureRenderer.TYPE;
        }
    }
}