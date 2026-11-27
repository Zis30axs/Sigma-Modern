package com.mentalfrostbyte.jello.gui.modern;

import java.util.Random;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Drifting snowfall - or whatever the current {@link ModernScene} theme lets drift instead: tumbling sakura
 * petals, rising embers, twinkling stardust. A fixed-size pool of flakes, each with a depth that drives its
 * size, fall speed, opacity and how far it shifts with the scene's parallax - near flakes are bigger, faster,
 * brighter and move more. With snow, roughly one in eight near flakes is a slowly spinning six-armed crystal
 * instead of a soft dot. Nothing is allocated per frame.
 */
final class ModernSnow {
    private final int capacity;
    private final float areaPerFlake;
    private final float[] x, y, depth, phase, spin, rotation, pick;
    private final Random random = new Random(0x51C3A);
    private int count;
    private int initialized;
    private int width, height;
    private float time;

    /** @param areaPerFlake GUI px² of screen per flake - lower is denser. */
    ModernSnow(int capacity, float areaPerFlake) {
        this.capacity = capacity;
        this.areaPerFlake = areaPerFlake;
        this.x = new float[capacity];
        this.y = new float[capacity];
        this.depth = new float[capacity];
        this.phase = new float[capacity];
        this.spin = new float[capacity];
        this.rotation = new float[capacity];
        this.pick = new float[capacity];
    }

    private void resize(int w, int h) {
        if (w == this.width && h == this.height) return;
        this.width = w;
        this.height = h;
        this.count = Math.min(this.capacity, Math.max(12, Math.round(w * h / this.areaPerFlake)));
        for (int i = 0; i < this.count; i++) {
            if (i >= this.initialized || this.x[i] > w + 24 || this.y[i] > h) spawn(i, this.random.nextFloat() * h);
        }
        this.initialized = Math.max(this.initialized, this.count);
    }

    private void spawn(int i, float startY) {
        float d = this.random.nextFloat();
        this.depth[i] = d * d;
        this.x[i] = this.random.nextFloat() * (this.width + 40) - 20;
        this.y[i] = startY;
        this.phase[i] = this.random.nextFloat() * 6.2832F;
        // Whether this flake draws as the theme's sprite is decided per frame from this, so a theme change
        // re-skins the flakes already in the air instead of waiting for them to respawn.
        this.pick[i] = this.random.nextFloat();
        this.spin[i] = (this.random.nextFloat() - 0.5F) * 1.2F;
        this.rotation[i] = this.random.nextFloat() * 6.2832F;
    }

    /**
     * @param dt     seconds since the last frame, already clamped by the caller
     * @param wind   horizontal drift in GUI px/s (positive = to the right)
     */
    void update(int w, int h, float dt, float wind) {
        resize(w, h);
        ModernScene.Particles style = ModernScene.shared().particles();
        this.time += dt;
        for (int i = 0; i < this.count; i++) {
            float d = this.depth[i];
            float fall = (7F + d * 30F) * style.fall;
            float sway = (float)Math.sin(this.time * (0.6F + d * 0.5F) + this.phase[i]) * (4F + d * 10F);
            this.y[i] += fall * dt;
            this.x[i] += (wind * (0.4F + d) + sway * 0.6F) * dt;
            this.rotation[i] += this.spin[i] * style.spin * dt;
            if (fall >= 0F ? this.y[i] > h + 12 : this.y[i] < -12) spawn(i, fall >= 0F ? -12 : h + 12);
            if (this.x[i] < -24) this.x[i] += this.width + 48;
            else if (this.x[i] > this.width + 24) this.x[i] -= this.width + 48;
        }
    }

    /** @param parallax the scene's own mouse parallax in GUI px, applied scaled by each flake's depth */
    void render(GuiGraphicsExtractor g, float parallax, float opacity) {
        ModernScene scene = ModernScene.shared();
        ModernScene.Particles style = scene.particles();
        int rgb = scene.particleRgb();
        for (int i = 0; i < this.count; i++) {
            float d = this.depth[i];
            float px = this.x[i] + parallax * (0.3F + d * 1.4F);
            float alpha = opacity * (0.28F + d * 0.62F);
            if (style.twinkle) alpha *= 0.55F + 0.45F * (float)Math.sin(this.time * (2.2F + this.pick[i] * 2.6F) + this.phase[i] * 3F);
            int color = Math.round(255 * Math.max(0F, alpha)) << 24 | rgb;
            if (style.shape != null && d > style.shapeMinDepth && this.pick[i] < style.shapeShare) {
                ModernIcons.drawRotated(g, style.shape, px, this.y[i], (5.5F + d * 5F) * style.size, this.rotation[i], color);
            } else {
                float size = (1.8F + d * 3.6F) * style.size;
                ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, px - size / 2F, this.y[i] - size / 2F, size, color);
            }
        }
    }
}
