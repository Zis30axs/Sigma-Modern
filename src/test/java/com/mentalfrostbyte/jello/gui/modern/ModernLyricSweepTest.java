package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mentalfrostbyte.jello.music.lyrics.Lyrics;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModernLyricSweepTest {
    // Line 0 is sung 1.0-2.0 s, then a 1 s pause; line 1 is sung 3.0-4.0 s, then a 6 s instrumental break.
    private static final Lyrics LYRICS = new Lyrics(Lyrics.Kind.WORD, List.of(
        new Lyrics.Line(1_000L, 2_000L, "abcd", List.of(new Lyrics.Word(1_000L, 500L, "ab"), new Lyrics.Word(1_500L, 500L, "cd"))),
        new Lyrics.Line(3_000L, 4_000L, "ef", List.of(new Lyrics.Word(3_000L, 1_000L, "ef"))),
        new Lyrics.Line(10_000L, 11_000L, "gh", List.of(new Lyrics.Word(10_000L, 1_000L, "gh")))));

    @Test
    void sweepsThroughTheWordsBeingSung() {
        assertEquals(0.5F, ModernLyricSweep.progress(LYRICS, 0, 1_500L), 1e-4F);
        assertEquals(0.75F, ModernLyricSweep.progress(LYRICS, 0, 1_750L), 1e-4F);
    }

    @Test
    void holdsAFinishedLineThroughAShortPause() {
        assertEquals(1F, ModernLyricSweep.progress(LYRICS, 0, 2_500L));
    }

    @Test
    void letsTheLineGoForALongBreakAndAfterTheLast() {
        assertEquals(-1F, ModernLyricSweep.progress(LYRICS, 1, 4_500L));
        assertEquals(-1F, ModernLyricSweep.progress(LYRICS, 2, 11_500L));
    }

    @Test
    void lineTimedLyricsLightTheWholeLine() {
        Lyrics lrc = new Lyrics(Lyrics.Kind.LINE, List.of(new Lyrics.Line(1_000L, 5_000L, "line", List.of())));
        assertEquals(1F, ModernLyricSweep.progress(lrc, 0, 1_200L));
        assertEquals(-1F, ModernLyricSweep.progress(lrc, 0, 900L));
    }
}
