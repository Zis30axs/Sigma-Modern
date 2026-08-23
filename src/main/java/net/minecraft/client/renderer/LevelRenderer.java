package net.minecraft.client.renderer;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.OptionalDouble;
import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Options;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.SimpleGizmoCollector;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
// MODIFIED for porting: implements sodium's LevelRendererExtension (core.render.world LevelRendererMixin). Sodium replaces
// the vanilla terrain renderer: the vanilla section render dispatcher / view area are never allocated, and the render, chunk
// update and completion queries below are forwarded to SodiumWorldRenderer.
public class LevelRenderer implements AutoCloseable, net.caffeinemc.mods.sodium.client.world.LevelRendererExtension,
    net.irisshaders.iris.mixin.LevelRendererAccessor,
    net.irisshaders.iris.shadows.CullingDataCache { // MODIFIED for porting: iris LevelRendererAccessor + shadows MixinLevelRenderer
    // MODIFIED for porting: sodium core.render.world LevelRendererMixin @Unique fields
    private static final EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> SODIUM_STATIC_MAP =
        new EnumMap<>(ChunkSectionLayer.class);

    private net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer sodium$renderer;

    private net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices sodium$matrices;

    @Override
    public net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer sodium$getWorldRenderer() {
        return this.sodium$renderer;
    }

    @Override
    public void sodium$setMatrices(final net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices matrices) {
        this.sodium$matrices = matrices;
    }

    @Override
    public net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices sodium$getMatrices() {
        return this.sodium$matrices;
    }

    private static final Identifier TRANSPARENCY_POST_CHAIN_ID = Identifier.withDefaultNamespace("transparency");
    private static final Identifier ENTITY_OUTLINE_POST_CHAIN_ID = Identifier.withDefaultNamespace("entity_outline");
    private static final int MINIMUM_TRANSPARENT_SORT_COUNT = 15;
    private static final float CHUNK_VISIBILITY_THRESHOLD = 0.3F;
    private static final Vector4fc SCREEN_SIZE_TARGET_CLEAR_COLOR = new Vector4f(0.0F);
    private static final Vector4fc ENTITY_OUTLINE_CLEAR_COLOR = new Vector4f(0.0F);
    private final GameRenderer gameRenderer;
    private final EntityRenderDispatcher entityRenderDispatcher;
    private final BlockEntityRenderDispatcher blockEntityRenderDispatcher;
    // MODIFIED for porting: iris.accesswidener declares `mutable field LevelRenderer renderBuffers`, so it lost its final.
    private RenderBuffers renderBuffers;

    // MODIFIED for porting: was iris's LevelRendererAccessor @Accessor("entityRenderDispatcher") / @Accessor("renderBuffers")
    // / @Accessor("levelRenderState")
    @Override
    public EntityRenderDispatcher getEntityRenderDispatcher() {
        return this.entityRenderDispatcher;
    }

    @Override
    public RenderBuffers getRenderBuffers() {
        return this.renderBuffers;
    }

    @Override
    public void setRenderBuffers(final RenderBuffers buffers) {
        this.renderBuffers = buffers;
    }

    @Override
    public LevelRenderState getLevelRenderState() {
        return this.levelRenderState;
    }
    private final FeatureRenderDispatcher featureRenderDispatcher;
    private final SubmitNodeStorage submitNodeStorage = new SubmitNodeStorage();
    private final ModelManager modelManager;
    private final TextureManager textureManager;
    private final AtlasManager atlasManager;
    private final ShaderManager shaderManager;
    private final LevelRenderState levelRenderState;
    /**
     * MODIFIED for porting: iris MixinLevelRenderer @Unique fields. {@code disableFrustumCulling} is written but never read
     * upstream either; it is kept so the assignment in the pipeline setup below stays faithful.
     */
    private net.irisshaders.iris.pipeline.WorldRenderingPipeline iris$pipeline;

    private boolean iris$warned;

    @SuppressWarnings("unused")
    private boolean iris$disableFrustumCulling;

    private final org.joml.Matrix4f iris$modelMatrix = new org.joml.Matrix4f();
    private final OptionsRenderState optionsRenderState;
    private @Nullable SkyRenderer skyRenderer;
    private final CloudRenderer cloudRenderer = new CloudRenderer();
    private final WorldBorderRenderer worldBorderRenderer = new WorldBorderRenderer();
    private final WeatherEffectRenderer weatherEffectRenderer = new WeatherEffectRenderer();
    private final SectionOcclusionGraph sectionOcclusionGraph = new SectionOcclusionGraph();
    /**
     * MODIFIED for porting: iris's shadows MixinLevelRenderer declares this {@code @Mutable @Shadow @Final} and swaps it with a
     * second list around the shadow pass (its CullingDataCache implementation), so it lost its {@code final}.
     * <p>
     * Upstream additionally declares five {@code savedLastCamera*} fields and a {@code double tmp;} local in {@code swap()};
     * none of them is ever read or written there, so they are not ported. Note also that sodium replaces the vanilla terrain
     * path entirely, which makes this list unused in practice - the swap is kept because {@code ShadowRenderer} calls it.
     */
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections = new ObjectArrayList<>(10000);

    // MODIFIED for porting: iris shadows MixinLevelRenderer @Unique field
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> iris$savedRenderChunks = new ObjectArrayList<>(69696);

    @Override
    public void saveState() {
        this.iris$swap();
    }

    @Override
    public void restoreState() {
        this.iris$swap();
    }

    // MODIFIED for porting: was iris's shadows MixinLevelRenderer#swap (@Unique)
    private void iris$swap() {
        ObjectArrayList<SectionRenderDispatcher.RenderSection> tmpList = this.visibleSections;
        this.visibleSections = this.iris$savedRenderChunks;
        this.iris$savedRenderChunks = tmpList;
    }
    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> nearbyVisibleSections = new ObjectArrayList<>(50);
    private @Nullable ViewArea viewArea;
    private final RenderTarget entityOutlineTarget;
    private final LevelTargetBundle targets = new LevelTargetBundle();
    private @Nullable SectionRenderDispatcher sectionRenderDispatcher;
    private @Nullable BlockPos lastTranslucentSortBlockPos;
    private int translucencyResortIterationIndex;
    private @Nullable GpuSampler chunkLayerSampler;
    private final SimpleGizmoCollector renderThreadGizmos = new SimpleGizmoCollector();
    private LevelRenderer.FinalizedGizmos finalizedGizmos = new LevelRenderer.FinalizedGizmos(new DrawableGizmoPrimitives(), new DrawableGizmoPrimitives());

    public LevelRenderer(
        final EntityRenderDispatcher entityRenderDispatcher,
        final BlockEntityRenderDispatcher blockEntityRenderDispatcher,
        final ModelManager modelManager,
        final TextureManager textureManager,
        final AtlasManager atlasManager,
        final ShaderManager shaderManager,
        final GameRenderer gameRenderer,
        final int width,
        final int height
    ) {
        this.gameRenderer = gameRenderer;
        this.entityRenderDispatcher = entityRenderDispatcher;
        this.blockEntityRenderDispatcher = blockEntityRenderDispatcher;
        this.renderBuffers = gameRenderer.renderBuffers();
        this.featureRenderDispatcher = gameRenderer.featureRenderDispatcher();
        this.modelManager = modelManager;
        this.textureManager = textureManager;
        this.atlasManager = atlasManager;
        this.shaderManager = shaderManager;
        this.levelRenderState = gameRenderer.gameRenderState().levelRenderState;
        this.optionsRenderState = gameRenderer.gameRenderState().optionsRenderState;
        this.entityOutlineTarget = new TextureTarget("Entity Outline", width, height, true, GpuFormat.RGBA8_UNORM);
        // MODIFIED for porting: sodium core.render.world LevelRendererMixin#init (<init> RETURN)
        this.sodium$renderer = new net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer(net.minecraft.client.Minecraft.getInstance());
    }

    public void render(
        final GraphicsResourceAllocator resourceAllocator,
        final DeltaTracker deltaTracker,
        final boolean renderOutline,
        final CameraRenderState cameraState,
        final Matrix4fc modelViewMatrix,
        final GpuBufferSlice terrainFog,
        final Vector4f fogColor,
        final boolean shouldRenderSky
    ) {
        // MODIFIED for porting: was iris's vertices.immediate MixinLevelRenderer#iris$immediateStateBeginLevelRender
        // (@Inject HEAD). Upstream applies it with a priority of 999 so it runs before the main iris mixins.
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            net.irisshaders.iris.vertices.ImmediateState.isRenderingLevel = true;
        }

        // MODIFIED for porting: was iris's MixinLevelRenderer#iris$setupPipeline (@Inject HEAD). Upstream's comment: begin
        // shader rendering after buffers have been cleared, so that shaders whose final pass does not write every pixel do not
        // produce very odd issues.
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            net.irisshaders.iris.compat.dh.DHCompat.checkFrame();
            this.iris$modelMatrix.set(modelViewMatrix);
            net.irisshaders.iris.uniforms.IrisTimeUniforms.updateTime();
            net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setGbufferModelView(modelViewMatrix);
            net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE
                .setGbufferProjection(
                    new org.joml.Matrix4f(
                        ((net.caffeinemc.mods.sodium.client.util.GameRendererStorage)net.minecraft.client.Minecraft.getInstance().gameRenderer)
                            .sodium$getProjectionMatrix()
                    )
                );
            float irisFakeTickDelta = deltaTracker.getGameTimeDeltaPartialTick(false);
            net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setTickDelta(irisFakeTickDelta);
            if (this.cloudRenderer.getTexture() != null) {
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE
                    .setCloudTime(
                        (this.levelRenderState.gameTime % (this.cloudRenderer.getTexture().width() * 400) + irisFakeTickDelta) * 0.03F
                    );
            } else {
                net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCloudTime(0);
            }

            this.iris$pipeline = net.irisshaders.iris.Iris.getPipelineManager().preparePipeline(net.irisshaders.iris.Iris.getCurrentDimension());
            this.iris$disableFrustumCulling = this.iris$pipeline.shouldDisableFrustumCulling();
            this.iris$pipeline.beginLevelRendering();
            this.iris$pipeline.setPhase(net.irisshaders.iris.pipeline.WorldRenderingPhase.NONE);
            net.irisshaders.iris.gl.IrisRenderSystem.backupAndDisableCullingState(this.iris$pipeline.shouldDisableOcclusionCulling());

            if (net.irisshaders.iris.Iris.shouldActivateWireframe() && net.minecraft.client.Minecraft.getInstance().isLocalServer()) {
                net.irisshaders.iris.gl.IrisRenderSystem.setPolygonMode(org.lwjgl.opengl.GL43C.GL_LINE);
            }
        }

        float deltaPartialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        final ProfilerFiller profiler = Profiler.get();
        profiler.push("repositionCamera");
        this.repositionCamera(cameraState);
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        modelViewStack.mul(modelViewMatrix);
        profiler.popPush("submitFeatures");
        this.submitFeatures(this.levelRenderState, this.submitNodeStorage, renderOutline);
        profiler.popPush("prepareFeatures");
        FeatureRenderDispatcher.PreparedFrame featureFrame = this.featureRenderDispatcher.prepareFrame(this.submitNodeStorage);
        profiler.popPush("setupFrameGraph");
        FrameGraphBuilder frame = new FrameGraphBuilder();
        this.targets.main = frame.importExternal("main", this.gameRenderer.mainRenderTarget());
        int screenWidth = this.gameRenderer.mainRenderTarget().width;
        int screenHeight = this.gameRenderer.mainRenderTarget().height;
        RenderTargetDescriptor screenSizeTargetDescriptor = new RenderTargetDescriptor(
            screenWidth, screenHeight, true, SCREEN_SIZE_TARGET_CLEAR_COLOR, GpuFormat.RGBA8_UNORM
        );
        PostChain transparencyChain = this.getTransparencyChain();
        if (transparencyChain != null) {
            this.targets.translucent = frame.createInternal("translucent", screenSizeTargetDescriptor);
            this.targets.itemEntity = frame.createInternal("item_entity", screenSizeTargetDescriptor);
            this.targets.particles = frame.createInternal("particles", screenSizeTargetDescriptor);
            this.targets.weather = frame.createInternal("weather", screenSizeTargetDescriptor);
            this.targets.clouds = frame.createInternal("clouds", screenSizeTargetDescriptor);
        }

        this.targets.entityOutline = frame.importExternal("entity_outline", this.entityOutlineTarget);
        FramePass clearPass = frame.addPass("clear");
        this.targets.main = clearPass.readsAndWrites(this.targets.main);
        clearPass.executes(
            () -> {
                RenderTarget mainRenderTarget = this.gameRenderer.mainRenderTarget();
                RenderSystem.getDevice()
                    .createCommandEncoder()
                    .clearColorAndDepthTextures(
                        mainRenderTarget.getColorTexture(), new Vector4f(fogColor.x, fogColor.y, fogColor.z, 0.0F), mainRenderTarget.getDepthTexture(), 0.0
                    );
            }
        );
        // MODIFIED for porting: was iris's MixinLevelRenderer#iris$beginLevelRender (@Inject at the first INVOKE of
        // FramePass#executes, shift AFTER, with @Local FrameGraphBuilder and @Local(ordinal = 0) FramePass) - an extra frame
        // graph pass that runs right after the clear pass and lets the pipeline set itself up on a cleared main target.
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            FramePass irisSetupPass = frame.addPass("iris_setup");
            this.targets.main = irisSetupPass.readsAndWrites(this.targets.main);
            irisSetupPass.requires(clearPass);
            irisSetupPass.executes(() -> {
                GpuBufferSlice params = RenderSystem.getShaderFog();
                this.iris$pipeline.onBeginClear();
                RenderSystem.setShaderFog(params);
            });
        }

        if (shouldRenderSky) {
            this.addSkyPass(frame, cameraState, terrainFog);
        }

        ChunkSectionsToRender chunkSectionsToRender = this.prepareChunkRenders(this.levelRenderState.cameraRenderState.viewRotationMatrix);
        // MODIFIED for porting: sodium core.render.world LevelRendererMixin#getRenderState (@WrapOperation around
        // prepareChunkRenders) - hands sodium's renderer the matrices and camera position for this frame, and refreshes the
        // fog color with the one actually used to render the sky (the one stored from FogRenderer is outdated by now).
        this.sodium$matrices = new net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices(
            ((net.caffeinemc.mods.sodium.client.util.GameRendererStorage)net.minecraft.client.Minecraft.getInstance().gameRenderer).sodium$getProjectionMatrix(),
            this.levelRenderState.cameraRenderState.viewRotationMatrix
        );
        ((net.caffeinemc.mods.sodium.client.util.SodiumChunkSection)chunkSectionsToRender)
            .sodium$setRendering(
                this.sodium$renderer,
                this.sodium$matrices,
                this.levelRenderState.cameraRenderState.pos.x,
                this.levelRenderState.cameraRenderState.pos.y,
                this.levelRenderState.cameraRenderState.pos.z
            );
        this.sodium$renderer.updateFogColor(fogColor);
        // MODIFIED for porting: was iris's MixinLevelRenderer#iris$renderTerrainShadows (@Inject at the INVOKE of
        // addMainPass; its @Group of two variants exists only to match either the six- or the seven-argument addMainPass
        // signature, and 26.2 has the six-argument one). Upstream's comment: do this before main pass submission so shadow
        // maps are ready before terrain draws.
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && net.irisshaders.iris.Iris.isPackInUseQuick()) {
            this.iris$pipeline
                .renderShadows(this, net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera(), this.levelRenderState.cameraRenderState);
        }

        this.addMainPass(frame, featureFrame, terrainFog, this.levelRenderState, profiler, chunkSectionsToRender);
        PostChain entityOutlineChain = this.shaderManager.getPostChain(ENTITY_OUTLINE_POST_CHAIN_ID, LevelTargetBundle.OUTLINE_TARGETS);
        if (featureFrame.hasAnyOutline() && entityOutlineChain != null) {
            entityOutlineChain.addToFrame(frame, screenWidth, screenHeight, this.targets);
        }

        CloudStatus cloudStatus = this.optionsRenderState.cloudStatus;
        if (cloudStatus != CloudStatus.OFF && ARGB.alpha(this.levelRenderState.cloudColor) > 0) {
            this.addCloudsPass(
                frame,
                cloudStatus,
                this.levelRenderState.cameraRenderState.pos,
                this.levelRenderState.gameTime,
                deltaPartialTick,
                this.levelRenderState.cloudColor,
                this.levelRenderState.cloudHeight,
                this.optionsRenderState.cloudRange
            );
        }

        this.addWeatherPass(frame, terrainFog);
        if (transparencyChain != null) {
            transparencyChain.addToFrame(frame, screenWidth, screenHeight, this.targets);
        }

        this.addAlwaysOnTopPass(frame, featureFrame, terrainFog);
        profiler.popPush("executeFrameGraph");
        frame.execute(resourceAllocator, new FrameGraphBuilder.Inspector() {
            @Override
            public void beforeExecutePass(final String name) {
                profiler.push(name);
            }

            @Override
            public void afterExecutePass(final String name) {
                profiler.pop();
            }
        });
        profiler.pop();
        this.targets.clear();
        /*
          MODIFIED for porting: was iris's MixinLevelRenderer#iris$endLevelRender (@Inject at the INVOKE of
          Matrix4fStack#popMatrix). Upstream injects a bit early on purpose, so that iris ends its rendering before mods that
          inject at RETURN (e.g. VoxelMap) draw their waypoint beams.
        */
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            net.irisshaders.iris.pathways.HandRenderer.INSTANCE
                .renderTranslucent(
                    modelViewMatrix,
                    deltaTracker.getGameTimeDeltaPartialTick(true),
                    net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera(),
                    this.levelRenderState.cameraRenderState,
                    net.minecraft.client.Minecraft.getInstance().gameRenderer,
                    this.iris$pipeline
                );
            Profiler.get().popPush("iris_final");

            if (net.irisshaders.iris.Iris.shouldActivateWireframe() && net.minecraft.client.Minecraft.getInstance().isLocalServer()) {
                net.irisshaders.iris.gl.IrisRenderSystem.setPolygonMode(org.lwjgl.opengl.GL43C.GL_FILL);
            }

            this.iris$pipeline.finalizeLevelRendering();
            this.iris$pipeline = null;

            if (!this.iris$warned) {
                this.iris$warned = true;
                net.irisshaders.iris.Iris.getUpdateChecker()
                    .getBetaInfo()
                    .ifPresent(
                        info -> net.minecraft.client.Minecraft.getInstance()
                            .gui
                            .hud
                            .getChat()
                            .addClientSystemMessage(
                                net.minecraft.network.chat.Component
                                    .literal("A new beta is out for Iris " + info.betaTag + ". Please redownload it.")
                                    .withStyle(net.minecraft.ChatFormatting.BOLD, net.minecraft.ChatFormatting.RED)
                            )
                    );
            }

            net.irisshaders.iris.gl.IrisRenderSystem.restoreCullingState();
        }

        modelViewStack.popMatrix();
        featureFrame.close();
        profiler.push("compileSections");
        this.compileSections(cameraState);
        profiler.pop();
        if (this.sectionRenderDispatcher != null) {
            this.sectionRenderDispatcher.lock();
            profiler.push("uploadTerrainBuffers");

            try {
                this.sectionRenderDispatcher.uploadTerrainBuffersToGpu();
            } finally {
                this.sectionRenderDispatcher.unlock();
            }

            profiler.pop();
        }

        profiler.push("updateSectionOcclusion");
        this.sectionOcclusionGraph.update(cameraState, this.optionsRenderState.fov, this.levelRenderState.chunkLoadingRenderState);
        profiler.pop();
        Runnable playerCompiledSectionCallback = this.levelRenderState.playerCompiledSectionCallback;
        if (playerCompiledSectionCallback != null && this.isSectionCompiledAndVisible(this.levelRenderState.cameraRenderState.blockPos)) {
            playerCompiledSectionCallback.run();
        }

        // MODIFIED for porting: was iris's vertices.immediate MixinLevelRenderer#iris$immediateStateEndLevelRender
        // (@Inject RETURN)
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            net.irisshaders.iris.vertices.ImmediateState.isRenderingLevel = false;
        }
    }

    private void submitFeatures(final LevelRenderState levelRenderState, final SubmitNodeCollector submitNodeCollector, final boolean renderOutline) {
        PoseStack poseStack = new PoseStack();
        this.submitEntities(poseStack, levelRenderState, submitNodeCollector);
        levelRenderState.entityRenderStates.clear();
        this.submitBlockEntities(poseStack, levelRenderState, submitNodeCollector);
        levelRenderState.blockEntityRenderStates.clear();
        this.submitBlockDestroyAnimation(poseStack, submitNodeCollector, levelRenderState);
        levelRenderState.blockBreakingRenderStates.clear();
        levelRenderState.particlesRenderState.submit(submitNodeCollector, levelRenderState.cameraRenderState);
        if (renderOutline) {
            this.submitBlockOutline(poseStack, this.submitNodeStorage, levelRenderState);
        }

        this.finalizeGizmoCollection();
        this.finalizedGizmos.standardPrimitives().submit(submitNodeCollector, levelRenderState.cameraRenderState, false);
        this.finalizedGizmos.alwaysOnTopPrimitives().submit(submitNodeCollector, levelRenderState.cameraRenderState, true);
        if (!levelRenderState.shouldShowEntityOutlines) {
            for (SubmitNodeCollection collection : this.submitNodeStorage.getSubmitsPerOrder().values()) {
                collection.outline.clear();
            }
        }

        this.checkPoseStack(poseStack);
    }

    private void repositionCamera(final CameraRenderState camera) {
        Vec3 cameraPos = camera.pos;
        SectionPos cameraSectionPos = SectionPos.of(cameraPos);
        if (this.viewArea.repositionCamera(cameraSectionPos)) {
            this.worldBorderRenderer.invalidate();
        }

        this.sectionRenderDispatcher.setCameraPosition(cameraPos);
    }

    private void addSkyPass(final FrameGraphBuilder frame, final CameraRenderState cameraState, final GpuBufferSlice skyFog) {
        FogType fogType = cameraState.fogType;
        if (fogType != FogType.POWDER_SNOW && fogType != FogType.LAVA && !cameraState.entityRenderState.doesMobEffectBlockSky) {
            if (this.levelRenderState.shouldResetSkyRenderer || this.skyRenderer == null) {
                if (this.skyRenderer != null) {
                    this.skyRenderer.close();
                }

                this.skyRenderer = new SkyRenderer(this.textureManager, this.atlasManager, this.gameRenderer.mainRenderTarget());
            }

            SkyRenderState state = this.levelRenderState.skyRenderState;
            if (state.skybox != DimensionType.Skybox.NONE) {
                FramePass pass = frame.addPass("sky");
                this.targets.main = pass.readsAndWrites(this.targets.main);
                pass.executes(
                    () -> {
                        /*
                          MODIFIED for porting: was iris's MixinLevelRenderer_Sky#preRenderSky (@Inject HEAD of the sky pass
                          lambda, cancellable). Upstream notes this is a modified copy of a sodium mixin with an added check for
                          whether a shader pack is active - with a pack loaded the pack draws the sky itself, so the guard only
                          applies when no pack is in use.

                          It prevents the sky layer from rendering when the fog distance is reduced from the default, which
                          would otherwise let the sky show through chunks culled by fog occlusion (this is also the cause of
                          MC-152504). The caveat, quoting upstream, is that the sun/stars/moon become invisible underwater -
                          consistent with Bedrock Edition, and arguably more correct since underwater fog already covers chunks
                          outside the water.
                        */
                        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && net.irisshaders.iris.Iris.getCurrentPack().isEmpty()) {
                            net.minecraft.client.Camera irisCamera = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera();
                            boolean irisIsSubmersed = irisCamera.getFluidInCamera() != FogType.NONE;
                            boolean irisBlockSky = this.getLevelRenderState().cameraRenderState.entityRenderState.doesMobEffectBlockSky;
                            boolean irisUseThickFog = net.minecraft.client.Minecraft.getInstance().gui.hud.getBossOverlay().shouldCreateWorldFog();

                            if (irisIsSubmersed || irisBlockSky || irisUseThickFog) {
                                return;
                            }
                        }

                        // MODIFIED for porting: was iris's MixinLevelRenderer#iris$beginSky (@Inject HEAD of the sky pass
                        // lambda). Upstream's comment: use CUSTOM_SKY until levelFogColor is called, as a heuristic to catch
                        // FabricSkyboxes.
                        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                            net.irisshaders.iris.Iris.getPipelineManager().getPipeline().ifPresent(p -> p.setPhase(net.irisshaders.iris.pipeline.WorldRenderingPhase.CUSTOM_SKY));
                        }

                        RenderSystem.setShaderFog(skyFog);
                        if (state.skybox == DimensionType.Skybox.END) {
                            this.skyRenderer.renderEndSky();
                            if (state.endFlashIntensity > 1.0E-5F) {
                                PoseStack poseStack = new PoseStack();
                                this.skyRenderer.renderEndFlash(poseStack, state.endFlashIntensity, state.endFlashXAngle, state.endFlashYAngle);
                            }
                        } else {
                            PoseStack poseStack = new PoseStack();
                            this.skyRenderer.renderSkyDisc(state.skyColor);
                            this.skyRenderer.renderSunriseAndSunset(poseStack, state.sunAngle, state.sunriseAndSunsetColor);
                            this.skyRenderer
                                .renderSunMoonAndStars(
                                    poseStack, state.sunAngle, state.moonAngle, state.starAngle, state.moonPhase, state.rainBrightness, state.starBrightness
                                );
                            if (state.shouldRenderDarkDisc) {
                                this.skyRenderer.renderDarkDisc();
                            }
                        }

                        // MODIFIED for porting: was iris's MixinLevelRenderer#iris$endSky (@Inject RETURN of the sky pass
                        // lambda)
                        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                            net.irisshaders.iris.Iris.getPipelineManager().getPipeline().ifPresent(p -> p.setPhase(net.irisshaders.iris.pipeline.WorldRenderingPhase.NONE));
                        }
                    }
                );
            }
        }
    }

    private void addMainPass(
        final FrameGraphBuilder frame,
        final FeatureRenderDispatcher.PreparedFrame featureFrame,
        final GpuBufferSlice terrainFog,
        final LevelRenderState levelRenderState,
        final ProfilerFiller profiler,
        final ChunkSectionsToRender chunkSectionsToRender
    ) {
        FramePass pass = frame.addPass("main");
        this.targets.main = pass.readsAndWrites(this.targets.main);
        if (this.targets.translucent != null) {
            this.targets.translucent = pass.readsAndWrites(this.targets.translucent);
        }

        if (this.targets.itemEntity != null) {
            this.targets.itemEntity = pass.readsAndWrites(this.targets.itemEntity);
        }

        if (this.targets.weather != null) {
            this.targets.weather = pass.readsAndWrites(this.targets.weather);
        }

        if (this.targets.particles != null) {
            this.targets.particles = pass.readsAndWrites(this.targets.particles);
        }

        if (featureFrame.hasAnyOutline() && this.targets.entityOutline != null) {
            this.targets.entityOutline = pass.readsAndWrites(this.targets.entityOutline);
        }

        ResourceHandle<RenderTarget> mainTarget = this.targets.main;
        ResourceHandle<RenderTarget> translucentTarget = this.targets.translucent;
        ResourceHandle<RenderTarget> itemEntityTarget = this.targets.itemEntity;
        ResourceHandle<RenderTarget> entityOutlineTarget = this.targets.entityOutline;
        ResourceHandle<RenderTarget> particleTarget = this.targets.particles;
        pass.executes(
            () -> {
                RenderSystem.setShaderFog(terrainFog);
                if (levelRenderState.shouldResetChunkLayerSampler || this.chunkLayerSampler == null) {
                    if (this.chunkLayerSampler != null) {
                        this.chunkLayerSampler.close();
                    }

                    int maxAnisotropy = this.optionsRenderState.textureFiltering == TextureFilteringMethod.ANISOTROPIC
                        ? this.optionsRenderState.maxAnisotropyValue
                        : 1;
                    // MODIFIED for porting: sodium core.render.world LevelRendererMixin#setFilterMode (@Redirect of the
                    // FilterMode.LINEAR constant) - allows control of the texture filtering mode.
                    FilterMode sodium$filterMode = net.caffeinemc.mods.sodium.client.SodiumClientMod.options().quality.pixelFilteringMode;
                    this.chunkLayerSampler = RenderSystem.getDevice()
                        .createSampler(
                            AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, sodium$filterMode, sodium$filterMode, maxAnisotropy, OptionalDouble.empty()
                        );
                }

                profiler.push("solidTerrain");
                this.iris$renderTerrainGroup(chunkSectionsToRender, ChunkSectionLayerGroup.OPAQUE, this.chunkLayerSampler);
                this.gameRenderer.lighting().setupFor(Lighting.Entry.LEVEL);
                if (levelRenderState.shouldShowEntityOutlines && entityOutlineTarget != null) {
                    RenderTarget outlineTarget = entityOutlineTarget.get();
                    RenderSystem.getDevice()
                        .createCommandEncoder()
                        .clearColorAndDepthTextures(outlineTarget.getColorTexture(), ENTITY_OUTLINE_CLEAR_COLOR, outlineTarget.getDepthTexture(), 0.0);
                }

                profiler.popPush("renderSolidFeatures");
                featureFrame.executeSolid();
                profiler.pop();
                if (translucentTarget != null) {
                    translucentTarget.get().copyDepthFrom(mainTarget.get());
                }

                if (itemEntityTarget != null) {
                    itemEntityTarget.get().copyDepthFrom(mainTarget.get());
                }

                if (particleTarget != null) {
                    particleTarget.get().copyDepthFrom(mainTarget.get());
                }

                profiler.push("renderTranslucentFeatures");
                // MODIFIED for porting: was iris's MixinLevelRenderer#iris$beginTranslucents (@Inject at the INVOKE of
                // PreparedFrame#executeTranslucent inside the main pass lambda) - the solid hand pass runs here, between the
                // solid and translucent parts of the frame.
                if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                    this.iris$pipeline.beginHand();
                    net.irisshaders.iris.pathways.HandRenderer.INSTANCE
                        .renderSolid(
                            this.iris$modelMatrix,
                            net.minecraft.client.Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true),
                            net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera(),
                            levelRenderState.cameraRenderState,
                            net.minecraft.client.Minecraft.getInstance().gameRenderer,
                            this.iris$pipeline
                        );
                    Profiler.get().popPush("iris_pre_translucent");
                    this.iris$pipeline.beginTranslucents();
                }

                featureFrame.executeTranslucent();
                profiler.pop();
                featureFrame.executeOutline();
                profiler.push("translucentTerrain");
                this.iris$renderTerrainGroup(chunkSectionsToRender, ChunkSectionLayerGroup.TRANSLUCENT, this.chunkLayerSampler);
                profiler.pop();
                featureFrame.executeTranslucentAfterTerrain();
            }
        );
    }

    private void addCloudsPass(
        final FrameGraphBuilder frame,
        final CloudStatus cloudStatus,
        final Vec3 cameraPosition,
        final long gameTime,
        final float partialTicks,
        final int cloudColor,
        final float cloudHeight,
        final int cloudRange
    ) {
        FramePass pass = frame.addPass("clouds");
        if (this.targets.clouds != null) {
            this.targets.clouds = pass.readsAndWrites(this.targets.clouds);
        } else {
            this.targets.main = pass.readsAndWrites(this.targets.main);
        }

        pass.executes(() -> {
            // MODIFIED for porting: was iris's MixinLevelRenderer#iris$beginClouds (@Inject HEAD of the clouds pass lambda)
            if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                this.iris$pipeline.setPhase(net.irisshaders.iris.pipeline.WorldRenderingPhase.CLOUDS);
            }

            // MODIFIED for porting: was sodium-extra's cloud MixinLevelRenderer#modifyCloudHeight
            // (@Redirect on CloudRenderer#render inside lambda$addCloudsPass$0)
            float effectiveCloudHeight = me.flashyreese.mods.sodiumextra.client.config.SodiumExtraFeatures.CLOUD
                    && me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod.options().extraSettings.cloudHeightOverride
                ? me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod.options().extraSettings.cloudHeight + 0.33F
                : cloudHeight;
            this.cloudRenderer.render(cloudColor, cloudStatus, effectiveCloudHeight, cloudRange, cameraPosition, gameTime, partialTicks);
            // MODIFIED for porting: was iris's MixinLevelRenderer#iris$endClouds (@Inject RETURN of the clouds pass lambda)
            if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                this.iris$pipeline.setPhase(net.irisshaders.iris.pipeline.WorldRenderingPhase.NONE);
            }
        });
    }

    /**
     * MODIFIED for porting: merges iris's two wrappers around {@code ChunkSectionsToRender#renderGroup} in the main pass
     * lambda - {@code skipRenderChunks} (@WrapWithCondition; a pipeline can skip all rendering) and
     * {@code iris$beginTerrainLayer} (@WrapOperation; the pack needs to know which terrain layer group is being drawn).
     */
    private void iris$renderTerrainGroup(
        final ChunkSectionsToRender chunkSectionsToRender, final ChunkSectionLayerGroup group, final com.mojang.blaze3d.textures.GpuSampler sampler
    ) {
        if (!net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            chunkSectionsToRender.renderGroup(group, sampler);
            return;
        }

        boolean skipAll = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable() instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline irisPipeline
            && irisPipeline.skipAllRendering();

        this.iris$pipeline.setPhase(net.irisshaders.iris.pipeline.WorldRenderingPhase.fromTerrainRenderType(group));

        if (!skipAll) {
            chunkSectionsToRender.renderGroup(group, sampler);
        }

        this.iris$pipeline.setPhase(net.irisshaders.iris.pipeline.WorldRenderingPhase.NONE);
    }

    private void addWeatherPass(final FrameGraphBuilder frame, final GpuBufferSlice fog) {
        int renderDistance = this.optionsRenderState.renderDistance * 16;
        FramePass pass = frame.addPass("weather");
        if (this.targets.weather != null) {
            this.targets.weather = pass.readsAndWrites(this.targets.weather);
        } else {
            this.targets.main = pass.readsAndWrites(this.targets.main);
        }

        pass.executes(
            () -> {
                // MODIFIED for porting: was iris's MixinLevelRenderer#iris$beginWeather (@Inject HEAD of the weather pass
                // lambda)
                if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                    this.iris$pipeline.setPhase(net.irisshaders.iris.pipeline.WorldRenderingPhase.RAIN_SNOW);
                }

                RenderSystem.setShaderFog(fog);
                CameraRenderState cameraState = this.levelRenderState.cameraRenderState;
                this.weatherEffectRenderer.render(cameraState.pos, this.levelRenderState.weatherRenderState);
                // MODIFIED for porting: was iris's MixinLevelRenderer#iris$beginWorldBorder (@Inject at the INVOKE of
                // WorldBorderRenderer#render inside the weather pass lambda)
                if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                    this.iris$pipeline.setPhase(net.irisshaders.iris.pipeline.WorldRenderingPhase.WORLD_BORDER);
                }

                this.worldBorderRenderer
                    .render(this.levelRenderState.worldBorderRenderState, cameraState.pos, renderDistance, this.levelRenderState.cameraRenderState.depthFar);
                // MODIFIED for porting: was iris's MixinLevelRenderer#iris$endWeather (@Inject RETURN of the weather pass
                // lambda)
                if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                    this.iris$pipeline.setPhase(net.irisshaders.iris.pipeline.WorldRenderingPhase.NONE);
                }
            }
        );
    }

    private void addAlwaysOnTopPass(final FrameGraphBuilder frame, final FeatureRenderDispatcher.PreparedFrame featureFrame, final GpuBufferSlice fog) {
        if (featureFrame.hasAnyAlwaysOnTop()) {
            FramePass pass = frame.addPass("always_on_top");
            this.targets.main = pass.readsAndWrites(this.targets.main);
            if (this.targets.itemEntity != null) {
                this.targets.itemEntity = pass.readsAndWrites(this.targets.itemEntity);
            }

            ResourceHandle<RenderTarget> mainTarget = this.targets.main;
            pass.executes(() -> {
                RenderSystem.setShaderFog(fog);
                PoseStack poseStack = new PoseStack();
                RenderTarget mainRenderTarget = mainTarget.get();
                RenderSystem.outputColorTextureOverride = mainRenderTarget.getColorTextureView();
                RenderSystem.outputDepthTextureOverride = mainRenderTarget.getDepthTextureView();
                // MODIFIED for porting: was iris's MixinLevelRenderer#skip (@WrapOperation around
                // CommandEncoder#clearDepthTexture inside the always_on_top pass lambda) - with a shader pack in use the depth
                // buffer must not be cleared here, because the pack's own passes still need it.
                if (!net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() || !net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse()) {
                    RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(mainRenderTarget.getDepthTexture(), 0.0);
                }

                featureFrame.executeAlwaysOnTop();
                RenderSystem.outputColorTextureOverride = null;
                RenderSystem.outputDepthTextureOverride = null;
                this.checkPoseStack(poseStack);
            });
        }
    }

    /**
     * MODIFIED for porting: sodium core.render.world LevelRendererMixin#prepareChunkRenders (@Overwrite) - sodium builds and
     * submits its own draw batches, so this only has to hand out an empty container bound to the block atlas.
     */
    public ChunkSectionsToRender prepareChunkRenders(final Matrix4fc modelViewMatrix) {
        return new ChunkSectionsToRender(
            net.minecraft.client.Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(),
            SODIUM_STATIC_MAP,
            -1,
            new GpuBufferSlice[0]
        );
    }

    // MODIFIED for porting: original vanilla body of prepareChunkRenders, replaced above
    @SuppressWarnings("unused")
    private ChunkSectionsToRender sodium$vanillaPrepareChunkRenders(final Matrix4fc modelViewMatrix) {
        ObjectListIterator<SectionRenderDispatcher.RenderSection> iterator = this.visibleSections.listIterator(0);
        EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> drawGroups = new EnumMap<>(ChunkSectionLayer.class);
        int largestIndexCount = 0;

        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            drawGroups.put(layer, new Int2ObjectOpenHashMap<>());
        }

        List<DynamicUniforms.ChunkSectionInfo> sectionInfos = new ArrayList<>();
        GpuTextureView blockAtlas = this.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        int textureAtlasWidth = blockAtlas.getWidth(0);
        int textureAtlasHeight = blockAtlas.getHeight(0);
        if (this.sectionRenderDispatcher != null) {
            this.sectionRenderDispatcher.lock();

            try {
                while (iterator.hasNext()) {
                    SectionRenderDispatcher.RenderSection section = iterator.next();
                    SectionMesh sectionMesh = section.getSectionMesh();
                    BlockPos renderOffset = section.getRenderOrigin();
                    long now = Util.getMillis();
                    int uboIndex = -1;

                    for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                        SectionMesh.SectionDraw draw = sectionMesh.getSectionDraw(layer);
                        SectionRenderDispatcher.RenderSectionBufferSlice slice = this.sectionRenderDispatcher.getRenderSectionSlice(sectionMesh, layer);
                        if (slice != null && draw != null && (!draw.hasCustomIndexBuffer() || slice.indexBuffer() != null)) {
                            if (uboIndex == -1) {
                                uboIndex = sectionInfos.size();
                                sectionInfos.add(
                                    new DynamicUniforms.ChunkSectionInfo(
                                        new Matrix4f(modelViewMatrix),
                                        renderOffset.getX(),
                                        renderOffset.getY(),
                                        renderOffset.getZ(),
                                        section.getVisibility(now),
                                        textureAtlasWidth,
                                        textureAtlasHeight
                                    )
                                );
                            }

                            int combinedHash = 173;
                            VertexFormat vertexFormat = layer.pipeline().getVertexFormatBinding(0);
                            GpuBuffer vertexBuffer = slice.vertexBuffer();
                            if (layer != ChunkSectionLayer.TRANSLUCENT) {
                                combinedHash = 31 * combinedHash + vertexBuffer.hashCode();
                            }

                            int firstIndex = 0;
                            GpuBuffer indexBuffer;
                            IndexType indexType;
                            if (!draw.hasCustomIndexBuffer()) {
                                if (draw.indexCount() > largestIndexCount) {
                                    largestIndexCount = draw.indexCount();
                                }

                                indexBuffer = null;
                                indexType = null;
                            } else {
                                indexBuffer = slice.indexBuffer();
                                indexType = draw.indexType();
                                if (layer != ChunkSectionLayer.TRANSLUCENT) {
                                    combinedHash = 31 * combinedHash + indexBuffer.hashCode();
                                    combinedHash = 31 * combinedHash + indexType.hashCode();
                                }

                                firstIndex = (int)(slice.indexBufferOffset() / indexType.bytes);
                            }

                            int finalUboIndex = uboIndex;
                            int baseVertex = (int)(slice.vertexBufferOffset() / vertexFormat.getVertexSize());
                            List<RenderPass.Draw<GpuBufferSlice[]>> draws = drawGroups.get(layer).computeIfAbsent(combinedHash, var0 -> new ArrayList<>());
                            draws.add(
                                new RenderPass.Draw<>(
                                    0,
                                    vertexBuffer,
                                    indexBuffer,
                                    indexType,
                                    firstIndex,
                                    draw.indexCount(),
                                    baseVertex,
                                    (sectionUbos, uploader) -> uploader.upload("ChunkSection", sectionUbos[finalUboIndex])
                                )
                            );
                        }
                    }
                }
            } finally {
                this.sectionRenderDispatcher.unlock();
            }
        }

        GpuBufferSlice[] chunkSectionInfos = RenderSystem.getDynamicUniforms()
            .writeChunkSections(sectionInfos.toArray(new DynamicUniforms.ChunkSectionInfo[0]));
        return new ChunkSectionsToRender(blockAtlas, drawGroups, largestIndexCount, chunkSectionInfos);
    }

    private void compileSections(final CameraRenderState camera) {
        ProfilerFiller profiler = Profiler.get();
        profiler.push("populateSectionsToCompile");
        BlockPos cameraPosition = camera.blockPos;
        long fadeDuration = Mth.floor(this.optionsRenderState.chunkSectionFadeInTime * 1000.0);

        for (SectionUpdateRenderState state : this.levelRenderState.sectionUpdateRenderStates) {
            BlockPos center = SectionPos.of(state.sectionNode()).center();
            double distSqr = center.distSqr(cameraPosition);
            boolean isNearby = distSqr < 768.0;
            boolean rebuildSync = false;
            if (this.optionsRenderState.prioritizeChunkUpdates == PrioritizeChunkUpdates.NEARBY) {
                rebuildSync = isNearby || state.playerChanged();
            } else if (this.optionsRenderState.prioritizeChunkUpdates == PrioritizeChunkUpdates.PLAYER_AFFECTED) {
                rebuildSync = state.playerChanged();
            }

            SectionRenderDispatcher.RenderSection section = this.viewArea.getRenderSection(state.sectionNode());
            if (!isNearby && !section.wasPreviouslyEmpty()) {
                section.setFadeDuration(fadeDuration);
            } else {
                section.setFadeDuration(0L);
            }

            section.setWasPreviouslyEmpty(false);
            if (rebuildSync) {
                profiler.push("compileSectionSynchronously");
                section.compileSync(state.region());
                profiler.pop();
            } else {
                section.compileAsync(state.region());
            }
        }

        profiler.popPush("scheduleTranslucentResort");
        this.scheduleTranslucentSectionResort(camera.pos);
        profiler.pop();
    }

    private void checkPoseStack(final PoseStack poseStack) {
        if (!poseStack.isEmpty()) {
            throw new IllegalStateException("Pose stack not empty");
        }
    }

    private void submitEntities(final PoseStack poseStack, final LevelRenderState levelRenderState, final SubmitNodeCollector output) {
        Vec3 cameraPos = levelRenderState.cameraRenderState.pos;
        double camX = cameraPos.x();
        double camY = cameraPos.y();
        double camZ = cameraPos.z();

        for (EntityRenderState state : levelRenderState.entityRenderStates) {
            this.entityRenderDispatcher.submit(state, levelRenderState.cameraRenderState, state.x - camX, state.y - camY, state.z - camZ, poseStack, output);
        }
    }

    private void submitBlockEntities(final PoseStack poseStack, final LevelRenderState levelRenderState, final SubmitNodeCollector submitNodeCollector) {
        Vec3 cameraPos = levelRenderState.cameraRenderState.pos;
        double camX = cameraPos.x();
        double camY = cameraPos.y();
        double camZ = cameraPos.z();

        for (BlockEntityRenderState renderState : levelRenderState.blockEntityRenderStates) {
            BlockPos blockPos = renderState.blockPos;
            poseStack.pushPose();
            poseStack.translate(blockPos.getX() - camX, blockPos.getY() - camY, blockPos.getZ() - camZ);
            this.blockEntityRenderDispatcher.submit(renderState, poseStack, submitNodeCollector, levelRenderState.cameraRenderState);
            poseStack.popPose();
        }
    }

    private void submitBlockDestroyAnimation(final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final LevelRenderState levelRenderState) {
        if (!levelRenderState.blockBreakingRenderStates.isEmpty()) {
            Vec3 cameraPos = levelRenderState.cameraRenderState.pos;
            double camX = cameraPos.x();
            double camY = cameraPos.y();
            double camZ = cameraPos.z();
            List<BlockStateModelPart> parts = new ArrayList<>();
            RandomSource random = RandomSource.createThreadLocalInstance();

            for (BlockBreakingRenderState state : levelRenderState.blockBreakingRenderStates) {
                if (state.blockState().getRenderShape() == RenderShape.MODEL) {
                    BlockPos pos = state.blockPos();
                    poseStack.pushPose();
                    poseStack.translate(pos.getX() - camX, pos.getY() - camY, pos.getZ() - camZ);
                    poseStack.translate(state.blockState().getOffset(pos));
                    BlockStateModel model = this.modelManager.getBlockStateModelSet().get(state.blockState());
                    random.setSeed(state.blockState().getSeed(pos));
                    model.collectParts(random, parts);
                    submitNodeCollector.submitBreakingBlockModel(poseStack, List.copyOf(parts), state.progress());
                    parts.clear();
                    poseStack.popPose();
                }
            }
        }
    }

    // MODIFIED for porting: the body of iris's MixinLevelRenderer#iris$beginBlockOutline
    private static net.minecraft.client.renderer.rendertype.RenderType iris$wrapOutline(
        final net.minecraft.client.renderer.rendertype.RenderType type
    ) {
        if (!net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            return type;
        }

        return new net.irisshaders.iris.layer.OuterWrappedRenderType(
            "iris:is_outline", type, net.irisshaders.iris.layer.IsOutlineRenderStateShard.INSTANCE
        );
    }

    private void submitBlockOutline(final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final LevelRenderState levelRenderState) {
        BlockOutlineRenderState state = levelRenderState.blockOutlineRenderState;
        if (state != null) {
            Vec3 cameraPos = levelRenderState.cameraRenderState.pos;
            BlockPos pos = state.pos();
            poseStack.pushPose();
            poseStack.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
            if (state.highContrast()) {
                // MODIFIED for porting: was iris's MixinLevelRenderer#iris$beginBlockOutline (@ModifyArg index 2 on
                // submitHitOutline, which matches both calls) - the wrapper tells the pack this geometry is the block outline.
                this.submitHitOutline(
                    poseStack,
                    submitNodeCollector,
                    iris$wrapOutline(RenderTypes.secondaryBlockOutline()),
                    state,
                    -16777216,
                    7.0F,
                    state.isTranslucent()
                );
            }

            int outlineColor = state.highContrast() ? -11010079 : ARGB.black(102);
            // MODIFIED for porting: iris MixinLevelRenderer#iris$beginBlockOutline, second of the two submitHitOutline calls
            this.submitHitOutline(
                poseStack,
                submitNodeCollector,
                iris$wrapOutline(RenderTypes.lines()),
                state,
                outlineColor,
                this.gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth,
                state.isTranslucent()
            );
            poseStack.popPose();
        }
    }

    private void submitHitOutline(
        final PoseStack poseStack,
        final SubmitNodeCollector submitNodeCollector,
        final RenderType renderType,
        final BlockOutlineRenderState state,
        final int color,
        final float width,
        final boolean afterTerrain
    ) {
        if (SharedConstants.DEBUG_SHAPES) {
            submitNodeCollector.submitShapeOutline(poseStack, state.shape(), renderType, -1, width, afterTerrain);
            if (state.collisionShape() != null) {
                submitNodeCollector.submitShapeOutline(
                    poseStack, state.collisionShape(), renderType, ARGB.colorFromFloat(0.4F, 0.0F, 0.0F, 0.0F), width, afterTerrain
                );
            }

            if (state.occlusionShape() != null) {
                submitNodeCollector.submitShapeOutline(
                    poseStack, state.occlusionShape(), renderType, ARGB.colorFromFloat(0.4F, 0.0F, 1.0F, 0.0F), width, afterTerrain
                );
            }

            if (state.interactionShape() != null) {
                submitNodeCollector.submitShapeOutline(
                    poseStack, state.interactionShape(), renderType, ARGB.colorFromFloat(0.4F, 0.0F, 0.0F, 1.0F), width, afterTerrain
                );
            }
        } else {
            submitNodeCollector.submitShapeOutline(poseStack, state.shape(), renderType, color, width, afterTerrain);
        }
    }

    public void resize(final int width, final int height) {
        this.sectionOcclusionGraph.invalidate();
        this.entityOutlineTarget.resize(width, height);
    }

    public void endFrame() {
        this.cloudRenderer.endFrame();
        // MODIFIED for porting: sodium core.render.world LevelRendererMixin#sodium$endFrame (RETURN)
        this.sodium$renderer.endFrame();
    }

    @Override
    public void close() {
        this.resetLevelRenderData();
        this.entityOutlineTarget.destroyBuffers();
        if (this.skyRenderer != null) {
            this.skyRenderer.close();
        }

        if (this.chunkLayerSampler != null) {
            this.chunkLayerSampler.close();
        }

        this.worldBorderRenderer.close();
        this.cloudRenderer.close();
        this.weatherEffectRenderer.close();
    }

    public void doEntityOutline() {
        if (this.levelRenderState.shouldShowEntityOutlines) {
            this.entityOutlineTarget
                .blitAndBlendToTexture(this.gameRenderer.mainRenderTarget().getColorTextureView(), this.gameRenderer.mainRenderTarget().getDepthTextureView());
        }
    }

    /**
     * MODIFIED for porting: sodium core.render.world LevelRendererMixin#sodium$replace (HEAD, cancellable). The vanilla
     * section render dispatcher and view area are replaced by no-op stand-ins so nothing is allocated for them, and the
     * reload is forwarded to sodium's renderer.
     */
    public void invalidateCompiledGeometry(final ClientLevel level, final Options options, final Camera camera, final BlockColors blockColors) {
        this.cloudRenderer.markForRebuild();
        LeavesBlock.setCutoutLeaves(options.cutoutLeaves().get());
        this.sodium$renderer.reload();
        this.sectionRenderDispatcher = new net.caffeinemc.mods.sodium.client.util.IgnoringSectionRenderDispatcher(
            Util.backgroundExecutor(), this.renderBuffers, null, this.sectionOcclusionGraph::schedulePropagationFrom
        );
        this.viewArea = new net.caffeinemc.mods.sodium.client.util.IgnoringViewArea(this.sectionRenderDispatcher);
        this.sectionOcclusionGraph.waitAndReset(this.viewArea);
        this.clearVisibleSections();
    }

    // MODIFIED for porting: original vanilla body of invalidateCompiledGeometry, replaced above
    @SuppressWarnings("unused")
    private void sodium$vanillaInvalidateCompiledGeometry(final ClientLevel level, final Options options, final Camera camera, final BlockColors blockColors) {
        SectionCompiler sectionCompiler = new SectionCompiler(
            options.ambientOcclusion().get(),
            options.cutoutLeaves().get(),
            this.modelManager.getBlockStateModelSet(),
            this.modelManager.getFluidStateModelSet(),
            blockColors
        );
        if (this.sectionRenderDispatcher == null) {
            this.sectionRenderDispatcher = new SectionRenderDispatcher(
                Util.backgroundExecutor(), this.renderBuffers, sectionCompiler, this.sectionOcclusionGraph::schedulePropagationFrom
            );
        } else {
            this.sectionRenderDispatcher.setCompiler(sectionCompiler);
        }

        this.cloudRenderer().markForRebuild();
        LeavesBlock.setCutoutLeaves(options.cutoutLeaves().get());
        if (this.viewArea != null) {
            this.viewArea.releaseAllBuffers();
        }

        this.sectionRenderDispatcher.clearCompileQueue();
        this.viewArea = new ViewArea(
            this.sectionRenderDispatcher,
            level.getMinY(),
            level.getMaxY(),
            level.getMinSectionY(),
            level.getMaxSectionY(),
            options.getEffectiveRenderDistance(),
            this.sectionOcclusionGraph
        );
        this.sectionOcclusionGraph().waitAndReset(this.viewArea);
        this.clearVisibleSections();
        SectionPos cameraSectionPos = SectionPos.of(camera.position());
        this.viewArea.repositionCamera(cameraSectionPos);
    }

    private @Nullable PostChain getTransparencyChain() {
        return !this.gameRenderer.gameRenderState().useShaderTransparency()
            ? null
            : this.shaderManager.getPostChain(TRANSPARENCY_POST_CHAIN_ID, LevelTargetBundle.SORTING_TARGETS);
    }

    private void scheduleTranslucentSectionResort(final Vec3 cameraPos) {
        if (!this.visibleSections.isEmpty()) {
            BlockPos cameraBlockPos = BlockPos.containing(cameraPos);
            boolean blockPosChanged = !cameraBlockPos.equals(this.lastTranslucentSortBlockPos);
            TranslucencyPointOfView pointOfView = new TranslucencyPointOfView();

            for (SectionRenderDispatcher.RenderSection section : this.nearbyVisibleSections) {
                this.scheduleResort(section, pointOfView, cameraPos, blockPosChanged, true);
            }

            this.translucencyResortIterationIndex = this.translucencyResortIterationIndex % this.visibleSections.size();
            int resortsLeft = Math.max(this.visibleSections.size() / 8, 15);

            while (resortsLeft-- > 0) {
                int index = this.translucencyResortIterationIndex++ % this.visibleSections.size();
                this.scheduleResort(this.visibleSections.get(index), pointOfView, cameraPos, blockPosChanged, false);
            }

            this.lastTranslucentSortBlockPos = cameraBlockPos;
        }
    }

    private void scheduleResort(
        final SectionRenderDispatcher.RenderSection section,
        final TranslucencyPointOfView pointOfView,
        final Vec3 cameraPos,
        final boolean blockPosChanged,
        final boolean isNearby
    ) {
        pointOfView.set(cameraPos, section.getSectionNode());
        boolean pointOfViewChanged = section.getSectionMesh().isDifferentPointOfView(pointOfView);
        boolean resortBecauseBlockPosChanged = blockPosChanged && (pointOfView.isAxisAligned() || isNearby);
        if ((resortBecauseBlockPosChanged || pointOfViewChanged) && !section.transparencyResortingScheduled() && section.hasTranslucentGeometry()) {
            section.resortTransparency();
        }
    }

    public void clearVisibleSections() {
        this.visibleSections.clear();
        this.nearbyVisibleSections.clear();
    }

    public void resetLevelRenderData() {
        // MODIFIED for porting: sodium core.render.world LevelRendererMixin#onTerrainUpdateScheduled is injected at RETURN;
        // it is placed here because the vanilla body below has several exit paths only in the sense of early returns - it has
        // none, so this call happens after the whole body (see the end of this method).
        if (this.viewArea != null) {
            this.viewArea.releaseAllBuffers();
            this.viewArea = null;
        }

        if (this.sectionRenderDispatcher != null) {
            this.sectionRenderDispatcher.dispose();
        }

        this.sectionRenderDispatcher = null;
        this.sectionOcclusionGraph.waitAndReset(null);
        this.clearVisibleSections();
        // MODIFIED for porting: sodium core.render.world LevelRendererMixin#onTerrainUpdateScheduled (RETURN)
        this.sodium$renderer.scheduleTerrainUpdate();
    }

    /**
     * MODIFIED for porting: sodium core.render.world LevelRendererMixin#hasRenderedAllSections (@Overwrite) - redirect the
     * check to sodium's renderer.
     */
    public boolean hasRenderedAllSections() {
        return this.sodium$renderer.isTerrainRenderComplete();
    }

    /**
     * MODIFIED for porting: sodium core.render.world LevelRendererMixin#isSectionCompiledAndVisible (@Overwrite) - redirect
     * chunk updates to sodium's renderer.
     */
    public boolean isSectionCompiledAndVisible(final BlockPos blockPos) {
        return this.sodium$renderer.isSectionReady(blockPos.getX() >> 4, blockPos.getY() >> 4, blockPos.getZ() >> 4);
    }

    public @Nullable SectionRenderDispatcher sectionRenderDispatcher() {
        return this.sectionRenderDispatcher;
    }

    public EntityRenderDispatcher entityRenderDispatcher() {
        return this.entityRenderDispatcher;
    }

    public BlockEntityRenderDispatcher blockEntityRenderDispatcher() {
        return this.blockEntityRenderDispatcher;
    }

    public @Nullable RenderTarget entityOutlineTarget() {
        return this.targets.entityOutline != null ? this.targets.entityOutline.get() : null;
    }

    public @Nullable RenderTarget translucentTarget() {
        return this.targets.translucent != null ? this.targets.translucent.get() : null;
    }

    public @Nullable RenderTarget itemEntityTarget() {
        return this.targets.itemEntity != null ? this.targets.itemEntity.get() : null;
    }

    public @Nullable RenderTarget particlesTarget() {
        return this.targets.particles != null ? this.targets.particles.get() : null;
    }

    public @Nullable RenderTarget weatherTarget() {
        return this.targets.weather != null ? this.targets.weather.get() : null;
    }

    public @Nullable RenderTarget cloudsTarget() {
        return this.targets.clouds != null ? this.targets.clouds.get() : null;
    }

    public CloudRenderer cloudRenderer() {
        return this.cloudRenderer;
    }

    public @Nullable SkyRenderer skyRenderer() {
        return this.skyRenderer;
    }

    public WeatherEffectRenderer weatherEffectRenderer() {
        return this.weatherEffectRenderer;
    }

    public WorldBorderRenderer worldBorderRenderer() {
        return this.worldBorderRenderer;
    }

    public @Nullable ViewArea viewArea() {
        return this.viewArea;
    }

    public ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections() {
        return this.visibleSections;
    }

    public ObjectArrayList<SectionRenderDispatcher.RenderSection> nearbyVisibleSections() {
        return this.nearbyVisibleSections;
    }

    public LongCollection expectedChunks() {
        return this.sectionOcclusionGraph.expectedChunks();
    }

    public SectionOcclusionGraph sectionOcclusionGraph() {
        return this.sectionOcclusionGraph;
    }

    public Gizmos.TemporaryCollection collectPerFrameRenderThreadGizmos() {
        return Gizmos.withCollector(this.renderThreadGizmos);
    }

    private void finalizeGizmoCollection() {
        DrawableGizmoPrimitives standardPrimitives = new DrawableGizmoPrimitives();
        DrawableGizmoPrimitives alwaysOnTopPrimitives = new DrawableGizmoPrimitives();
        long currentMillis = Util.getMillis();

        for (SimpleGizmoCollector.GizmoInstance instance : this.renderThreadGizmos.drainGizmos()) {
            instance.gizmo().emit(instance.isAlwaysOnTop() ? alwaysOnTopPrimitives : standardPrimitives, instance.getAlphaMultiplier(currentMillis));
        }

        this.finalizedGizmos = new LevelRenderer.FinalizedGizmos(standardPrimitives, alwaysOnTopPrimitives);
    }

    public void addMainThreadGizmos(final List<SimpleGizmoCollector.GizmoInstance> mainThreadGizmos) {
        this.renderThreadGizmos.addTemporaryGizmos(mainThreadGizmos);
    }

    @OnlyIn(Dist.CLIENT)
    private record FinalizedGizmos(DrawableGizmoPrimitives standardPrimitives, DrawableGizmoPrimitives alwaysOnTopPrimitives) {
    }
}