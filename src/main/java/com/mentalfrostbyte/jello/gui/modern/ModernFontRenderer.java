package com.mentalfrostbyte.jello.gui.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

import net.minecraft.resources.Identifier;

import java.io.IOException;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontEdging;
import io.github.humbleui.skija.FontHinting;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.TextBlob;
import io.github.humbleui.skija.TextLine;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.skija.shaper.Shaper;
import io.github.humbleui.types.Rect;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Modern-only text shaping and coverage rasterization, independent of Minecraft's font atlas. */
final class ModernFontRenderer implements AutoCloseable {
    private static final float SIZE = 11;
    private static final float BASELINE = 9;

    private static final int MAX_LAYOUTS = 512;
    private static final long CACHE_BYTES = 32L * 1024 * 1024;
    private final Typeface face;
    private final Font font;
    private final Shaper shaper = Shaper.make();

    private final Map<String, TextLine> layouts = new LinkedHashMap<>(64, 0.75F, true);
    private final Map<Key, Run> textures = new LinkedHashMap<>(64, 0.75F, true);
    private GuiGraphicsExtractor lastExtractor;
    private long frame;
    // Shared by every face's renderer: texture ids live in one namespace, so per-instance counters would
    // hand two renderers the same id and each would overwrite (and later release) the other's glyph runs.
    private static long serial;
    private long bytes;

    static final String SERIF_TEXT = "/assets/minecraft/font/sigma/anthropic_serif_text.ttf";
    static final String SERIF_DISPLAY = "/assets/minecraft/font/sigma/anthropic_serif_display.ttf";
    static final String SERIF_DISPLAY_ITALIC = "/assets/minecraft/font/sigma/anthropic_serif_display_italic.ttf";

    ModernFontRenderer() {
        this(SERIF_TEXT);
    }

    /** {@code resource} is a static TTF instance cut from Anthropic Serif; see SIGMA_MODERN.md "Typography". */
    ModernFontRenderer(String resource) {
        try (var stream = ModernFontRenderer.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing bundled font " + resource);
            try (Data data = Data.makeFromBytes(stream.readAllBytes())) {
                this.face = FontMgr.getDefault().makeFromData(data);
            }
            if (this.face == null) throw new IOException("Invalid bundled font " + resource);
            this.font = new Font(this.face, SIZE).setEdging(FontEdging.ANTI_ALIAS)
                .setSubpixel(true).setMetricsLinear(true).setHinting(FontHinting.NONE);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load Modern's font", e);
        }
    }

    private TextLine layout(String text) {
        TextLine cached = this.layouts.get(text);
        if (cached != null) return cached;
        TextLine result = this.shaper.shapeLine(text, this.font);
        this.layouts.put(text, result);
        if (this.layouts.size() > MAX_LAYOUTS) this.layouts.remove(this.layouts.keySet().iterator().next()).close();
        return result;
    }
    int width(String text) {
        return text.isEmpty() ? 0 : (int)Math.ceil(layout(text).getWidth());
    }

    String fit(String text, int width) {
        if (text.isEmpty() || width(text) <= width) return text;
        if (width <= 0) return "";
        // Never split surrogate pairs or combining sequences when clipping editable text.
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        ArrayList<Integer> ends = new ArrayList<>();
        for (int end = iterator.first(); end != BreakIterator.DONE; end = iterator.next()) ends.add(end);
        int low = 0, high = ends.size() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (width(text.substring(0, ends.get(mid))) <= width) low = mid;
            else high = mid - 1;
        }
        return text.substring(0, ends.get(low));
    }

    void draw(GuiGraphicsExtractor g, String text, int x, int y, int color, boolean shadow) {
        color = ModernStyle.a(color);
        if (text.isEmpty() || (color >>> 24) == 0) return;
        if (this.lastExtractor != g) {
            this.lastExtractor = g;
            this.frame++;
            trim();
        }
        // Rasterize for the actual device size, including local title transforms and GUI scale.
        float transformScale = Math.max((float)Math.hypot(g.pose().m00(), g.pose().m01()),
            (float)Math.hypot(g.pose().m10(), g.pose().m11()));
        int density = Math.clamp((int)Math.ceil(transformScale * Minecraft.getInstance().getWindow().getGuiScale() * 2), 2, 32);
        Key key = new Key(text, density);
        Run run = this.textures.get(key);
        if (run == null) {
            run = upload(text, density);
            this.textures.put(key, run);
            this.bytes += run.bytes;
        }
        run.lastFrame = this.frame;
        if (shadow) blit(g, run, x + 1, y + 1, (color & 0xFF000000) | ((color & 0x00FCFCFC) >>> 2));
        blit(g, run, x, y, color);
    }

    private Run upload(String text, int density) {
        Raster raster = rasterize(text, density);
        ModernSkiaRaster.Image image = raster.image;
        Identifier id = Identifier.withDefaultNamespace("sigma/modern_text/" + serial++);
        ModernSkiaRaster.upload(id, image);
        return new Run(id, raster, (long)image.getWidth() * image.getHeight() * 8, this.frame);
    }

    /** Package-visible for headless coverage/scale regression checks. */
    Raster rasterize(String text, int density) {
        TextLine line = layout(text);
        Rect bounds;
        try (TextBlob blob = line.getTextBlob()) {
            bounds = blob == null ? Rect.makeXYWH(0, 0, 0, 0) : blob.getBounds();
        }
        int left = (int)Math.floor(bounds.getLeft()) - 1;
        int top = (int)Math.floor(BASELINE + bounds.getTop()) - 1;
        int width = Math.max(1, (int)Math.ceil(bounds.getRight()) + 1 - left);
        int height = Math.max(1, (int)Math.ceil(BASELINE + bounds.getBottom()) + 1 - top);
        float scale = Math.min(density, Math.min(4096F / width, 2048F / height));
        ModernSkiaRaster.Image image = ModernSkiaRaster.render(Math.max(1, (int)Math.ceil(width * scale)),
            Math.max(1, (int)Math.ceil(height * scale)), canvas -> {
                try (Paint paint = new Paint().setColor(0xFFFFFFFF).setAntiAlias(true)) {
                    canvas.scale(scale, scale);
                    canvas.drawTextLine(line, -left, BASELINE - top, paint);
                }
            });
        return new Raster(image, left, top, scale);
    }

    private static void blit(GuiGraphicsExtractor g, Run run, int x, int y, int color) {
        int width = run.width, height = run.height;
        g.pose().pushMatrix();
        try {
            g.pose().translate(x + run.left, y + run.top);
            g.pose().scale(1 / run.scale, 1 / run.scale);
            // The textured-quad shader preserves coverage alpha; no vanilla text alpha cutoff.
            g.blit(RenderPipelines.GUI_TEXTURED, run.id, 0, 0, 0, 0, width, height, width, height, color);
        } finally {
            g.pose().popMatrix();
        }
    }

    private void trim() {
        var iterator = this.textures.entrySet().iterator();
        while (iterator.hasNext()) {
            Run run = iterator.next().getValue();
            // Extraction is deferred: keep current/recent frames alive until submitted draws finish.
            if (this.frame - run.lastFrame <= 2) continue;
            if (this.bytes <= CACHE_BYTES && this.textures.size() <= 256 && this.frame - run.lastFrame < 600) continue;
            Minecraft.getInstance().getTextureManager().release(run.id);
            this.bytes -= run.bytes;
            iterator.remove();
        }
    }

    record Raster(ModernSkiaRaster.Image image, int left, int top, float scale) {}
    private record Key(String text, int density) {}
    private static final class Run {
        final Identifier id;
        final int width, height, left, top;
        final float scale;
        final long bytes;
        long lastFrame;
        Run(Identifier id, Raster raster, long bytes, long lastFrame) {
            this.id = id;
            this.width = raster.image.getWidth();
            this.height = raster.image.getHeight();
            this.left = raster.left;
            this.top = raster.top;
            this.scale = raster.scale;
            this.bytes = bytes;
            this.lastFrame = lastFrame;
        }
    }

    @Override public void close() {
        this.layouts.values().forEach(TextLine::close);
        this.layouts.clear();
        this.shaper.close();
        this.font.close();
        this.face.close();
        if (!this.textures.isEmpty()) {
            this.textures.values().forEach(run -> Minecraft.getInstance().getTextureManager().release(run.id));
            this.textures.clear();
            this.bytes = 0;
        }
    }
}