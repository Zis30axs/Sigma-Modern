package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.music.Track;
import com.mentalfrostbyte.jello.music.lyrics.Lyrics;
import com.mentalfrostbyte.jello.music.lyrics.LyricsService;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.humbleui.skija.FilterBlurMode;
import io.github.humbleui.skija.MaskFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Shader;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Each sung line floats in the local world as a high-DPI Skia billboard.
 * Textures are rasterized strictly ONCE per lyric line; vocal sweep, cover-color halo,
 * and bass-reactive glow run 100% on the GPU with zero per-frame Skia cost.
 */
public final class WorldLyrics {
    private static final int FADE_IN_MS = 280, FADE_OUT_MS = 440;
    private static final float SCALE = 0.023F;
    private static final double BASE_DISTANCE = 6.0, MAX_DISTANCE = 10.0;
    private static final double MIN_DISTANCE = 2.5, MAX_ANCHOR_DISTANCE = 14.0;
    private static final double OBSTACLE_MARGIN = 0.25, MOVEMENT_LOOK_AHEAD = 0.5;
    private static final long RELOCATION_COOLDOWN_NS = 1_000_000_000L;
    private static final long RELOCATION_DURATION_NS = 250_000_000L;
    private static final long PLACEMENT_RETRY_NS = 250_000_000L;
    private static final long OUT_OF_VIEW_DELAY_NS = 300_000_000L;
    private static final float DENSITY = 2.0F;
    private static final float MAIN_SCALE = 1.15F, EXTRA_SCALE = 0.80F;
    private static final float MAIN_LINE_H = 15.5F, EXTRA_LINE_H = 11.5F;
    private static final float MAX_MAIN_W = 240F, MAX_EXTRA_W = 230F;
    private static final float PAD_X = 28F, PAD_Y = 18F;
    private static final float SWEEP_FEATHER = 14F;

    private static final Identifier GLOW_TEX = Identifier.withDefaultNamespace("sigma/world_lyrics/soft_glow");
    private static final Identifier BASE_A = Identifier.withDefaultNamespace("sigma/world_lyrics/base_a");
    private static final Identifier LIT_A = Identifier.withDefaultNamespace("sigma/world_lyrics/lit_a");
    private static final Identifier BASE_B = Identifier.withDefaultNamespace("sigma/world_lyrics/base_b");
    private static final Identifier LIT_B = Identifier.withDefaultNamespace("sigma/world_lyrics/lit_b");
    private static boolean glowUploaded;

    private Identifier activeBase = BASE_A, activeLit = LIT_A;
    private Identifier retiringBase = BASE_B, retiringLit = LIT_B;
    private boolean activeUploaded, retiringUploaded;

    private @Nullable ClientLevel level;
    private @Nullable Track track;
    private long lastPosition, entered, retired, lastFrameNanos;
    private int index = -1;
    private float time, bass, anchorVisibility;
    private Vec3 lastEye = Vec3.ZERO, anchor = Vec3.ZERO;
    private Vec3 relocationFrom = Vec3.ZERO, relocationTo = Vec3.ZERO;
    private long lastPlacementAttemptNanos, lastRelocatedNanos, relocationStartNanos;
    private long outOfViewSinceNanos, lastOcclusionCheckNanos;
    private boolean hasAnchor, placementBlocked;
    private @Nullable Caption current, previous;
    private Lyrics.@Nullable Line cachedLine;
    private @Nullable String cachedExtra;
    private @Nullable CardMetrics cachedMetrics;

    private record LineBand(float x, float y, float width, float height) {}
    private record CardMetrics(float width, float height, float contentWidth, float contentHeight,
                               float totalMainWidth, List<LineBand> mainBands) {}

    private record Caption(Vec3 anchor, Identifier baseTex, Identifier litTex, CardMetrics metrics,
                           float progress, int accent, int deep, float pulse,
                           float opacity, float rise, float scale, float recede) {
        void submit(PoseStack pose, SubmitNodeCollector output, CameraRenderState camera) {
            Vec3 pos = recede > 0F ? recedeFrom(anchor, camera.pos, recede) : anchor;
            double distance = camera.pos.distanceTo(pos);
            float proximity = (float)Math.clamp((distance - (MIN_DISTANCE - 0.5)) / 0.5, 0.0, 1.0);
            float alphaF = opacity * proximity;
            int alpha = Math.round(255 * alphaF);
            if (alpha < 4 || metrics.width <= 0F || metrics.height <= 0F) return;

            pose.pushPose();
            pose.translate(pos.x - camera.pos.x, pos.y - camera.pos.y + rise, pos.z - camera.pos.z);
            pose.mulPose(camera.orientation);
            float size = SCALE * scale * (float)Math.clamp(distance / BASE_DISTANCE, 0.45, MAX_DISTANCE / BASE_DISTANCE);
            pose.scale(size, -size, size);

            float w = metrics.width, h = metrics.height;
            float hw = w * 0.5F, hh = h * 0.5F;
            int accentRgb = accent & 0xFFFFFF;
            int deepRgb = ModernStyle.mix(0xFF060C16, deep, 0.35F) & 0xFFFFFF;
            int litTint = (alpha << 24) | (ModernStyle.mix(0xFFFFFFFF, accent, 0.16F) & 0xFFFFFF);
            int baseTint = (alpha << 24) | 0xFFFFFF;

            // 1. Feathered ambient scrim + travelling cover-color vocal halo (both on 1 static glow texture).
            if (glowUploaded) {
                float scrimW = metrics.contentWidth * 0.62F + 28F;
                float scrimH = metrics.contentHeight * 0.65F + 18F;
                int scrimColor = (Math.round(alphaF * 112F) << 24) | deepRgb;

                // Locate active vocal sweep point for the travelling halo.
                float targetAdv = Math.max(1F, metrics.totalMainWidth) * progress;
                float haloX = 0F, haloY = -hh + PAD_Y + MAIN_LINE_H * 0.45F;
                float walked = 0F;
                for (int i = 0; i < metrics.mainBands.size(); i++) {
                    LineBand b = metrics.mainBands.get(i);
                    if (targetAdv <= walked + b.width || i == metrics.mainBands.size() - 1) {
                        haloX = -hw + b.x + Math.clamp(targetAdv - walked, 0F, b.width);
                        haloY = -hh + b.y + b.height * 0.45F;
                        break;
                    }
                    walked += b.width;
                }
                float haloRx = 42F + pulse * 16F, haloRy = 20F + pulse * 7F;
                int haloColor = (Math.round(alphaF * (44F + pulse * 36F)) << 24) | accentRgb;

                final float fHaloX = haloX, fHaloY = haloY;
                output.submitCustomGeometry(pose, RenderTypes.entityTranslucentEmissive(GLOW_TEX, false), (entry, buffer) -> {
                    quad(entry, buffer, -scrimW, -scrimH, scrimW, scrimH, 0F, 0F, 1F, 1F, 0.02F, scrimColor, scrimColor);
                    quad(entry, buffer, fHaloX - haloRx, fHaloY - haloRy, fHaloX + haloRx, fHaloY + haloRy,
                            0F, 0F, 1F, 1F, 0.015F, haloColor, haloColor);
                });
            }

            // 2. Base layer: unsung main lyric + translation (1 quad).
            output.submitCustomGeometry(pose, RenderTypes.entityTranslucentEmissive(baseTex, false), (entry, buffer) ->
                    quad(entry, buffer, -hw, -hh, hw, hh, 0F, 0F, 1F, 1F, 0.0F, baseTint, baseTint));

            // 3. GPU-clipped smooth vocal sweep of the lit+bloom lyric layer (1-2 quads per wrapped line, zero CPU cost).
            if (progress > 0.001F && !metrics.mainBands.isEmpty()) {
                int litZero = litTint & 0x00FFFFFF;
                float totalW = Math.max(1F, metrics.totalMainWidth);
                output.submitCustomGeometry(pose, RenderTypes.entityTranslucentEmissive(litTex, false), (entry, buffer) -> {
                    float consumed = 0F;
                    for (LineBand band : metrics.mainBands) {
                        float lp = lineProgress(progress, consumed, band.width, totalW);
                        consumed += band.width;
                        if (lp <= 0.001F) continue;
                        float bandTop = Math.max(0F, band.y - 5F);
                        float bandBot = Math.min(h, band.y + band.height + 5F);
                        float v0 = bandTop / h, v1 = bandBot / h;
                        float y0 = -hh + bandTop, y1 = -hh + bandBot;

                        float sweepPx = band.x + band.width * lp;
                        float solidEnd = Math.clamp(sweepPx, 0F, w);
                        float featherEnd = Math.clamp(sweepPx + SWEEP_FEATHER, solidEnd, w);
                        if (solidEnd > 0F) {
                            quad(entry, buffer, -hw, y0, -hw + solidEnd, y1,
                                    0F, v0, solidEnd / w, v1, -0.004F, litTint, litTint);
                        }
                        if (featherEnd > solidEnd) {
                            quad(entry, buffer, -hw + solidEnd, y0, -hw + featherEnd, y1,
                                    solidEnd / w, v0, featherEnd / w, v1, -0.004F, litTint, litZero);
                        }
                    }
                });
            }
            pose.popPose();
        }

        private static void quad(PoseStack.Pose entry, VertexConsumer buffer,
                                 float x0, float y0, float x1, float y1,
                                 float u0, float v0, float u1, float v1, float z,
                                 int leftColor, int rightColor) {
            buffer.addVertex(entry, x0, y0, z).setColor(leftColor).setUv(u0, v0)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(entry, 0F, 0F, 1F);
            buffer.addVertex(entry, x0, y1, z).setColor(leftColor).setUv(u0, v1)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(entry, 0F, 0F, 1F);
            buffer.addVertex(entry, x1, y1, z).setColor(rightColor).setUv(u1, v1)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(entry, 0F, 0F, 1F);
            buffer.addVertex(entry, x1, y0, z).setColor(rightColor).setUv(u1, v0)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(entry, 0F, 0F, 1F);
        }
    }

    public static final class Frame {
        private final @Nullable Caption old, active;
        private Frame(@Nullable Caption old, @Nullable Caption active) { this.old = old; this.active = active; }
        public void submit(PoseStack pose, SubmitNodeCollector output, CameraRenderState camera) {
            if (old != null) old.submit(pose, output, camera);
            if (active != null) active.submit(pose, output, camera);
        }
    }

    public void clear() {
        level = null;
        track = null;
        current = previous = null;
        index = -1;
        cachedLine = null;
        cachedExtra = null;
        cachedMetrics = null;
        lastFrameNanos = 0L;
        lastEye = Vec3.ZERO;
        bass = 0F;
        resetPlacement();
        releaseActive();
        releaseRetiring();
    }

    private void resetPlacement() {
        anchor = relocationFrom = relocationTo = Vec3.ZERO;
        hasAnchor = placementBlocked = false;
        anchorVisibility = 0F;
        lastPlacementAttemptNanos = lastRelocatedNanos = relocationStartNanos = 0L;
        outOfViewSinceNanos = lastOcclusionCheckNanos = 0L;
    }

    private void releaseActive() {
        if (activeUploaded) {
            activeUploaded = false;
            ModernSkiaRaster.release(activeBase);
            ModernSkiaRaster.release(activeLit);
        }
    }

    private void releaseRetiring() {
        if (retiringUploaded) {
            retiringUploaded = false;
            ModernSkiaRaster.release(retiringBase);
            ModernSkiaRaster.release(retiringLit);
        }
    }

    private void swapSlots() {
        Identifier tmpBase = activeBase, tmpLit = activeLit;
        activeBase = retiringBase;
        activeLit = retiringLit;
        retiringBase = tmpBase;
        retiringLit = tmpLit;
        boolean tmpUp = activeUploaded;
        activeUploaded = retiringUploaded;
        retiringUploaded = tmpUp;
    }

    private static void ensureGlowTexture() {
        if (glowUploaded) return;
        ModernSkiaRaster.upload(GLOW_TEX, ModernSkiaRaster.render(64, 64, canvas -> {
            try (Shader shader = Shader.makeRadialGradient(32F, 32F, 32F,
                    new int[]{0xFFFFFFFF, 0xB8FFFFFF, 0x42FFFFFF, 0x00FFFFFF});
                 Paint paint = new Paint().setAntiAlias(true).setShader(shader)) {
                canvas.drawCircle(32F, 32F, 32F, paint);
            }
        }));
        glowUploaded = true;
    }

    public @Nullable Frame extract(Minecraft mc, CameraRenderState camera, float partialTick) {
        Client client = Client.getInstance();
        if (!client.isStarted() || mc.level == null || mc.player == null || camera.isPanoramicMode
                || mc.getCameraEntity() != mc.player || !client.getMusicLibrary().lyrics().worldLyrics()) {
            if (level != null) clear();
            return null;
        }
        var player = client.getMusicPlayer();
        if (!player.isPlaying() || player.isBuffering()) {
            if (level != null) clear();
            return null;
        }
        long nowNanos = System.nanoTime();
        long elapsedNanos = lastFrameNanos == 0L ? 0L : Math.max(0L, nowNanos - lastFrameNanos);
        float dt = lastFrameNanos == 0L ? 0.016F : Math.min(0.05F, elapsedNanos / 1_000_000_000F);

        long pos = player.positionMs();
        Vec3 eye = mc.player.getEyePosition(partialTick);
        boolean discontinuity = level != mc.level || track != player.current()
                || (lastFrameNanos != 0L && playbackJumped(lastPosition, pos, elapsedNanos));
        // A teleport changes placement, while ordinary travel never discards the current lyric or its textures.
        boolean teleported = !discontinuity && lastFrameNanos != 0L && eye.distanceToSqr(lastEye) > 256.0;
        if (discontinuity) {
            clear();
            level = mc.level;
            track = player.current();
        }
        lastFrameNanos = nowNanos;
        lastEye = eye;
        lastPosition = pos;
        time += dt;

        ModernCoverColors.update(track, client.getMusicEffects().islandColor());
        float[] levels = ModernMusicView.levels(player.spectrum(), 5);
        float targetBass = levels == null ? 0F : Math.clamp((levels[0] + levels[1]) * 0.5F, 0F, 1F);
        bass = ModernStyle.smooth(bass, targetBass, dt, 10F);

        var now = ModernLyricSweep.now(player, pos);
        int next = now == null ? -1 : now.index();
        if (next != index) {
            if (current != null && activeUploaded) {
                previous = current;
                retired = pos;
                swapSlots();
            } else {
                previous = null;
                releaseRetiring();
            }
            current = null;
            index = next;
            entered = pos;
            cachedLine = null;
            cachedMetrics = null;
            resetPlacement();
        }

        int accent = ModernCoverColors.accent();
        int deep = ModernCoverColors.deep();

        if (now != null) {
            updatePlacement(mc, eye, camera, dt, nowNanos, teleported);
            ensureGlowTexture();
            var line = now.line();
            String text = line.text();
            String extra = LyricsService.extra(line, client.getMusicLibrary().lyrics().language());
            float progress = Math.clamp(now.progress(), 0F, 1F);

            // Rasterize strictly ONCE per lyric line change — zero Skia work during playback.
            if (!activeUploaded || cachedMetrics == null || line != cachedLine || !Objects.equals(extra, cachedExtra)) {
                Rasterized card = rasterizeLine(text, extra);
                ModernSkiaRaster.uploadColor(activeBase, card.baseImage);
                ModernSkiaRaster.upload(activeLit, card.litImage);
                activeUploaded = true;
                cachedLine = line;
                cachedExtra = extra;
                cachedMetrics = card.metrics;
            }
            float shown = ease(pos - entered, FADE_IN_MS);
            float bob = (float)Math.sin(time * 1.45) * 0.032F;
            float scale = (0.93F + 0.07F * shown) * (1F + bass * 0.022F);
            current = new Caption(anchor, activeBase, activeLit, cachedMetrics, progress, accent, deep, bass,
                    shown * anchorVisibility, -0.14F * (1F - shown) + bob, scale, 0F);
        }

        Caption ghost = null;
        if (previous != null && retiringUploaded) {
            float out = Math.clamp((pos - retired) / (float)FADE_OUT_MS, 0F, 1F);
            if (out >= 1F) {
                previous = null;
                releaseRetiring();
            } else {
                float fade = 1F - out;
                ghost = new Caption(previous.anchor, previous.baseTex, previous.litTex, previous.metrics,
                        previous.progress, previous.accent, previous.deep, 0F,
                        previous.opacity * fade * fade, previous.rise + 0.24F * out,
                        previous.scale * (1F - 0.10F * out), 0.34F * out);
            }
        }
        return current == null && ghost == null ? null : new Frame(ghost, current);
    }

    /** Relocation only changes the world pose; the lyric index, entry time and uploaded textures remain intact. */
    private void updatePlacement(Minecraft mc, Vec3 eye, CameraRenderState camera, float dt, long nowNanos, boolean teleported) {
        if (relocationStartNanos != 0L) {
            float t = (float)Math.clamp((nowNanos - relocationStartNanos) / (double)RELOCATION_DURATION_NS, 0.0, 1.0);
            anchor = relocationFrom.lerp(relocationTo, t * t * (3F - 2F * t));
            if (t >= 1F) relocationStartNanos = 0L;
        }

        boolean inView = !hasAnchor || anchorInView(camera, anchor);
        if (inView) outOfViewSinceNanos = 0L;
        else if (outOfViewSinceNanos == 0L) outOfViewSinceNanos = nowNanos;
        boolean outOfView = outOfViewSinceNanos != 0L && nowNanos - outOfViewSinceNanos >= OUT_OF_VIEW_DELAY_NS;

        // Check the anchor's sightline at most four times per second, including while a relocation is in progress.
        if (hasAnchor && (lastOcclusionCheckNanos == 0L || nowNanos - lastOcclusionCheckNanos >= PLACEMENT_RETRY_NS)) {
            placementBlocked = mc.level.clip(new ClipContext(eye, anchor,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() != HitResult.Type.MISS;
            lastOcclusionCheckNanos = nowNanos;
        }
        double distanceSqr = eye.distanceToSqr(anchor);
        boolean relocate = teleported || !hasAnchor || placementBlocked || outOfView
                || distanceSqr < MIN_DISTANCE * MIN_DISTANCE || distanceSqr > MAX_ANCHOR_DISTANCE * MAX_ANCHOR_DISTANCE;
        boolean retryReady = lastPlacementAttemptNanos == 0L || nowNanos - lastPlacementAttemptNanos >= PLACEMENT_RETRY_NS;
        boolean cooldownReady = !hasAnchor || lastRelocatedNanos == 0L || nowNanos - lastRelocatedNanos >= RELOCATION_COOLDOWN_NS;
        if (relocate && (teleported || (retryReady && cooldownReady))) {
            lastPlacementAttemptNanos = nowNanos;
            double speed = mc.player.getDeltaMovement().horizontalDistance() * 20.0;
            Vec3 nextAnchor = place(mc, eye, camera.yRot, index, generationDistance(speed));
            if (nextAnchor == null) {
                placementBlocked = true;
            } else {
                if (!hasAnchor || teleported) {
                    anchor = nextAnchor;
                    relocationStartNanos = 0L;
                } else {
                    relocationFrom = anchor;
                    relocationTo = nextAnchor;
                    relocationStartNanos = nowNanos;
                }
                hasAnchor = true;
                placementBlocked = false;
                lastRelocatedNanos = lastOcclusionCheckNanos = nowNanos;
                outOfViewSinceNanos = 0L;
            }
        }
        float visibilityStep = dt * 1_000_000_000F / RELOCATION_DURATION_NS;
        anchorVisibility = hasAnchor && !placementBlocked
                ? Math.min(1F, anchorVisibility + visibilityStep)
                : Math.max(0F, anchorVisibility - visibilityStep);
    }

    private static boolean anchorInView(CameraRenderState camera, Vec3 anchor) {
        if (!camera.initialized) return true;
        Vec3 relative = anchor.subtract(camera.pos);
        Vector4f clip = new Vector4f((float)relative.x, (float)relative.y, (float)relative.z, 1F);
        camera.viewRotationMatrix.transform(clip);
        camera.projectionMatrix.transform(clip);
        // Rendering projection stays meaningful even when an Iris pack disables the culling frustum.
        float margin = clip.w * 1.15F;
        return clip.w > 0F && Math.abs(clip.x) <= margin && Math.abs(clip.y) <= margin;
    }

    private record Rasterized(ModernSkiaRaster.Image baseImage, ModernSkiaRaster.Image litImage, CardMetrics metrics) {}

    private static Rasterized rasterizeLine(String text, @Nullable String extra) {
        List<String> mainLines = ModernTypography.wrap(ModernTypography.Face.TEXT, text, MAIN_SCALE, MAX_MAIN_W, 2);
        List<String> extraLines = extra == null || extra.isBlank()
                ? List.of()
                : ModernTypography.wrap(ModernTypography.Face.TEXT, extra, EXTRA_SCALE, MAX_EXTRA_W, 2);

        float[] mainWidths = new float[mainLines.size()];
        float totalMainWidth = 0F, maxW = 64F;
        for (int i = 0; i < mainLines.size(); i++) {
            mainWidths[i] = ModernTypography.measure(ModernTypography.Face.TEXT, mainLines.get(i), MAIN_SCALE);
            totalMainWidth += mainWidths[i];
            maxW = Math.max(maxW, mainWidths[i]);
        }
        float[] extraWidths = new float[extraLines.size()];
        for (int i = 0; i < extraLines.size(); i++) {
            extraWidths[i] = ModernTypography.measure(ModernTypography.Face.TEXT, extraLines.get(i), EXTRA_SCALE);
            maxW = Math.max(maxW, extraWidths[i]);
        }

        float mainBlockH = mainLines.size() * MAIN_LINE_H;
        float extraGap = extraLines.isEmpty() ? 0F : 3.5F;
        float extraBlockH = extraLines.size() * EXTRA_LINE_H;
        float contentH = mainBlockH + extraGap + extraBlockH;

        final float contentW = maxW;
        final float logicalW = contentW + PAD_X * 2F;
        final float logicalH = contentH + PAD_Y * 2F;
        int pxW = Math.clamp((int)Math.ceil(logicalW * DENSITY), 16, 1024);
        int pxH = Math.clamp((int)Math.ceil(logicalH * DENSITY), 16, 512);
        final float sx = pxW / logicalW, sy = pxH / logicalH;

        LineBand[] bands = new LineBand[mainLines.size()];
        for (int i = 0; i < mainLines.size(); i++) {
            bands[i] = new LineBand((logicalW - mainWidths[i]) * 0.5F, PAD_Y + i * MAIN_LINE_H, mainWidths[i], MAIN_LINE_H);
        }

        // Base texture: soft drop shadow + unsung main lyric + crisp translation.
        ModernSkiaRaster.Image baseImage = ModernSkiaRaster.render(pxW, pxH, canvas -> {
            canvas.scale(sx, sy);
            try (MaskFilter shadowBlur = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 1.5F);
                 Paint shadow = new Paint().setAntiAlias(true).setColor(0x88040A14).setMaskFilter(shadowBlur);
                 Paint dim = new Paint().setAntiAlias(true).setColor(0x8CDCE8F4);
                 Paint extraPaint = new Paint().setAntiAlias(true).setColor(0xE2E6F0FA)) {
                for (int i = 0; i < mainLines.size(); i++) {
                    LineBand b = bands[i];
                    ModernTypography.paint(ModernTypography.Face.TEXT, canvas, mainLines.get(i), b.x, b.y + 0.9F, MAIN_SCALE, shadow);
                    ModernTypography.paint(ModernTypography.Face.TEXT, canvas, mainLines.get(i), b.x, b.y, MAIN_SCALE, dim);
                }
                float extraTop = PAD_Y + mainBlockH + extraGap;
                for (int i = 0; i < extraLines.size(); i++) {
                    float lx = (logicalW - extraWidths[i]) * 0.5F;
                    float ly = extraTop + i * EXTRA_LINE_H;
                    ModernTypography.paint(ModernTypography.Face.TEXT, canvas, extraLines.get(i), lx, ly + 0.7F, EXTRA_SCALE, shadow);
                    ModernTypography.paint(ModernTypography.Face.TEXT, canvas, extraLines.get(i), lx, ly, EXTRA_SCALE, extraPaint);
                }
            }
        });

        // Lit coverage mask: luminous white bloom + crisp white foreground (tinted & swept on GPU).
        ModernSkiaRaster.Image litImage = ModernSkiaRaster.render(pxW, pxH, canvas -> {
            canvas.scale(sx, sy);
            try (MaskFilter bloomBlur = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 3.6F);
                 Paint bloom = new Paint().setAntiAlias(true).setColor(0x88FFFFFF).setMaskFilter(bloomBlur);
                 Paint solid = new Paint().setAntiAlias(true).setColor(0xFFFFFFFF)) {
                for (int i = 0; i < mainLines.size(); i++) {
                    LineBand b = bands[i];
                    ModernTypography.paint(ModernTypography.Face.TEXT, canvas, mainLines.get(i), b.x, b.y, MAIN_SCALE, bloom);
                    ModernTypography.paint(ModernTypography.Face.TEXT, canvas, mainLines.get(i), b.x, b.y, MAIN_SCALE, solid);
                }
            }
        });

        CardMetrics metrics = new CardMetrics(logicalW, logicalH, contentW, contentH, totalMainWidth, List.of(bands));
        return new Rasterized(baseImage, litImage, metrics);
    }

    static float lineProgress(float overallProgress, float priorWidth, float lineWidth, float totalWidth) {
        if (lineWidth <= 0F || totalWidth <= 0F) return overallProgress >= 1F ? 1F : 0F;
        return Math.clamp((overallProgress * totalWidth - priorWidth) / lineWidth, 0F, 1F);
    }

    static Vec3 recedeFrom(Vec3 anchor, Vec3 cameraPos, float amount) {
        Vec3 away = anchor.subtract(cameraPos);
        double len = away.length();
        return len < 1e-5 ? anchor : anchor.add(away.scale(amount / len));
    }

    static float ease(long elapsed, int duration) {
        float t = Math.clamp(elapsed / (float)duration, 0F, 1F);
        float inv = 1F - t;
        return 1F - inv * inv * inv;
    }

    // The lyric service measures progress in UTF-16 units. Never split a supplementary glyph in half.
    static int boundary(String text, float progress) {
        int n = Math.clamp((int)(progress * text.length()), 0, text.length());
        return n > 0 && n < text.length() && Character.isLowSurrogate(text.charAt(n))
                && Character.isHighSurrogate(text.charAt(n - 1)) ? n - 1 : n;
    }

    static Vec3 target(Vec3 eye, float yaw, int index) {
        return target(eye, yaw, index, BASE_DISTANCE);
    }

    private static Vec3 target(Vec3 eye, float yaw, int index, double forward) {
        double a = Math.toRadians(yaw);
        double right = (index & 1) == 0 ? 0.85 : -0.85;
        return eye.add(-Math.sin(a) * forward - Math.cos(a) * right, -0.40,
                Math.cos(a) * forward - Math.sin(a) * right);
    }

    static double generationDistance(double speed) {
        if (!Double.isFinite(speed)) return BASE_DISTANCE;
        return Math.clamp(BASE_DISTANCE + Math.max(0.0, speed) * MOVEMENT_LOOK_AHEAD, BASE_DISTANCE, MAX_DISTANCE);
    }

    static boolean playbackJumped(long previousPosition, long position, long elapsedNanos) {
        return position < previousPosition || position - previousPosition > Math.max(0L, elapsedNanos) / 1_000_000L + 1200L;
    }

    static @Nullable Vec3 beforeObstacle(Vec3 eye, Vec3 surface) {
        Vec3 delta = surface.subtract(eye);
        double distance = delta.length() - OBSTACLE_MARGIN;
        return distance < MIN_DISTANCE ? null : eye.add(delta.normalize().scale(distance));
    }

    private static @Nullable Vec3 place(Minecraft mc, Vec3 eye, float yaw, int index, double forward) {
        Vec3 target = target(eye, yaw, index, forward);
        var hit = mc.level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() == HitResult.Type.MISS) return target;
        Vec3 other = target(eye, yaw, index ^ 1, forward);
        var alternate = mc.level.clip(new ClipContext(eye, other, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (alternate.getType() == HitResult.Type.MISS) return other;
        Vec3 surface = hit.getLocation().distanceToSqr(eye) >= alternate.getLocation().distanceToSqr(eye)
                ? hit.getLocation() : alternate.getLocation();
        return beforeObstacle(eye, surface);
    }
}
