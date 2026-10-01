package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The snow of the old BrainFreeze overlay: a flake for every two columns of the screen, each a small white dot about 3 to 12
 * pixels across, drifting down at its own speed and sideways on a wind that swells and fades, leaving one edge and coming
 * back in at the opposite one.
 */
final class JelloSnow {
    private static final float MAX_FALL = 60.0F;
    private static final float MAX_DRIFT = 30.0F;

    private final Random random = new Random();
    private final List<Flake> flakes = new ArrayList<>();
    private float wind;
    private float time;
    private int width;
    private int height;

    private static final class Flake {
        float x;
        float y;
        final float size;
        final float fall;
        final float drift;

        Flake(final Random random, final int width, final int height) {
            this.x = random.nextInt(Math.max(1, width));
            this.y = random.nextInt(Math.max(1, height));
            this.size = 1 + random.nextInt(2) + random.nextFloat();
            this.fall = random.nextFloat() * MAX_FALL;
            this.drift = (random.nextFloat() / 2.0F) * (random.nextBoolean() ? -1.0F : 1.0F) * MAX_DRIFT;
        }
    }

    void draw(final LegacyCanvas c, final float alpha, final float dt) {
        int w = c.width();
        int h = c.height();
        int wanted = w / c.guiScale() / 2;
        if (w != this.width || h != this.height) {
            this.width = w;
            this.height = h;
            this.flakes.clear();
        }
        while (this.flakes.size() < wanted) {
            this.flakes.add(new Flake(this.random, w, h));
        }
        while (this.flakes.size() > wanted) {
            this.flakes.remove(this.flakes.size() - 1);
        }

        this.time += dt;
        // The wind sways slowly from side to side, so the flakes lean together as well as each going its own way.
        this.wind = (float) Math.sin(this.time * 0.5F) * 15.0F;
        int color = LegacyCanvas.alpha(0xFFFFFFFF, alpha * 0.5F);
        for (Flake flake : this.flakes) {
            flake.x += (this.wind + flake.drift) * dt;
            flake.y += flake.fall * dt;
            if (flake.x < 0) {
                flake.x = w;
            } else if (flake.x > w) {
                flake.x = 0;
            }
            if (flake.y > h) {
                flake.y = 0;
            }
            c.disc(flake.x, flake.y, flake.size * 1.5F, color);
        }
    }
}
