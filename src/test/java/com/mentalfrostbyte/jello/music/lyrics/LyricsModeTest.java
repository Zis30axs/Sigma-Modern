package com.mentalfrostbyte.jello.music.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** What each lyric mode shows of word-timed and line-timed lyrics. */
class LyricsModeTest {
    private static final Lyrics WORD = new Lyrics(Lyrics.Kind.WORD, List.of(
        new Lyrics.Line(1_000L, 3_000L, "ab", List.of(new Lyrics.Word(1_000L, 1_000L, "a"), new Lyrics.Word(2_000L, 1_000L, "b")))));
    private static final Lyrics LINE = new Lyrics(Lyrics.Kind.LINE, List.of(new Lyrics.Line(1_000L, 3_000L, "ab", List.of())));

    @Test
    void autoShowsWhateverThereIs() {
        assertSame(WORD, LyricsService.show(WORD, LyricsService.Mode.AUTO));
        assertSame(LINE, LyricsService.show(LINE, LyricsService.Mode.AUTO));
    }

    @Test
    void lineModeDropsWordTimingButKeepsTheLines() {
        Lyrics shown = LyricsService.show(WORD, LyricsService.Mode.LINE);
        assertEquals(Lyrics.Kind.LINE, shown.kind());
        assertEquals(1, shown.lines().size());
        assertEquals("ab", shown.lines().getFirst().text());
        assertEquals(3_000L, shown.lines().getFirst().endMs());
        assertTrue(shown.lines().getFirst().words().isEmpty());
        assertSame(LINE, LyricsService.show(LINE, LyricsService.Mode.LINE));
    }

    @Test
    void wordModeShowsOnlyWordTimedLyrics() {
        assertSame(WORD, LyricsService.show(WORD, LyricsService.Mode.WORD));
        assertFalse(LyricsService.show(LINE, LyricsService.Mode.WORD).hasLines());
        assertSame(Lyrics.INSTRUMENTAL, LyricsService.show(Lyrics.INSTRUMENTAL, LyricsService.Mode.WORD));
    }
}
