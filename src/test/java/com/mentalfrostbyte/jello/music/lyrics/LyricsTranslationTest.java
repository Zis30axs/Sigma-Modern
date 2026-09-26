package com.mentalfrostbyte.jello.music.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Translations and romanizations hung on lyric lines, and what each language setting shows. */
class LyricsTranslationTest {
    // Shaped like NetEase's reply for Lemon (lrc, tlyric and romalrc share their time tags).
    private static final String LRC = "[00:25.820]戻らない幸せがあることを\n[00:31.580]最後にあなたが教えてくれた\n[00:40.000]言えずに隠してた昏い過去も";
    private static final String TLYRIC = "[00:25.820]有着无法挽回的幸福\n[00:31.580]最终是你告诉我\n[00:40.000]//";
    private static final String ROMALRC = "[00:25.820]mo do ra na i shi a wa se ga a ru ko to wo\n[00:31.580]sa i go ni a na ta ga o shi e te ku re ta";

    @Test
    void netEaseTranslationsMatchTheirLinesByTime() {
        Lyrics lyrics = LyricsParser.attach(LyricsParser.lrc(LRC), LyricsParser.lrc(TLYRIC), LyricsParser.lrc(ROMALRC));
        assertEquals("有着无法挽回的幸福", lyrics.lines().get(0).translation());
        assertEquals("最终是你告诉我", lyrics.lines().get(1).translation());
        assertNull(lyrics.lines().get(2).translation(), "a // placeholder is no translation");
        assertEquals("mo do ra na i shi a wa se ga a ru ko to wo", lyrics.lines().get(0).romanization());
        assertNull(lyrics.lines().get(2).romanization());
    }

    @Test
    void qqTranslationsMatchWordTimedLinesToTheCentisecond() {
        // QQ: the QRC counts milliseconds, its translation (contentts) is LRC in hundredths.
        Lyrics qrc = LyricsParser.qrc("[2880,4002]ど(2880,304)れ(3184,352)\n[6882,5528]未(6882,376)だ(7258,576)");
        Lyrics lyrics = LyricsParser.attach(qrc, LyricsParser.lrc("[00:02.88]那该有多好\n[00:06.88]你依旧出现在我梦里"),
            LyricsParser.qrc("[2880,4001]do (2880,303)re (3184,351)\n[6881,5527]i (6881,184)ma (7065,191)"));
        assertEquals(Lyrics.Kind.WORD, lyrics.kind());
        assertEquals("那该有多好", lyrics.lines().get(0).translation());
        assertEquals("你依旧出现在我梦里", lyrics.lines().get(1).translation());
        assertEquals("do re", lyrics.lines().get(0).romanization());
        assertEquals(2, lyrics.lines().get(0).words().size(), "word timing survives");
    }

    @Test
    void aTranslationTooFarFromEveryLineIsLeftOut() {
        Lyrics lyrics = LyricsParser.attach(LyricsParser.lrc("[00:10.00]a\n[00:20.00]b"), LyricsParser.lrc("[00:15.00]between"), null);
        assertNull(lyrics.lines().get(0).translation());
        assertNull(lyrics.lines().get(1).translation());
    }

    @Test
    void aSecondSourceOnlyFillsTheGaps() {
        Lyrics own = LyricsParser.attach(LyricsParser.lrc("[00:10.00]a\n[00:20.00]b"), LyricsParser.lrc("[00:10.00]甲"), null);
        Lyrics filled = LyricsParser.attach(own, LyricsParser.lrc("[00:10.00]别的\n[00:20.00]乙"), null);
        assertEquals("甲", filled.lines().get(0).translation(), "the lyrics' own service wins");
        assertEquals("乙", filled.lines().get(1).translation());
    }

    @Test
    void translationOnlyReadsTranslatedLinesAsTheirTranslation() {
        Lyrics word = LyricsParser.attach(LyricsParser.yrc("[1000,2000](1000,1000,0)Hel(2000,1000,0)lo\n[4000,1000](4000,1000,0)World"),
            LyricsParser.lrc("[00:01.00]你好"), null);
        Lyrics shown = LyricsService.show(word, LyricsService.Mode.AUTO, LyricsService.Language.TRANSLATION_ONLY);
        assertEquals("你好", shown.lines().get(0).text());
        assertTrue(shown.lines().get(0).words().isEmpty(), "a translation has no word timing: it lights whole");
        assertEquals("World", shown.lines().get(1).text());
        assertEquals(1, shown.lines().get(1).words().size());
        assertSame(word, LyricsService.show(word, LyricsService.Mode.AUTO, LyricsService.Language.TRANSLATION));
    }

    @Test
    void lineModeKeepsTheTranslations() {
        Lyrics word = LyricsParser.attach(LyricsParser.yrc("[1000,2000](1000,1000,0)Hel(2000,1000,0)lo"), LyricsParser.lrc("[00:01.00]你好"), null);
        Lyrics shown = LyricsService.show(word, LyricsService.Mode.LINE, LyricsService.Language.TRANSLATION);
        assertEquals(Lyrics.Kind.LINE, shown.kind());
        assertEquals("你好", shown.lines().getFirst().translation());
        assertEquals("你好", LyricsService.extra(shown.lines().getFirst(), LyricsService.Language.TRANSLATION));
        assertNull(LyricsService.extra(shown.lines().getFirst(), LyricsService.Language.ORIGINAL));
        assertEquals(List.of(), shown.lines().getFirst().words());
    }
}
