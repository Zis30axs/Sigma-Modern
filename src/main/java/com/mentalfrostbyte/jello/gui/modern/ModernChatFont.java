package com.mentalfrostbyte.jello.gui.modern;

import com.mojang.blaze3d.font.GlyphInfo;
import java.util.function.DoubleSupplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * A vanilla {@link Font} measuring in Anthropic Serif at the chat's text size, for the chat {@link ModernChat}
 * sets in that face.
 *
 * <p>Everything vanilla lays out with a font - the chat's line wrapping, the input box's scrolling, caret and
 * click-to-position, the suggestion list's x and width - keeps doing so, through this font; {@link ModernChat}
 * then draws the same text with Skia, at {@link #scale()}. The default font's advances come from the chat's
 * Skia face (see {@link ModernFontRenderer#PLAIN}), so the drawn runs land exactly where vanilla measured them.
 * Characters Skia doesn't draw - resource-pack glyphs in the private use areas, other fonts such as {@code alt}
 * or the player-head sprites - keep vanilla's advance (scaled like the rest) and are drawn by vanilla.</p>
 *
 * <p>If vanilla code ever draws with this font, it gets vanilla's glyphs at serif spacing: legible, never
 * blank.</p>
 */
public final class ModernChatFont extends Font {
    private final Font vanilla;
    private final DoubleSupplier scale;

    /** {@code scale} is read live, so a changed text size shows on the next frame. */
    ModernChatFont(Font vanilla, DoubleSupplier scale) {
        super(metrics(vanilla.provider(), scale));
        this.vanilla = vanilla;
        this.scale = scale;
    }

    /** The font this one measures over; what text Modern doesn't draw itself (the IME's composition, say) uses. */
    public Font vanilla() {
        return this.vanilla;
    }

    /** How much larger than Modern's 11px text this font lays out and is drawn. */
    float scale() {
        return (float)this.scale.getAsDouble();
    }

    /** The advance {@code codepoint} in {@code style} is laid out with - the one width every chat layout comes from. */
    float advance(int codepoint, Style style) {
        return serifMetrics(codepoint, style) ? serifAdvance(codepoint) * scale()
            : this.getSplitter().stringWidth(FormattedCharSequence.codepoint(codepoint, style));
    }

    /** True when {@code codepoint} in {@code style} is drawn by Skia rather than by vanilla. */
    static boolean drawsSerif(int codepoint, Style style) {
        return serifMetrics(codepoint, style) && !style.isObfuscated();
    }

    /** Measured in the serif - obfuscated text too, since the layout can't tell it apart. */
    private static boolean serifMetrics(int codepoint, Style style) {
        return style.getFont().equals(FontDescription.DEFAULT) && !privateUse(codepoint);
    }

    private static float serifAdvance(int codepoint) {
        return ModernChat.face(false, false).advance(codepoint);
    }

    private static boolean privateUse(int codepoint) {
        return codepoint >= 0xE000 && codepoint <= 0xF8FF || codepoint >= 0xF0000;
    }

    private static Font.Provider metrics(Font.Provider vanilla, DoubleSupplier scale) {
        return new Font.Provider() {
            @Override
            public GlyphSource glyphs(FontDescription font) {
                return new MetricGlyphs(vanilla.glyphs(font), font.equals(FontDescription.DEFAULT), scale);
            }

            @Override
            public EffectGlyph effect() {
                return vanilla.effect();
            }
        };
    }

    /**
     * Vanilla's glyphs with the chat's advances. Nothing is cached: vanilla's glyphs are rebuilt on a resource
     * reload, and the serif advances are already cached by the renderer.
     */
    private record MetricGlyphs(GlyphSource vanilla, boolean defaultFont, DoubleSupplier scale) implements GlyphSource {
        @Override
        public BakedGlyph getGlyph(int codepoint) {
            BakedGlyph glyph = this.vanilla.getGlyph(codepoint);
            float scale = (float)this.scale.getAsDouble();
            // Skia's synthesized bold doesn't widen a glyph, so neither does the layout.
            if (this.defaultFont && !privateUse(codepoint)) return new MetricGlyph(glyph, serifAdvance(codepoint) * scale, 0F);
            if (scale == 1F) return glyph;
            GlyphInfo info = glyph.info();
            return new MetricGlyph(glyph, info.getAdvance() * scale, info.getBoldOffset() * scale);
        }

        @Override
        public BakedGlyph getRandomGlyph(RandomSource random, int width) {
            return this.vanilla.getRandomGlyph(random, width);
        }
    }

    private record MetricGlyph(BakedGlyph vanilla, float advance, float boldOffset) implements BakedGlyph, GlyphInfo {
        @Override
        public GlyphInfo info() {
            return this;
        }

        @Override
        public float getAdvance() {
            return this.advance;
        }

        @Override
        public float getBoldOffset() {
            return this.boldOffset;
        }

        @Override
        public TextRenderable.@Nullable Styled createGlyph(float x, float y, int color, int shadowColor, Style style, float boldOffset, float shadowOffset) {
            return this.vanilla.createGlyph(x, y, color, shadowColor, style, boldOffset, shadowOffset);
        }
    }
}
