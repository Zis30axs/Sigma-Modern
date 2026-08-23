package net.minecraft.client.renderer.feature;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.MatrixUtil;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class ItemFeatureRenderer extends RenderTypeFeatureRenderer<ItemFeatureRenderer.Submit> {
    public static final FeatureRendererType<ItemFeatureRenderer.Submit> TYPE = FeatureRendererType.create("Item");
    public static final Identifier ENCHANTED_GLINT_ARMOR = Identifier.withDefaultNamespace("textures/misc/enchanted_glint_armor.png");
    public static final Identifier ENCHANTED_GLINT_ITEM = Identifier.withDefaultNamespace("textures/misc/enchanted_glint_item.png");
    private static final float SPECIAL_FOIL_UI_SCALE = 0.5F;
    private static final float SPECIAL_FOIL_FIRST_PERSON_SCALE = 0.75F;
    private static final float SPECIAL_FOIL_TEXTURE_SCALE = 0.0078125F;
    public static final int NO_TINT = -1;
    private final QuadInstance quadInstance = new QuadInstance();

    @Override
    protected void buildGroup(final FeatureFrameContext context, final List<ItemFeatureRenderer.Submit> submits) {
        for (ItemFeatureRenderer.Submit submit : submits) {
            this.prepareSubmit(submit, false);
        }

        for (ItemFeatureRenderer.Submit submit : submits) {
            this.prepareSubmit(submit, true);
        }
    }

    private void prepareSubmit(final ItemFeatureRenderer.Submit submit, final boolean foil) {
        // MODIFIED for porting: was iris's entity_render_context MixinItemFeatureRenderer#iris$set (@Inject HEAD) and
        // #iris$clear (@Inject RETURN) - the submit carries the entity/block-entity/item ids the pack needs.
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            ((net.irisshaders.iris.mixinterface.ModelStorage)(Object)submit).iris$set();

            try {
                this.iris$prepareSubmit(submit, foil);
            } finally {
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentRenderedItem(0);
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentEntity(0);
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentBlockEntity(0);
            }

            return;
        }

        this.iris$prepareSubmit(submit, foil);
    }

    // MODIFIED for porting: original vanilla body of prepareSubmit
    private void iris$prepareSubmit(final ItemFeatureRenderer.Submit submit, final boolean foil) {
        if (foil) {
            this.prepareFoilSubmit(submit);
        } else if (submit.outlineColor() != 0) {
            this.prepareOutlineSubmit(submit);
        } else {
            this.prepareMainSubmit(submit);
        }
    }

    private void prepareMainSubmit(final ItemFeatureRenderer.Submit submit) {
        this.quadInstance.setLightCoords(submit.lightCoords());
        this.quadInstance.setOverlayCoords(submit.overlayCoords());

        for (BakedQuad quad : submit.quads()) {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            RenderType renderType = material.itemRenderType();
            this.quadInstance.setColor(getLayerColorSafe(submit.tintLayers(), material));
            this.getVertexBuilder(renderType).putBakedQuad(submit.pose(), quad, this.quadInstance);
        }
    }

    private void prepareOutlineSubmit(final ItemFeatureRenderer.Submit submit) {
        for (BakedQuad quad : submit.quads()) {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            RenderType renderType = material.itemRenderType().outline().orElse(null);
            if (renderType != null) {
                this.quadInstance.setColor(submit.outlineColor());
                this.getVertexBuilder(renderType).putBakedQuad(submit.pose(), quad, this.quadInstance);
            }
        }
    }

    private void prepareFoilSubmit(final ItemFeatureRenderer.Submit submit) {
        ItemStackRenderState.FoilType foilType = submit.foilType();
        if (foilType != ItemStackRenderState.FoilType.NONE) {
            PoseStack.Pose foilDecalPose = foilType == ItemStackRenderState.FoilType.SPECIAL
                ? computeFoilDecalPose(submit.displayContext(), submit.pose())
                : null;

            for (BakedQuad quad : submit.quads()) {
                VertexConsumer foilBuffer = this.getFoilBuffer(quad.materialInfo().itemRenderType(), foilDecalPose);
                foilBuffer.putBakedQuad(submit.pose(), quad, this.quadInstance);
            }
        }
    }

    private VertexConsumer getFoilBuffer(final RenderType renderType, final PoseStack.@Nullable Pose foilDecalPose) {
        RenderType foilRenderType = useTransparentGlint(renderType) ? RenderTypes.glintTranslucent() : RenderTypes.glint();
        VertexConsumer foilBuffer = this.getVertexBuilder(foilRenderType);
        if (foilDecalPose != null) {
            foilBuffer = new SheetedDecalTextureGenerator(foilBuffer, foilDecalPose, 0.0078125F);
        }

        return foilBuffer;
    }

    private static PoseStack.Pose computeFoilDecalPose(final ItemDisplayContext type, final PoseStack.Pose pose) {
        PoseStack.Pose foilDecalPose = pose.copy();
        if (type == ItemDisplayContext.GUI) {
            MatrixUtil.mulComponentWise(foilDecalPose.pose(), 0.5F);
        } else if (type.firstPerson()) {
            MatrixUtil.mulComponentWise(foilDecalPose.pose(), 0.75F);
        }

        return foilDecalPose;
    }

    private static boolean useTransparentGlint(final RenderType renderType) {
        return Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency() && renderType.outputTarget() == OutputTarget.ITEM_ENTITY_TARGET;
    }

    private static int getLayerColorSafe(final int[] layers, final int layer) {
        return layer >= 0 && layer < layers.length ? layers[layer] : -1;
    }

    private static int getLayerColorSafe(final int[] tintLayers, final BakedQuad.MaterialInfo material) {
        return material.isTinted() ? getLayerColorSafe(tintLayers, material.tintIndex()) : -1;
    }

    @OnlyIn(Dist.CLIENT)
    /**
     * MODIFIED for porting: iris's entity_render_context MixinItemSubmit adds four mutable {@code @Unique} fields to this record
     * (see the ModelStorage block below), which a record cannot have. It was therefore rewritten as a plain final class with
     * the same components and accessors; {@code equals}, {@code hashCode} and {@code toString} are implemented exactly as
     * the record's generated ones (the captured ids are set after construction and are deliberately not part of them).
     */
    public static final class Submit implements TranslucentSubmit, net.irisshaders.iris.mixinterface.ModelStorage {
        private final PoseStack.Pose pose;
        private final ItemDisplayContext displayContext;
        private final int lightCoords;
        private final int overlayCoords;
        private final int outlineColor;
        private final int[] tintLayers;
        private final List<BakedQuad> quads;
        private final ItemStackRenderState.FoilType foilType;

        public Submit(
            final PoseStack.Pose pose,
            final ItemDisplayContext displayContext,
            final int lightCoords,
            final int overlayCoords,
            final int outlineColor,
            final int[] tintLayers,
            final List<BakedQuad> quads,
            final ItemStackRenderState.FoilType foilType
        ) {
            this.pose = pose;
            this.displayContext = displayContext;
            this.lightCoords = lightCoords;
            this.overlayCoords = overlayCoords;
            this.outlineColor = outlineColor;
            this.tintLayers = tintLayers;
            this.quads = quads;
            this.foilType = foilType;
        }

        public PoseStack.Pose pose() {
            return this.pose;
        }

        public ItemDisplayContext displayContext() {
            return this.displayContext;
        }

        public int lightCoords() {
            return this.lightCoords;
        }

        public int overlayCoords() {
            return this.overlayCoords;
        }

        public int outlineColor() {
            return this.outlineColor;
        }

        public int[] tintLayers() {
            return this.tintLayers;
        }

        public List<BakedQuad> quads() {
            return this.quads;
        }

        public ItemStackRenderState.FoilType foilType() {
            return this.foilType;
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
                && java.util.Objects.equals(this.displayContext, other.displayContext)
                && this.lightCoords == other.lightCoords
                && this.overlayCoords == other.overlayCoords
                && this.outlineColor == other.outlineColor
                && java.util.Arrays.equals(this.tintLayers, other.tintLayers)
                && java.util.Objects.equals(this.quads, other.quads)
                && java.util.Objects.equals(this.foilType, other.foilType);
        }

        @Override
        public int hashCode() {
            return java.util.Objects
                .hash(
                this.pose,
                this.displayContext,
                this.lightCoords,
                this.overlayCoords,
                this.outlineColor,
                java.util.Arrays.hashCode(this.tintLayers),
                this.quads,
                this.foilType
                );
        }

        @Override
        public String toString() {
            return "Submit[pose="
                + this.pose
                + ", displayContext="
                + this.displayContext
                + ", lightCoords="
                + this.lightCoords
                + ", overlayCoords="
                + this.overlayCoords
                + ", outlineColor="
                + this.outlineColor
                + ", tintLayers="
                + java.util.Arrays.toString(this.tintLayers)
                + ", quads="
                + this.quads
                + ", foilType="
                + this.foilType
                + "]";
        }

        /**
         * MODIFIED for porting: iris entity_render_context MixinItemSubmit @Unique fields (its ModelStorage implementation) - each
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

        public boolean hasTranslucency() {
            for (BakedQuad quad : this.quads()) {
                if (quad.materialInfo().itemRenderType().hasBlending()) {
                    return true;
                }
            }

            return false;
        }

        @Override
        public float distanceToCameraSq() {
            return TranslucentSubmit.computeDistanceToCameraSq(this.pose.pose());
        }

        @Override
        public FeatureRendererType<ItemFeatureRenderer.Submit> featureType() {
            return ItemFeatureRenderer.TYPE;
        }
    }
}