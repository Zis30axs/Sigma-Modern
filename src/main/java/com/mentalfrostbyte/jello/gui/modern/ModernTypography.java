package com.mentalfrostbyte.jello.gui.modern;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Applies the same font to glyph rendering, measuring and clipping.
 *
 * <p>{@link Face#TEXT} (Anthropic Serif, text optical size) is the default for every UI string.
 * {@link Face#DISPLAY}/{@link Face#DISPLAY_ITALIC} are the display optical-size cuts, meant only for text
 * drawn several times larger than normal - the main menu's wordmark and similar headings.</p>
 */
public final class ModernTypography {
    public enum Face { TEXT, DISPLAY, DISPLAY_ITALIC }

    private static final ModernFontRenderer TEXT = new ModernFontRenderer();
    private static final ModernFontRenderer DISPLAY = new ModernFontRenderer(ModernFontRenderer.SERIF_DISPLAY);
    private static final ModernFontRenderer DISPLAY_ITALIC = new ModernFontRenderer(ModernFontRenderer.SERIF_DISPLAY_ITALIC);

    private ModernTypography() {}

    private static ModernFontRenderer renderer(Face face) {
        return switch (face) {
            case TEXT -> TEXT;
            case DISPLAY -> DISPLAY;
            case DISPLAY_ITALIC -> DISPLAY_ITALIC;
        };
    }

    public static void draw(GuiGraphicsExtractor g, String text, int x, int y, int color, boolean shadow) {
        TEXT.draw(g, text, x, y, color, shadow);
    }

    public static void draw(GuiGraphicsExtractor g, Component text, int x, int y, int color, boolean shadow) {
        TEXT.draw(g, text.getString(), x, y, color, shadow);
    }

    /**
     * Draws at a sub-pixel position and a scale through the pose stack. The renderer rasterizes for the
     * final device scale, so large text stays crisp - but keep {@code scale} fixed per element rather than
     * animating it, since every distinct scale is a separate cached raster.
     */
    public static void draw(GuiGraphicsExtractor g, Face face, String text, float x, float y, float scale, int color) {
        if ((color >>> 24) == 0) return;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        renderer(face).draw(g, text, 0, 0, color, false);
        g.pose().popMatrix();
    }

    /** {@link Face#TEXT} at a sub-pixel position and a scale, with the same drop shadow as the unscaled draw. */
    public static void draw(GuiGraphicsExtractor g, String text, float x, float y, float scale, int color, boolean shadow) {
        if ((color >>> 24) == 0) return;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        TEXT.draw(g, text, 0, 0, color, shadow);
        g.pose().popMatrix();
    }

    public static int width(String text) {
        return TEXT.width(text);
    }

    public static int width(Component text) {
        return TEXT.width(text.getString());
    }

    public static float width(Face face, String text, float scale) {
        return renderer(face).width(text) * scale;
    }

    public static String fit(String text, int width) {
        return TEXT.fit(text, width);
    }

    /** Like {@link #fit} but marks the cut with an ellipsis - for names and captions, not editable text. */
    public static String ellipsize(String text, int width) {
        if (TEXT.width(text) <= width) return text;
        int dots = TEXT.width("...");
        if (width <= dots) return TEXT.fit(text, width);
        return TEXT.fit(text, width - dots).stripTrailing() + "...";
    }

    /**
     * Greedy line wrapping in {@code face} at {@code scale}: breaks at spaces, and between any two CJK
     * characters (which have no spaces to break at). At most {@code maxLines} lines; if the text doesn't
     * fit, the last line ends in an ellipsis.
     */
    public static java.util.List<String> wrap(Face face, String text, float scale, float maxWidth, int maxLines) {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        StringBuilder word = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (isCjk(cp)) {
                if (!word.isEmpty()) { tokens.add(word.toString()); word.setLength(0); }
                tokens.add(new String(Character.toChars(cp)));
            } else {
                word.appendCodePoint(cp);
                if (cp == ' ') { tokens.add(word.toString()); word.setLength(0); }
            }
        });
        if (!word.isEmpty()) tokens.add(word.toString());

        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String candidate = line + token;
            if (line.isEmpty() || width(face, candidate.stripTrailing(), scale) <= maxWidth) {
                line.append(token);
                continue;
            }
            if (lines.size() == maxLines - 1) {
                // No room for another line: the rest goes on this one, cut with an ellipsis.
                StringBuilder rest = new StringBuilder(line);
                for (int j = i; j < tokens.size(); j++) rest.append(tokens.get(j));
                lines.add(ellipsize(face, rest.toString().strip(), scale, maxWidth));
                return lines;
            }
            lines.add(line.toString().strip());
            line.setLength(0);
            line.append(token.stripLeading());
        }
        if (!line.isEmpty()) lines.add(ellipsize(face, line.toString().strip(), scale, maxWidth));
        return lines;
    }

    private static String ellipsize(Face face, String text, float scale, float maxWidth) {
        if (width(face, text, scale) <= maxWidth) return text;
        int end = text.length();
        while (end > 0 && width(face, text.substring(0, end).stripTrailing() + "...", scale) > maxWidth) {
            end = text.offsetByCodePoints(end, -1);
        }
        return text.substring(0, end).stripTrailing() + "...";
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
            || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
            || (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0xFF00 && cp <= 0xFFEF);
    }

    /** Multiplies {@code alpha} (0..1) into an ARGB color's own alpha, for fade-in/out of anything drawn. */
    public static int fade(int argb, float alpha) {
        alpha = Math.max(0, Math.min(1, alpha));
        return Math.round((argb >>> 24) * alpha) << 24 | (argb & 0x00FFFFFF);
    }
}
