package com.mentalfrostbyte.jello.music.lyrics;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Timed lyrics for one track. {@link Kind#WORD} lines carry per-word timings (NetEase YRC, QQ Music QRC) for a
 * karaoke sweep; {@link Kind#LINE} lines only have a start time (LRC), so interfaces light the whole line. A line
 * may carry a translation and a romanization (from the same service, matched by time) to show under it.
 */
public record Lyrics(Kind kind, List<Line> lines) {
    public enum Kind {
        /** Nothing found (yet, or at all). */
        NONE,
        /** The source says the track is instrumental. */
        INSTRUMENTAL,
        LINE,
        WORD
    }

    public static final Lyrics NONE = new Lyrics(Kind.NONE, List.of());
    public static final Lyrics INSTRUMENTAL = new Lyrics(Kind.INSTRUMENTAL, List.of());

    /**
     * @param endMs when the line stops being current: the next line's start, or for the last line the end of its
     *              last word (or its start plus a few seconds for line-timed lyrics)
     * @param words empty for line-timed lyrics
     * @param translation the line in Chinese (NetEase {@code tlyric}, QQ {@code contentts}), when there is one
     * @param romanization how it sounds, in Latin letters (NetEase {@code romalrc}, QQ {@code contentroma})
     */
    public record Line(long startMs, long endMs, String text, List<Word> words, @Nullable String translation, @Nullable String romanization) {
        public Line(long startMs, long endMs, String text, List<Word> words) {
            this(startMs, endMs, text, words, null, null);
        }

        public Line withExtras(@Nullable String translation, @Nullable String romanization) {
            return new Line(this.startMs, this.endMs, this.text, this.words, translation, romanization);
        }

        /** 0..1 through this line's words at {@code positionMs}; for line-timed lines 1 once it has started. */
        public float progress(long positionMs) {
            if (this.words.isEmpty()) return positionMs >= this.startMs ? 1F : 0F;
            float sung = 0F;
            int total = 0;
            for (Word word : this.words) total += word.text().length();
            if (total == 0) return 0F;
            for (Word word : this.words) {
                int len = word.text().length();
                if (positionMs >= word.startMs() + word.durationMs()) sung += len;
                else if (positionMs > word.startMs()) sung += len * (positionMs - word.startMs()) / (float)Math.max(1L, word.durationMs());
            }
            return Math.min(1F, sung / total);
        }
    }

    public record Word(long startMs, long durationMs, String text) {}

    public boolean hasLines() {
        return !this.lines.isEmpty();
    }

    /** Index of the line current at {@code positionMs} (the last one that has started), or -1 before the first. */
    public int indexAt(long positionMs) {
        int lo = 0, hi = this.lines.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (this.lines.get(mid).startMs() <= positionMs) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }
}
