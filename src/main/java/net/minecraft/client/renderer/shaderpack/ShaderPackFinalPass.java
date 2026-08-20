package net.minecraft.client.renderer.shaderpack;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

final class ShaderPackFinalPass implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final ShaderPackManager manager;
    private final String packName;
    private final ShaderPackBackend backend;
    private final long generation;
    private boolean initialized;
    private boolean ready;
    private boolean missing;
    private @Nullable String failureReason;
    private @Nullable RenderPipeline pipeline;
    private @Nullable TextureTarget copyTarget;

    ShaderPackFinalPass(final ShaderPackManager manager, final String packName, final ShaderPackBackend backend, final long generation) {
        this.manager = manager;
        this.packName = packName;
        this.backend = backend;
        this.generation = generation;
    }

    void apply(final RenderTarget mainTarget) {
        if (!this.initialized) {
            this.initialize();
        }
        if (!this.ready || this.pipeline == null) {
            return;
        }

        try {
            this.ensureCopyTarget(mainTarget.width, mainTarget.height);
            TextureTarget sourceTarget = Objects.requireNonNull(this.copyTarget);
            GpuTexture mainColor = Objects.requireNonNull(mainTarget.getColorTexture(), "Main color texture is unavailable");
            GpuTextureView mainColorView = Objects.requireNonNull(mainTarget.getColorTextureView(), "Main color texture view is unavailable");
            GpuTexture sourceColor = Objects.requireNonNull(sourceTarget.getColorTexture(), "Final-pass copy texture is unavailable");
            GpuTextureView sourceColorView = Objects.requireNonNull(sourceTarget.getColorTextureView(), "Final-pass copy texture view is unavailable");
            GpuDevice device = RenderSystem.getDevice();
            CommandEncoder encoder = device.createCommandEncoder();
            encoder.copyTextureToTexture(mainColor, sourceColor, 0, 0, 0, 0, 0, mainTarget.width, mainTarget.height);

            try (RenderPass pass = encoder.createRenderPass(() -> "Sigma shader pack final", mainColorView, Optional.empty())) {
                pass.setPipeline(this.pipeline);
                pass.bindTexture("colortex0", sourceColorView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.draw(3, 1, 0, 0);
            }
        } catch (RuntimeException exception) {
            this.fail("runtime final-pass failure: " + safeMessage(exception), exception);
        }
    }

    String status() {
        if (this.ready) {
            return "final.fsh active on " + this.backend.displayName() + " (colortex0 subset)";
        }
        if (this.missing) {
            return "pack has no final.fsh";
        }
        if (this.failureReason != null) {
            return "final.fsh fallback: " + this.failureReason;
        }
        return "final.fsh waiting for the next world frame";
    }

    private void initialize() {
        this.initialized = true;
        Optional<ShaderPackSource> opened = this.manager.openPack(this.packName);
        if (opened.isEmpty()) {
            this.fail("selected pack could not be opened", null);
            return;
        }

        try (ShaderPackSource source = opened.get()) {
            if (!source.contains("final.fsh")) {
                this.missing = true;
                return;
            }
        } catch (IOException exception) {
            this.fail("could not inspect final.fsh: " + safeMessage(exception), exception);
            return;
        }

        Optional<String> expanded = this.manager.preprocessShader(this.packName, "final.fsh");
        if (expanded.isEmpty()) {
            this.fail("final.fsh preprocessing failed", null);
            return;
        }

        ShaderPackFinalTransformer.Result transformed;
        try {
            transformed = ShaderPackFinalTransformer.transform(expanded.get());
        } catch (IllegalArgumentException exception) {
            this.fail(safeMessage(exception), exception);
            return;
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            this.initialized = false;
            return;
        }

        Identifier shaderId = Identifier.fromNamespaceAndPath("sigma", "shaderpack/final_" + Long.toUnsignedString(this.generation, 16));
        ShaderSource source = (id, type) -> {
            if (!id.equals(shaderId)) {
                return null;
            }
            return type == ShaderType.VERTEX ? transformed.vertexSource() : transformed.fragmentSource();
        };
        BindGroupLayout finalLayout = BindGroupLayout.builder().withSampler("colortex0").build();
        RenderPipeline finalPipeline = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("sigma", "pipeline/shader_final_" + Long.toUnsignedString(this.generation, 16)))
            .withBindGroupLayout(finalLayout)
            .withVertexShader(shaderId)
            .withFragmentShader(shaderId)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .build();
        CompiledRenderPipeline compiled = device.precompilePipeline(finalPipeline, source);
        if (!compiled.isValid()) {
            this.fail("backend rejected the transformed final.fsh", null);
            return;
        }

        this.pipeline = finalPipeline;
        this.ready = true;
        LOGGER.info("Enabled shader-pack final.fsh subset on {} for {}", this.backend.displayName(), this.packName);
    }

    private void ensureCopyTarget(final int width, final int height) {
        if (this.copyTarget == null) {
            this.copyTarget = new TextureTarget("Sigma Shader Final Copy", width, height, false, GpuFormat.RGBA8_UNORM);
        } else if (this.copyTarget.width != width || this.copyTarget.height != height) {
            this.copyTarget.resize(width, height);
        }
    }

    private void fail(final String reason, final @Nullable Throwable throwable) {
        this.ready = false;
        this.failureReason = truncate(reason);
        this.closeCopyTarget();
        if (throwable == null) {
            LOGGER.warn("Shader-pack final pass disabled on {} for {}: {}", this.backend.displayName(), this.packName, this.failureReason);
        } else {
            LOGGER.warn("Shader-pack final pass disabled on {} for {}: {}", this.backend.displayName(), this.packName, this.failureReason, throwable);
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

    private void closeCopyTarget() {
        if (this.copyTarget != null) {
            this.copyTarget.destroyBuffers();
            this.copyTarget = null;
        }
    }

    @Override
    public void close() {
        this.ready = false;
        this.closeCopyTarget();
    }
}
