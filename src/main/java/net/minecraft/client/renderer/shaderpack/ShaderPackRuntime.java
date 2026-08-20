package net.minecraft.client.renderer.shaderpack;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class ShaderPackRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier VANILLA_TERRAIN_SHADER = Identifier.withDefaultNamespace("core/terrain");
    private static final ShaderPackRuntime INSTANCE = new ShaderPackRuntime();
    private @Nullable ShaderPackManager manager;
    private ShaderPackBackend activeBackend = ShaderPackBackend.UNKNOWN;
    private @Nullable String activePack;
    private long generation;
    private boolean bridgeReady;
    private boolean bridgeFailed;
    private Map<ChunkSectionLayer, RenderPipeline> terrainPipelines = Map.of();
    private @Nullable ShaderPackFinalPass finalPass;

    private ShaderPackRuntime() {
    }

    public static RenderPipeline terrainPipeline(final ChunkSectionLayer layer) {
        return INSTANCE.resolveTerrainPipeline(layer);
    }

    public static void applyFinalPass() {
        INSTANCE.renderFinalPass();
    }

    public static void invalidate() {
        synchronized (INSTANCE) {
            INSTANCE.manager = null;
            INSTANCE.reset(ShaderPackBackend.UNKNOWN, null);
        }
    }

    public static String status() {
        synchronized (INSTANCE) {
            ShaderPackManager manager = INSTANCE.manager();
            ShaderPackBackend backend = ShaderPackBackend.current();
            String pack = manager.selectedPackPath().isPresent() ? manager.selectedPack().orElse(null) : null;
            INSTANCE.updateSelection(backend, pack);
            if (pack == null) {
                return "No shader pack selected";
            }
            if (!backend.supportsCustomShaderPipelines()) {
                return "Shader rendering unavailable on " + backend.displayName() + "; vanilla renderer active";
            }

            String terrainStatus;
            if (INSTANCE.bridgeReady) {
                terrainStatus = "terrain bridge active";
            } else if (INSTANCE.bridgeFailed) {
                terrainStatus = "terrain bridge failed; vanilla terrain active";
            } else {
                terrainStatus = "terrain bridge ready to initialize";
            }

            String finalStatus = INSTANCE.finalPass == null ? "final.fsh waiting for the next world frame" : INSTANCE.finalPass.status();
            return backend.displayName() + ": " + terrainStatus + "; " + finalStatus;
        }
    }

    private synchronized RenderPipeline resolveTerrainPipeline(final ChunkSectionLayer layer) {
        ShaderPackManager manager = this.manager();
        ShaderPackBackend backend = ShaderPackBackend.current();
        String pack = manager.selectedPackPath().isPresent() ? manager.selectedPack().orElse(null) : null;
        this.updateSelection(backend, pack);
        if (pack == null || !backend.supportsCustomShaderPipelines()) {
            return layer.pipeline();
        }

        if (!this.bridgeReady && !this.bridgeFailed) {
            this.tryInitializeTerrainBridge();
        }

        return this.bridgeReady ? this.terrainPipelines.getOrDefault(layer, layer.pipeline()) : layer.pipeline();
    }

    private synchronized void renderFinalPass() {
        ShaderPackManager manager = this.manager();
        ShaderPackBackend backend = ShaderPackBackend.current();
        String pack = manager.selectedPackPath().isPresent() ? manager.selectedPack().orElse(null) : null;
        this.updateSelection(backend, pack);
        if (pack == null || !backend.supportsCustomShaderPipelines()) {
            return;
        }

        if (this.finalPass == null) {
            this.finalPass = new ShaderPackFinalPass(manager, pack, backend, this.generation);
        }

        RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        this.finalPass.apply(mainTarget);
    }

    private ShaderPackManager manager() {
        if (this.manager == null) {
            this.manager = new ShaderPackManager(Minecraft.getInstance().gameDirectory.toPath());
        }
        return this.manager;
    }

    private void updateSelection(final ShaderPackBackend backend, final @Nullable String pack) {
        if (backend != this.activeBackend || !Objects.equals(pack, this.activePack)) {
            this.reset(backend, pack);
        }
    }

    private void reset(final ShaderPackBackend backend, final @Nullable String pack) {
        if (this.finalPass != null) {
            this.finalPass.close();
            this.finalPass = null;
        }
        this.activeBackend = backend;
        this.activePack = pack;
        this.generation++;
        this.bridgeReady = false;
        this.bridgeFailed = false;
        this.terrainPipelines = Map.of();
    }

    private void tryInitializeTerrainBridge() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return;
        }

        ShaderManager shaderManager = Minecraft.getInstance().getShaderManager();
        if (shaderManager.getShader(VANILLA_TERRAIN_SHADER, ShaderType.VERTEX) == null
            || shaderManager.getShader(VANILLA_TERRAIN_SHADER, ShaderType.FRAGMENT) == null) {
            return;
        }

        Identifier bridgeShader = Identifier.fromNamespaceAndPath("sigma", "shaderpack/terrain_" + Long.toUnsignedString(this.generation, 16));
        ShaderSource bridgeSource = (id, type) -> id.equals(bridgeShader) ? shaderManager.getShader(VANILLA_TERRAIN_SHADER, type) : null;
        EnumMap<ChunkSectionLayer, RenderPipeline> pipelines = new EnumMap<>(ChunkSectionLayer.class);
        pipelines.put(ChunkSectionLayer.SOLID, this.createTerrainPipeline(ChunkSectionLayer.SOLID, bridgeShader));
        pipelines.put(ChunkSectionLayer.CUTOUT, this.createTerrainPipeline(ChunkSectionLayer.CUTOUT, bridgeShader));
        pipelines.put(ChunkSectionLayer.TRANSLUCENT, this.createTerrainPipeline(ChunkSectionLayer.TRANSLUCENT, bridgeShader));

        for (RenderPipeline pipeline : pipelines.values()) {
            CompiledRenderPipeline compiled = device.precompilePipeline(pipeline, bridgeSource);
            if (!compiled.isValid()) {
                this.bridgeFailed = true;
                this.terrainPipelines = Map.of();
                LOGGER.warn(
                    "Shader terrain bridge failed to compile on {}. Keeping the vanilla terrain renderer active.",
                    this.activeBackend.displayName()
                );
                return;
            }
        }

        this.terrainPipelines = Map.copyOf(pipelines);
        this.bridgeReady = true;
        LOGGER.info(
            "Shader terrain bridge initialized on {} for selected pack {}. Pack GLSL transformation will replace the bridge sources in the next stage.",
            this.activeBackend.displayName(),
            this.activePack
        );
    }

    private RenderPipeline createTerrainPipeline(final ChunkSectionLayer layer, final Identifier shader) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("sigma", "pipeline/shader_bridge_" + layer.label() + "_" + Long.toUnsignedString(this.generation, 16)))
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.CHUNK_SECTION)
            .withVertexShader(shader)
            .withFragmentShader(shader)
            .withVertexBinding(0, DefaultVertexFormat.BLOCK)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(DepthStencilState.DEFAULT);

        if (layer == ChunkSectionLayer.CUTOUT) {
            builder.withShaderDefine("ALPHA_CUTOUT", 0.5F);
        } else if (layer == ChunkSectionLayer.TRANSLUCENT) {
            builder.withShaderDefine("ALPHA_CUTOUT", 0.1F);
            builder.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT));
        }

        return builder.build();
    }
}
