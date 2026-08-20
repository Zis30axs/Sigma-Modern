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
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class ShaderPackRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ShaderPackRuntime INSTANCE = new ShaderPackRuntime();
    private final ShaderPackRenderTargets gbufferTargets = new ShaderPackRenderTargets();
    private @Nullable ShaderPackManager manager;
    private ShaderPackBackend activeBackend = ShaderPackBackend.UNKNOWN;
    private @Nullable String activePack;
    private long generation;
    private boolean terrainInitialized;
    private int terrainActivePipelines;
    private int terrainMaxColorAttachments = 1;
    private @Nullable String terrainFailureReason;
    private Map<ChunkSectionLayer, RenderPipeline> terrainPipelines = Map.of();
    private Map<ChunkSectionLayer, String> terrainProgramNames = Map.of();
    private Map<ChunkSectionLayer, List<Integer>> terrainColorTargets = Map.of();
    private Set<Integer> terrainExtraTargets = Set.of();
    private @Nullable ShaderPackFinalPass finalPass;

    private ShaderPackRuntime() {
    }

    public static RenderPipeline terrainPipeline(final ChunkSectionLayer layer) {
        return INSTANCE.resolveTerrainPipeline(layer);
    }

    public static int terrainColorAttachmentCount(final ChunkSectionLayer layer) {
        return INSTANCE.resolveTerrainColorAttachmentCount(layer);
    }

    public static RenderPassDescriptor terrainPassDescriptor(
        final CommandEncoder encoder,
        final ChunkSectionLayer layer,
        final RenderTarget renderTarget,
        final Supplier<String> label
    ) {
        return INSTANCE.createTerrainPassDescriptor(encoder, layer, renderTarget, label);
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
            if (!INSTANCE.terrainInitialized) {
                terrainStatus = "terrain pack GLSL waiting for the first terrain draw";
            } else if (INSTANCE.terrainActivePipelines == 0) {
                terrainStatus = "terrain pack GLSL fallback: "
                    + (INSTANCE.terrainFailureReason == null ? "vanilla terrain active" : INSTANCE.terrainFailureReason);
            } else {
                terrainStatus = "terrain pack GLSL " + INSTANCE.terrainActivePipelines + "/3 active";
                if (INSTANCE.terrainMaxColorAttachments > 1) {
                    terrainStatus += "; MRT up to " + INSTANCE.terrainMaxColorAttachments + " attachments";
                }
                if (INSTANCE.terrainActivePipelines < 3 && INSTANCE.terrainFailureReason != null) {
                    terrainStatus += "; fallback: " + INSTANCE.terrainFailureReason;
                }
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

        if (!this.terrainInitialized) {
            this.tryInitializeTerrainPipelines();
        }

        return this.terrainPipelines.getOrDefault(layer, layer.pipeline());
    }

    private synchronized int resolveTerrainColorAttachmentCount(final ChunkSectionLayer layer) {
        this.resolveTerrainPipeline(layer);
        return this.terrainColorTargets.getOrDefault(layer, List.of(0)).size();
    }

    private synchronized RenderPassDescriptor createTerrainPassDescriptor(
        final CommandEncoder encoder,
        final ChunkSectionLayer layer,
        final RenderTarget renderTarget,
        final Supplier<String> label
    ) {
        List<Integer> colorTargets = this.terrainColorTargets.getOrDefault(layer, List.of(0));
        if (colorTargets.size() > 1 || !this.terrainExtraTargets.isEmpty()) {
            this.gbufferTargets.prepare(encoder, renderTarget.width, renderTarget.height, this.terrainExtraTargets);
        }

        RenderPassDescriptor descriptor = RenderPassDescriptor.create(label);
        for (int colorTarget : colorTargets) {
            descriptor.withColorAttachment(this.gbufferTargets.attachmentView(colorTarget, renderTarget));
        }
        if (renderTarget.getDepthTextureView() != null) {
            descriptor.withDepthAttachment(renderTarget.getDepthTextureView());
        }
        return descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, renderTarget.width, renderTarget.height));
    }

    private synchronized void renderFinalPass() {
        try {
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
            this.finalPass.apply(mainTarget, this.gbufferTargets);
        } finally {
            this.gbufferTargets.endFrame();
        }
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
        this.gbufferTargets.release();
        this.activeBackend = backend;
        this.activePack = pack;
        this.generation++;
        this.terrainInitialized = false;
        this.terrainActivePipelines = 0;
        this.terrainMaxColorAttachments = 1;
        this.terrainFailureReason = null;
        this.terrainPipelines = Map.of();
        this.terrainProgramNames = Map.of();
        this.terrainColorTargets = Map.of();
        this.terrainExtraTargets = Set.of();
    }

    private void tryInitializeTerrainPipelines() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null || this.activePack == null) {
            return;
        }

        String packName = Objects.requireNonNull(this.activePack);
        Optional<ShaderPackProgramSet> programSet = this.manager().inspectPrograms(packName);
        if (programSet.isEmpty()) {
            this.terrainInitialized = true;
            this.terrainFailureReason = "program discovery failed";
            return;
        }

        ShaderPackProgramResolver resolver = new ShaderPackProgramResolver(programSet.get());
        EnumMap<ChunkSectionLayer, RenderPipeline> pipelines = new EnumMap<>(ChunkSectionLayer.class);
        EnumMap<ChunkSectionLayer, String> programNames = new EnumMap<>(ChunkSectionLayer.class);
        EnumMap<ChunkSectionLayer, List<Integer>> colorTargets = new EnumMap<>(ChunkSectionLayer.class);
        this.tryCreateTerrainPipeline(
            device,
            resolver,
            ChunkSectionLayer.SOLID,
            ShaderPackProgramResolver.TerrainProgram.SOLID,
            ShaderPackTerrainTransformer.AlphaMode.SOLID,
            pipelines,
            programNames,
            colorTargets
        );
        this.tryCreateTerrainPipeline(
            device,
            resolver,
            ChunkSectionLayer.CUTOUT,
            ShaderPackProgramResolver.TerrainProgram.CUTOUT,
            ShaderPackTerrainTransformer.AlphaMode.CUTOUT,
            pipelines,
            programNames,
            colorTargets
        );
        this.tryCreateTerrainPipeline(
            device,
            resolver,
            ChunkSectionLayer.TRANSLUCENT,
            ShaderPackProgramResolver.TerrainProgram.TRANSLUCENT,
            ShaderPackTerrainTransformer.AlphaMode.TRANSLUCENT,
            pipelines,
            programNames,
            colorTargets
        );

        Set<Integer> extraTargets = new HashSet<>();
        int maxAttachments = 1;
        for (List<Integer> targets : colorTargets.values()) {
            maxAttachments = Math.max(maxAttachments, targets.size());
            for (int target : targets) {
                if (target != 0) {
                    extraTargets.add(target);
                }
            }
        }

        this.terrainPipelines = Map.copyOf(pipelines);
        this.terrainProgramNames = Map.copyOf(programNames);
        this.terrainColorTargets = Map.copyOf(colorTargets);
        this.terrainExtraTargets = Set.copyOf(extraTargets);
        this.terrainMaxColorAttachments = maxAttachments;
        this.terrainActivePipelines = pipelines.size();
        this.terrainInitialized = true;
        if (pipelines.isEmpty()) {
            LOGGER.warn(
                "No shader-pack terrain program could be activated on {} for {}{}. Vanilla terrain remains active.",
                this.activeBackend.displayName(),
                packName,
                this.terrainFailureReason == null ? "" : ": " + this.terrainFailureReason
            );
        } else {
            LOGGER.info(
                "Activated {}/3 shader-pack terrain pipelines on {} for {}: {} draw targets {}",
                pipelines.size(),
                this.activeBackend.displayName(),
                packName,
                this.terrainProgramNames,
                this.terrainColorTargets
            );
        }
    }

    private void tryCreateTerrainPipeline(
        final GpuDevice device,
        final ShaderPackProgramResolver resolver,
        final ChunkSectionLayer layer,
        final ShaderPackProgramResolver.TerrainProgram requested,
        final ShaderPackTerrainTransformer.AlphaMode alphaMode,
        final EnumMap<ChunkSectionLayer, RenderPipeline> pipelines,
        final EnumMap<ChunkSectionLayer, String> programNames,
        final EnumMap<ChunkSectionLayer, List<Integer>> colorTargets
    ) {
        Optional<ShaderPackProgramResolver.ResolvedProgram> resolved = resolver.resolve(requested);
        if (resolved.isEmpty()) {
            this.recordTerrainFailure(requested.fileBase() + " and its fallbacks are missing");
            return;
        }

        ShaderPackProgramResolver.ResolvedProgram program = resolved.get();
        if (hasUnsupportedStages(program.program())) {
            this.recordTerrainFailure(program.resolvedName() + " uses geometry, tessellation, or compute stages");
            return;
        }

        String packName = Objects.requireNonNull(this.activePack);
        Optional<String> vertexSource = this.manager().preprocessShader(packName, program.resolvedName() + ".vsh");
        Optional<String> fragmentSource = this.manager().preprocessShader(packName, program.resolvedName() + ".fsh");
        if (vertexSource.isEmpty() || fragmentSource.isEmpty()) {
            this.recordTerrainFailure("failed to preprocess " + program.resolvedName());
            return;
        }

        ShaderPackTerrainTransformer.Result transformed;
        try {
            transformed = ShaderPackTerrainTransformer.transform(vertexSource.get(), fragmentSource.get(), alphaMode);
        } catch (IllegalArgumentException exception) {
            this.recordTerrainFailure(program.resolvedName() + ": " + safeMessage(exception));
            LOGGER.debug("Shader-pack terrain program {} is outside the current compatibility subset", program.resolvedName(), exception);
            return;
        }

        if (transformed.colorTargets().size() > device.getDeviceInfo().limits().maxColorAttachments()) {
            this.recordTerrainFailure(
                program.resolvedName() + " requests " + transformed.colorTargets().size() + " color attachments; backend limit is "
                    + device.getDeviceInfo().limits().maxColorAttachments()
            );
            return;
        }

        Identifier shaderId = Identifier.fromNamespaceAndPath(
            "sigma",
            "shaderpack/terrain_" + layer.label() + "_" + Long.toUnsignedString(this.generation, 16)
        );
        ShaderSource source = (id, type) -> {
            if (!id.equals(shaderId)) {
                return null;
            }
            return type == ShaderType.VERTEX ? transformed.vertexSource() : transformed.fragmentSource();
        };
        RenderPipeline pipeline = this.createTerrainPipeline(layer, shaderId, transformed.colorTargets());
        CompiledRenderPipeline compiled = device.precompilePipeline(pipeline, source);
        if (!compiled.isValid()) {
            this.recordTerrainFailure(this.activeBackend.displayName() + " rejected transformed " + program.resolvedName());
            return;
        }

        pipelines.put(layer, pipeline);
        colorTargets.put(layer, transformed.colorTargets());
        programNames.put(layer, program.direct() ? program.resolvedName() : requested.fileBase() + " -> " + program.resolvedName());
    }

    private static boolean hasUnsupportedStages(final ShaderPackProgramSet.Program program) {
        return program.stages().contains(ShaderPackProgramSet.Stage.GEOMETRY)
            || program.stages().contains(ShaderPackProgramSet.Stage.COMPUTE)
            || program.stages().contains(ShaderPackProgramSet.Stage.TESS_CONTROL)
            || program.stages().contains(ShaderPackProgramSet.Stage.TESS_EVALUATION);
    }

    private void recordTerrainFailure(final String reason) {
        if (this.terrainFailureReason == null) {
            this.terrainFailureReason = truncate(reason);
        }
    }

    private static String safeMessage(final Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static String truncate(final String value) {
        String singleLine = value.replace('\n', ' ').replace('\r', ' ').strip();
        return singleLine.length() <= 180 ? singleLine : singleLine.substring(0, 177) + "...";
    }

    private RenderPipeline createTerrainPipeline(final ChunkSectionLayer layer, final Identifier shader, final List<Integer> colorTargets) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("sigma", "pipeline/shader_terrain_" + layer.label() + "_" + Long.toUnsignedString(this.generation, 16)))
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

        ColorTargetState targetState = layer == ChunkSectionLayer.TRANSLUCENT
            ? new ColorTargetState(BlendFunction.TRANSLUCENT)
            : ColorTargetState.DEFAULT;
        for (int location = 0; location < colorTargets.size(); location++) {
            builder.withColorTargetState(location, targetState);
        }
        return builder.build();
    }
}
