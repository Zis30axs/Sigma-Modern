package net.minecraft.client.renderer.shaderpack;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class ShaderPackDeferredRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ShaderPackDeferredRuntime INSTANCE = new ShaderPackDeferredRuntime();
    private @Nullable ShaderPackManager manager;
    private ShaderPackBackend activeBackend = ShaderPackBackend.UNKNOWN;
    private @Nullable String activePack;
    private long generation;
    private long targetEpoch = Long.MIN_VALUE;
    private @Nullable ShaderPackDeferredPasses passes;
    private boolean worldFrameArmed;
    private boolean preTranslucentProcessed;

    private ShaderPackDeferredRuntime() {
    }

    public static void markWorldFrame() {
        synchronized (INSTANCE) {
            INSTANCE.worldFrameArmed = true;
            INSTANCE.preTranslucentProcessed = false;
        }
    }

    public static void beginTranslucents() {
        INSTANCE.renderBeforeTranslucents();
    }

    public static void capturePreHandDepth() {
        INSTANCE.capturePreHandDepthInternal();
    }

    public static void invalidate() {
        synchronized (INSTANCE) {
            INSTANCE.manager = null;
            INSTANCE.reset(ShaderPackBackend.UNKNOWN, null);
            INSTANCE.worldFrameArmed = false;
            INSTANCE.preTranslucentProcessed = false;
        }
    }

    public static String status() {
        synchronized (INSTANCE) {
            return INSTANCE.passes == null ? "deferred waiting for pre-translucent stage" : INSTANCE.passes.status();
        }
    }

    private synchronized void renderBeforeTranslucents() {
        if (!this.worldFrameArmed || this.preTranslucentProcessed) {
            return;
        }
        this.preTranslucentProcessed = true;

        try {
            ShaderPackRenderTargets targets = this.activeTargets();
            if (targets == null) {
                return;
            }

            ShaderPackManager manager = this.manager();
            ShaderPackBackend backend = ShaderPackBackend.current();
            String pack = manager.selectedPackPath().isPresent() ? manager.selectedPack().orElse(null) : null;
            this.updateSelection(backend, pack);
            if (pack == null || !backend.supportsCustomShaderPipelines()) {
                return;
            }

            RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            CommandEncoder depthEncoder = RenderSystem.getDevice().createCommandEncoder();
            targets.captureDepthSnapshot(1, depthEncoder, mainTarget);

            if (this.passes == null) {
                this.passes = new ShaderPackDeferredPasses(manager, pack, backend, this.generation);
            }

            this.passes.apply(mainTarget, targets);
            CommandEncoder normalizeEncoder = RenderSystem.getDevice().createCommandEncoder();
            targets.normalizeFlipsToMain(normalizeEncoder, mainTarget);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "Shader-pack deferred stage failed on {}; continuing with the normal translucent renderer: {}",
                this.activeBackend.displayName(),
                safeMessage(exception),
                exception
            );
        }
    }

    private synchronized void capturePreHandDepthInternal() {
        if (!this.worldFrameArmed) {
            return;
        }

        try {
            ShaderPackRenderTargets targets = this.activeTargets();
            if (targets == null) {
                return;
            }

            ShaderPackManager manager = this.manager();
            ShaderPackBackend backend = ShaderPackBackend.current();
            String pack = manager.selectedPackPath().isPresent() ? manager.selectedPack().orElse(null) : null;
            this.updateSelection(backend, pack);
            if (pack == null || !backend.supportsCustomShaderPipelines()) {
                return;
            }

            RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            targets.captureDepthSnapshot(2, RenderSystem.getDevice().createCommandEncoder(), mainTarget);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "Could not capture shader-pack pre-hand depth on {}: {}",
                this.activeBackend.displayName(),
                safeMessage(exception),
                exception
            );
        }
    }

    private @Nullable ShaderPackRenderTargets activeTargets() {
        ShaderPackRenderTargets targets = ShaderPackRenderTargets.active();
        if (targets == null) {
            return null;
        }
        if (targets.epoch() != this.targetEpoch) {
            this.targetEpoch = targets.epoch();
            this.manager = null;
            this.reset(ShaderPackBackend.UNKNOWN, null);
        }
        return targets;
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
        this.activeBackend = backend;
        this.activePack = pack;
        this.generation++;
        this.passes = null;
    }

    private static String safeMessage(final Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
