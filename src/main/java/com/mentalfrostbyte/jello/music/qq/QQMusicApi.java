package com.mentalfrostbyte.jello.music.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * QQ Music's public web endpoints, used to find word-by-word QRC lyrics for a song NetEase only has line-timed
 * lyrics for (ported from SigmaClient):
 * <pre>search(title artist) -> {@link QQMusicMatcher} picks the candidate -> {@link #fetchQrc} -> {@link QQMusicDecoder}</pre>
 * Neither endpoint needs a login.
 */
public final class QQMusicApi {
    private static final String SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp";
    // The old desktop-client endpoint: XML carrying the lyrics as hex (triple modified-DES + zlib). Numeric ids only.
    private static final String LYRIC_URL = "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 8_000;

    /** A search result. {@code durationMs} is 0 when unknown. */
    public record QQTrack(long songId, String songMid, String name, String artist, String album, long durationMs) {}

    private QQMusicApi() {}

    public static List<QQTrack> search(String keyword, int limit) throws IOException {
        List<QQTrack> tracks = new ArrayList<>();
        if (keyword == null || keyword.isBlank()) return tracks;
        String url = SEARCH_URL + "?format=json&p=1&n=" + Math.max(1, limit) + "&w=" + URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8);
        JsonElement root = JsonParser.parseString(get(url, "https://y.qq.com/"));
        JsonObject data = child(root, "data"), song = child(data, "song");
        if (song == null || !song.has("list") || !song.get("list").isJsonArray()) return tracks;
        for (JsonElement element : song.getAsJsonArray("list")) {
            if (!element.isJsonObject()) continue;
            QQTrack track = parse(element.getAsJsonObject());
            if (track != null) tracks.add(track);
        }
        return tracks;
    }

    /**
     * A song's lyrics from QQ Music: the QRC ({@code content}, word-timed, possibly still wrapped in XML), its
     * translation ({@code contentts}, LRC timed like the QRC's lines) and romanization ({@code contentroma}, QRC);
     * {@code null} for any the song doesn't have.
     */
    public record QQLyrics(@Nullable String qrc, @Nullable String translation, @Nullable String romanization) {}

    public static @Nullable QQLyrics fetchLyrics(long songId) throws IOException {
        if (songId <= 0L) return null;
        String xml = get(LYRIC_URL + "?version=15&miniversion=82&lrctype=4&musicid=" + songId, "https://y.qq.com/portal/player.html");
        return new QQLyrics(section(xml, "content"), section(xml, "contentts"), section(xml, "contentroma"));
    }

    /** The decrypted QRC for {@code songId} (possibly still wrapped in XML), or {@code null} if there is none. */
    public static @Nullable String fetchQrc(long songId) throws IOException {
        QQLyrics lyrics = fetchLyrics(songId);
        return lyrics == null ? null : lyrics.qrc();
    }

    /** The CDATA of {@code <tag>}: encrypted hex (decrypted here) or, for some tags, plain text. */
    private static @Nullable String section(String xml, String tag) {
        int open = xml.indexOf("<" + tag + ">");
        if (open < 0) open = xml.indexOf("<" + tag + " ");
        if (open < 0) return null;
        int start = xml.indexOf("<![CDATA[", open);
        int close = xml.indexOf("</" + tag + ">", open);
        if (start < 0 || (close >= 0 && start > close)) return null;
        int end = xml.indexOf("]]>", start);
        if (end < 0) return null;
        String text = xml.substring(start + 9, end).trim();
        if (text.isEmpty()) return null;
        return text.chars().allMatch(c -> Character.digit(c, 16) >= 0) ? QQMusicDecoder.decryptLyrics(text) : text;
    }

    private static @Nullable JsonObject child(@Nullable JsonElement parent, String name) {
        if (parent == null || !parent.isJsonObject()) return null;
        JsonElement child = parent.getAsJsonObject().get(name);
        return child != null && child.isJsonObject() ? child.getAsJsonObject() : null;
    }

    private static @Nullable QQTrack parse(JsonObject song) {
        try {
            long id = song.has("songid") ? song.get("songid").getAsLong() : song.has("id") ? song.get("id").getAsLong() : 0L;
            if (id <= 0L) return null;
            String mid = song.has("songmid") ? song.get("songmid").getAsString() : song.has("mid") ? song.get("mid").getAsString() : "";
            String name = song.has("songname") ? song.get("songname").getAsString() : song.has("name") ? song.get("name").getAsString() : "";
            StringBuilder artist = new StringBuilder();
            if (song.has("singer") && song.get("singer").isJsonArray()) {
                JsonArray singers = song.getAsJsonArray("singer");
                for (JsonElement singer : singers) {
                    if (singer.isJsonObject() && singer.getAsJsonObject().has("name")) {
                        artist.append(artist.isEmpty() ? "" : "/").append(singer.getAsJsonObject().get("name").getAsString());
                    }
                }
            }
            String album = song.has("albumname") ? song.get("albumname").getAsString() : "";
            long duration = song.has("interval") ? song.get("interval").getAsLong() * 1000L : 0L;
            return new QQTrack(id, mid, name, artist.toString(), album, duration);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String get(String url, String referer) throws IOException {
        HttpURLConnection conn = (HttpURLConnection)URI.create(url).toURL().openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", referer);
            int status = conn.getResponseCode();
            try (InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream()) {
                if (in == null) throw new IOException("HTTP " + status + " from QQ Music");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                return out.toString(StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }
}
