package net.minecraft.client.renderer.shaderpack;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

public final class ShaderPackCompositeRuntime {
    private static final ShaderPackCompositeRuntime INSTANCE = new ShaderPackCompositeRuntime();
    private @Nullable ShaderPackManager manager;
    private ShaderPackBackend activeBackend = ShaderPackBackend.UNKNOWN;
    private @Nullable String activePack;
    private long generation;
    private long targetEpoch = Long.MIN_VALUE;
    private @Nullable ShaderPackCompositePasses passes;

    private ShaderPackCompositeRuntime() {
    }

    public static void apply() {
        INSTANCE.render();
    }

    public static void invalidate() {
        synchronized (INSTANCE) {
            INSTANCE.manager = null;
            INSTANCE.reset(ShaderPackBackend.UNKNOWN, null);
        }
    }

    public static String status() {
        synchronized (INSTANCE) {
            return INSTANCE.passes == null ? "composite waiting for world end" : INSTANCE.passes.status();
        }
    }

    private synchronized void render() {
        // Keep the terrain/final runtime selection synchronized before touching its shared render targets.
        ShaderPackRuntime.status();
        ShaderPackRenderTargets targets = ShaderPackRenderTargets.active();
        if (targets == null) {
            return;
        }
        if (targets.epoch() != this.targetEpoch) {
            this.targetEpoch = targets.epoch();
            this.manager = null;
            this.reset(ShaderPackBackend.UNKNOWN, null);
        }

        ShaderPackManager manager = this.manager();
        ShaderPackBackend backend = ShaderPackBackend.current();
        String pack = manager.selectedPackPath().isPresent() ? manager.selectedPack().orElse(null) : null;
        this.updateSelection(backend, pack);
        if (pack == null || !backend.supportsCustomShaderPipelines()) {
            return;
        }
        if (this.passes == null) {
            this.passes = new ShaderPackCompositePasses(manager, pack, backend, this.generation);
        }

        RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        this.passes.apply(mainTarget, targets);
        if (targets.isFlipped(0)) {
            targets.resolveColor0ToPrimary(RenderSystem.getDevice().createCommandEncoder(), mainTarget);
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
        this.activeBackend = backend;
        this.activePack = pack;
        this.generation++;
        this.passes = null;
    }
}
