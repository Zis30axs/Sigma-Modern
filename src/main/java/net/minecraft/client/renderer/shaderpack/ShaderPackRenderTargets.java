package net.minecraft.client.renderer.shaderpack;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

final class ShaderPackRenderTargets {
    static final int COLOR_TARGET_COUNT = 8;
    private static final Vector4f CLEAR_COLOR = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
    private final TextureTarget[] targets = new TextureTarget[COLOR_TARGET_COUNT];
    private final Set<Integer> preparedThisFrame = new HashSet<>();
    private int width = -1;
    private int height = -1;

    void prepare(final CommandEncoder encoder, final int width, final int height, final Collection<Integer> requiredTargets) {
        this.ensureSize(width, height);
        for (int target : requiredTargets) {
            if (target <= 0 || target >= COLOR_TARGET_COUNT || !this.preparedThisFrame.add(target)) {
                continue;
            }

            TextureTarget renderTarget = this.ensureTarget(target);
            GpuTexture texture = Objects.requireNonNull(renderTarget.getColorTexture(), "colortex" + target + " color texture is unavailable");
            encoder.clearColorTexture(texture, CLEAR_COLOR);
        }
    }

    GpuTextureView attachmentView(final int target, final RenderTarget primaryTarget) {
        if (target == 0) {
            return Objects.requireNonNull(primaryTarget.getColorTextureView(), "Primary terrain color texture view is unavailable");
        }
        if (target < 0 || target >= COLOR_TARGET_COUNT) {
            throw new IllegalArgumentException("Color target out of range: " + target);
        }
        return Objects.requireNonNull(this.ensureTarget(target).getColorTextureView(), "colortex" + target + " texture view is unavailable");
    }

    @Nullable GpuTextureView samplerView(final int target) {
        if (target <= 0 || target >= COLOR_TARGET_COUNT || !this.preparedThisFrame.contains(target)) {
            return null;
        }
        TextureTarget renderTarget = this.targets[target];
        return renderTarget == null ? null : renderTarget.getColorTextureView();
    }

    void endFrame() {
        this.preparedThisFrame.clear();
    }

    void release() {
        this.preparedThisFrame.clear();
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        for (int index = 1; index < this.targets.length; index++) {
            TextureTarget target = this.targets[index];
            if (target != null) {
                target.destroyBuffers();
                this.targets[index] = null;
            }
        }
        this.width = -1;
        this.height = -1;
    }

    private void ensureSize(final int width, final int height) {
        if (width == this.width && height == this.height) {
            return;
        }
        this.width = width;
        this.height = height;
        this.preparedThisFrame.clear();
        for (int index = 1; index < this.targets.length; index++) {
            TextureTarget target = this.targets[index];
            if (target != null) {
                target.resize(width, height);
            }
        }
    }

    private TextureTarget ensureTarget(final int target) {
        TextureTarget renderTarget = this.targets[target];
        if (renderTarget == null) {
            if (this.width <= 0 || this.height <= 0) {
                throw new IllegalStateException("Shader render-target size is not initialized");
            }
            renderTarget = new TextureTarget("Sigma Shader colortex" + target, this.width, this.height, false, GpuFormat.RGBA8_UNORM);
            this.targets[target] = renderTarget;
        }
        return renderTarget;
    }
}
