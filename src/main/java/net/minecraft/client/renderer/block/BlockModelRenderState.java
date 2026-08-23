package net.minecraft.client.renderer.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.MatrixUtil;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class BlockModelRenderState
    implements net.irisshaders.iris.mixinterface.BlockModelRenderStateExtension { // MODIFIED for porting: iris entity_render_context BlockModelRenderStateMixin
    // MODIFIED for porting: iris entity_render_context BlockModelRenderStateMixin @Unique field
    private net.minecraft.world.level.block.state.BlockState iris$block;

    @Override
    public void setBlock(final net.minecraft.world.level.block.state.BlockState block) {
        this.iris$block = block;
    }

    // MODIFIED for porting: was iris's entity_render_context BlockModelRenderStateMixin#iris$setupId (@Unique)
    private void iris$setupId() {
        if (!net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() || net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getBlockStateIds() == null) {
            return;
        }

        net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentBlockEntity(1);
        net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentRenderedItem(net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getBlockStateIds().getOrDefault(this.iris$block, 0));
    }

    public static final int[] EMPTY_TINTS = new int[0];
    private @Nullable List<BlockStateModelPart> modelParts;
    private @Nullable Matrix4fc transformation;
    private @Nullable RenderType renderType;
    private @Nullable SpecialModelRenderer<?> specialRenderer;
    private @Nullable Matrix4fc specialRendererTransformation;
    private @Nullable IntList tintLayers;
    public int blockLightCoords;
    private @Nullable RandomSource randomSource;

    public void clear() {
        this.modelParts = null;
        this.transformation = null;
        this.renderType = null;
        this.specialRenderer = null;
        this.specialRendererTransformation = null;
        this.blockLightCoords = 0;
        if (this.tintLayers != null) {
            this.tintLayers.clear();
        }
    }

    public IntList tintLayers() {
        if (this.tintLayers == null) {
            this.tintLayers = new IntArrayList();
        }

        return this.tintLayers;
    }

    public <T> void setupSpecialModel(final SpecialModelRenderer<T> renderer, final Matrix4fc transformation) {
        this.specialRenderer = renderer;
        this.specialRendererTransformation = identityToNull(transformation);
    }

    public List<BlockStateModelPart> setupModel(final Matrix4fc transformation, final boolean hasTranslucency) {
        this.transformation = identityToNull(transformation);
        this.renderType = hasTranslucency ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockItemSheet();
        if (this.modelParts == null) {
            this.modelParts = new ObjectArrayList<>();
        } else {
            this.modelParts.clear();
        }

        return this.modelParts;
    }

    public void submit(
        final PoseStack poseStack,
        final SubmitNodeCollector submitNodeCollector,
        final int externalLightCoords,
        final int overlayCoords,
        final int outlineColor
    ) {
        // MODIFIED for porting: was iris's entity_render_context BlockModelRenderStateMixin#onRender (@Inject HEAD) and
        // #onRenderEnd (@Inject TAIL); the @Share("lastBState") LocalIntRef is a local here.
        int iris$lastBState = net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() ? net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentRenderedBlockEntity() : 0;
        this.iris$setupId();
        this.submitModel(this.renderType, poseStack, submitNodeCollector, externalLightCoords, overlayCoords, outlineColor);
        if (this.specialRenderer != null) {
            int lightCoords = LightCoordsUtil.max(externalLightCoords, this.blockLightCoords);
            if (this.specialRendererTransformation != null) {
                poseStack.pushPose();
                poseStack.mulPose(this.specialRendererTransformation);
                submitSpecialRenderer(this.specialRenderer, poseStack, submitNodeCollector, lightCoords, overlayCoords, outlineColor);
                poseStack.popPose();
            } else {
                submitSpecialRenderer(this.specialRenderer, poseStack, submitNodeCollector, lightCoords, overlayCoords, outlineColor);
            }
        }

        // MODIFIED for porting: iris entity_render_context BlockModelRenderStateMixin#onRenderEnd (@Inject TAIL)
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentBlockEntity(iris$lastBState);
            net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentRenderedItem(0);
        }
    }

    private static @Nullable Matrix4fc identityToNull(final Matrix4fc transformation) {
        return MatrixUtil.checkPropertyRaw(transformation, 4) ? null : transformation;
    }

    private void submitModel(
        final RenderType renderType,
        final PoseStack poseStack,
        final SubmitNodeCollector submitNodeCollector,
        final int externalLightCoords,
        final int overlayCoords,
        final int outlineColor
    ) {
        if (this.modelParts != null && !this.modelParts.isEmpty()) {
            List<BlockStateModelPart> modelPartsCopy = new ObjectArrayList<>(this.modelParts);
            int[] tints = this.tintLayers != null ? this.tintLayers.toArray(EMPTY_TINTS) : EMPTY_TINTS;
            int lightCoords = LightCoordsUtil.max(externalLightCoords, this.blockLightCoords);
            if (this.transformation != null) {
                poseStack.pushPose();
                poseStack.mulPose(this.transformation);
                submitNodeCollector.submitBlockModel(poseStack, renderType, modelPartsCopy, tints, lightCoords, overlayCoords, outlineColor);
                poseStack.popPose();
            } else {
                submitNodeCollector.submitBlockModel(poseStack, renderType, modelPartsCopy, tints, lightCoords, overlayCoords, outlineColor);
            }
        }
    }

    private static void submitSpecialRenderer(
        final SpecialModelRenderer<?> renderer,
        final PoseStack poseStack,
        final SubmitNodeCollector submitNodeCollector,
        final int lightCoords,
        final int overlayCoords,
        final int outlineColor
    ) {
        renderer.submit(null, poseStack, submitNodeCollector, lightCoords, overlayCoords, false, outlineColor);
    }

    public void submitOnlyOutline(
        final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final int lightCoords, final int overlayCoords, final int outlineColor
    ) {
        this.submitModel(RenderTypes.outline(TextureAtlas.LOCATION_BLOCKS), poseStack, submitNodeCollector, lightCoords, overlayCoords, outlineColor);
    }

    public void submitWithZOffset(
        final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final int lightCoords, final int overlayCoords, final int outlineColor
    ) {
        this.submitModel(
            RenderTypes.entitySolidZOffsetForward(TextureAtlas.LOCATION_BLOCKS), poseStack, submitNodeCollector, lightCoords, overlayCoords, outlineColor
        );
    }

    public boolean isEmpty() {
        return this.modelParts == null && this.specialRenderer == null;
    }

    public RandomSource scratchRandomSource(final long seed) {
        if (this.randomSource == null) {
            this.randomSource = RandomSource.create(seed);
        } else {
            this.randomSource.setSeed(seed);
        }

        return this.randomSource;
    }
}