package com.mentalfrostbyte.jello.music.netease;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mentalfrostbyte.jello.music.Track;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The NetEase Cloud Music calls the player uses, each confirmed against the live service when this was ported:
 * search ({@code /api/cloudsearch/pc}, which answers anonymously with covers and durations), chart and playlist
 * contents ({@code /weapi/v6/playlist/detail} + {@code /weapi/v3/song/detail}), stream URLs (eapi first - it also
 * returns 30-second previews of paid songs - then weapi, then the public {@code outer/url} redirect), and lyrics
 * from {@code /api/song/lyric/v1}, the only endpoint that returns word-timed YRC.
 */
public final class NeteaseApi {
    public static final long CHART_HOT = 3778678L;
    public static final long CHART_NEW = 3779629L;
    public static final long CHART_SOARING = 19723756L;
    public static final long CHART_ORIGINAL = 2884035L;
    public static final String TRACK_PREFIX = "netease:";

    private final NeteaseSession session;

    public NeteaseApi(NeteaseSession session) {
        this.session = session;
    }

    public NeteaseSession session() {
        return this.session;
    }

    // --- tracks -------------------------------------------------------------------------------------

    public List<Track> search(String keyword, int limit) throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("s", keyword);
        data.addProperty("type", 1);
        data.addProperty("limit", limit);
        data.addProperty("offset", 0);
        data.addProperty("total", true);
        JsonObject reply = checked(this.session.eapi("/api/cloudsearch/pc", data));
        JsonObject result = reply.has("result") && reply.get("result").isJsonObject() ? reply.getAsJsonObject("result") : null;
        return result == null || !result.has("songs") ? List.of() : tracks(result.getAsJsonArray("songs"));
    }

    /** A chart or playlist's first {@code limit} songs. */
    public List<Track> playlist(long id, int limit) throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("id", id);
        data.addProperty("n", limit);
        data.addProperty("s", 0);
        JsonObject playlist = checked(this.session.weapi("/weapi/v6/playlist/detail", data)).getAsJsonObject("playlist");
        if (playlist == null) return List.of();
        // Inline tracks stop at 20; the full order is in trackIds, resolved through song/detail.
        JsonArray ids = playlist.has("trackIds") ? playlist.getAsJsonArray("trackIds") : new JsonArray();
        List<Long> wanted = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, ids.size()); i++) wanted.add(ids.get(i).getAsJsonObject().get("id").getAsLong());
        if (wanted.isEmpty()) return playlist.has("tracks") ? tracks(playlist.getAsJsonArray("tracks")) : List.of();
        return songDetails(wanted);
    }

    /** An artist's most popular songs (their own list comes in an older format without covers: resolved again). */
    public List<Track> artistSongs(long artistId, int limit) throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("id", String.valueOf(artistId));
        data.addProperty("order", "hot");
        data.addProperty("limit", limit);
        data.addProperty("offset", 0);
        JsonObject reply = checked(this.session.weapi("/weapi/v1/artist/songs", data));
        List<Long> ids = new ArrayList<>();
        if (reply.has("songs") && reply.get("songs").isJsonArray()) {
            for (JsonElement song : reply.getAsJsonArray("songs")) {
                if (song.isJsonObject() && song.getAsJsonObject().has("id")) ids.add(song.getAsJsonObject().get("id").getAsLong());
            }
        }
        return ids.isEmpty() ? List.of() : songDetails(ids);
    }

    /** The signed-in account's daily recommendations (empty when signed out). */
    public List<Track> dailySongs() throws IOException {
        JsonObject reply = checked(this.session.weapi("/weapi/v3/discovery/recommend/songs", new JsonObject()));
        JsonElement data = reply.get("data");
        if (data == null || !data.isJsonObject() || !data.getAsJsonObject().has("dailySongs")) return List.of();
        return tracks(data.getAsJsonObject().getAsJsonArray("dailySongs"));
    }

    /** One of a user's playlists: its own songs and the ones they saved. */
    public record PlaylistInfo(long id, String name, @Nullable String cover, int trackCount) {}

    public List<PlaylistInfo> userPlaylists(long userId) throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("uid", userId);
        data.addProperty("limit", 100);
        data.addProperty("offset", 0);
        data.addProperty("includeVideo", true);
        return playlists(checked(this.session.weapi("/weapi/user/playlist", data)));
    }

    static List<PlaylistInfo> playlists(JsonObject reply) {
        List<PlaylistInfo> out = new ArrayList<>();
        if (!reply.has("playlist") || !reply.get("playlist").isJsonArray()) return out;
        for (JsonElement element : reply.getAsJsonArray("playlist")) {
            if (!element.isJsonObject()) continue;
            JsonObject list = element.getAsJsonObject();
            try {
                String cover = list.has("coverImgUrl") && !list.get("coverImgUrl").isJsonNull() ? coverUrl(list.get("coverImgUrl").getAsString()) : null;
                int count = list.has("trackCount") && !list.get("trackCount").isJsonNull() ? list.get("trackCount").getAsInt() : 0;
                out.add(new PlaylistInfo(list.get("id").getAsLong(), list.get("name").getAsString(), cover, count));
            } catch (RuntimeException ignored) {
                // A malformed entry is skipped.
            }
        }
        return out;
    }

    /** Full song records (covers, VIP flags, lengths) for {@code ids}, in their order. */
    private List<Track> songDetails(List<Long> ids) throws IOException {
        List<Track> out = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += 500) {
            List<Long> part = ids.subList(from, Math.min(ids.size(), from + 500));
            JsonArray c = new JsonArray(), wanted = new JsonArray();
            for (long id : part) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", id);
                c.add(entry);
                wanted.add(id);
            }
            JsonObject detail = new JsonObject();
            detail.addProperty("c", c.toString());
            detail.addProperty("ids", wanted.toString());
            JsonObject songs = checked(this.session.weapi("/weapi/v3/song/detail", detail));
            if (songs.has("songs")) out.addAll(tracks(songs.getAsJsonArray("songs")));
        }
        return out;
    }

    private static List<Track> tracks(JsonArray songs) {
        List<Track> out = new ArrayList<>();
        for (JsonElement element : songs) {
            if (!element.isJsonObject()) continue;
            JsonObject song = element.getAsJsonObject();
            try {
                long id = song.get("id").getAsLong();
                StringBuilder artists = new StringBuilder();
                JsonArray ar = song.has("ar") ? song.getAsJsonArray("ar") : song.has("artists") ? song.getAsJsonArray("artists") : new JsonArray();
                for (JsonElement artist : ar) {
                    if (artist.isJsonObject() && artist.getAsJsonObject().has("name") && !artist.getAsJsonObject().get("name").isJsonNull()) {
                        artists.append(artists.isEmpty() ? "" : " / ").append(artist.getAsJsonObject().get("name").getAsString());
                    }
                }
                JsonObject al = song.has("al") && song.get("al").isJsonObject() ? song.getAsJsonObject("al")
                    : song.has("album") && song.get("album").isJsonObject() ? song.getAsJsonObject("album") : new JsonObject();
                String album = al.has("name") && !al.get("name").isJsonNull() ? al.get("name").getAsString() : "";
                String cover = al.has("picUrl") && !al.get("picUrl").isJsonNull() ? coverUrl(al.get("picUrl").getAsString()) : null;
                long duration = song.has("dt") ? song.get("dt").getAsLong() : song.has("duration") ? song.get("duration").getAsLong() : 0L;
                // fee 1 (VIP) and 4 (purchase) play as 30-second previews without a signed-in account.
                int fee = song.has("fee") && !song.get("fee").isJsonNull() ? song.get("fee").getAsInt() : 0;
                String tag = fee == 1 ? "VIP" : fee == 4 ? "PAID" : "";
                out.add(new Track(TRACK_PREFIX + id, song.get("name").getAsString(), artists.toString(), album, tag, duration, cover));
            } catch (RuntimeException ignored) {
                // A malformed entry is skipped rather than failing the whole list.
            }
        }
        return out;
    }

    /** NetEase serves covers at any size: ask for one that fits the player, over https. */
    private static String coverUrl(String url) {
        String secure = url.startsWith("http://") ? "https://" + url.substring(7) : url;
        return secure + (secure.contains("?") ? "&" : "?") + "param=256y256";
    }

    public static long songId(Track track) {
        if (!track.id().startsWith(TRACK_PREFIX)) throw new IllegalArgumentException("Not a NetEase track: " + track.id());
        return Long.parseLong(track.id().substring(TRACK_PREFIX.length()));
    }

    // --- streams ------------------------------------------------------------------------------------

    /**
     * Where to stream {@code songId} from. {@code trialEndMs} is set when only a preview is available (the
     * clip then starts at 0 and lasts that long). {@code null} when nothing playable came back.
     */
    public record Stream(String url, int bitrate, long trialEndMs) {}

    public @Nullable Stream stream(long songId) throws IOException {
        // eapi: 320k when free, a 30 s preview of paid songs; MP3 only (the decoder handles nothing else).
        for (String level : new String[]{"exhigh", "standard"}) {
            JsonObject params = new JsonObject();
            params.addProperty("ids", "[" + songId + "]");
            params.addProperty("level", level);
            params.addProperty("encodeType", "mp3");
            Stream stream = pick(this.session.eapi("/api/song/enhance/player/url/v1", params));
            if (stream != null) return stream;
        }
        JsonObject data = new JsonObject();
        data.addProperty("ids", "[" + songId + "]");
        data.addProperty("level", "standard");
        data.addProperty("encodeType", "mp3");
        Stream stream = pick(this.session.weapi("/weapi/song/enhance/player/url/v1", data));
        return stream != null ? stream : outer(songId);
    }

    private static @Nullable Stream pick(JsonObject reply) {
        if (!reply.has("data") || !reply.get("data").isJsonArray() || reply.getAsJsonArray("data").isEmpty()) return null;
        JsonObject item = reply.getAsJsonArray("data").get(0).getAsJsonObject();
        if (!item.has("url") || item.get("url").isJsonNull()) return null;
        String type = item.has("type") && !item.get("type").isJsonNull() ? item.get("type").getAsString() : "";
        if (!type.equalsIgnoreCase("mp3")) return null;
        long trialEnd = 0L;
        if (item.has("freeTrialInfo") && item.get("freeTrialInfo").isJsonObject()) {
            JsonObject trial = item.getAsJsonObject("freeTrialInfo");
            if (trial.has("end") && !trial.get("end").isJsonNull()) trialEnd = trial.get("end").getAsLong() * 1000L;
        }
        int br = item.has("br") && !item.get("br").isJsonNull() ? item.get("br").getAsInt() : 0;
        return new Stream(item.get("url").getAsString(), br, trialEnd);
    }

    /** The web player's public redirect: resolves free songs even when the APIs are being strict. */
    private static @Nullable Stream outer(long songId) {
        try {
            String url = "https://music.163.com/song/media/outer/url?id=" + songId + ".mp3";
            for (int hop = 0; hop < 5; hop++) {
                HttpURLConnection conn = (HttpURLConnection)URI.create(url).toURL().openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(NeteaseSession.TIMEOUT_MS);
                conn.setReadTimeout(NeteaseSession.TIMEOUT_MS);
                conn.setRequestMethod("HEAD");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setRequestProperty("Referer", NeteaseSession.WEB + "/");
                int status = conn.getResponseCode();
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (status == 200) return url.contains("/404") ? null : new Stream(url, 128_000, 0L);
                if (status / 100 != 3 || location == null || location.contains("/404")) return null;
                url = URI.create(url).resolve(location).toString();
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Nothing playable.
        }
        return null;
    }

    // --- lyrics -------------------------------------------------------------------------------------

    /**
     * The raw lyric texts NetEase has for a song; empty strings when absent. {@code translation} ({@code tlyric})
     * and {@code romanization} ({@code romalrc}) are LRC timed like {@code lrc}, for songs not in Chinese.
     */
    public record LyricTexts(String yrc, String lrc, String translation, String romanization, boolean instrumental) {}

    public LyricTexts lyrics(long songId) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("id", songId);
        params.addProperty("cp", false);
        for (String key : new String[]{"tv", "lv", "rv", "kv", "yv", "ytv", "yrv"}) params.addProperty(key, 0);
        JsonObject reply = checked(this.session.eapi("/api/song/lyric/v1", params));
        boolean instrumental = (reply.has("pureMusic") && reply.get("pureMusic").getAsBoolean())
            || (reply.has("nolyric") && reply.get("nolyric").getAsBoolean());
        return new LyricTexts(text(reply, "yrc"), text(reply, "lrc"), text(reply, "tlyric"), text(reply, "romalrc"), instrumental);
    }

    private static String text(JsonObject reply, String key) {
        if (!reply.has(key) || !reply.get(key).isJsonObject()) return "";
        JsonElement lyric = reply.getAsJsonObject(key).get("lyric");
        return lyric == null || lyric.isJsonNull() ? "" : lyric.getAsString();
    }

    private static JsonObject checked(JsonObject reply) throws IOException {
        int code = reply.has("code") && reply.get("code").isJsonPrimitive() ? reply.get("code").getAsInt() : -1;
        if (code != 200) throw new IOException("NetEase replied with code " + code);
        return reply;
    }
}
