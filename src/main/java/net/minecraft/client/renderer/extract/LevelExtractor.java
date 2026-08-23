package net.minecraft.client.renderer.extract;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;
import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.client.renderer.debug.GameTestBlockHighlightRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.SimpleGizmoCollector;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
// MODIFIED for porting: sodium core.render.world LevelExtractorMixin. Terrain culling, chunk invalidation, the block
// entity extraction and the terrain statistics are all forwarded to SodiumWorldRenderer.
public class LevelExtractor implements ResourceManagerReloadListener {
    // MODIFIED for porting: sodium core.render.world LevelExtractorMixin @Unique field
    private net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer sodium$renderer;

    // MODIFIED for porting: sodium core.render.world LevelExtractorMixin#checkRenderer
    private void sodium$checkRenderer() {
        this.sodium$renderer = ((net.caffeinemc.mods.sodium.client.world.LevelRendererExtension)net.minecraft.client.Minecraft.getInstance().levelRenderer).sodium$getWorldRenderer();
    }

    private static final float CHUNK_VISIBILITY_THRESHOLD = 0.3F;
    private final Minecraft minecraft;
    private final LevelRenderer levelRenderer;
    private @Nullable ClientLevel level;
    private @Nullable SectionUpdateTracker sectionUpdateTracker;
    private final LevelRenderState levelRenderState;
    public final DebugRenderer debugRenderer = new DebugRenderer();
    public final GameTestBlockHighlightRenderer gameTestBlockHighlightRenderer = new GameTestBlockHighlightRenderer();
    private final SimpleGizmoCollector mainThreadGizmos = new SimpleGizmoCollector();
    private double prevCamRotX = Double.MIN_VALUE;
    private double prevCamRotY = Double.MIN_VALUE;
    private int lastViewDistance = -1;
    private boolean shouldInvalidateCompiledGeometry;
    private boolean shouldResetLevelRenderData;
    private boolean shouldResetChunkLayerSampler;
    private boolean shouldResetSkyRenderer;

    public LevelExtractor(final Minecraft minecraft, final LevelRenderState levelRenderState, final LevelRenderer levelRenderer) {
        this.minecraft = minecraft;
        this.levelRenderer = levelRenderer;
        this.levelRenderState = levelRenderState;
    }

    public void extract(final DeltaTracker deltaTracker, final Camera camera, final float deltaPartialTick) {
        // MODIFIED for porting: sodium core.render.world LevelExtractorMixin#sodium$setRenderer (HEAD)
        this.sodium$checkRenderer();
        if (this.minecraft.options.getEffectiveRenderDistance() != this.lastViewDistance) {
            this.allChanged();
        }

        Vec3 cameraPos = camera.position();
        if (this.sectionUpdateTracker != null) {
            this.sectionUpdateTracker.repositionCamera(SectionPos.of(cameraPos));
        }

        if (this.shouldResetLevelRenderData) {
            this.levelRenderer.resetLevelRenderData();
            this.shouldResetLevelRenderData = false;
        }

        this.levelRenderState.reset();
        ProfilerFiller profiler = Profiler.get();
        profiler.push("level");
        this.levelRenderState.shouldResetChunkLayerSampler = this.shouldResetChunkLayerSampler;
        this.shouldResetChunkLayerSampler = false;
        this.levelRenderState.shouldResetSkyRenderer = this.shouldResetSkyRenderer;
        this.shouldResetSkyRenderer = false;
        this.levelRenderState.gameTime = this.level.getGameTime();
        Frustum cullFrustum = camera.getCullFrustum();
        profiler.push("prepareDispatchers");
        this.levelRenderer.blockEntityRenderDispatcher().prepare(cameraPos);
        this.levelRenderer.entityRenderDispatcher().prepare(camera, this.minecraft.crosshairPickEntity);
        if (this.shouldInvalidateCompiledGeometry) {
            this.levelRenderer.invalidateCompiledGeometry(this.level, this.minecraft.options, camera, this.minecraft.getBlockColors());
            this.shouldInvalidateCompiledGeometry = false;
        } else if (camera.getCapturedFrustum() == null) {
            double camRotX = Math.floor(camera.xRot() / 2.0F);
            double camRotY = Math.floor(camera.yRot() / 2.0F);
            // MODIFIED for porting: sodium core.render.world LevelExtractorMixin#cullTerrain (injected before the
            // consumeFrustumUpdate call) - the terrain setup phase is redirected to sodium's renderer.
            this.sodium$cullTerrain(camera, cullFrustum);
            if (this.levelRenderer.sectionOcclusionGraph().consumeFrustumUpdate() || camRotX != this.prevCamRotX || camRotY != this.prevCamRotY) {
                profiler.popPush("applyFrustum");
                // MODIFIED for porting: sodium core.render.world LevelExtractorMixin#sodium$cancel (@Redirect of
                // applyFrustum) - the vanilla visible-section list is not used any more.
                this.prevCamRotX = camRotX;
                this.prevCamRotY = camRotY;
            }
        }

        if (this.sectionUpdateTracker != null && this.level != null) {
            ClientChunkCache chunkCache = this.level.getChunkSource();
            this.levelRenderState.chunkLoadingRenderState.addedEmptySections = chunkCache.addedEmptySections();
            this.levelRenderState.chunkLoadingRenderState.removedEmptySections = chunkCache.removedEmptySections();
            this.levelRenderState.chunkLoadingRenderState.addedLoadedChunks = chunkCache.addedLoadedChunks();
            this.levelRenderState.chunkLoadingRenderState.removedLoadedChunks = chunkCache.removedLoadedChunks();
            chunkCache.flipUpdateTrackingSets();
            LongCollection expectedChunks = this.levelRenderer.expectedChunks();
            expectedChunks.forEach(expectedChunk -> {
                if (chunkCache.hasChunk(ChunkPos.getX(expectedChunk), ChunkPos.getZ(expectedChunk))) {
                    this.levelRenderState.chunkLoadingRenderState.loadedExpectedChunks.add(expectedChunk);
                }
            });
            profiler.popPush("sectionUpdates");
            RenderRegionCache cache = new RenderRegionCache();

            for (SectionRenderDispatcher.RenderSection section : this.levelRenderer.visibleSections()) {
                SectionUpdateTracker.SectionDirtyState dirtyState = this.sectionUpdateTracker.getDirtyState(section.getSectionNode());
                if (dirtyState != null
                    && dirtyState.isDirty()
                    && (
                        section.sectionMesh.get() != CompiledSectionMesh.UNCOMPILED
                            || this.sectionUpdateTracker.hasAllNeighbors(this.level, section.getSectionNode())
                    )) {
                    this.levelRenderState
                        .sectionUpdateRenderStates
                        .add(
                            new SectionUpdateRenderState(
                                section.getSectionNode(), dirtyState.isDirtyFromPlayer(), cache.createRegion(this.level, section.getSectionNode())
                            )
                        );
                    dirtyState.setNotDirty();
                }
            }
        }

        profiler.popPush("entities");
        this.extractVisibleEntities(camera, cullFrustum, deltaTracker, this.levelRenderState);
        profiler.popPush("blockEntities");
        this.extractVisibleBlockEntities(camera, deltaPartialTick, this.levelRenderState);
        profiler.popPush("blockOutline");
        this.extractBlockOutline(camera, this.levelRenderState);
        profiler.popPush("blockBreaking");
        this.extractBlockDestroyAnimation(camera, this.levelRenderState);
        profiler.popPush("weather");
        this.levelRenderer.weatherEffectRenderer().extractRenderState(this.level, deltaPartialTick, cameraPos, this.levelRenderState.weatherRenderState);
        SkyRenderer skyRenderer = this.levelRenderer.skyRenderer();
        if (skyRenderer != null) {
            profiler.popPush("sky");
            skyRenderer.extractRenderState(this.level, deltaPartialTick, camera, this.levelRenderState.skyRenderState);
        }

        profiler.popPush("border");
        this.levelRenderer
            .worldBorderRenderer()
            .extract(
                this.level.getWorldBorder(),
                deltaPartialTick,
                cameraPos,
                this.minecraft.options.getEffectiveRenderDistance() * 16,
                this.levelRenderState.worldBorderRenderState
            );
        profiler.popPush("particles");
        this.minecraft.particleEngine.extract(this.levelRenderState.particlesRenderState, new Frustum(cullFrustum).offset(-3.0F), camera, deltaPartialTick);
        profiler.popPush("cloud");
        this.levelRenderState.cloudColor = camera.attributeProbe().getValue(EnvironmentAttributes.CLOUD_COLOR, deltaPartialTick);
        if (ARGB.alpha(this.levelRenderState.cloudColor) > 0) {
            this.levelRenderState.cloudHeight = camera.attributeProbe().getValue(EnvironmentAttributes.CLOUD_HEIGHT, deltaPartialTick);
        }

        profiler.popPush("debug");
        this.debugRenderer.emitGizmos(cullFrustum, cameraPos.x, cameraPos.y, cameraPos.z, deltaTracker.getGameTimeDeltaPartialTick(false));
        this.gameTestBlockHighlightRenderer.emitGizmos();
        this.levelRenderState.render3dCrosshair = this.minecraft.debugEntries.isCurrentlyEnabled(DebugScreenEntries.THREE_DIMENSIONAL_CROSSHAIR);
        ClientPacketListener connection = this.minecraft.getConnection();
        if (connection != null) {
            this.levelRenderState.playerCompiledSectionCallback = connection.getPlayerCompiledSectionCallback();
        }

        this.levelRenderState.shouldShowEntityOutlines = this.shouldShowEntityOutlines(camera);
        this.extractGizmos();
        profiler.pop();
        profiler.pop();
    }

    private void extractVisibleEntities(final Camera camera, final Frustum frustum, final DeltaTracker deltaTracker, final LevelRenderState output) {
        Vec3 cameraPos = camera.position();
        double camX = cameraPos.x();
        double camY = cameraPos.y();
        double camZ = cameraPos.z();
        TickRateManager tickRateManager = this.minecraft.level.tickRateManager();
        Entity.setViewScale(
            Mth.clamp(this.minecraft.options.getEffectiveRenderDistance() / 8.0, 1.0, 2.5) * this.minecraft.options.entityDistanceScaling().get()
        );
        EntityRenderDispatcher entityRenderDispatcher = this.levelRenderer.entityRenderDispatcher();

        // MODIFIED for porting: was iris's MixinLevelRenderer_SkipRendering#skipRenderEntities (@WrapOperation around
        // ClientLevel#entitiesForRendering) - a pipeline can skip all rendering entirely. Upstream's trailing
        // "// TODO IMS 24w35a block entities" note means block entities are deliberately not covered yet.
        Iterable<Entity> irisEntities = net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()
                && net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable() instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline irisPipeline
                && irisPipeline.skipAllRendering()
            ? java.util.Collections.emptyList()
            : this.level.entitiesForRendering();

        for (Entity entity : irisEntities) {
            if (this.isEntityVisible(entity, frustum, camX, camY, camZ)
                && (entity != camera.entity() || camera.isDetached() || camera.entity() instanceof LivingEntity && ((LivingEntity)camera.entity()).isSleeping())
                && (!(entity instanceof LocalPlayer) || camera.entity() == entity)) {
                if (entity.tickCount == 0) {
                    entity.xOld = entity.getX();
                    entity.yOld = entity.getY();
                    entity.zOld = entity.getZ();
                }

                float partialEntity = deltaTracker.getGameTimeDeltaPartialTick(!tickRateManager.isEntityFrozen(entity));
                EntityRenderState state = this.extractEntity(entity, partialEntity);
                output.entityRenderStates.add(state);
            }
        }

        output.lastEntityRenderStateCount = output.entityRenderStates.size();
    }

    public boolean isEntityVisible(final Entity entity, final Frustum frustum, final double camX, final double camY, final double camZ) {
        if (this.level == null) {
            return false;
        } else if (this.levelRenderer.entityRenderDispatcher().shouldRender(entity, frustum, camX, camY, camZ)
            || this.minecraft.player != null && entity.hasIndirectPassenger(this.minecraft.player)) {
            BlockPos blockPos = entity.blockPosition();
            return this.level.isOutsideBuildHeight(blockPos.getY()) || this.levelRenderer.isSectionCompiledAndVisible(blockPos);
        } else {
            return false;
        }
    }

    private EntityRenderState extractEntity(final Entity entity, final float partialTickTime) {
        return this.levelRenderer.entityRenderDispatcher().extractEntity(entity, partialTickTime);
    }

    /**
     * MODIFIED for porting: sodium core.render.world LevelExtractorMixin#extractVisibleBlockEntities (HEAD, cancellable) -
     * sodium tracks the visible block entities itself.
     */
    private void extractVisibleBlockEntities(final Camera camera, final float deltaPartialTick, final LevelRenderState levelRenderState) {
        this.sodium$renderer.extractBlockEntities(camera, deltaPartialTick, this.level.destructionProgress(), levelRenderState);
    }

    /**
     * MODIFIED for porting: was sodium's core.render.world LevelExtractorMixin#cullTerrain.
     */
    private void sodium$cullTerrain(final Camera camera, final Frustum frustum) {
        net.caffeinemc.mods.sodium.client.render.viewport.Viewport viewport = ((net.caffeinemc.mods.sodium.client.render.viewport.ViewportProvider)frustum).sodium$createViewport();
        boolean updateChunksImmediately = net.caffeinemc.mods.sodium.client.util.FlawlessFrames.isActive();
        boolean useOcclusionCulling = this.levelRenderState.cameraRenderState.smartCull;
        this.sodium$renderer
            .setupTerrain(
                camera,
                viewport,
                ((net.caffeinemc.mods.sodium.client.util.FogStorage)net.minecraft.client.Minecraft.getInstance().gameRenderer).sodium$getFogParameters(),
                useOcclusionCulling,
                updateChunksImmediately,
                ((net.caffeinemc.mods.sodium.mixin.core.render.world.FrustumAccessor)frustum).sodium$getMatrix()
            );
    }

    // MODIFIED for porting: original vanilla body of extractVisibleBlockEntities, replaced above
    @SuppressWarnings("unused")
    private void sodium$vanillaExtractVisibleBlockEntities(final Camera camera, final float deltaPartialTick, final LevelRenderState levelRenderState) {
        Vec3 cameraPos = camera.position();
        double camX = cameraPos.x();
        double camY = cameraPos.y();
        double camZ = cameraPos.z();
        PoseStack poseStack = new PoseStack();

        for (SectionRenderDispatcher.RenderSection section : this.levelRenderer.visibleSections()) {
            List<BlockEntity> renderableBlockEntities = section.getSectionMesh().getRenderableBlockEntities();
            if (!renderableBlockEntities.isEmpty() && !(section.getVisibility(Util.getMillis()) < 0.3F)) {
                for (BlockEntity blockEntity : renderableBlockEntities) {
                    BlockPos blockPos = blockEntity.getBlockPos();
                    SortedSet<BlockDestructionProgress> progresses = this.level.destructionProgress().get(blockPos.asLong());
                    ModelFeatureRenderer.CrumblingOverlay breakProgress;
                    if (progresses != null && !progresses.isEmpty()) {
                        poseStack.pushPose();
                        poseStack.translate(blockPos.getX() - camX, blockPos.getY() - camY, blockPos.getZ() - camZ);
                        breakProgress = new ModelFeatureRenderer.CrumblingOverlay(progresses.last().getProgress(), poseStack.last());
                        poseStack.popPose();
                    } else {
                        breakProgress = null;
                    }

                    BlockEntityRenderState state = this.levelRenderer
                        .blockEntityRenderDispatcher()
                        .tryExtractRenderState(blockEntity, deltaPartialTick, breakProgress, false);
                    if (state != null) {
                        levelRenderState.blockEntityRenderStates.add(state);
                    }
                }
            }
        }

        Iterator<BlockEntity> iterator = this.level.getGloballyRenderedBlockEntities().iterator();

        while (iterator.hasNext()) {
            BlockEntity blockEntity = iterator.next();
            if (blockEntity.isRemoved()) {
                iterator.remove();
            } else {
                BlockEntityRenderState state = this.levelRenderer
                    .blockEntityRenderDispatcher()
                    .tryExtractRenderState(blockEntity, deltaPartialTick, null, true);
                if (state != null) {
                    levelRenderState.blockEntityRenderStates.add(state);
                }
            }
        }
    }

    private void extractBlockDestroyAnimation(final Camera camera, final LevelRenderState levelRenderState) {
        Vec3 cameraPos = camera.position();
        double camX = cameraPos.x();
        double camY = cameraPos.y();
        double camZ = cameraPos.z();
        levelRenderState.blockBreakingRenderStates.clear();

        for (Entry<SortedSet<BlockDestructionProgress>> entry : this.level.destructionProgress().long2ObjectEntrySet()) {
            BlockPos pos = BlockPos.of(entry.getLongKey());
            if (!(pos.distToCenterSqr(camX, camY, camZ) > 1024.0)) {
                SortedSet<BlockDestructionProgress> progresses = entry.getValue();
                if (progresses != null && !progresses.isEmpty()) {
                    int progress = progresses.last().getProgress();
                    levelRenderState.blockBreakingRenderStates.add(new BlockBreakingRenderState(pos, this.level.getBlockState(pos), progress));
                }
            }
        }
    }

    private void extractBlockOutline(final Camera camera, final LevelRenderState levelRenderState) {
        levelRenderState.blockOutlineRenderState = null;
        if (this.minecraft.hitResult instanceof BlockHitResult blockHitResult) {
            if (blockHitResult.getType() != HitResult.Type.MISS) {
                BlockPos pos = blockHitResult.getBlockPos();
                BlockState state = this.level.getBlockState(pos);
                if (!state.isAir() && this.level.getWorldBorder().isWithinBounds(pos)) {
                    BlockStateModel blockStateModel = this.minecraft.getModelManager().getBlockStateModelSet().get(state);
                    boolean isBlockTranslucent = blockStateModel.hasMaterialFlag(1);
                    boolean highContrast = this.minecraft.options.highContrastBlockOutline().get();
                    CollisionContext context = CollisionContext.of(camera.entity());
                    VoxelShape shape = state.getShape(this.level, pos, context);
                    if (SharedConstants.DEBUG_SHAPES) {
                        VoxelShape collisionShape = state.getCollisionShape(this.level, pos, context);
                        VoxelShape occlusionShape = state.getOcclusionShape();
                        VoxelShape interactionShape = state.getInteractionShape(this.level, pos);
                        levelRenderState.blockOutlineRenderState = new BlockOutlineRenderState(
                            pos, isBlockTranslucent, highContrast, shape, collisionShape, occlusionShape, interactionShape
                        );
                    } else {
                        levelRenderState.blockOutlineRenderState = new BlockOutlineRenderState(pos, isBlockTranslucent, highContrast, shape);
                    }
                }
            }
        }
    }

    private void extractGizmos() {
        this.mainThreadGizmos.addTemporaryGizmos(Minecraft.getInstance().getPerTickGizmos());
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            this.mainThreadGizmos.addTemporaryGizmos(server.getPerTickGizmos());
        }

        this.levelRenderer.addMainThreadGizmos(this.mainThreadGizmos.drainGizmos());
    }

    private void applyFrustum(final Frustum frustum) {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("applyFrustum called from wrong thread: " + Thread.currentThread().getName());
        }

        this.levelRenderer.clearVisibleSections();
        this.levelRenderer
            .sectionOcclusionGraph()
            .addSectionsInFrustum(frustum, this.levelRenderer.visibleSections(), this.levelRenderer.nearbyVisibleSections());
    }

    private boolean shouldShowEntityOutlines(final Camera camera) {
        return !camera.isPanoramicMode() && this.minecraft.player != null;
    }

    /**
     * MODIFIED for porting: iris fabulous MixinDisableFabulousGraphics @Unique method - fabulous graphics (improved
     * transparency) is incompatible with shader packs, so it is switched off whenever shaders are on.
     */
    private static void iris$disableFabulousGraphics() {
        if (!net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            return;
        }

        net.minecraft.client.Options options = net.minecraft.client.Minecraft.getInstance().options;

        if (!net.irisshaders.iris.Iris.getIrisConfig().areShadersEnabled()) {
            // Nothing to do here, shaders are disabled.
            return;
        }

        if (options.improvedTransparency().get()) {
            options.improvedTransparency().set(false);
            options.graphicsPreset().set(net.minecraft.client.GraphicsPreset.CUSTOM);
        }
    }

    @Override
    public void onResourceManagerReload(final ResourceManager resourceManager) {
        // MODIFIED for porting: iris fabulous MixinDisableFabulousGraphics#iris$disableFabulousGraphicsOnResourceReload (HEAD)
        iris$disableFabulousGraphics();
        this.shouldResetSkyRenderer = true;
    }

    public void setLevel(final @Nullable ClientLevel level) {
        // MODIFIED for porting: sodium core.render.world LevelExtractorMixin#sodium$setRenderer (RETURN of setLevel). It is
        // done first here because the vanilla body below has no other exit path and the renderer must know the level before
        // anything else uses it.
        this.sodium$checkRenderer();
        this.sodium$renderer.setLevel(level);
        this.level = level;
        if (level != null) {
            this.allChanged();
        } else {
            this.levelRenderer.entityRenderDispatcher().resetCamera();
            this.sectionUpdateTracker = null;
        }

        this.shouldResetLevelRenderData = true;
        this.gameTestBlockHighlightRenderer.clear();
    }

    public void allChanged() {
        // MODIFIED for porting: iris fabulous MixinDisableFabulousGraphics#iris$disableFabulousGraphicsOnLevelRendererReload
        // (@Inject HEAD) - this runs whenever the user changes the graphics mode, so the change can still be reverted here.
        iris$disableFabulousGraphics();
        if (this.level != null) {
            this.level.clearTintCaches();
            Options options = this.minecraft.options;
            this.lastViewDistance = options.getEffectiveRenderDistance();
            this.sectionUpdateTracker = new SectionUpdateTracker(this.level, this.lastViewDistance);
            Camera camera = this.minecraft.gameRenderer.mainCamera();
            SectionPos cameraSectionPos = SectionPos.of(camera.position());
            this.sectionUpdateTracker.repositionCamera(cameraSectionPos);
            this.shouldInvalidateCompiledGeometry = true;
        }
    }

    public void resetSampler() {
        this.shouldResetChunkLayerSampler = true;
    }

    public void blockChanged(final BlockPos pos, final @Block.UpdateFlags int updateFlags) {
        this.setBlockDirty(pos, (updateFlags & 8) != 0);
    }

    /**
     * MODIFIED for porting: sodium core.render.world LevelExtractorMixin#setBlockDirty (@Overwrite) - redirect chunk updates
     * to sodium's renderer.
     */
    private void setBlockDirty(final BlockPos pos, final boolean playerChanged) {
        this.sodium$checkRenderer();
        this.sodium$renderer
            .scheduleRebuildForBlockArea(
                pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1, pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, playerChanged
            );
    }

    // MODIFIED for porting: original vanilla body of setBlockDirty(BlockPos, boolean), replaced above
    @SuppressWarnings("unused")
    private void sodium$vanillaSetBlockDirty(final BlockPos pos, final boolean playerChanged) {
        for (int z = pos.getZ() - 1; z <= pos.getZ() + 1; z++) {
            for (int x = pos.getX() - 1; x <= pos.getX() + 1; x++) {
                for (int y = pos.getY() - 1; y <= pos.getY() + 1; y++) {
                    this.setSectionDirty(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(y), SectionPos.blockToSectionCoord(z), playerChanged);
                }
            }
        }
    }

    /**
     * MODIFIED for porting: sodium core.render.world LevelExtractorMixin#setBlocksDirty (@Overwrite) - redirect chunk updates
     * to sodium's renderer.
     */
    public void setBlocksDirty(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
        this.sodium$checkRenderer();
        this.sodium$renderer.scheduleRebuildForBlockArea(x0, y0, z0, x1, y1, z1, false);
    }

    // MODIFIED for porting: original vanilla body of setBlocksDirty, replaced above
    @SuppressWarnings("unused")
    private void sodium$vanillaSetBlocksDirty(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
        for (int z = z0 - 1; z <= z1 + 1; z++) {
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                for (int y = y0 - 1; y <= y1 + 1; y++) {
                    this.setSectionDirty(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(y), SectionPos.blockToSectionCoord(z));
                }
            }
        }
    }

    public void setBlockDirty(final BlockPos pos, final BlockState oldState, final BlockState newState) {
        if (this.minecraft.getModelManager().requiresRender(oldState, newState)) {
            this.setBlocksDirty(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
        }
    }

    /**
     * MODIFIED for porting: sodium core.render.world LevelExtractorMixin#setSectionDirtyWithNeighbors (@Overwrite) - redirect
     * chunk updates to sodium's renderer.
     */
    public void setSectionDirtyWithNeighbors(final int sectionX, final int sectionY, final int sectionZ) {
        this.sodium$checkRenderer();
        this.sodium$renderer
            .scheduleRebuildForChunks(sectionX - 1, sectionY - 1, sectionZ - 1, sectionX + 1, sectionY + 1, sectionZ + 1, false);
    }

    public void setSectionRangeDirty(
        final int minSectionX, final int minSectionY, final int minSectionZ, final int maxSectionX, final int maxSectionY, final int maxSectionZ
    ) {
        for (int z = minSectionZ; z <= maxSectionZ; z++) {
            for (int x = minSectionX; x <= maxSectionX; x++) {
                for (int y = minSectionY; y <= maxSectionY; y++) {
                    this.setSectionDirty(x, y, z);
                }
            }
        }
    }

    public void setSectionDirty(final int sectionX, final int sectionY, final int sectionZ) {
        this.setSectionDirty(sectionX, sectionY, sectionZ, false);
    }

    /**
     * MODIFIED for porting: sodium core.render.world LevelExtractorMixin#setSectionDirty (@Overwrite) - redirect chunk
     * updates to sodium's renderer.
     */
    private void setSectionDirty(final int sectionX, final int sectionY, final int sectionZ, final boolean playerChanged) {
        this.sodium$checkRenderer();
        this.sodium$renderer.scheduleRebuildForChunk(sectionX, sectionY, sectionZ, playerChanged);
    }

    public Gizmos.TemporaryCollection collectPerFrameMainThreadGizmos() {
        return Gizmos.withCollector(this.mainThreadGizmos);
    }

    /**
     * MODIFIED for porting: sodium core.render.world LevelExtractorMixin#countRenderedSections (@Overwrite) - redirect to
     * sodium's renderer.
     */
    public int countRenderedSections() {
        this.sodium$checkRenderer();
        return this.sodium$renderer.getVisibleChunkCount();
    }

    @VisibleForDebug
    /**
     * MODIFIED for porting: sodium core.render.world LevelExtractorMixin#sectionStatistics (@Overwrite) - replace the debug
     * string with sodium's own.
     */
    public @Nullable String sectionStatistics() {
        this.sodium$checkRenderer();
        return this.sodium$renderer.getChunksDebugString();
    }

    // MODIFIED for porting: original vanilla body of sectionStatistics, replaced above
    @SuppressWarnings("unused")
    private @Nullable String sodium$vanillaSectionStatistics() {
        ViewArea viewArea = this.levelRenderer.viewArea();
        if (viewArea == null) {
            return null;
        }

        int totalSections = viewArea.size();
        int rendered = this.countRenderedSections();
        SectionRenderDispatcher sectionRenderDispatcher = this.levelRenderer.sectionRenderDispatcher();
        return String.format(
            Locale.ROOT,
            "C: %d/%d %sD: %d, %s",
            rendered,
            totalSections,
            this.minecraft.smartCull ? "(s) " : "",
            this.lastViewDistance,
            sectionRenderDispatcher == null ? "null" : sectionRenderDispatcher.getStats()
        );
    }

    @VisibleForDebug
    public @Nullable String entityStatistics() {
        return this.level == null
            ? null
            : "E: "
                + this.levelRenderState.lastEntityRenderStateCount
                + "/"
                + this.level.getEntityCount()
                + ", SD: "
                + this.level.getServerSimulationDistance();
    }

    @VisibleForDebug
    public double totalSections() {
        return this.sectionUpdateTracker == null ? 0.0 : this.sectionUpdateTracker.size();
    }

    @VisibleForDebug
    public double lastViewDistance() {
        return this.lastViewDistance;
    }
}