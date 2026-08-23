package net.minecraft.client.renderer.feature;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

@OnlyIn(Dist.CLIENT)
public class NameTagFeatureRenderer extends RenderTypeFeatureRenderer<NameTagFeatureRenderer.Submit> {
    // MODIFIED for porting: iris entity_render_context MixinEntityRenderer @Unique constant
    private static final net.irisshaders.iris.shaderpack.materialmap.NamespacedId IRIS_NAME_TAG_ID = new net.irisshaders.iris.shaderpack.materialmap.NamespacedId("minecraft", "name_tag");

    public static final FeatureRendererType<NameTagFeatureRenderer.Submit> TYPE = FeatureRendererType.create("Name Tag");

    @Override
    /**
     * MODIFIED for porting: was iris's entity_render_context MixinEntityRenderer#setNameTagId (@Inject HEAD) and #resetId
     * (@Inject RETURN) plus its two @Unique fields - name tags get their own entity id for the pack. The {@code lastId} field
     * is only used across this one call, so it is a local here.
     */
    protected void buildGroup(final FeatureFrameContext context, final List<NameTagFeatureRenderer.Submit> submits) {
        int iris$lastId = -100;
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            it.unimi.dsi.fastutil.objects.Object2IntFunction<net.irisshaders.iris.shaderpack.materialmap.NamespacedId> irisEntityIds = net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getEntityIds();
            if (irisEntityIds != null) {
                iris$lastId = net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentRenderedEntity();
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentEntity(irisEntityIds.applyAsInt(IRIS_NAME_TAG_ID));
            }
        }

        try {
        NameTagFeatureRenderer.GlyphRenderer glyphRenderer = new NameTagFeatureRenderer.GlyphRenderer();

        for (NameTagFeatureRenderer.Submit nameTag : submits) {
            Font.PreparedText preparedText = prepareText(context.font(), nameTag);
            glyphRenderer.prepare(nameTag, nameTag.displayMode());
            preparedText.visit(glyphRenderer);
        }
        } finally {
            // MODIFIED for porting: was iris's entity_render_context MixinEntityRenderer#resetId (@Inject RETURN)
            if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                if (iris$lastId != -100) {
                    net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentEntity(iris$lastId);
                }
            }
        }
    }

    private static Font.PreparedText prepareText(final Font font, final NameTagFeatureRenderer.Submit nameTag) {
        return font.prepareText(nameTag.text().getVisualOrderText(), nameTag.x(), nameTag.y(), nameTag.color(), false, false, nameTag.backgroundColor());
    }

    @OnlyIn(Dist.CLIENT)
    private class GlyphRenderer implements Font.GlyphVisitor {
        private final Matrix4f pose = new Matrix4f();
        private int lightCoords = 15728880;
        private Font.DisplayMode displayMode = Font.DisplayMode.NORMAL;

        public void prepare(final NameTagFeatureRenderer.Submit submit, final Font.DisplayMode displayMode) {
            this.pose.set(submit.pose());
            this.lightCoords = submit.lightCoords();
            this.displayMode = displayMode;
        }

        @Override
        public void acceptRenderable(final TextRenderable renderable) {
            VertexConsumer builder = NameTagFeatureRenderer.this.getVertexBuilder(renderable.renderType(this.displayMode));
            renderable.render(this.pose, builder, this.lightCoords, false);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public record Submit(Matrix4fc pose, float x, float y, Component text, int lightCoords, int color, int backgroundColor, Font.DisplayMode displayMode)
        implements TranslucentSubmit {
        @Override
        public float distanceToCameraSq() {
            return TranslucentSubmit.computeDistanceToCameraSq(this.pose);
        }

        @Override
        public FeatureRendererType<NameTagFeatureRenderer.Submit> featureType() {
            return NameTagFeatureRenderer.TYPE;
        }
    }
}