package net.minecraft.client.renderer.blockentity;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.EndPortalRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public abstract class AbstractEndPortalRenderer<T extends TheEndPortalBlockEntity, S extends EndPortalRenderState> implements BlockEntityRenderer<T, S> {
    private static final Vector3fc FROM = new Vector3f(0.0F, 0.0F, 0.0F);
    private static final Vector3fc TO = new Vector3f(1.0F, 1.0F, 1.0F);
    private static final Map<Direction, List<Vector3fc>> FACES = Util.makeEnumMap(
        Direction.class,
        direction -> {
            FaceInfo faceInfo = FaceInfo.fromFacing(direction);
            return List.of(
                faceInfo.getVertexInfo(0).select(FROM, TO),
                faceInfo.getVertexInfo(1).select(FROM, TO),
                faceInfo.getVertexInfo(2).select(FROM, TO),
                faceInfo.getVertexInfo(3).select(FROM, TO)
            );
        }
    );
    public static final Identifier END_SKY_LOCATION = Identifier.withDefaultNamespace("textures/environment/end_sky.png");
    public static final Identifier END_PORTAL_LOCATION = Identifier.withDefaultNamespace("textures/entity/end_portal/end_portal.png");
    private static final List<Direction> ALL_FACES = List.of(Direction.values());

    public void extractRenderState(
        final T blockEntity,
        final S state,
        final float partialTicks,
        final Vec3 cameraPosition,
        final ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress
    ) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
        state.facesToShow.clear();

        for (Direction direction : Direction.values()) {
            if (blockEntity.shouldRenderFace(direction)) {
                state.facesToShow.add(direction);
            }
        }
    }

    // MODIFIED for porting: iris MixinTheEndPortalRenderer @Unique constants - the tint of its flat end-portal replacement
    private static final float IRIS_RED = 0.075F;

    private static final float IRIS_GREEN = 0.15F;

    private static final float IRIS_BLUE = 0.2F;

    protected static void submitCube(
        final Collection<Direction> facesToShow, final RenderType renderType, final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector
    ) {
        if (!facesToShow.isEmpty()) {
            // MODIFIED for porting: was iris's MixinTheEndPortalRenderer#iris$renderType (@ModifyArg index 1 on
            // SubmitNodeCollector#submitCustomGeometry) - with a shader pack loaded the portal is drawn as ordinary geometry
            // with the plain end-portal texture, because the vanilla end-portal render type relies on a post effect the pack
            // replaces.
            RenderType effectiveRenderType = net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && net.irisshaders.iris.Iris.getCurrentPack().isPresent()
                ? net.minecraft.client.renderer.rendertype.RenderTypes.entitySolid(END_PORTAL_LOCATION)
                : renderType;
            submitNodeCollector.submitCustomGeometry(poseStack, effectiveRenderType, (pose, buffer) -> {
                // MODIFIED for porting: was iris's MixinTheEndPortalRenderer#iris$onRender (@Inject HEAD of
                // lambda$submitCube$0, cancellable) - the flat animated replacement geometry.
                if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && net.irisshaders.iris.Iris.getCurrentPack().isPresent()) {
                    iris$submitFlatPortal(facesToShow, pose, buffer);
                    return;
                }

                for (Direction direction : facesToShow) {
                    for (Vector3fc faceVertex : FACES.get(direction)) {
                        buffer.addVertex(pose, faceVertex);
                    }
                }
            });
        }
    }

    /**
     * MODIFIED for porting: the body of iris's MixinTheEndPortalRenderer#iris$onRender. Upstream additionally carries an unused
     * {@code @Unique quad(...)} helper (nothing calls it), which is not ported.
     */
    private static void iris$submitFlatPortal(
        final Collection<Direction> facesToShow, final PoseStack.Pose pose, final com.mojang.blaze3d.vertex.VertexConsumer buffer
    ) {
        int overlay = net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;
        int light = net.minecraft.util.LightCoordsUtil.FULL_BRIGHT;
        // animation with a period of 100 seconds.
        // note that texture coordinates are wrapping, not clamping.
        float progress = net.irisshaders.iris.uniforms.SystemTimeUniforms.TIMER.getFrameTimeCounter() * 0.01F % 1.0F;

        for (Direction direction : facesToShow) {
            float nx = direction.getStepX();
            float ny = direction.getStepY();
            float nz = direction.getStepZ();
            List<Vector3fc> vertices = FACES.get(direction);
            Vector3fc vertex0 = vertices.get(0);
            buffer.addVertex(pose, vertex0.x(), vertex0.y(), vertex0.z())
                .setColor(IRIS_RED, IRIS_GREEN, IRIS_BLUE, 1.0F)
                .setUv(0.0F + progress, 0.0F + progress)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
            Vector3fc vertex1 = vertices.get(1);
            buffer.addVertex(pose, vertex1.x(), vertex1.y(), vertex1.z())
                .setColor(IRIS_RED, IRIS_GREEN, IRIS_BLUE, 1.0F)
                .setUv(0.0F + progress, 0.2F + progress)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
            Vector3fc vertex2 = vertices.get(2);
            buffer.addVertex(pose, vertex2.x(), vertex2.y(), vertex2.z())
                .setColor(IRIS_RED, IRIS_GREEN, IRIS_BLUE, 1.0F)
                .setUv(0.2F + progress, 0.2F + progress)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
            Vector3fc vertex3 = vertices.get(3);
            buffer.addVertex(pose, vertex3.x(), vertex3.y(), vertex3.z())
                .setColor(IRIS_RED, IRIS_GREEN, IRIS_BLUE, 1.0F)
                .setUv(0.2F + progress, 0.0F + progress)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
        }
    }

    public static void submitSpecial(final RenderType renderType, final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector) {
        submitCube(ALL_FACES, renderType, poseStack, submitNodeCollector);
    }

    public static void getExtents(final Consumer<Vector3fc> output) {
        FACES.values().forEach(vertices -> vertices.forEach(output));
    }
}