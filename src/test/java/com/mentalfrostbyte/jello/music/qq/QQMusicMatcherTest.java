package com.mentalfrostbyte.jello.music.qq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class QQMusicMatcherTest {
    private static QQMusicApi.QQTrack track(long id, String name, String artist, long ms) {
        return new QQMusicApi.QQTrack(id, "", name, artist, "", ms);
    }

    @Test
    void picksTheSameSongOverCoversAndOtherVersions() {
        List<QQMusicApi.QQTrack> candidates = List.of(
            track(1, "起风了 (吉他版)", "某翻唱", 200_000L),
            track(2, "起风了", "买辣椒也用券", 325_000L),
            track(3, "起风了", "周深", 310_000L));
        QQMusicMatcher.Match match = QQMusicMatcher.match(candidates, "起风了", "买辣椒也用券", 325_868L);
        assertEquals(2L, match.track().songId());
    }

    @Test
    void rejectsWhenNothingIsCloseEnough() {
        assertNull(QQMusicMatcher.match(List.of(track(9, "完全不同的歌", "别人", 90_000L)), "起风了", "买辣椒也用券", 325_000L));
        assertNull(QQMusicMatcher.match(List.of(), "起风了", "", 0L));
    }

    @Test
    void normalizeDropsBracketedNotesAndPunctuation() {
        assertEquals("起风了", QQMusicMatcher.normalize("起风了 (Live版)"));
        assertEquals("jaychou", QQMusicMatcher.normalize("Jay-Chou"));
    }
}
