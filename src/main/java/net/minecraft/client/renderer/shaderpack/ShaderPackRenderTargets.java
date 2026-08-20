package net.minecraft.client.renderer.shaderpack;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

final class ShaderPackRenderTargets {
    static final int COLOR_TARGET_COUNT = 8;
    private static final int DEPTH_TARGET_COUNT = 3;
    private static final Vector4f CLEAR_COLOR = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
    private static @Nullable ShaderPackRenderTargets active;
    private final TextureTarget[] mainTargets = new TextureTarget[COLOR_TARGET_COUNT];
    private final TextureTarget[] altTargets = new TextureTarget[COLOR_TARGET_COUNT];
    private final boolean[] flipped = new boolean[COLOR_TARGET_COUNT];
    private final GpuTexture[] depthSnapshots = new GpuTexture[DEPTH_TARGET_COUNT];
    private final GpuTextureView[] depthSnapshotViews = new GpuTextureView[DEPTH_TARGET_COUNT];
    private final Set<Integer> preparedThisFrame = new HashSet<>();
    private final Set<Integer> preparedDepthThisFrame = new HashSet<>();
    private int width = -1;
    private int height = -1;
    private long epoch;

    ShaderPackRenderTargets() {
        active = this;
    }

    static @Nullable ShaderPackRenderTargets active() {
        return active;
    }

    long epoch() {
        return this.epoch;
    }

    void prepare(final CommandEncoder encoder, final int width, final int height, final Collection<Integer> requiredTargets) {
        this.ensureSize(width, height);
        for (int target : requiredTargets) {
            this.validateTarget(target);
            if (!this.preparedThisFrame.add(target)) {
                continue;
            }

            if (target != 0) {
                TextureTarget mainTarget = this.ensureOwnedTarget(this.mainTargets, target, "main");
                GpuTexture mainTexture = Objects.requireNonNull(mainTarget.getColorTexture(), "colortex" + target + " main texture is unavailable");
                encoder.clearColorTexture(mainTexture, CLEAR_COLOR);
            }

            TextureTarget altTarget = this.ensureOwnedTarget(this.altTargets, target, "alt");
            GpuTexture altTexture = Objects.requireNonNull(altTarget.getColorTexture(), "colortex" + target + " alt texture is unavailable");
            encoder.clearColorTexture(altTexture, CLEAR_COLOR);
        }
    }

    void captureDepthSnapshot(final int target, final CommandEncoder encoder, final RenderTarget primaryTarget) {
        this.validateDepthTarget(target);
        this.ensureSize(primaryTarget.width, primaryTarget.height);
        if (!this.preparedDepthThisFrame.add(target)) {
            return;
        }

        GpuTexture source = Objects.requireNonNull(primaryTarget.getDepthTexture(), "Primary depth texture is unavailable");
        GpuTexture snapshot = this.ensureDepthSnapshot(target, source.getFormat());
        encoder.copyTextureToTexture(source, snapshot, 0, 0, 0, 0, 0, primaryTarget.width, primaryTarget.height);
    }

    GpuTextureView attachmentView(final int target, final RenderTarget primaryTarget) {
        this.validateTarget(target);
        if (target == 0) {
            return Objects.requireNonNull(primaryTarget.getColorTextureView(), "Primary terrain color texture view is unavailable");
        }
        return Objects.requireNonNull(
            this.ensureOwnedTarget(this.mainTargets, target, "main").getColorTextureView(),
            "colortex" + target + " main texture view is unavailable"
        );
    }

    @Nullable GpuTextureView samplerView(final int target) {
        if (target <= 0 || target >= COLOR_TARGET_COUNT || !this.preparedThisFrame.contains(target)) {
            return null;
        }
        TextureTarget renderTarget = this.flipped[target] ? this.altTargets[target] : this.mainTargets[target];
        return renderTarget == null ? null : renderTarget.getColorTextureView();
    }

    @Nullable GpuTextureView currentView(final int target, final RenderTarget primaryTarget) {
        this.validateTarget(target);
        if (target != 0 && !this.preparedThisFrame.contains(target)) {
            return null;
        }
        if (this.flipped[target]) {
            return Objects.requireNonNull(
                this.ensureOwnedTarget(this.altTargets, target, "alt").getColorTextureView(),
                "colortex" + target + " alt texture view is unavailable"
            );
        }
        return target == 0
            ? primaryTarget.getColorTextureView()
            : Objects.requireNonNull(
                this.ensureOwnedTarget(this.mainTargets, target, "main").getColorTextureView(),
                "colortex" + target + " main texture view is unavailable"
            );
    }

    GpuTextureView nextView(final int target, final RenderTarget primaryTarget) {
        this.validateTarget(target);
        if (this.flipped[target]) {
            return target == 0
                ? Objects.requireNonNull(primaryTarget.getColorTextureView(), "Primary color texture view is unavailable")
                : Objects.requireNonNull(
                    this.ensureOwnedTarget(this.mainTargets, target, "main").getColorTextureView(),
                    "colortex" + target + " main texture view is unavailable"
                );
        }
        return Objects.requireNonNull(
            this.ensureOwnedTarget(this.altTargets, target, "alt").getColorTextureView(),
            "colortex" + target + " alt texture view is unavailable"
        );
    }

    void flip(final int target) {
        this.validateTarget(target);
        this.flipped[target] = !this.flipped[target];
    }

    boolean isFlipped(final int target) {
        this.validateTarget(target);
        return this.flipped[target];
    }

    void resolveColor0ToPrimary(final CommandEncoder encoder, final RenderTarget primaryTarget) {
        if (!this.flipped[0]) {
            return;
        }
        GpuTextureView currentView = Objects.requireNonNull(this.currentView(0, primaryTarget), "Current colortex0 view is unavailable");
        GpuTexture destination = Objects.requireNonNull(primaryTarget.getColorTexture(), "Primary color texture is unavailable");
        encoder.copyTextureToTexture(currentView.texture(), destination, 0, 0, 0, 0, 0, primaryTarget.width, primaryTarget.height);
    }

    void normalizeFlipsToMain(final CommandEncoder encoder, final RenderTarget primaryTarget) {
        for (int target = 0; target < COLOR_TARGET_COUNT; target++) {
            if (!this.flipped[target]) {
                continue;
            }

            GpuTextureView current = Objects.requireNonNull(this.currentView(target, primaryTarget), "Current colortex" + target + " view is unavailable");
            GpuTexture destination = target == 0
                ? Objects.requireNonNull(primaryTarget.getColorTexture(), "Primary color texture is unavailable")
                : Objects.requireNonNull(
                    this.ensureOwnedTarget(this.mainTargets, target, "main").getColorTexture(),
                    "colortex" + target + " main texture is unavailable"
                );
            if (current.texture() != destination) {
                encoder.copyTextureToTexture(current.texture(), destination, 0, 0, 0, 0, 0, primaryTarget.width, primaryTarget.height);
            }
            this.flipped[target] = false;
        }
    }

    @Nullable GpuTextureView depthSamplerView(final int target, final RenderTarget primaryTarget) {
        if (target == 0) {
            return primaryTarget.getDepthTextureView();
        }
        if (target <= 0 || target >= DEPTH_TARGET_COUNT || !this.preparedDepthThisFrame.contains(target)) {
            return null;
        }
        return this.depthSnapshotViews[target];
    }

    void endFrame() {
        this.preparedThisFrame.clear();
        this.preparedDepthThisFrame.clear();
        Arrays.fill(this.flipped, false);
    }

    void release() {
        this.preparedThisFrame.clear();
        this.preparedDepthThisFrame.clear();
        Arrays.fill(this.flipped, false);
        this.epoch++;
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        for (int index = 0; index < COLOR_TARGET_COUNT; index++) {
            this.destroy(this.mainTargets, index);
            this.destroy(this.altTargets, index);
        }
        this.destroyDepthSnapshots();
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
        this.preparedDepthThisFrame.clear();
        Arrays.fill(this.flipped, false);
        for (TextureTarget target : this.mainTargets) {
            if (target != null) {
                target.resize(width, height);
            }
        }
        for (TextureTarget target : this.altTargets) {
            if (target != null) {
                target.resize(width, height);
            }
        }
        this.destroyDepthSnapshots();
    }

    private TextureTarget ensureOwnedTarget(final TextureTarget[] targets, final int target, final String side) {
        TextureTarget renderTarget = targets[target];
        if (renderTarget == null) {
            if (this.width <= 0 || this.height <= 0) {
                throw new IllegalStateException("Shader render-target size is not initialized");
            }
            renderTarget = new TextureTarget(
                "Sigma Shader colortex" + target + " " + side,
                this.width,
                this.height,
                false,
                GpuFormat.RGBA8_UNORM
            );
            targets[target] = renderTarget;
        }
        return renderTarget;
    }

    private GpuTexture ensureDepthSnapshot(final int target, final GpuFormat format) {
        GpuTexture texture = this.depthSnapshots[target];
        if (texture != null && texture.getFormat() != format) {
            this.destroyDepthSnapshot(target);
            texture = null;
        }
        if (texture == null) {
            if (this.width <= 0 || this.height <= 0) {
                throw new IllegalStateException("Shader render-target size is not initialized");
            }
            GpuDevice device = RenderSystem.getDevice();
            texture = device.createTexture(
                () -> "Sigma Shader depthtex" + target,
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                format,
                this.width,
                this.height,
                1,
                1
            );
            this.depthSnapshots[target] = texture;
            this.depthSnapshotViews[target] = device.createTextureView(texture);
        }
        return texture;
    }

    private void destroy(final TextureTarget[] targets, final int index) {
        TextureTarget target = targets[index];
        if (target != null) {
            target.destroyBuffers();
            targets[index] = null;
        }
    }

    private void destroyDepthSnapshots() {
        for (int index = 1; index < DEPTH_TARGET_COUNT; index++) {
            this.destroyDepthSnapshot(index);
        }
    }

    private void destroyDepthSnapshot(final int index) {
        GpuTexture texture = this.depthSnapshots[index];
        if (texture != null) {
            texture.close();
            this.depthSnapshots[index] = null;
        }
        GpuTextureView view = this.depthSnapshotViews[index];
        if (view != null) {
            view.close();
            this.depthSnapshotViews[index] = null;
        }
    }

    private void validateTarget(final int target) {
        if (target < 0 || target >= COLOR_TARGET_COUNT) {
            throw new IllegalArgumentException("Color target out of range: " + target);
        }
    }

    private void validateDepthTarget(final int target) {
        if (target <= 0 || target >= DEPTH_TARGET_COUNT) {
            throw new IllegalArgumentException("Depth snapshot target out of range: " + target);
        }
    }
}
