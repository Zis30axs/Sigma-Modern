package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.music.MusicPlayer;
import com.mentalfrostbyte.jello.music.Track;
import com.mentalfrostbyte.jello.music.lyrics.LyricCredits;
import com.mentalfrostbyte.jello.music.lyrics.Lyrics;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * Karaoke text: a lyric line drawn dim with the sung part swept over it in a bright color. Word-timed lyrics
 * (NetEase YRC, QQ Music QRC) sweep through each word as it is sung; line-timed ones (LRC) light the whole
 * line at once. The sweep's edge moves through a glyph rather than jumping from one to the next.
 */
final class ModernLyricSweep {
    private ModernLyricSweep() {}

    /** The line being sung: {@code progress} is 0..1 through it (always 1 for line-timed lyrics). */
    record Now(Lyrics lyrics, int index, Lyrics.Line line, float progress) {}

    /**
     * What {@code player}'s current track is singing now, or {@code null} between lines, without lyrics, and on the
     * credit lines lyrics open with ("title - artist", "作词 : ...") - those aren't sung; small displays show the
     * track's details for them instead.
     */
    static @Nullable Now now(MusicPlayer player) {
        Track track = player.current();
        if (track == null) return null;
        Lyrics lyrics = Client.getInstance().getMusicLibrary().lyrics().get(track);
        if (!lyrics.hasLines()) return null;
        long position = player.positionMs();
        int index = lyrics.indexAt(position);
        if (index < 0) return null;
        Lyrics.Line line = lyrics.lines().get(index);
        float progress = progress(lyrics, index, position);
        if (line.text().isBlank() || progress < 0F || LyricCredits.isCredit(line, track)) return null;
        return new Now(lyrics, index, line, progress);
    }

    /** Past this, the gap before the next line counts as a break and the finished line is let go. */
    private static final long HOLD_MS = 2_500L;

    /**
     * How far line {@code index} is sung at {@code position} (1 for line-timed lyrics), or -1 once it is over. A
     * finished line stays fully lit through a short pause before the next, so displays don't blink in between.
     */
    static float progress(Lyrics lyrics, int index, long position) {
        Lyrics.Line line = lyrics.lines().get(index);
        if (position < line.startMs()) return -1F;
        if (position >= line.endMs()) {
            boolean last = index + 1 >= lyrics.lines().size();
            long nextStart = last ? Long.MAX_VALUE : lyrics.lines().get(index + 1).startMs();
            if (nextStart - line.endMs() > HOLD_MS || position - line.endMs() > HOLD_MS) return -1F;
            return 1F;
        }
        return lyrics.kind() == Lyrics.Kind.WORD ? line.progress(position) : 1F;
    }

    /**
     * Where the sweep's edge falls in {@code text} with {@code lit} (0..1 of its characters) sung: its distance from
     * the text's start, moving through a glyph rather than jumping from one to the next.
     */
    static float edgeX(ModernTypography.Face face, String text, float scale, float lit) {
        lit = Math.max(0F, Math.min(1F, lit));
        float chars = lit * text.length();
        int whole = Math.min(text.length(), (int)chars);
        int next = whole;
        if (next < text.length()) next += Character.isHighSurrogate(text.charAt(next)) && next + 1 < text.length() ? 2 : 1;
        float before = ModernTypography.width(face, text.substring(0, whole), scale);
        float glyph = ModernTypography.width(face, text.substring(0, next), scale) - before;
        return before + glyph * (chars - whole) / Math.max(1, next - whole);
    }

    /**
     * Draws {@code text} in {@code dim}, then its first {@code lit} (0..1 of its characters) again in
     * {@code bright}, clipped at the sweep. The pixel column the edge falls in is drawn at a partial alpha, so the
     * sweep glides at sub-pixel speed instead of stepping a whole GUI pixel at a time.
     */
    static void draw(GuiGraphicsExtractor g, ModernTypography.Face face, String text, float x, float y, float scale,
                     float lit, int dim, int bright) {
        if (text.isEmpty()) return;
        lit = Math.max(0F, Math.min(1F, lit));
        if (lit >= 1F) {
            ModernTypography.draw(g, face, text, x, y, scale, bright);
            return;
        }
        ModernTypography.draw(g, face, text, x, y, scale, dim);
        if (lit <= 0F) return;

        float edge = x + edgeX(face, text, scale, lit);
        int left = (int)Math.floor(x - 2F), top = (int)Math.floor(y - 3F), bottom = (int)Math.ceil(y + 12F * scale + 3F);
        int full = (int)Math.floor(edge);
        if (full > left) {
            g.enableScissor(left, top, full, bottom);
            try {
                ModernTypography.draw(g, face, text, x, y, scale, bright);
            } finally {
                g.disableScissor();
            }
        }
        float partial = edge - full;
        if (partial > 0.02F) {
            g.enableScissor(full, top, full + 1, bottom);
            try {
                ModernTypography.draw(g, face, text, x, y, scale, ModernStyle.mix(dim, bright, partial));
            } finally {
                g.disableScissor();
            }
        }
    }
}
