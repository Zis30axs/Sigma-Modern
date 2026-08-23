package net.minecraft.client.gui.font;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public record GlyphRenderTypes(RenderType normal, RenderType seeThrough, RenderType polygonOffset, RenderPipeline guiPipeline) {
    public static GlyphRenderTypes createForGrayscaleTexture(final Identifier name) {
        return new GlyphRenderTypes(
            RenderTypes.textGrayscale(name),
            RenderTypes.textGrayscaleSeeThrough(name),
            RenderTypes.textGrayscalePolygonOffset(name),
            RenderPipelines.GUI_TEXT_GRAYSCALE
        );
    }

    public static GlyphRenderTypes createForColorTexture(final Identifier name) {
        return new GlyphRenderTypes(RenderTypes.text(name), RenderTypes.textSeeThrough(name), RenderTypes.textPolygonOffset(name), RenderPipelines.GUI_TEXT);
    }

    /**
     * MODIFIED for porting: was iris's entity_render_context MixinGlyphRenderType#iris$select (@WrapMethod) - text drawn as part
     * of a block entity is tagged so the shader pack sees it as block-entity geometry.
     */
    public RenderType select(final Font.DisplayMode mode) {
        RenderType renderType = switch (mode) {
            case NORMAL -> this.normal;
            case SEE_THROUGH -> this.seeThrough;
            case POLYGON_OFFSET -> this.polygonOffset;
        };

        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && net.irisshaders.iris.vertices.ImmediateState.isRenderingBEs) {
            renderType = net.irisshaders.iris.layer.OuterWrappedRenderType.wrapExactlyOnce("iris:block_entity", renderType, net.irisshaders.iris.layer.BlockEntityRenderStateShard.INSTANCE);
        }

        return renderType;
    }
}