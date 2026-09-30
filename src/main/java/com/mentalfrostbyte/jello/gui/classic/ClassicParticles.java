package com.mentalfrostbyte.jello.gui.classic;

import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Classic's drifting dots ({@code ParticleOverlay}): white specks that float slowly across the screen, each
 * fading out over a few seconds and being replaced by a new one somewhere else.
 *
 * <p>There are {@code width / 8} of them. A dot is 0-5 px across and drifts at up to 21 px/s in each direction
 * (the old client moved it a fixed amount per frame, so these are per second at the 60 fps it was tuned for).
 * Opacity falls by 0.003 a frame less half a thousandth per pixel of size: the smallest dots are gone in five
 * seconds, the biggest last half a minute. One new dot is added per frame until the field is full, so it fills
 * in over the first second rather than popping in.</p>
 */
public final class ClassicParticles {
    private final List<Dot> dots = new ArrayList<>();
    private final Random random = new Random();
    private long lastNanos = System.nanoTime();

    /** Advances and draws the field, shifted by {@code (offsetX, offsetY)} (the menu's pointer parallax). */
    public void draw(final LegacyCanvas c, final float offsetX, final float offsetY) {
        long now = System.nanoTime();
        // The old rates were per frame at about 60 fps.
        float frames = Math.min(6.0F, (now - this.lastNanos) / 1.0E9F * 60.0F);
        this.lastNanos = now;

        int width = c.width();
        int height = c.height();
        int wanted = width / 8;
        for (int spawned = 0; this.dots.size() < wanted && spawned < Math.max(1, Math.round(frames)); spawned++) {
            this.dots.add(new Dot(this.random, this.random.nextInt(width), this.random.nextInt(height)));
        }
        while (this.dots.size() > wanted) {
            this.dots.remove(0);
        }

        var iterator = this.dots.iterator();
        while (iterator.hasNext()) {
            Dot dot = iterator.next();
            dot.step(frames);
            if (dot.x < -50 || dot.x > width + 50 || dot.y < -50 || dot.y > height + 50 || dot.opacity <= 0.0F) {
                iterator.remove();
                continue;
            }
            c.disc(dot.x + offsetX, dot.y + offsetY, dot.size, LegacyCanvas.alpha(0xFFFFFFFF, dot.opacity));
        }
    }

    private static final class Dot {
        final float size;
        final float vx;
        final float vy;
        float x;
        float y;
        float opacity;

        Dot(final Random random, final float x, final float y) {
            this.x = x;
            this.y = y;
            this.size = random.nextInt(4) + random.nextFloat();
            this.vx = (0.5F - random.nextFloat()) * 0.7F;
            this.vy = (0.5F - random.nextFloat()) * 0.7F;
            this.opacity = random.nextFloat();
        }

        void step(final float frames) {
            this.x += this.vx * frames;
            this.y += this.vy * frames;
            this.opacity = Math.max(0.0F, Math.min(1.0F, this.opacity + (-0.003F + 5.0E-4F * this.size) * frames));
        }
    }
}
