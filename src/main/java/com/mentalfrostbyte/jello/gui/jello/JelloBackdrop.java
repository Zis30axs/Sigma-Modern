package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyBlurredImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.resources.Identifier;

/**
 * Jello's living backdrop: three photographic layers that slide against each other as the pointer moves, with
 * soft bubbles floating between the middle and the front layer.
 *
 * <p>This is the old {@code MainMenuScreen.draw}, ported number for number:</p>
 * <ul>
 *   <li>The scene is a 3840x1080 picture in three layers (sky, middle, foreground) stretched to
 *       {@code 2 * width + layerWidth} by {@code height + 114}. The pointer's x pans all layers left; the
 *       back layers are 600 and 450 px (at 1920 wide) wider than the front one, so they pan by 0.69x and
 *       0.77x of what the front does - that difference is the parallax. The pointer's y lifts the picture by
 *       up to {@code 114 * y / width}.</li>
 *   <li>The layers follow the pointer with a lag: each frame closes {@code min(1, 0.7 * frameMillis / 36.2)} of
 *       the gap, the old client's frame-time factor.</li>
 *   <li>{@code width * height / 14000} bubbles, 12-20 px across, drift at 19-77 px/s and are shoved away by the
 *       pointer inside 114 px, glowing brighter the closer it is, then settle back to their own drift.</li>
 *   <li>A blurred copy of the sky ({@link LegacyBlurredImage}) fades in over everything when a panel such as
 *       the changelog or the goodbye screen opens, followed by a black wash of up to 30 %.</li>
 * </ul>
 */
public final class JelloBackdrop {
    private static final String PANORAMA = "/assets/minecraft/textures/gui/sigma/legacy/jello/background/panorama5.png";
    private static final float PUSH_RADIUS = 114.0F;

    private final Random random = new Random();
    private final List<Bubble> bubbles = new ArrayList<>();
    private LegacyBlurredImage blurred;
    private int bubbleWidth;
    private int bubbleHeight;

    private float offsetX;
    private float offsetY;
    private boolean placed;
    private long lastNanos = System.nanoTime();
    private float factor = 1.0F;

    /** Frame-time factor of the last {@link #draw}: about 0.32 at 60 fps, 1 when frames take 52 ms or more. */
    public float factor() {
        return this.factor;
    }

    /**
     * Draws the scene. {@code overlay} (0..1) is how far a panel over the menu has opened: it brings up the
     * blurred copy and the dark wash.
     */
    public void draw(final LegacyCanvas c, final double mouseX, final double mouseY, final float overlay) {
        int width = c.width();
        int height = c.height();
        long now = System.nanoTime();
        this.factor = Math.min(1.0F, 0.7F * ((now - this.lastNanos) / 1.0E6F) / 36.2F);
        this.lastNanos = now;

        float targetX = (float) -mouseX;
        float targetY = (float) (mouseY / width * -114.0);
        if (!this.placed) {
            this.offsetX = targetX;
            this.offsetY = targetY;
            this.placed = true;
        }

        float pf = 0.5F + this.offsetX / width;
        float scale = width / 1920.0F;
        int backWidth = (int) (600.0F * scale);
        int middleWidth = (int) (450.0F * scale);
        int frontWidth = 0;

        this.layer(c, LegacyTexture.JELLO_BACKGROUND, this.offsetX - backWidth * pf, width * 2 + backWidth, height, 0xFFFFFFFF);
        this.layer(c, LegacyTexture.JELLO_MIDDLE, this.offsetX - middleWidth * pf, width * 2 + middleWidth, height, 0xFFFFFFFF);
        this.bubbles(c, mouseX, mouseY, width, height);
        this.layer(c, LegacyTexture.JELLO_FOREGROUND, this.offsetX - frontWidth * pf, width * 2 + frontWidth, height, 0xFFFFFFFF);

        if (overlay > 0.0F) {
            Identifier blur = this.blurredTexture();
            if (blur != null) {
                c.image(blur, this.blurred.width(), this.blurred.height(), this.offsetX, this.offsetY - 50, width * 2, height + 200,
                    LegacyCanvas.alpha(0xFFFEFEFE, overlay));
            }
            c.fill(0, 0, width, height, LegacyCanvas.alpha(0xFF010101, overlay * 0.3F));
        }

        // Follow the pointer.
        this.offsetX += (targetX - this.offsetX) * this.factor;
        this.offsetY += (targetY - this.offsetY) * this.factor;
    }

    private void layer(final LegacyCanvas c, final LegacyTexture texture, final float x, final int w, final int height, final int color) {
        c.image(texture, x, this.offsetY, w, height + 114, color);
    }

    private Identifier blurredTexture() {
        if (this.blurred == null) {
            this.blurred = LegacyBlurredImage.of(PANORAMA, 0.075F, 8, 1.1F);
        }
        return this.blurred.texture();
    }

    private void bubbles(final LegacyCanvas c, final double mouseX, final double mouseY, final int width, final int height) {
        if (this.bubbleWidth != width || this.bubbleHeight != height) {
            this.bubbleWidth = width;
            this.bubbleHeight = height;
            this.bubbles.clear();
            int count = width * height / 14000;
            for (int i = 0; i < count; i++) {
                int size = 7 + this.random.nextInt(5);
                int vx = (1 + this.random.nextInt(4)) * (this.random.nextBoolean() ? 1 : -1);
                int vy = 1 + this.random.nextInt(2);
                this.bubbles.add(new Bubble(this.random.nextInt(width), this.random.nextInt(height), size, vx, vy));
            }
        }
        for (Bubble b : this.bubbles) {
            b.step(mouseX, mouseY, width, height, this.factor);
            c.disc(b.x, b.y, b.size - 1, LegacyCanvas.alpha(0xFFFEFEFE, 0.07F + (b.nearness > 0.0F ? b.nearness * 0.3F : 0.0F)));
        }
    }

    /** One floating bubble ({@code FloatingBubble}). */
    private static final class Bubble {
        final int size;
        final float baseVx;
        final float baseVy;
        float x;
        float y;
        float vx;
        float vy;
        float nearness;

        Bubble(final float x, final float y, final int size, final int vx, final int vy) {
            this.x = x;
            this.y = y;
            this.size = size;
            this.vx = this.baseVx = vx;
            this.vy = this.baseVy = vy;
        }

        void step(final double mouseX, final double mouseY, final int width, final int height, final float speed) {
            this.x += this.vx * speed;
            this.y += this.vy * speed;
            if (this.x + this.size < 0.0F) {
                this.x = width;
            } else if (this.x > width) {
                this.x = -this.size;
            }
            if (this.y + this.size < 0.0F) {
                this.y = height;
            } else if (this.y > height) {
                this.y = -this.size;
            }

            float dx = (float) (mouseX - Math.round(this.x));
            float dy = (float) (mouseY - Math.round(this.y));
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            this.nearness = 1.0F - distance / PUSH_RADIUS;
            if (distance >= PUSH_RADIUS) {
                this.vx -= (this.vx - this.baseVx) * 0.05F * speed;
                this.vy -= (this.vy - this.baseVy) * 0.05F * speed;
            } else {
                float awayX = this.x - (float) mouseX;
                float awayY = this.y - (float) mouseY;
                float length = (float) Math.sqrt(awayX * awayX + awayY * awayY);
                float half = Math.max(0.001F, length / 2.0F);
                this.vx += awayX / half / (1.0F + this.nearness) * speed;
                this.vy += awayY / half / (1.0F + this.nearness) * speed;
            }
        }
    }
}
