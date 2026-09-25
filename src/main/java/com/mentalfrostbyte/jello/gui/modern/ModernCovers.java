package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.music.Track;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterMipmap;
import io.github.humbleui.skija.FilterMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.MipmapMode;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;

/**
 * Album covers with rounded corners, rasterized by Skia once per cover, size bucket and corner radius and
 * drawn with a tint so they fade like everything else. A track without artwork (or whose artwork can't be
 * read) gets a generated one - a night gradient crossed by ice-blue waves, its hue and shape seeded by the
 * title - which also stands in while a remote cover ({@code https://...}) downloads in the background. Rasters
 * are kept in a small LRU and their textures released when they fall out of it.
 */
final class ModernCovers {
    private static final int[] BUCKETS = {24, 32, 48, 64, 96, 128, 192, 256, 384, 512};
    private static final int CACHE_SIZE = 40;
    private static final Map<Key, Identifier> CACHE = new java.util.LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Identifier> eldest) {
            if (size() <= CACHE_SIZE) return false;
            ModernSkiaRaster.release(eldest.getValue());
            return true;
        }
    };
    private static final Map<String, java.util.concurrent.CompletableFuture<byte[]>> REMOTE = new java.util.LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, java.util.concurrent.CompletableFuture<byte[]>> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private static final java.util.concurrent.ExecutorService DOWNLOADS = java.util.concurrent.Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "Sigma cover download");
        thread.setDaemon(true);
        return thread;
    });
    private record Key(String cover, int px, int radiusPercent) {}

    private ModernCovers() {}

    /**
     * Draws {@code track}'s cover in a {@code size} square at ({@code x}, {@code y}), with corners rounded by
     * {@code radius} (a fraction of the size, 0..0.5), multiplied by {@code color}.
     */
    static void draw(GuiGraphicsExtractor g, @Nullable Track track, float x, float y, float size, float radius, int color) {
        color = ModernStyle.a(color);
        if ((color >>> 24) == 0 || size <= 0) return;
        float transform = Math.max((float)Math.hypot(g.pose().m00(), g.pose().m01()), (float)Math.hypot(g.pose().m10(), g.pose().m11()));
        int px = bucket(size * transform * Minecraft.getInstance().getWindow().getGuiScale());
        String cover = track == null ? "generated:" : track.cover() != null ? track.cover() : "generated:" + track.title();
        if (cover.startsWith("http")) {
            // Until the download lands (or if it fails), the generated cover stands in.
            byte[] bytes = remote(cover);
            if (bytes == null) cover = "generated:" + track.title();
        }
        int radiusPercent = Math.round(Math.max(0F, Math.min(0.5F, radius)) * 100F);
        Identifier id = CACHE.computeIfAbsent(new Key(cover, px, radiusPercent), key -> {
            Identifier texture = Identifier.withDefaultNamespace("sigma/modern_cover/" + Integer.toHexString(key.cover().hashCode())
                + "_" + key.px() + "_" + key.radiusPercent());
            ModernSkiaRaster.uploadColor(texture, ModernSkiaRaster.render(key.px(), key.px(), canvas -> paint(canvas, key)));
            return texture;
        });
        g.pose().pushMatrix();
        try {
            g.pose().translate(x, y);
            g.pose().scale(size / 64F, size / 64F);
            g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0, 0, 64, 64, px, px, px, px, color);
        } finally {
            g.pose().popMatrix();
        }
    }

    private static int bucket(float px) {
        for (int b : BUCKETS) if (b >= px) return b;
        return BUCKETS[BUCKETS.length - 1];
    }

    private static void paint(Canvas c, Key key) {
        int px = key.px();
        float r = px * key.radiusPercent() / 100F;
        c.save();
        try (PathBuilder builder = new PathBuilder().addRRect(RRect.makeXYWH(0, 0, px, px, r)); Path clip = builder.detach()) {
            c.clipPath(clip, true);
            Image image = key.cover().startsWith("generated:") ? null
                : key.cover().startsWith("http") ? decode(remote(key.cover())) : load(key.cover());
            if (image != null) {
                try (image; Paint paint = new Paint().setAntiAlias(true)) {
                    c.drawImageRect(image, Rect.makeWH(image.getWidth(), image.getHeight()), Rect.makeWH(px, px),
                        new FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), paint, true);
                }
            } else {
                generated(c, px, key.cover().hashCode());
            }
        }
        c.restore();
    }

    /** A night gradient crossed by three soft ice waves. */
    private static void generated(Canvas c, int px, int seed) {
        java.util.Random random = new java.util.Random(seed);
        float hue = 190F + random.nextFloat() * 40F;
        try (Shader shader = Shader.makeLinearGradient(0, 0, px * 0.3F, px, new int[]{0xFF081627, hsv(hue, 0.55F, 0.32F)});
             Paint base = new Paint().setShader(shader)) {
            c.drawRect(Rect.makeWH(px, px), base);
        }
        for (int i = 0; i < 3; i++) {
            float y0 = px * (0.35F + 0.14F * i + random.nextFloat() * 0.06F);
            float amp = px * (0.06F + random.nextFloat() * 0.08F);
            float phase = random.nextFloat() * 6.28F;
            int color = hsv(hue - 10F + i * 12F, 0.35F + i * 0.1F, 0.95F);
            try (PathBuilder b = new PathBuilder().moveTo(-2, y0)) {
                for (int s = 1; s <= 24; s++) {
                    float x = px * s / 24F;
                    b.lineTo(x, y0 + (float)Math.sin(phase + s * 0.42F) * amp);
                }
                try (Path wave = b.detach(); Paint paint = new Paint().setAntiAlias(true).setMode(PaintMode.STROKE)
                         .setStrokeWidth(px * (0.05F - i * 0.012F)).setColor((0x70 - i * 0x18) << 24 | (color & 0xFFFFFF))) {
                    c.drawPath(wave, paint);
                }
            }
        }
    }

    private static int hsv(float hue, float saturation, float value) {
        int rgb = java.awt.Color.HSBtoRGB(hue / 360F, saturation, value);
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    /** The downloaded bytes of a remote cover, or {@code null} while it downloads (or if it failed). */
    private static byte @Nullable [] remote(String url) {
        java.util.concurrent.CompletableFuture<byte[]> future = REMOTE.computeIfAbsent(url, u -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)java.net.URI.create(u).toURL().openConnection();
                conn.setConnectTimeout(8_000);
                conn.setReadTimeout(8_000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                try (InputStream in = conn.getInputStream()) {
                    return in.readNBytes(4 * 1024 * 1024);
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                return null;
            }
        }, DOWNLOADS));
        return future.isDone() ? future.getNow(null) : null;
    }

    /**
     * The encoded bytes of {@code cover} (a URL or a resource location): a remote one once its download has
     * arrived ({@code null} until then, and it is started if need be), a packaged one at once.
     */
    static byte @Nullable [] coverBytes(String cover) {
        if (cover.startsWith("http")) return remote(cover);
        Identifier id = Identifier.tryParse(cover);
        if (id == null) return null;
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(id);
        if (resource.isEmpty()) return null;
        try (InputStream in = resource.get().open()) {
            return in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    private static @Nullable Image decode(byte @Nullable [] bytes) {
        if (bytes == null) return null;
        try {
            return Image.makeFromEncoded(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private static @Nullable Image load(String location) {
        Identifier id = Identifier.tryParse(location);
        if (id == null) return null;
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(id);
        if (resource.isEmpty()) return null;
        try (InputStream in = resource.get().open()) {
            return Image.makeFromEncoded(in.readAllBytes());
        } catch (Exception e) {
            return null;
        }
    }
}
