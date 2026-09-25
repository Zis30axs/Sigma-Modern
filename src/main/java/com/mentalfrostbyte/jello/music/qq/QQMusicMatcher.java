package com.mentalfrostbyte.jello.music.qq;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Picks the QQ Music search result that is really the song playing (ported from SigmaClient). Titles rarely
 * match exactly across services - covers, live versions, featured artists, bracketed notes - so candidates are
 * scored on title (0.5), artist (0.25) and duration (0.25); below {@link #MIN_SCORE} nothing is accepted, so a
 * wrong song's lyrics are never shown.
 */
public final class QQMusicMatcher {
    static final double MIN_SCORE = 0.55;

    public record Match(QQMusicApi.QQTrack track, double score) {}

    private QQMusicMatcher() {}

    public static @Nullable Match match(List<QQMusicApi.QQTrack> candidates, String title, String artist, long durationMs) {
        Match best = null;
        for (QQMusicApi.QQTrack candidate : candidates) {
            double score = score(candidate, title, artist, durationMs);
            if (best == null || score > best.score()) best = new Match(candidate, score);
        }
        return best == null || best.score() < MIN_SCORE ? null : best;
    }

    static double score(QQMusicApi.QQTrack candidate, String title, String artist, long durationMs) {
        double titleScore = similarity(normalize(title), normalize(candidate.name()));
        String a = normalize(artist), b = normalize(candidate.artist());
        // "Jay Chou" vs "Jay Chou/Fei Yu-ching": one containing the other counts as the same artist.
        double artistScore = a.isEmpty() || b.isEmpty() ? 0.5 : a.contains(b) || b.contains(a) ? 1.0 : similarity(a, b);
        double durationScore;
        if (durationMs <= 0 || candidate.durationMs() <= 0) {
            durationScore = 0.5;
        } else {
            long diff = Math.abs(durationMs - candidate.durationMs());
            durationScore = diff <= 3_000L ? 1.0 : diff >= 15_000L ? 0.0 : 1.0 - (diff - 3_000L) / 12_000.0;
        }
        return 0.5 * titleScore + 0.25 * artistScore + 0.25 * durationScore;
    }

    /** Lower case, bracketed notes ("(Live)", "（伴奏）") and whitespace/punctuation removed. */
    static String normalize(@Nullable String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
            .replaceAll("[\\(（\\[【].*?[\\)）\\]】]", "")
            .replaceAll("[\\s\\-_·,，.。!！?？'\"/]", "")
            .trim();
    }

    /** 1 - Levenshtein distance / longer length. */
    static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int[] prev = new int[b.length() + 1], curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return 1.0 - (double)prev[b.length()] / Math.max(a.length(), b.length());
    }
}
