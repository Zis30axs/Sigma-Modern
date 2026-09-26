package com.mentalfrostbyte.jello.music.lyrics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Parses the three lyric formats the music player meets, in the shapes the live services return them:
 * <ul>
 *   <li><b>LRC</b> (NetEase {@code lrc}): {@code [mm:ss.xx]text}; also {@code [mm:ss]}, three-digit fractions,
 *       several time tags on one line and {@code [offset:±ms]}.</li>
 *   <li><b>YRC</b> (NetEase {@code yrc}, from {@code /api/song/lyric/v1}):
 *       {@code [lineStart,lineDur](wordStart,wordDur,0)word(wordStart,wordDur,0)word...} - each word's timing
 *       comes <em>before</em> it.</li>
 *   <li><b>QRC</b> (QQ Music, decrypted): {@code [lineStart,lineDur]word(wordStart,wordDur)word(...)} - each
 *       timing comes <em>after</em> its word - usually wrapped in XML as {@code LyricContent="..."}.</li>
 * </ul>
 * NetEase puts credits first as JSON lines ({@code {"t":0,"c":[{"tx":"作曲: "},{"tx":"..."}]}}); they become plain
 * lines. Metadata tags ({@code [ti:...]}) are skipped. Parsers never throw on bad input: they return what they
 * could read. {@link #attach} hangs translations and romanizations (lyrics of any of these formats) on the lines
 * they belong to.
 */
public final class LyricsParser {
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("^\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);
    private static final Pattern META = Pattern.compile("^\\[[a-zA-Z#]+:.*]\\s*$");
    private static final Pattern LINE_HEADER = Pattern.compile("^\\[(\\d+),(\\d+)]");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+)(?:,-?\\d+)?\\)([^(]*)");
    private static final Pattern QRC_WORD = Pattern.compile("(.*?)\\((\\d+),(\\d+)\\)");
    private static final long LINE_TAIL_MS = 4_000L;
    /** How far apart a line and its translation may start and still be taken for each other. */
    private static final long MATCH_MS = 1_200L;

    private LyricsParser() {}

    // --- LRC ------------------------------------------------------------------------------------------

    public static Lyrics lrc(String text) {
        if (text == null || text.isBlank()) return Lyrics.NONE;
        long offset = 0L;
        List<Raw> raw = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            Matcher offsetMatch = OFFSET.matcher(line);
            if (offsetMatch.find()) {
                offset = Long.parseLong(offsetMatch.group(1));
                continue;
            }
            if (line.startsWith("{")) {
                Raw credit = credit(line);
                if (credit != null) raw.add(credit);
                continue;
            }
            Matcher time = LRC_TIME.matcher(line);
            List<Long> starts = new ArrayList<>();
            int end = 0;
            while (time.find() && time.start() == end) {
                long ms = Long.parseLong(time.group(1)) * 60_000L + Long.parseLong(time.group(2)) * 1_000L;
                String fraction = time.group(3);
                if (fraction != null) ms += Long.parseLong(fraction) * (fraction.length() == 1 ? 100L : fraction.length() == 2 ? 10L : 1L);
                starts.add(ms);
                end = time.end();
            }
            if (starts.isEmpty()) continue;
            String content = line.substring(end).trim();
            if (content.isEmpty()) continue;
            for (long start : starts) raw.add(new Raw(start, -1L, content, List.of()));
        }
        // An LRC offset is added to the displayed time: positive shows lines earlier.
        final long shift = -offset;
        raw.replaceAll(r -> new Raw(Math.max(0L, r.start + shift), r.end, r.text, r.words));
        return build(Lyrics.Kind.LINE, raw);
    }

    // --- YRC ------------------------------------------------------------------------------------------

    public static Lyrics yrc(String text) {
        if (text == null || text.isBlank()) return Lyrics.NONE;
        List<Raw> raw = new ArrayList<>();
        boolean anyWords = false;
        for (String line : text.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("{")) {
                Raw credit = credit(line);
                if (credit != null) raw.add(credit);
                continue;
            }
            Matcher header = LINE_HEADER.matcher(line);
            if (!header.find()) continue;
            long lineStart = Long.parseLong(header.group(1)), lineDur = Long.parseLong(header.group(2));
            List<Lyrics.Word> words = new ArrayList<>();
            Matcher word = YRC_WORD.matcher(line.substring(header.end()));
            while (word.find()) {
                String w = word.group(3);
                if (w.isEmpty()) continue;
                words.add(new Lyrics.Word(Long.parseLong(word.group(1)), Long.parseLong(word.group(2)), w));
            }
            if (words.isEmpty()) continue;
            anyWords = true;
            raw.add(new Raw(lineStart, lineStart + lineDur, joined(words), words));
        }
        return anyWords ? build(Lyrics.Kind.WORD, raw) : Lyrics.NONE;
    }

    // --- QRC ------------------------------------------------------------------------------------------

    public static Lyrics qrc(String text) {
        if (text == null || text.isBlank()) return Lyrics.NONE;
        String body = unescapeXml(lyricContent(text));
        List<Raw> raw = new ArrayList<>();
        boolean anyWords = false;
        long offset = 0L;
        for (String line : body.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            Matcher offsetMatch = OFFSET.matcher(line);
            if (offsetMatch.find()) {
                offset = Long.parseLong(offsetMatch.group(1));
                continue;
            }
            if (META.matcher(line).matches()) continue;
            Matcher header = LINE_HEADER.matcher(line);
            long lineStart = -1L, lineEnd = -1L;
            if (header.find()) {
                lineStart = Long.parseLong(header.group(1));
                lineEnd = lineStart + Long.parseLong(header.group(2));
                line = line.substring(header.end());
            }
            List<Lyrics.Word> words = new ArrayList<>();
            Matcher word = QRC_WORD.matcher(line);
            while (word.find()) {
                String w = word.group(1);
                if (w.isEmpty()) continue;
                words.add(new Lyrics.Word(Long.parseLong(word.group(2)), Long.parseLong(word.group(3)), w));
            }
            if (words.isEmpty()) continue;
            anyWords = true;
            if (lineStart < 0L) lineStart = words.getFirst().startMs();
            raw.add(new Raw(lineStart, lineEnd, joined(words), words));
        }
        if (!anyWords) return Lyrics.NONE;
        final long shift = -offset;
        raw.replaceAll(r -> shift == 0L ? r : new Raw(Math.max(0L, r.start + shift), r.end < 0 ? r.end : Math.max(0L, r.end + shift), r.text,
            r.words.stream().map(w -> new Lyrics.Word(Math.max(0L, w.startMs() + shift), w.durationMs(), w.text())).toList()));
        return build(Lyrics.Kind.WORD, raw);
    }

    /** The {@code LyricContent} attribute's value when the QRC is wrapped in XML, else the text itself. */
    private static String lyricContent(String text) {
        int attr = text.indexOf("LyricContent=\"");
        if (attr < 0) return text;
        int start = attr + "LyricContent=\"".length();
        // The value ends at the quote that closes the element ("... />"), not at a quote inside a lyric.
        int end = text.lastIndexOf("/>");
        int quote = end < 0 ? -1 : text.lastIndexOf('"', end);
        if (quote <= start) quote = text.indexOf('"', start);
        return quote < 0 ? text.substring(start) : text.substring(start, quote);
    }

    private static String unescapeXml(String text) {
        return text.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    // --- translations ---------------------------------------------------------------------------------

    /**
     * {@code base} with translations and romanizations from {@code translation} / {@code romanization}, matched by
     * time: each of their lines goes to the base line starting nearest to it, within {@link #MATCH_MS}. It only
     * fills lines that have none yet - so the lyrics' own service goes first and another can fill the gaps.
     * Placeholders ({@code //}), blank lines and lines merely repeating the original are dropped.
     */
    public static Lyrics attach(Lyrics base, @Nullable Lyrics translation, @Nullable Lyrics romanization) {
        if (!base.hasLines()) return base;
        String[] t = match(base, translation), r = match(base, romanization);
        if (t == null && r == null) return base;
        List<Lyrics.Line> lines = new ArrayList<>(base.lines().size());
        for (int i = 0; i < base.lines().size(); i++) {
            Lyrics.Line line = base.lines().get(i);
            String tr = line.translation() != null ? line.translation() : t == null ? null : t[i];
            String ro = line.romanization() != null ? line.romanization() : r == null ? null : r[i];
            lines.add(line.withExtras(tr, ro));
        }
        return new Lyrics(base.kind(), List.copyOf(lines));
    }

    private static String @Nullable [] match(Lyrics base, @Nullable Lyrics extra) {
        if (extra == null || !extra.hasLines()) return null;
        List<Lyrics.Line> lines = base.lines();
        String[] out = new String[lines.size()];
        long[] gap = new long[lines.size()];
        java.util.Arrays.fill(gap, Long.MAX_VALUE);
        boolean any = false;
        for (Lyrics.Line line : extra.lines()) {
            String text = line.text().strip();
            if (text.isEmpty() || text.equals("//")) continue;
            int i = nearest(lines, line.startMs());
            long d = Math.abs(lines.get(i).startMs() - line.startMs());
            if (d > MATCH_MS || d >= gap[i] || text.equals(lines.get(i).text().strip())) continue;
            out[i] = text;
            gap[i] = d;
            any = true;
        }
        return any ? out : null;
    }

    /** The line (of lines sorted by start) starting nearest to {@code start}. */
    private static int nearest(List<Lyrics.Line> lines, long start) {
        int lo = 0, hi = lines.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).startMs() < start) lo = mid + 1;
            else hi = mid;
        }
        return lo > 0 && Math.abs(lines.get(lo - 1).startMs() - start) <= Math.abs(lines.get(lo).startMs() - start) ? lo - 1 : lo;
    }

    // --- shared ---------------------------------------------------------------------------------------

    private record Raw(long start, long end, String text, List<Lyrics.Word> words) {}

    /** A NetEase credit line: {@code {"t":ms,"c":[{"tx":"..."},...]}}. */
    private static Raw credit(String line) {
        try {
            JsonElement json = JsonParser.parseString(line);
            if (!json.isJsonObject()) return null;
            JsonObject object = json.getAsJsonObject();
            if (!object.has("t") || !object.has("c") || !object.get("c").isJsonArray()) return null;
            StringBuilder text = new StringBuilder();
            for (JsonElement part : object.getAsJsonArray("c")) {
                if (part.isJsonObject() && part.getAsJsonObject().has("tx")) text.append(part.getAsJsonObject().get("tx").getAsString());
            }
            String content = text.toString().trim();
            return content.isEmpty() ? null : new Raw(object.get("t").getAsLong(), -1L, content, List.of());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String joined(List<Lyrics.Word> words) {
        StringBuilder text = new StringBuilder();
        for (Lyrics.Word word : words) text.append(word.text());
        return text.toString().trim();
    }

    /** Sorts, then gives every line an end: the next line's start, or its own last word / a short tail. */
    private static Lyrics build(Lyrics.Kind kind, List<Raw> raw) {
        raw.sort((a, b) -> Long.compare(a.start, b.start));
        List<Lyrics.Line> lines = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Raw r = raw.get(i);
            long next = i + 1 < raw.size() ? raw.get(i + 1).start : Long.MAX_VALUE;
            long own = r.end >= 0 ? r.end : !r.words.isEmpty() ? r.words.getLast().startMs() + r.words.getLast().durationMs() : r.start + LINE_TAIL_MS;
            lines.add(new Lyrics.Line(r.start, Math.min(next, Math.max(own, r.start)), r.text, List.copyOf(r.words)));
        }
        return lines.isEmpty() ? Lyrics.NONE : new Lyrics(kind, List.copyOf(lines));
    }
}
