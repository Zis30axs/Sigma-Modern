package com.mentalfrostbyte.jello.gui.modern;

import io.github.humbleui.skija.Paint;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Anti-aliased discs for the old screens' floating bubbles and particles.
 *
 * <p>One 128 px Skia mask is drawn scaled to any size and tinted, so a screenful of bubbles costs one texture
 * and no allocation per frame. Radii are floats (a bubble is 3.5 px across on some screens), which is why this
 * isn't {@link ModernStyle#rounded}, whose radius is a whole number of pixels.</p>
 */
public final class LegacyShapes {
    private static final int MASK = 128;
    private static Identifier disc;

    private LegacyShapes() {}

    /** A filled circle centred on {@code (cx, cy)}, in the current pose's units. */
    public static void disc(GuiGraphicsExtractor g, float cx, float cy, float radius, int color) {
        if (radius <= 0 || (color >>> 24) == 0) return;
        Identifier id = mask();
        g.pose().pushMatrix();
        try {
            g.pose().translate(cx - radius, cy - radius);
            float scale = 2 * radius / MASK;
            g.pose().scale(scale, scale);
            g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0, 0, MASK, MASK, MASK, MASK, color);
        } finally {
            g.pose().popMatrix();
        }
    }

    private static Identifier mask() {
        if (disc == null) {
            Identifier id = Identifier.withDefaultNamespace("sigma/legacy_shape/disc");
            ModernSkiaRaster.upload(id, ModernSkiaRaster.render(MASK, MASK, canvas -> {
                try (Paint paint = new Paint().setColor(0xFFFFFFFF).setAntiAlias(true)) {
                    canvas.drawCircle(MASK / 2F, MASK / 2F, MASK / 2F - 1, paint);
                }
            }));
            disc = id;
        }
        return disc;
    }
}
