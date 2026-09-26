package com.mentalfrostbyte.jello.gui.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The shared backdrop of SigmaModern's full-screen pages (worlds, servers, settings): the painted night of
 * the main menu when no world is loaded - or the world itself from the pause menu - blurred behind a cold
 * tint, with a light snowfall drifting in front, sharp.
 *
 * <p>The scene ends its own GUI stratum before the blur is requested, because
 * {@code blurBeforeThisStratum()} only blurs earlier strata. Owns the page's frame clock too, so every
 * animation on the page shares one {@link #dt()}.</p>
 */
final class ModernBackdrop {
    private final ModernSnow snow;
    private long lastFrame;
    private float dt;
    private float time;
    private float parallaxX, parallaxY;

    ModernBackdrop(int flakes, float areaPerFlake) {
        this.snow = new ModernSnow(flakes, areaPerFlake);
    }

    float dt() {
        return this.dt;
    }

    float time() {
        return this.time;
    }

    void render(GuiGraphicsExtractor g, Minecraft minecraft, int width, int height, int mouseX, int mouseY) {
        long now = System.nanoTime();
        this.dt = this.lastFrame == 0L ? 0F : Math.min(0.05F, (now - this.lastFrame) / 1_000_000_000F);
        this.lastFrame = now;
        this.time += this.dt;
        this.parallaxX = ModernStyle.smooth(this.parallaxX, Math.max(-1F, Math.min(1F, mouseX / (float)Math.max(1, width) * 2F - 1F)), this.dt, 2F);
        this.parallaxY = ModernStyle.smooth(this.parallaxY, Math.max(-1F, Math.min(1F, mouseY / (float)Math.max(1, height) * 2F - 1F)), this.dt, 2F);

        boolean world = minecraft.level != null;
        if (!world) {
            ModernScene.shared().render(g, width, height, this.parallaxX * 0.5F, this.parallaxY * 0.5F, this.time);
            g.nextStratum();
        }
        g.blurBeforeThisStratum();
        ModernStyle.fillGradient(g, 0, 0, width, height, world ? 0x66050F1A : 0x4D050F1A, world ? 0x99050F1A : 0x8C050F1A);
        this.snow.update(width, height, this.dt, 5F + this.parallaxX * 6F);
        this.snow.render(g, this.parallaxX * 6F, world ? 0.4F : 0.65F);
    }
}
