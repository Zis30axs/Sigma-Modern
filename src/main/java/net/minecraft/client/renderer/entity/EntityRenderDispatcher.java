package net.minecraft.client.renderer.entity;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class EntityRenderDispatcher implements ResourceManagerReloadListener {
    /**
     * MODIFIED for porting: iris entity_render_context MixinEntityRenderDispatcher @Unique constants and the body of its
     * #iris$beginEntityRender - it tells the shader pack which entity is being drawn.
     */
    private static final net.irisshaders.iris.shaderpack.materialmap.NamespacedId IRIS_CURRENT_PLAYER = new net.irisshaders.iris.shaderpack.materialmap.NamespacedId("minecraft", "current_player");

    private static final net.irisshaders.iris.shaderpack.materialmap.NamespacedId IRIS_CONVERTING_VILLAGER = new net.irisshaders.iris.shaderpack.materialmap.NamespacedId("minecraft", "zombie_villager_converting");

    private static final it.unimi.dsi.fastutil.objects.Object2ObjectMap<net.minecraft.world.entity.EntityType<?>, net.irisshaders.iris.shaderpack.materialmap.NamespacedId> IRIS_ENTITY_IDS =
        new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>();

    private static <S extends net.minecraft.client.renderer.entity.state.EntityRenderState> void iris$beginEntityRender(final S entity) {
        if (!net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            return;
        }

        it.unimi.dsi.fastutil.objects.Object2IntFunction<net.irisshaders.iris.shaderpack.materialmap.NamespacedId> entityIds = net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getEntityIds();

        if (entityIds == null || !net.irisshaders.iris.vertices.ImmediateState.isRenderingLevel) {
            return;
        }

        int intId;

        // TODO: Add special types

        if (entity instanceof net.minecraft.client.renderer.entity.state.ZombieVillagerRenderState zombie
            && zombie.isConverting
            && net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.hasVillagerConversionId()) {
            intId = entityIds.applyAsInt(IRIS_CONVERTING_VILLAGER);
        } else if (entity instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState ars
            && net.minecraft.client.Minecraft.getInstance().getCameraEntity() instanceof net.minecraft.client.player.AbstractClientPlayer acs
            && acs.getId() == ars.id) {
            if (entityIds.containsKey(IRIS_CURRENT_PLAYER)) {
                intId = entityIds.getInt(IRIS_CURRENT_PLAYER);
            } else {
                intId = entityIds.applyAsInt(iris$entityId(entity));
            }
        } else {
            intId = entityIds.applyAsInt(iris$entityId(entity));
        }

        net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentEntity(intId);
    }

    private static net.irisshaders.iris.shaderpack.materialmap.NamespacedId iris$entityId(final net.minecraft.client.renderer.entity.state.EntityRenderState entity) {
        return IRIS_ENTITY_IDS
            .computeIfAbsent(
                entity.entityType,
                k -> {
                    net.minecraft.resources.Identifier entityId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(entity.entityType);
                    return new net.irisshaders.iris.shaderpack.materialmap.NamespacedId(entityId.getNamespace(), entityId.getPath());
                }
            );
    }

    // MODIFIED for porting: was iris's MixinEntityRenderDispatcher#iris$maybeSuppressShadow (@Unique)
    private static boolean iris$shouldSuppressVanillaEntityShadow() {
        if (!net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            return false;
        }

        net.irisshaders.iris.pipeline.WorldRenderingPipeline pipeline = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
        return pipeline != null && pipeline.shouldDisableVanillaEntityShadows();
    }

    private Map<EntityType<?>, EntityRenderer<?, ?>> renderers = ImmutableMap.of();
    private Map<PlayerModelType, AvatarRenderer<AbstractClientPlayer>> playerRenderers = Map.of();
    private Map<PlayerModelType, AvatarRenderer<ClientMannequin>> mannequinRenderers = Map.of();
    public final TextureManager textureManager;
    public @Nullable Camera camera;
    public Entity crosshairPickEntity;
    private final BlockModelResolver blockModelResolver;
    private final ItemModelResolver itemModelResolver;
    private final MapRenderer mapRenderer;
    private final ItemInHandRenderer itemInHandRenderer;
    private final AtlasManager atlasManager;
    private final Font font;
    public final Options options;
    private final Supplier<EntityModelSet> entityModels;
    private final EquipmentAssetManager equipmentAssets;
    private final PlayerSkinRenderCache playerSkinRenderCache;

    public <E extends Entity> int getPackedLightCoords(final E entity, final float partialTickTime) {
        return this.getRenderer(entity).getPackedLightCoords(entity, partialTickTime);
    }

    public EntityRenderDispatcher(
        final Minecraft minecraft,
        final TextureManager textureManager,
        final BlockModelResolver blockModelResolver,
        final ItemModelResolver itemModelResolver,
        final MapRenderer mapRenderer,
        final AtlasManager atlasManager,
        final Font font,
        final Options options,
        final Supplier<EntityModelSet> entityModels,
        final EquipmentAssetManager equipmentAssets,
        final PlayerSkinRenderCache playerSkinRenderCache
    ) {
        this.textureManager = textureManager;
        this.blockModelResolver = blockModelResolver;
        this.itemModelResolver = itemModelResolver;
        this.mapRenderer = mapRenderer;
        this.atlasManager = atlasManager;
        this.playerSkinRenderCache = playerSkinRenderCache;
        this.itemInHandRenderer = new ItemInHandRenderer(minecraft, this, itemModelResolver);
        this.font = font;
        this.options = options;
        this.entityModels = entityModels;
        this.equipmentAssets = equipmentAssets;
    }

    public <T extends Entity> EntityRenderer<? super T, ?> getRenderer(final T entity) {
        return (EntityRenderer<? super T, ?>)(switch (entity) {
            case AbstractClientPlayer player -> this.getAvatarRenderer(this.playerRenderers, player);
            case ClientMannequin mannequin -> this.getAvatarRenderer(this.mannequinRenderers, mannequin);
            default -> (EntityRenderer)this.renderers.get(entity.getType());
        });
    }

    public AvatarRenderer<AbstractClientPlayer> getPlayerRenderer(final AbstractClientPlayer player) {
        return this.getAvatarRenderer(this.playerRenderers, player);
    }

    private <T extends Avatar & ClientAvatarEntity> AvatarRenderer<T> getAvatarRenderer(final Map<PlayerModelType, AvatarRenderer<T>> renderers, final T entity) {
        PlayerModelType model = entity.getSkin().model();
        AvatarRenderer<T> playerRenderer = renderers.get(model);
        return playerRenderer != null ? playerRenderer : renderers.get(PlayerModelType.WIDE);
    }

    public <S extends EntityRenderState> EntityRenderer<?, ? super S> getRenderer(final S entityRenderState) {
        if (entityRenderState instanceof AvatarRenderState player) {
            PlayerModelType model = player.skin.model();
            EntityRenderer<? extends Avatar, ?> playerRenderer = (EntityRenderer<? extends Avatar, ?>)this.playerRenderers.get(model);
            return (EntityRenderer<?, ? super S>)(playerRenderer != null ? playerRenderer : (EntityRenderer)this.playerRenderers.get(PlayerModelType.WIDE));
        } else {
            return (EntityRenderer<?, ? super S>)this.renderers.get(entityRenderState.entityType);
        }
    }

    public void prepare(final Camera camera, final Entity crosshairPickEntity) {
        this.camera = camera;
        this.crosshairPickEntity = crosshairPickEntity;
    }

    public <E extends Entity> boolean shouldRender(final E entity, final Frustum culler, final double camX, final double camY, final double camZ) {
        EntityRenderer<? super E, ?> renderer = this.getRenderer(entity);
        return renderer.shouldRender(entity, culler, camX, camY, camZ);
    }

    public <E extends Entity> EntityRenderState extractEntity(final E entity, final float partialTicks) {
        EntityRenderer<? super E, ?> renderer = this.getRenderer(entity);

        try {
            return renderer.createRenderState(entity, partialTicks);
        } catch (Throwable t) {
            CrashReport report = CrashReport.forThrowable(t, "Extracting render state for an entity in world");
            CrashReportCategory entityCat = report.addCategory("Entity being extracted");
            entity.fillCrashReportCategory(entityCat);
            CrashReportCategory rendererCategory = this.fillRendererDetails(renderer, report);
            rendererCategory.setDetail("Delta", partialTicks);
            throw new ReportedException(report);
        }
    }

    public <S extends EntityRenderState> void submit(
        final S renderState,
        final CameraRenderState camera,
        final double x,
        final double y,
        final double z,
        final PoseStack poseStack,
        final SubmitNodeCollector submitNodeCollector
    ) {
        EntityRenderer<?, ? super S> renderer = this.getRenderer(renderState);

        try {
            Vec3 pos = renderer.getRenderOffset(renderState);
            double relativeX = x + pos.x();
            double relativeY = y + pos.y();
            double relativeZ = z + pos.z();
            poseStack.pushPose();
            // MODIFIED for porting: was iris's entity_render_context MixinEntityRenderDispatcher#iris$beginEntityRender
            // (@Inject at the INVOKE of PoseStack#pushPose, shift AFTER). Upstream's comment: injected after the push since at
            // this point most cancellation checks have already passed. Its "// TODO: Add special types" note is carried over.
            iris$beginEntityRender(renderState);
            poseStack.translate(relativeX, relativeY, relativeZ);
            renderer.submit(renderState, poseStack, submitNodeCollector, camera);
            if (renderState.displayFireAnimation) {
                submitNodeCollector.submitFlame(poseStack, renderState, Mth.rotationAroundAxis(Mth.Y_AXIS, camera.orientation, new Quaternionf()));
            }

            if (renderState instanceof AvatarRenderState) {
                poseStack.translate(-pos.x(), -pos.y(), -pos.z());
            }

            if (!renderState.shadowPieces.isEmpty()) {
                // MODIFIED for porting: was iris's MixinEntityRenderDispatcher#iris$maybeSuppressEntityShadow
                // (@WrapWithCondition on SubmitNodeCollector#submitShadow) plus its @Unique #iris$maybeSuppressShadow - a
                // shader pack that casts real shadows does not want vanilla's blob shadow on top. The mixin's other @Unique
                // constants (RENDER_SHADOW, RENDER_BLOCK_SHADOW, shadowId, flameId, cachedId) are unused there and are not
                // ported.
                if (!iris$shouldSuppressVanillaEntityShadow()) {
                    submitNodeCollector.submitShadow(poseStack, renderState.shadowRadius, renderState.shadowPieces);
                }
            }

            if (!(renderState instanceof AvatarRenderState)) {
                poseStack.translate(-pos.x(), -pos.y(), -pos.z());
            }

            // MODIFIED for porting: was iris's entity_render_context MixinEntityRenderDispatcher#iris$endEntityRender
            // (@Inject at the INVOKE of PoseStack#popPose)
            if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentEntity(0);
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentRenderedItem(0);
            }

            poseStack.popPose();
        } catch (Throwable t) {
            CrashReport report = CrashReport.forThrowable(t, "Rendering entity in world");
            CrashReportCategory entityCat = report.addCategory("EntityRenderState being rendered");
            renderState.fillCrashReportCategory(entityCat);
            this.fillRendererDetails(renderer, report);
            throw new ReportedException(report);
        }
    }

    private <S extends EntityRenderState> CrashReportCategory fillRendererDetails(final EntityRenderer<?, S> renderer, final CrashReport report) {
        CrashReportCategory category = report.addCategory("Renderer details");
        category.setDetail("Assigned renderer", renderer);
        return category;
    }

    public void resetCamera() {
        this.camera = null;
    }

    public double distanceToSqr(final Entity entity) {
        return this.camera.position().distanceToSqr(entity.position());
    }

    public ItemInHandRenderer getItemInHandRenderer() {
        return this.itemInHandRenderer;
    }

    @Override
    public void onResourceManagerReload(final ResourceManager resourceManager) {
        EntityRendererProvider.Context context = new EntityRendererProvider.Context(
            this,
            this.blockModelResolver,
            this.itemModelResolver,
            this.mapRenderer,
            resourceManager,
            this.entityModels.get(),
            this.equipmentAssets,
            this.atlasManager,
            this.font,
            this.playerSkinRenderCache
        );
        this.renderers = EntityRenderers.createEntityRenderers(context);
        this.playerRenderers = EntityRenderers.createAvatarRenderers(context);
        this.mannequinRenderers = EntityRenderers.createAvatarRenderers(context);
    }
}
