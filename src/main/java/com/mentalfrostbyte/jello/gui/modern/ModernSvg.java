package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.jello.module.ModuleCategory;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.svg.SVGDOM;
import io.github.humbleui.skija.svg.SVGLength;
import io.github.humbleui.skija.svg.SVGLengthUnit;
import io.github.humbleui.skija.svg.SVGSVG;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SVG files from the client's resources, drawn through Skia's own SVG renderer. Like {@link ModernIcons}, each file is
 * rasterized once per size it's shown at on the device, so it stays crisp at any GUI scale, and the texture is kept.
 *
 * <p>A {@linkplain #mask mask} keeps only where the drawing is and is tinted by the caller, the way text is - the
 * category icons are drawn white for that. A {@linkplain #picture picture} keeps its own colours - the flags. An SVG
 * should give only a {@code viewBox}; it is scaled to fill the box it's drawn in.</p>
 */
final class ModernSvg {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sigma/Svg");
    private static final int MAX_PX = 512;

    private static final Map<String, Optional<byte[]>> FILES = new HashMap<>();
    private static final Map<Key, Identifier> CACHE = new HashMap<>();
    private static long serial;

    private record Key(String resource, int width, int height, boolean mask) {}

    private ModernSvg() {}

    /** The icon a category is shown with. */
    static String categoryIcon(ModuleCategory category) {
        return "/assets/minecraft/sigma/icons/category/" + category.name().toLowerCase(Locale.ROOT) + ".svg";
    }

    /** Draws {@code resource}'s shape filling the box, in {@code color}. */
    static void mask(GuiGraphicsExtractor g, String resource, float x, float y, float w, float h, int color) {
        draw(g, resource, x, y, w, h, color, true);
    }

    /** Draws {@code resource} in its own colours filling the box. */
    static void picture(GuiGraphicsExtractor g, String resource, float x, float y, float w, float h) {
        draw(g, resource, x, y, w, h, 0xFFFFFFFF, false);
    }

    private static void draw(GuiGraphicsExtractor g, String resource, float x, float y, float w, float h, int color, boolean mask) {
        color = ModernStyle.a(color);
        if ((color >>> 24) == 0 || w <= 0 || h <= 0) return;
        Optional<byte[]> file = FILES.computeIfAbsent(resource, ModernSvg::read);
        if (file.isEmpty()) return;
        // Rasterize for the actual device size, including local transforms and GUI scale.
        float transform = Math.max((float)Math.hypot(g.pose().m00(), g.pose().m01()),
            (float)Math.hypot(g.pose().m10(), g.pose().m11()));
        double device = transform * Minecraft.getInstance().getWindow().getGuiScale();
        int pw = Math.clamp((int)Math.ceil(w * device), 1, MAX_PX), ph = Math.clamp((int)Math.ceil(h * device), 1, MAX_PX);
        Identifier id = CACHE.computeIfAbsent(new Key(resource, pw, ph, mask), key -> {
            Identifier texture = Identifier.withDefaultNamespace("sigma/modern_svg/" + serial++);
            ModernSkiaRaster.Image image = rasterize(file.get(), pw, ph);
            if (mask) ModernSkiaRaster.upload(texture, image);
            else ModernSkiaRaster.uploadColor(texture, image);
            return texture;
        });
        g.pose().pushMatrix();
        try {
            g.pose().translate(x, y);
            g.pose().scale(w / pw, h / ph);
            g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0, 0, pw, ph, pw, ph, pw, ph, color);
        } finally {
            g.pose().popMatrix();
        }
    }

    /** {@code svg} drawn to fill a {@code width} by {@code height} image. Touches no game state; package-visible for tests. */
    static ModernSkiaRaster.Image rasterize(byte[] svg, int width, int height) {
        try (Data data = Data.makeFromBytes(svg); SVGDOM dom = new SVGDOM(data); SVGSVG root = dom.getRoot()) {
            // Fill the container whatever size the file itself declares; the viewBox then scales the drawing to it.
            root.setWidth(new SVGLength(100, SVGLengthUnit.PERCENTAGE)).setHeight(new SVGLength(100, SVGLengthUnit.PERCENTAGE));
            dom.setContainerSize(width, height);
            return ModernSkiaRaster.render(width, height, dom::render);
        }
    }

    private static Optional<byte[]> read(String resource) {
        try (InputStream stream = ModernSvg.class.getResourceAsStream(resource)) {
            if (stream != null) return Optional.of(stream.readAllBytes());
            LOGGER.warn("Missing SVG {}", resource);
        } catch (IOException failure) {
            LOGGER.warn("Could not read SVG {}", resource, failure);
        }
        return Optional.empty();
    }
}
