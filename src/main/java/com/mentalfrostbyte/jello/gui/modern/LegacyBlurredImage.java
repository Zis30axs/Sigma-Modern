package com.mentalfrostbyte.jello.gui.modern;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.resources.Identifier;

/**
 * A heavily blurred copy of one of the old client's backdrops (the Jello panorama behind the changelog, the
 * goodbye screen and the alt manager).
 *
 * <p>The old client built these once with AWT: shrink the picture to a small fraction of its size, run a
 * Gaussian ({@code sigma = radius / 3}) along each axis, and push the saturation up by a tenth, then stretch
 * the little result over the whole window. That is cheap and gives a very soft frosted backdrop without any
 * per-frame blur pass. The same recipe runs here on a worker thread (decoding a 3840 px PNG is not something
 * to do inside a frame); {@link #texture()} returns {@code null} until it has finished and registers the
 * texture on the render thread the first time it is asked for after that.</p>
 */
public final class LegacyBlurredImage {
    private static final Map<String, LegacyBlurredImage> CACHE = new HashMap<>();
    private static long serial;

    private final Identifier id;
    private volatile Pixels ready;
    private boolean registered;
    private int width;
    private int height;

    private LegacyBlurredImage(final String resource, final float scale, final int radius, final float saturation) {
        this.id = Identifier.withDefaultNamespace("sigma/legacy_blur/" + serial++);
        Thread worker = new Thread(() -> {
            try {
                this.ready = build(resource, scale, radius, saturation);
            } catch (IOException | RuntimeException failure) {
                com.mentalfrostbyte.Client.logger.error("Cannot build the blurred backdrop {}", resource, failure);
            }
        }, "Sigma legacy backdrop");
        worker.setDaemon(true);
        worker.start();
    }

    /** The blurred image for a classpath resource, started on first use and shared afterwards. Render thread only. */
    public static LegacyBlurredImage of(final String resource, final float scale, final int radius, final float saturation) {
        return CACHE.computeIfAbsent(resource + '|' + scale + '|' + radius + '|' + saturation,
            key -> new LegacyBlurredImage(resource, scale, radius, saturation));
    }

    /** The texture id once the image is ready, else {@code null} (the caller just draws without it meanwhile). */
    public Identifier texture() {
        if (!this.registered) {
            Pixels pixels = this.ready;
            if (pixels == null) {
                return null;
            }
            NativeImage image = new NativeImage(pixels.width, pixels.height, false);
            for (int y = 0; y < pixels.height; y++) {
                for (int x = 0; x < pixels.width; x++) {
                    image.setPixel(x, y, pixels.argb[y * pixels.width + x]);
                }
            }
            ModernSkiaRaster.registerColor(this.id, image);
            this.width = pixels.width;
            this.height = pixels.height;
            this.registered = true;
        }
        return this.id;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    record Pixels(int width, int height, int[] argb) {
    }

    private static Pixels build(final String resource, final float scale, final int radius, final float saturation) throws IOException {
        BufferedImage source;
        try (InputStream in = LegacyBlurredImage.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing " + resource);
            }
            source = ImageIO.read(in);
        }
        return blur(shrink(source, scale), radius, saturation);
    }

    /** Area-averaged shrink: every source pixel counts, so a 3840 px picture doesn't alias down to a dozen samples. */
    static Pixels shrink(final BufferedImage source, final float scale) {
        int sw = source.getWidth();
        int sh = source.getHeight();
        int w = Math.max(1, Math.round(sw * scale));
        int h = Math.max(1, Math.round(sh * scale));
        int[] src = source.getRGB(0, 0, sw, sh, null, 0, sw);
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = (int) ((long) y * sh / h);
            int y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * sh / h));
            for (int x = 0; x < w; x++) {
                int x0 = (int) ((long) x * sw / w);
                int x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * sw / w));
                long r = 0;
                long g = 0;
                long b = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int p = src[yy * sw + xx];
                        r += p >> 16 & 0xFF;
                        g += p >> 8 & 0xFF;
                        b += p & 0xFF;
                    }
                }
                long n = (long) (y1 - y0) * (x1 - x0);
                out[y * w + x] = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        }
        return new Pixels(w, h, out);
    }

    /** {@code sigma = radius / 3}, edges clamped, then the saturation multiplier. */
    static Pixels blur(final Pixels in, final int radius, final float saturation) {
        int w = in.width;
        int h = in.height;
        float sigma = Math.max(0.01F, radius / 3.0F);
        int r = Math.max(1, radius);
        float[] kernel = new float[2 * r + 1];
        float sum = 0;
        for (int i = -r; i <= r; i++) {
            kernel[i + r] = (float) Math.exp(-(i * i) / (2.0 * sigma * sigma));
            sum += kernel[i + r];
        }
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= sum;
        }

        float[][] channels = new float[3][w * h];
        for (int i = 0; i < w * h; i++) {
            int p = in.argb[i];
            channels[0][i] = p >> 16 & 0xFF;
            channels[1][i] = p >> 8 & 0xFF;
            channels[2][i] = p & 0xFF;
        }
        float[] tmp = new float[w * h];
        for (float[] c : channels) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    float acc = 0;
                    for (int k = -r; k <= r; k++) {
                        acc += c[y * w + Math.clamp(x + k, 0, w - 1)] * kernel[k + r];
                    }
                    tmp[y * w + x] = acc;
                }
            }
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    float acc = 0;
                    for (int k = -r; k <= r; k++) {
                        acc += tmp[Math.clamp(y + k, 0, h - 1) * w + x] * kernel[k + r];
                    }
                    c[y * w + x] = acc;
                }
            }
        }

        int[] out = new int[w * h];
        float[] hsb = new float[3];
        for (int i = 0; i < w * h; i++) {
            Color.RGBtoHSB(Math.round(channels[0][i]), Math.round(channels[1][i]), Math.round(channels[2][i]), hsb);
            out[i] = Color.HSBtoRGB(hsb[0], Math.min(1.0F, hsb[1] * saturation), hsb[2]) | 0xFF000000;
        }
        return new Pixels(w, h, out);
    }
}
