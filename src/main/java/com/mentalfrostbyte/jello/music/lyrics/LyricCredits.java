package com.mentalfrostbyte.jello.music.lyrics;

import com.mentalfrostbyte.jello.music.Track;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The credits lyrics usually open with - "title - artist", then "作词 : 米果", "作曲 : 高桥优", "编曲 : ..." (NetEase's
 * JSON credit lines read the same once parsed; QQ writes "词：..." / "曲：...") - told apart from sung lines, and the
 * lyricist and composer read out of them.
 *
 * <p>A credit is a "role : name" line whose role is a known one (作词, 作曲, 编曲, 制作人, ... or Lyrics, Composer,
 * Producer, ...) or a short all-Chinese one, or the "title - artist" line. Lines like "Baby: I love you" are sung,
 * not credits: their "role" is neither.</p>
 */
public final class LyricCredits {
    /** Who wrote the words and the music, when the lyrics say; {@code null} for what they don't. */
    public record Credits(@Nullable String lyricist, @Nullable String composer) {
        public boolean isEmpty() {
            return this.lyricist == null && this.composer == null;
        }
    }

    private static final Pattern ROLE = Pattern.compile("^\\s*([^:：]{1,16}?)\\s*[:：]\\s*(.+?)\\s*$");
    private static final Set<String> LYRICIST = Set.of("作词", "词", "作詞", "詞", "填词", "作词人", "lyrics", "lyricist", "lyricsby", "writtenby", "words");
    private static final Set<String> COMPOSER = Set.of("作曲", "曲", "作曲人", "composer", "composedby", "music", "musicby");
    private static final Set<String> OTHER_ROLES = Set.of("arranger", "arrangedby", "producer", "producedby", "vocals", "vocal", "mixedby",
        "masteredby", "recordedby", "mixing", "mastering", "recording", "guitar", "bass", "drums", "strings", "op", "sp", "isrc");

    private LyricCredits() {}

    /** Whether {@code line} is a credit (or the "title - artist" line) rather than something sung. */
    public static boolean isCredit(Lyrics.Line line, @Nullable Track track) {
        String text = line.text().strip();
        if (text.isEmpty()) return false;
        if (track != null && !track.title().isBlank() && text.startsWith(track.title()) && text.contains(" - ")) return true;
        Matcher m = ROLE.matcher(text);
        return m.matches() && isRole(m.group(1));
    }

    /** The lyricist and composer named in {@code lyrics}' credit lines (the first of each). */
    public static Credits credits(Lyrics lyrics) {
        String lyricist = null, composer = null;
        for (Lyrics.Line line : lyrics.lines()) {
            Matcher m = ROLE.matcher(line.text().strip());
            if (!m.matches()) continue;
            String role = key(m.group(1)), name = m.group(2).strip();
            if (name.isEmpty()) continue;
            if (lyricist == null && LYRICIST.contains(role)) lyricist = name;
            else if (composer == null && COMPOSER.contains(role)) composer = name;
            if (lyricist != null && composer != null) break;
        }
        return new Credits(lyricist, composer);
    }

    private static boolean isRole(String role) {
        String key = key(role);
        if (LYRICIST.contains(key) || COMPOSER.contains(key) || OTHER_ROLES.contains(key)) return true;
        // Chinese credits name many more roles (编曲, 制作人, 和声, 混音, 监制, 出品...): any short all-Chinese one.
        return key.length() <= 6 && key.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    private static String key(String role) {
        return role.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
