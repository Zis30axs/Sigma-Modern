package com.mentalfrostbyte.jello.music.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.music.Track;
import org.junit.jupiter.api.Test;

/** Credit lines told apart from sung ones, and the lyricist and composer read out of them. */
class LyricCreditsTest {
    private static final Track TRACK = new Track("netease:1", "起风了", "买辣椒也用券", "起风了", "", 325_000L, null);

    private static Lyrics.Line line(String text) {
        return new Lyrics.Line(0L, 1_000L, text, java.util.List.of());
    }

    @Test
    void recognisesCreditsInTheShapesTheServicesUse() {
        // NetEase: JSON credit lines (parsed) and LRC ones; QQ: 词：/曲：; the title line.
        Lyrics netease = LyricsParser.lrc("{\"t\":0,\"c\":[{\"tx\":\"作词: \"},{\"tx\":\"米果\"}]}\n{\"t\":1000,\"c\":[{\"tx\":\"作曲: \"},{\"tx\":\"高桥优\"}]}\n"
            + "[00:02.00]编曲 : 刘胡轶\n[00:24.00]这一路上走走停停");
        assertTrue(LyricCredits.isCredit(netease.lines().get(0), TRACK));
        assertTrue(LyricCredits.isCredit(netease.lines().get(2), TRACK));
        assertFalse(LyricCredits.isCredit(netease.lines().get(3), TRACK), "a sung line");
        assertTrue(LyricCredits.isCredit(line("起风了 - 买辣椒也用券"), TRACK));
        assertTrue(LyricCredits.isCredit(line("词：林夕"), TRACK));
        assertTrue(LyricCredits.isCredit(line("Lyrics by : Max Martin"), TRACK));
        assertFalse(LyricCredits.isCredit(line("Baby: I love you"), TRACK), "a sung line with a colon");
        assertFalse(LyricCredits.isCredit(line("我曾难自拔于世界之大"), TRACK));
    }

    @Test
    void readsTheLyricistAndComposer() {
        Lyrics lyrics = LyricsParser.lrc("{\"t\":0,\"c\":[{\"tx\":\"作词: \"},{\"tx\":\"米果\"}]}\n{\"t\":1000,\"c\":[{\"tx\":\"作曲: \"},{\"tx\":\"高桥优\"}]}\n"
            + "[00:24.00]这一路上走走停停");
        LyricCredits.Credits credits = LyricCredits.credits(lyrics);
        assertEquals("米果", credits.lyricist());
        assertEquals("高桥优", credits.composer());
        assertTrue(LyricCredits.credits(LyricsParser.lrc("[00:01.00]just singing")).isEmpty());
        assertNull(LyricCredits.credits(LyricsParser.lrc("[00:00.00]Composer : Someone\n[00:05.00]la la")).lyricist());
    }
}
