package com.mentalfrostbyte.jello.music.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The three lyric formats, on real (trimmed) replies from NetEase and QQ Music captured for these tests. */
class LyricsParserTest {
    private static String fixture(String name) throws IOException {
        try (InputStream in = LyricsParserTest.class.getResourceAsStream("/music/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void neteaseLrcKeepsCreditsAndLineTimes() throws IOException {
        Lyrics lyrics = LyricsParser.lrc(fixture("netease_lrc.txt"));
        assertEquals(Lyrics.Kind.LINE, lyrics.kind());
        assertEquals(6, lyrics.lines().size());
        assertEquals("作曲: 高桥优", lyrics.lines().get(0).text());
        assertEquals(24_969L, lyrics.lines().get(2).startMs());
        assertEquals("这一路上走走停停", lyrics.lines().get(2).text());
        assertEquals(28_070L, lyrics.lines().get(2).endMs(), "a line lasts until the next one starts");
    }

    @Test
    void neteaseYrcHasWordTimingsBeforeEachWord() throws IOException {
        Lyrics lyrics = LyricsParser.yrc(fixture("netease_yrc.txt"));
        assertEquals(Lyrics.Kind.WORD, lyrics.kind());
        Lyrics.Line line = lyrics.lines().get(2);
        assertEquals(24_810L, line.startMs());
        assertEquals("这一路上走走停停", line.text());
        assertEquals(8, line.words().size());
        assertEquals(new Lyrics.Word(24_810L, 630L, "这"), line.words().get(0));
        assertEquals(new Lyrics.Word(27_600L, 360L, "停"), line.words().get(7));
        assertEquals(0F, line.progress(24_000L));
        assertEquals(1F, line.progress(28_000L));
        assertEquals(0.5F, line.progress(26_490L), 0.01F, "four of eight characters sung");
    }

    @Test
    void qqQrcIsUnwrappedFromXmlWithTimingsAfterEachWord() throws IOException {
        Lyrics lyrics = LyricsParser.qrc(fixture("qq_qrc.txt"));
        assertEquals(Lyrics.Kind.WORD, lyrics.kind());
        assertEquals(4, lyrics.lines().size(), "metadata tags ([ti:], [offset:]) are skipped");
        Lyrics.Line first = lyrics.lines().get(0);
        assertEquals(0L, first.startMs());
        assertEquals(new Lyrics.Word(0L, 1_827L, "起"), first.words().get(0));
        assertEquals("起风了 - 买辣椒也用券", first.text());
        assertEquals("编曲：池洼浩一 (Kouichi Ikekubo)", lyrics.lines().get(3).text());
    }

    @Test
    void lrcHandlesRepeatedTagsShortTimesAndOffset() {
        Lyrics lyrics = LyricsParser.lrc("[ti:Song]\n[offset:500]\n[00:10][01:20.5]chorus\n[00:30.123]verse\n");
        assertEquals(3, lyrics.lines().size());
        assertEquals(9_500L, lyrics.lines().get(0).startMs(), "a positive offset shows lines earlier");
        assertEquals("chorus", lyrics.lines().get(0).text());
        assertEquals(29_623L, lyrics.lines().get(1).startMs());
        assertEquals(80_000L, lyrics.lines().get(2).startMs(), "01:20.5 is 80.5 s, minus the offset");
        assertEquals("chorus", lyrics.lines().get(2).text());
    }

    @Test
    void indexAtFindsTheLastStartedLine() throws IOException {
        Lyrics lyrics = LyricsParser.lrc(fixture("netease_lrc.txt"));
        assertEquals(-1, new Lyrics(Lyrics.Kind.LINE, lyrics.lines().subList(2, 6)).indexAt(1_000L));
        assertEquals(2, lyrics.indexAt(25_000L));
        assertEquals(5, lyrics.indexAt(999_999L));
    }

    @Test
    void garbageNeverThrows() {
        assertSame(Lyrics.NONE, LyricsParser.lrc(""));
        assertSame(Lyrics.NONE, LyricsParser.yrc("not lyrics at all"));
        assertSame(Lyrics.NONE, LyricsParser.qrc("<xml LyricContent=\"[ti:x]\"/>"));
        assertTrue(LyricsParser.lrc("{broken json\n[00:01.00]ok").hasLines());
    }
}
