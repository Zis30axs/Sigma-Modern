package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.music.Track;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

/**
 * An album cover's colours, for tinting the island (and the spectrum and particles): its most vivid hue as a bright
 * {@code accent}, and a {@code deep} version of it dark enough to sit under white text.
 *
 * <p>Worked out on the CPU, on a thread of its own, from the cover's encoded bytes (the ones {@link ModernCovers}
 * already downloaded) decoded with ImageIO - no texture is read back from the GPU and nothing touches OpenGL, so
 * it can't upset the graphics driver. A grey cover (nothing vivid enough to pick) has no palette, and the island
 * keeps its ice colours.</p>
 */
final class ModernCoverColors {
    record Palette(int accent, int deep) {}

    private static final int GRID = 32, HUES = 36;
    private static final Map<String, CompletableFuture<@Nullable Palette>> CACHE = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CompletableFuture<@Nullable Palette>> eldest) {
            return size() > 64;
        }
    };
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Sigma cover colours");
        thread.setDaemon(true);
        return thread;
    });

    /** The colours with no cover to go by (or the setting off): the ice theme's. */
    static final int ICE_ACCENT = 0xFF9FDCFF, ICE_DEEP = 0xFF112536;
    private static int accentTarget = ICE_ACCENT, deepTarget = ICE_DEEP, accentNow = ICE_ACCENT, deepNow = ICE_DEEP;
    private static float tintTarget, tintNow;
    private static long lastUpdate;

    private ModernCoverColors() {}

    /**
     * Eases the colours on show towards {@code track}'s palette (ice when {@code enabled} is off or the cover has
     * none). While a new cover is still being looked at the previous colours stay, rather than flashing back to
     * ice between songs. Safe to call more than once a frame - it moves by the time passed.
     */
    static void update(@Nullable Track track, boolean enabled) {
        if (!enabled) {
            accentTarget = ICE_ACCENT;
            deepTarget = ICE_DEEP;
            tintTarget = 0F;
        } else {
            CompletableFuture<@Nullable Palette> future = palette(track);
            if (future != null && future.isDone()) {
                Palette palette = future.isCompletedExceptionally() ? null : future.getNow(null);
                accentTarget = palette == null ? ICE_ACCENT : palette.accent();
                deepTarget = palette == null ? ICE_DEEP : palette.deep();
                tintTarget = palette == null ? 0F : 1F;
            }
        }
        long now = System.nanoTime();
        float dt = lastUpdate == 0L ? 1F : Math.min(0.1F, (now - lastUpdate) / 1_000_000_000F);
        lastUpdate = now;
        float t = 1F - (float)Math.exp(-dt * 3F);
        accentNow = ModernStyle.mix(accentNow, accentTarget, t);
        deepNow = ModernStyle.mix(deepNow, deepTarget, t);
        tintNow += (tintTarget - tintNow) * t;
    }

    /** How far the colours on show are the cover's rather than ice, 0..1 - for parts that keep their own look untinted. */
    static float tint() {
        return tintNow;
    }

    /** The bright colour on show now (opaque). */
    static int accent() {
        return accentNow;
    }

    /** The dark colour on show now (opaque), for backgrounds under white text. */
    static int deep() {
        return deepNow;
    }

    /**
     * {@code track}'s palette as far as it is known: {@code null} while its cover downloads or is being looked at
     * (keep showing the previous one); a done future holding {@code null} for a cover without one.
     */
    static @Nullable CompletableFuture<@Nullable Palette> palette(@Nullable Track track) {
        if (track == null || track.cover() == null) return CompletableFuture.completedFuture(null);
        String cover = track.cover();
        CompletableFuture<@Nullable Palette> known = CACHE.get(cover);
        if (known != null) return known;
        byte[] bytes = ModernCovers.coverBytes(cover);
        if (bytes == null) return null;
        CompletableFuture<@Nullable Palette> future = CompletableFuture.supplyAsync(() -> extract(bytes), WORKER);
        CACHE.put(cover, future);
        return future;
    }

    /** The palette of an encoded image; {@code null} for an unreadable or colourless one. */
    static @Nullable Palette extract(byte[] bytes) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return null;
        float[] weight = new float[HUES], red = new float[HUES], green = new float[HUES], blue = new float[HUES];
        float vivid = 0F;
        float[] hsb = new float[3];
        for (int gy = 0; gy < GRID; gy++) {
            for (int gx = 0; gx < GRID; gx++) {
                int rgb = image.getRGB(gx * image.getWidth() / GRID + image.getWidth() / GRID / 2,
                    gy * image.getHeight() / GRID + image.getHeight() / GRID / 2);
                int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
                Color.RGBtoHSB(r, g, b, hsb);
                // Vivid pixels count; near-black, near-white and grey ones don't.
                if (hsb[1] < 0.22F || hsb[2] < 0.18F) continue;
                float w = hsb[1] * hsb[2];
                int bucket = Math.min(HUES - 1, (int)(hsb[0] * HUES));
                weight[bucket] += w;
                red[bucket] += r * w;
                green[bucket] += g * w;
                blue[bucket] += b * w;
                vivid += w;
            }
        }
        if (vivid < GRID * GRID * 0.03F) return null;
        int best = 0;
        float bestScore = -1F;
        for (int i = 0; i < HUES; i++) {
            float score = weight[i] + 0.5F * (weight[(i + HUES - 1) % HUES] + weight[(i + 1) % HUES]);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        float w = 0F, r = 0F, g = 0F, b = 0F;
        for (int d = -1; d <= 1; d++) {
            int i = (best + d + HUES) % HUES;
            w += weight[i];
            r += red[i];
            g += green[i];
            b += blue[i];
        }
        Color.RGBtoHSB(Math.round(r / w), Math.round(g / w), Math.round(b / w), hsb);
        float hue = hsb[0], saturation = Math.max(0.45F, hsb[1]);
        int accent = 0xFF000000 | Color.HSBtoRGB(hue, Math.min(0.85F, saturation), 0.95F) & 0xFFFFFF;
        int deep = 0xFF000000 | Color.HSBtoRGB(hue, Math.min(0.62F, saturation), 0.24F) & 0xFFFFFF;
        return new Palette(accent, deep);
    }
}
