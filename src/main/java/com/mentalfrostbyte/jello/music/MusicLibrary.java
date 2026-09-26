package com.mentalfrostbyte.jello.music;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mentalfrostbyte.jello.music.lyrics.LyricsService;
import com.mentalfrostbyte.jello.music.netease.NeteaseAccount;
import com.mentalfrostbyte.jello.music.netease.NeteaseApi;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;

/**
 * What the music interfaces browse - NetEase charts, an artist, the signed-in account's daily picks and
 * playlists, search - plus lyrics and the NetEase account. Every call returns a future the interface polls;
 * nothing here blocks the game thread. Lists are fetched once and kept (a failed one stays failed until
 * {@link #retry} is asked for it); per-account ones are keyed by the sign-in generation, so a different login
 * fetches its own. With no online source (the offline preview) there is nothing to browse.
 */
public final class MusicLibrary {
    private final @Nullable NeteaseApi netease;
    private final LyricsService lyrics;
    private final @Nullable NeteaseAccount account;
    private final Map<String, CompletableFuture<?>> cache = new ConcurrentHashMap<>();
    private final ExecutorService threads = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "Sigma music library");
        thread.setDaemon(true);
        return thread;
    });

    public MusicLibrary(@Nullable NeteaseApi netease) {
        this.netease = netease;
        this.lyrics = new LyricsService(netease);
        this.account = netease == null ? null : new NeteaseAccount(netease.session());
    }

    public boolean isOnline() {
        return this.netease != null;
    }

    public LyricsService lyrics() {
        return this.lyrics;
    }

    /** The NetEase login (QR code), or {@code null} offline. */
    public @Nullable NeteaseAccount account() {
        return this.account;
    }

    // --- lists ----------------------------------------------------------------------------------------

    /** The hot chart: what the player queues when it has nothing else. */
    public CompletableFuture<ListSource> chart() {
        return playlist(NeteaseApi.CHART_HOT, "网易云 · 热歌榜", 100);
    }

    /** A chart or playlist's first {@code limit} songs, queued under {@code name}. */
    public CompletableFuture<ListSource> playlist(long id, String name, int limit) {
        return cached("playlist:" + id, api -> new ListSource(name, api.playlist(id, limit)));
    }

    public CompletableFuture<ListSource> artist(long id, String name) {
        return cached("artist:" + id, api -> new ListSource(name, api.artistSongs(id, 100)));
    }

    public CompletableFuture<ListSource> daily() {
        return cached("daily@" + generation(), api -> new ListSource("每日推荐", api.dailySongs()));
    }

    /** The signed-in account's playlists (its own and saved ones); needs its user id, from the profile. */
    public CompletableFuture<List<NeteaseApi.PlaylistInfo>> myPlaylists() {
        NeteaseAccount account = this.account;
        NeteaseApi api = this.netease;
        if (account == null || api == null) return CompletableFuture.failedFuture(new IllegalStateException("offline"));
        @SuppressWarnings("unchecked")
        CompletableFuture<List<NeteaseApi.PlaylistInfo>> future = (CompletableFuture<List<NeteaseApi.PlaylistInfo>>)this.cache.computeIfAbsent(
            "mine@" + generation(), k -> account.profile().thenApplyAsync(profile -> {
                try {
                    return api.userPlaylists(profile.userId());
                } catch (Exception e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }, this.threads));
        return future;
    }

    public CompletableFuture<ListSource> search(String query) {
        NeteaseApi api = this.netease;
        if (api == null) return CompletableFuture.failedFuture(new IllegalStateException("offline"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new ListSource("搜索 · " + query, api.search(query, 30));
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }, this.threads);
    }

    /** Forgets {@code failed} (a list that failed or came back empty) so asking again fetches it again. */
    public void retry(CompletableFuture<?> failed) {
        this.cache.entrySet().removeIf(entry -> {
            if (entry.getValue() != failed) return false;
            // The playlists need the profile first; a failed profile has to be asked for again too.
            if (entry.getKey().startsWith("mine@") && this.account != null) this.account.refreshProfile();
            return true;
        });
    }

    private int generation() {
        return this.account == null ? 0 : this.account.generation();
    }

    private interface Load<T> {
        T load(NeteaseApi api) throws Exception;
    }

    private <T> CompletableFuture<T> cached(String key, Load<T> load) {
        NeteaseApi api = this.netease;
        if (api == null) return CompletableFuture.failedFuture(new IllegalStateException("offline"));
        @SuppressWarnings("unchecked")
        CompletableFuture<T> future = (CompletableFuture<T>)this.cache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            try {
                return load.load(api);
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }, this.threads));
        return future;
    }

    // --- config ---------------------------------------------------------------------------------------

    /** Reads the lyric channel, mode and language from the {@code "music"} settings; unknown values keep the defaults. */
    public void read(JsonObject config) {
        JsonElement music = config.get("music");
        if (music == null || !music.isJsonObject()) return;
        LyricsService.Channel channel = option(music.getAsJsonObject(), "lyricChannel", LyricsService.Channel.class);
        if (channel != null) this.lyrics.setChannel(channel);
        LyricsService.Mode mode = option(music.getAsJsonObject(), "lyricMode", LyricsService.Mode.class);
        if (mode != null) this.lyrics.setMode(mode);
        LyricsService.Language language = option(music.getAsJsonObject(), "lyricLanguage", LyricsService.Language.class);
        if (language != null) this.lyrics.setLanguage(language);
    }

    /** Adds the lyric settings to the {@code "music"} object, keeping whatever else is saved there. */
    public void write(JsonObject config) {
        JsonElement existing = config.get("music");
        JsonObject music = existing != null && existing.isJsonObject() ? existing.getAsJsonObject() : new JsonObject();
        music.addProperty("lyricChannel", this.lyrics.channel().name().toLowerCase(Locale.ROOT));
        music.addProperty("lyricMode", this.lyrics.mode().name().toLowerCase(Locale.ROOT));
        music.addProperty("lyricLanguage", this.lyrics.language().name().toLowerCase(Locale.ROOT));
        config.add("music", music);
    }

    private static <E extends Enum<E>> @Nullable E option(JsonObject music, String name, Class<E> type) {
        JsonElement value = music.get(name);
        if (value == null || !value.isJsonPrimitive()) return null;
        try {
            return Enum.valueOf(type, value.getAsString().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void close() {
        this.threads.shutdownNow();
        this.lyrics.close();
        if (this.account != null) this.account.close();
    }
}
