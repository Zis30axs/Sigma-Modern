package net.minecraft.client.renderer.shaderpack;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

final class ShaderPackCompositePasses {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern PASS_NAME = Pattern.compile("^composite([0-9]*)$", Pattern.CASE_INSENSITIVE);
    private final ShaderPackManager manager;
    private final String packName;
    private final ShaderPackBackend backend;
    private final long generation;
    private boolean initialized;
    private boolean ready;
    private boolean missing;
    private @Nullable String failureReason;
    private List<Pass> passes = List.of();
    private Set<Integer> requiredColorTargets = Set.of();
    private Set<Integer> requiredDepthSamplers = Set.of();

    ShaderPackCompositePasses(final ShaderPackManager manager, final String packName, final ShaderPackBackend backend, final long generation) {
        this.manager = manager;
        this.packName = packName;
        this.backend = backend;
        this.generation = generation;
    }

    Outcome apply(final RenderTarget mainTarget, final ShaderPackRenderTargets renderTargets) {
        this.initializeIfNeeded();
        if (!this.ready || this.passes.isEmpty()) {
            return Outcome.NONE;
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try {
            renderTargets.prepare(encoder, mainTarget.width, mainTarget.height, this.requiredColorTargets);
            for (Pass screenPass : this.passes) {
                RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Sigma shader " + screenPass.name());
                for (int colorTarget : screenPass.transformed().colorTargets()) {
                    descriptor.withColorAttachment(renderTargets.nextView(colorTarget, mainTarget));
                }
                descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, mainTarget.width, mainTarget.height));

                try (RenderPass pass = encoder.createRenderPass(descriptor)) {
                    pass.setPipeline(screenPass.pipeline());
                    for (int colorSampler : screenPass.transformed().colorSamplers()) {
                        GpuTextureView view = renderTargets.currentView(colorSampler, mainTarget);
                        if (view == null) {
                            throw new IllegalStateException("colortex" + colorSampler + " is unavailable for " + screenPass.name());
                        }
                        pass.bindTexture(
                            "colortex" + colorSampler,
                            view,
                            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
                        );
                    }
                    for (int depthSampler : screenPass.transformed().depthSamplers()) {
                        GpuTextureView view = renderTargets.depthSamplerView(depthSampler, mainTarget);
                        if (view == null) {
                            throw new IllegalStateException("depthtex" + depthSampler + " is unavailable for " + screenPass.name());
                        }
                        pass.bindTexture(
                            "depthtex" + depthSampler,
                            view,
                            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
                        );
                    }
                    pass.draw(3, 1, 0, 0);
                }

                for (int colorTarget : screenPass.transformed().colorTargets()) {
                    renderTargets.flip(colorTarget);
                }
            }
            return Outcome.COMPLETE;
        } catch (RuntimeException exception) {
            this.fail("runtime composite failure: " + safeMessage(exception), exception);
            try {
                renderTargets.resolveColor0ToPrimary(encoder, mainTarget);
            } catch (RuntimeException copyFailure) {
                LOGGER.debug("Could not preserve the current colortex0 output after a composite failure", copyFailure);
            }
            return Outcome.FAILED;
        }
    }

    String status() {
        if (!this.initialized) {
            return "composite waiting for world end";
        }
        if (this.ready) {
            return this.passes.size() + " composite pass" + (this.passes.size() == 1 ? "" : "es") + " active";
        }
        if (this.missing) {
            return "no composite passes";
        }
        if (this.failureReason != null) {
            return "composite fallback: " + this.failureReason;
        }
        return "composite waiting";
    }

    private void initializeIfNeeded() {
        if (!this.initialized) {
            this.initialize();
        }
    }

    private void initialize() {
        this.initialized = true;
        Optional<ShaderPackProgramSet> programSet = this.manager.inspectPrograms(this.packName);
        if (programSet.isEmpty()) {
            this.fail("program discovery failed", null);
            return;
        }

        List<ProgramCandidate> candidates = new ArrayList<>();
        for (ShaderPackProgramSet.Program program : programSet.get().programs()) {
            ProgramOrder order = parseOrder(program.name());
            if (order != null) {
                candidates.add(new ProgramCandidate(program, order));
            }
        }
        candidates.sort(Comparator.comparing(ProgramCandidate::order));
        if (candidates.isEmpty()) {
            this.missing = true;
            return;
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            this.initialized = false;
            return;
        }

        List<Pass> builtPasses = new ArrayList<>();
        Set<Integer> colorTargets = new LinkedHashSet<>();
        Set<Integer> depthSamplers = new LinkedHashSet<>();
        int ordinal = 0;
        for (ProgramCandidate candidate : candidates) {
            ShaderPackProgramSet.Program program = candidate.program();
            if (hasUnsupportedStages(program)) {
                this.fail(program.name() + " uses geometry, tessellation, or compute stages", null);
                return;
            }

            Optional<String> fragmentSource = this.manager.preprocessShader(this.packName, program.name() + ".fsh");
            if (fragmentSource.isEmpty()) {
                this.fail("failed to preprocess " + program.name() + ".fsh", null);
                return;
            }

            ShaderPackCompositeTransformer.Result transformed;
            try {
                transformed = ShaderPackCompositeTransformer.transform(fragmentSource.get());
            } catch (IllegalArgumentException exception) {
                this.fail(program.name() + ": " + safeMessage(exception), exception);
                return;
            }
            if (transformed.depthSamplers().contains(1)) {
                this.fail(program.name() + " requests depthtex1; pre-translucent depth snapshots are not enabled in this milestone", null);
                return;
            }
            if (transformed.colorTargets().size() > device.getDeviceInfo().limits().maxColorAttachments()) {
                this.fail(
                    program.name() + " requests " + transformed.colorTargets().size() + " color attachments; backend limit is "
                        + device.getDeviceInfo().limits().maxColorAttachments(),
                    null
                );
                return;
            }

            String suffix = Long.toUnsignedString(this.generation, 16) + "_" + ordinal++;
            Identifier shaderId = Identifier.fromNamespaceAndPath("sigma", "shaderpack/screen_" + suffix);
            ShaderSource shaderSource = (id, type) -> {
                if (!id.equals(shaderId)) {
                    return null;
                }
                return type == ShaderType.VERTEX ? transformed.vertexSource() : transformed.fragmentSource();
            };
            RenderPipeline pipeline = this.createPipeline(program.name(), suffix, shaderId, transformed);
            CompiledRenderPipeline compiled = device.precompilePipeline(pipeline, shaderSource);
            if (!compiled.isValid()) {
                this.fail(this.backend.displayName() + " rejected transformed " + program.name(), null);
                return;
            }

            builtPasses.add(new Pass(program.name(), transformed, pipeline));
            colorTargets.addAll(transformed.colorTargets());
            colorTargets.addAll(transformed.colorSamplers());
            depthSamplers.addAll(transformed.depthSamplers());
        }

        this.passes = List.copyOf(builtPasses);
        this.requiredColorTargets = Set.copyOf(colorTargets);
        this.requiredDepthSamplers = Set.copyOf(depthSamplers);
        this.ready = true;
        LOGGER.info(
            "Enabled {} shader-pack composite passes on {} for {} with color targets {}",
            this.passes.size(),
            this.backend.displayName(),
            this.packName,
            this.requiredColorTargets
        );
    }

    private RenderPipeline createPipeline(
        final String programName,
        final String suffix,
        final Identifier shaderId,
        final ShaderPackCompositeTransformer.Result transformed
    ) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("sigma", "pipeline/shader_screen_" + suffix))
            .withVertexShader(shaderId)
            .withFragmentShader(shaderId)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false);
        for (int attachment = 0; attachment < transformed.colorTargets().size(); attachment++) {
            builder.withColorTargetState(attachment, ColorTargetState.DEFAULT);
        }
        if (!transformed.colorSamplers().isEmpty() || !transformed.depthSamplers().isEmpty()) {
            BindGroupLayout.Builder layout = BindGroupLayout.builder();
            for (int sampler : transformed.colorSamplers()) {
                layout.withSampler("colortex" + sampler);
            }
            for (int sampler : transformed.depthSamplers()) {
                layout.withSampler("depthtex" + sampler);
            }
            builder.withBindGroupLayout(layout.build());
        }
        return builder.build();
    }

    private void fail(final String reason, final @Nullable Throwable throwable) {
        this.ready = false;
        this.failureReason = truncate(reason);
        if (throwable == null) {
            LOGGER.warn("Shader-pack composite passes disabled on {} for {}: {}", this.backend.displayName(), this.packName, this.failureReason);
        } else {
            LOGGER.warn("Shader-pack composite passes disabled on {} for {}: {}", this.backend.displayName(), this.packName, this.failureReason, throwable);
        }
    }

    private static @Nullable ProgramOrder parseOrder(final String name) {
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            return null;
        }
        String leaf = name;
        Matcher matcher = PASS_NAME.matcher(leaf);
        if (!matcher.matches()) {
            return null;
        }
        int index = matcher.group(1).isEmpty() ? 0 : Integer.parseInt(matcher.group(1));
        return new ProgramOrder(index, leaf.toLowerCase(Locale.ROOT));
    }

    private static boolean hasUnsupportedStages(final ShaderPackProgramSet.Program program) {
        return program.stages().contains(ShaderPackProgramSet.Stage.GEOMETRY)
            || program.stages().contains(ShaderPackProgramSet.Stage.COMPUTE)
            || program.stages().contains(ShaderPackProgramSet.Stage.TESS_CONTROL)
            || program.stages().contains(ShaderPackProgramSet.Stage.TESS_EVALUATION);
    }

    private static String safeMessage(final Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static String truncate(final String value) {
        String singleLine = value.replace('\n', ' ').replace('\r', ' ').strip();
        return singleLine.length() <= 180 ? singleLine : singleLine.substring(0, 177) + "...";
    }

    enum Outcome {
        NONE,
        COMPLETE,
        FAILED
    }

    private record Pass(String name, ShaderPackCompositeTransformer.Result transformed, RenderPipeline pipeline) {
    }

    private record ProgramCandidate(ShaderPackProgramSet.Program program, ProgramOrder order) {
    }

    private record ProgramOrder(int index, String name) implements Comparable<ProgramOrder> {
        @Override
        public int compareTo(final ProgramOrder other) {
            int indexCompare = Integer.compare(this.index, other.index);
            return indexCompare != 0 ? indexCompare : this.name.compareTo(other.name);
        }
    }
}
