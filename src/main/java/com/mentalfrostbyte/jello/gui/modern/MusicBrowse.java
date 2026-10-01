package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.music.ListSource;
import com.mentalfrostbyte.jello.music.MusicLibrary;
import com.mentalfrostbyte.jello.music.MusicPlayer;
import com.mentalfrostbyte.jello.music.Track;
import com.mentalfrostbyte.jello.music.netease.NeteaseAccount;
import com.mentalfrostbyte.jello.music.netease.NeteaseApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * What the music windows browse, apart from how they draw it: the categories on the rail, which one is open, the
 * search box's text and its latest results, an opened playlist, and what a click on a row does. SigmaModern's window
 * ({@link ModernMusicBrowser}) and Jello's ({@code JelloMusicPanel}) are two looks over this one model, so a search
 * typed in one is still there in the other, and a category added to {@link #CATEGORIES} shows up in both.
 *
 * <p>The state is static, like the window positions it sits beside: it outlives any one window. Everything is read
 * on the render thread; the lists themselves come from {@link MusicLibrary}'s cache as futures, so nothing here
 * blocks.</p>
 */
public final class MusicBrowse {
    static final int PLAYLIST_LIMIT = 200;
    static final int MAX_QUERY = 60;

    public enum Kind { SEARCH, PLAYLIST, ARTIST, DAILY, MINE }

    /** Where a category's list stands: nothing asked yet, on its way, failed, empty, or ready to show. */
    public enum State { IDLE, LOADING, FAILED, EMPTY, READY }

    /** One entry of the rail. Adding a category is adding a line to {@link #CATEGORIES}. */
    public record Category(String key, String en, String zh, String titleEn, String titleZh, Kind kind, long id, boolean needsLogin) {
        public String label() {
            return ModernText.t(this.en, this.zh);
        }

        public String title() {
            return ModernText.t(this.titleEn, this.titleZh);
        }
    }

    public static final List<Category> CATEGORIES = List.of(
        new Category("search", "Search", "搜索", "Search", "搜索", Kind.SEARCH, 0L, false),
        new Category("hot", "Hot", "热歌", "NetEase · Hot", "网易云 · 热歌榜", Kind.PLAYLIST, NeteaseApi.CHART_HOT, false),
        new Category("new", "New", "新歌", "NetEase · New", "网易云 · 新歌榜", Kind.PLAYLIST, NeteaseApi.CHART_NEW, false),
        new Category("soaring", "Rising", "飙升", "NetEase · Rising", "网易云 · 飙升榜", Kind.PLAYLIST, NeteaseApi.CHART_SOARING, false),
        new Category("original", "Original", "原创", "NetEase · Original", "网易云 · 原创榜", Kind.PLAYLIST, NeteaseApi.CHART_ORIGINAL, false),
        new Category("wuwa", "WuWa", "鸣潮", "Wuthering Waves", "鸣潮 · 热门", Kind.ARTIST, 61908633L, false),
        new Category("daily", "Daily", "日推", "Daily picks", "每日推荐", Kind.DAILY, 0L, true),
        new Category("mine", "Lists", "歌单", "My playlists", "我的歌单", Kind.MINE, 0L, true));

    // -Dsigma.debug.musicDemo=1: the charts and the artist list a made-up set of songs (captures, with no network).
    private static final boolean DEMO = System.getProperty("sigma.debug.musicDemo") != null;

    private static String category = "hot";
    private static NeteaseApi.@Nullable PlaylistInfo openPlaylist;
    private static String query = "";
    private static @Nullable String searched;
    private static @Nullable CompletableFuture<ListSource> results;
    private static boolean debugApplied;

    private MusicBrowse() {}

    /** Whether the made-up demo lists are on ({@code -Dsigma.debug.musicDemo}). */
    public static boolean demo() {
        return DEMO;
    }

    /** {@code -Dsigma.debug.musicSearch=<query>}: the search category opens already searched (captures); once. */
    static void applyDebug() {
        String debug = System.getProperty("sigma.debug.musicSearch");
        if (!debugApplied && debug != null && !debug.isBlank()) {
            debugApplied = true;
            category = "search";
            query = debug.strip();
            submit();
        }
    }

    // --- the categories -------------------------------------------------------------------------------

    public static boolean signedIn() {
        NeteaseAccount account = Client.getInstance().getMusicLibrary().account();
        return account != null && account.state().phase() == NeteaseAccount.Phase.SIGNED_IN;
    }

    /** The categories on the rail: all of them, or those that don't need a signed-in account. */
    public static List<Category> categories(boolean signedIn) {
        List<Category> out = new ArrayList<>();
        for (Category c : CATEGORIES) if (signedIn || !c.needsLogin()) out.add(c);
        return out;
    }

    public static String category() {
        return current().key();
    }

    /** The category on show; one that needs a login falls back to the hot chart once signed out. */
    public static Category current() {
        boolean signedIn = signedIn();
        for (Category c : CATEGORIES) if (c.key().equals(category) && (signedIn || !c.needsLogin())) return c;
        category = "hot";
        openPlaylist = null;
        return CATEGORIES.get(1);
    }

    /**
     * The rail chose {@code key}. Choosing "playlists" again goes back to their list. Returns whether the list
     * underneath changed, so the caller puts its scroll back at the top.
     */
    public static boolean select(String key) {
        boolean again = key.equals(category);
        if (again && key.equals("mine") && openPlaylist != null) {
            openPlaylist = null;
            return true;
        }
        if (again) return false;
        category = key;
        return true;
    }

    // --- the search -----------------------------------------------------------------------------------

    public static String query() {
        return query;
    }

    /** The text of the search box; a longer one is cut to {@link #MAX_QUERY} characters. */
    public static void setQuery(String text) {
        query = text.codePointCount(0, text.length()) > MAX_QUERY ? text.substring(0, text.offsetByCodePoints(0, MAX_QUERY)) : text;
    }

    public static @Nullable String searched() {
        return searched;
    }

    /** Searches for the box's text; an empty box clears the results. */
    public static void submit() {
        String q = query.strip();
        if (q.isEmpty()) {
            searched = null;
            results = null;
            return;
        }
        searched = q;
        // Replacing the future is what drops an earlier, slower search: only this one is ever read.
        results = Client.getInstance().getMusicLibrary().search(q);
    }

    /** The box's cross: its text and its results go. */
    public static void clearSearch() {
        query = "";
        searched = null;
        results = null;
    }

    // --- what's listed --------------------------------------------------------------------------------

    /** The songs of {@code c}, or {@code null} when it lists playlists (or nothing has been searched yet). */
    public static @Nullable CompletableFuture<ListSource> songs(Category c) {
        if (DEMO && c.kind() != Kind.SEARCH && c.kind() != Kind.MINE) return CompletableFuture.completedFuture(demoSongs());
        MusicLibrary library = Client.getInstance().getMusicLibrary();
        return switch (c.kind()) {
            case SEARCH -> searched == null ? null : results;
            case PLAYLIST -> library.playlist(c.id(), c.title(), 100);
            case ARTIST -> library.artist(c.id(), c.title());
            case DAILY -> library.daily();
            case MINE -> openPlaylist == null ? null : library.playlist(openPlaylist.id(), openPlaylist.name(), PLAYLIST_LIMIT);
        };
    }

    /** The playlists of {@code c} (the account's list of them), or {@code null} when it lists songs. */
    public static @Nullable CompletableFuture<List<NeteaseApi.PlaylistInfo>> playlists(Category c) {
        return c.kind() == Kind.MINE && openPlaylist == null ? Client.getInstance().getMusicLibrary().myPlaylists() : null;
    }

    private static ListSource demoSongs() {
        List<Track> made = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            made.add(new Track("demo-" + i, "Song number " + i + " with a longer name", "Artist " + i, "", "", 200_000L + i * 1000L, null));
        }
        return new ListSource("Demo", made);
    }

    private static <T> @Nullable T ready(@Nullable CompletableFuture<T> future) {
        return future != null && future.isDone() && !future.isCompletedExceptionally() ? future.join() : null;
    }

    private static @Nullable CompletableFuture<?> pending(Category c) {
        CompletableFuture<ListSource> songs = songs(c);
        return songs != null ? songs : playlists(c);
    }

    /** The tracks of {@code c} once they have arrived, else {@code null}. */
    public static @Nullable List<Track> tracks(Category c) {
        ListSource source = ready(songs(c));
        return source == null ? null : source.tracks();
    }

    /** The playlists of {@code c} once they have arrived, else {@code null}. */
    public static @Nullable List<NeteaseApi.PlaylistInfo> lists(Category c) {
        return ready(playlists(c));
    }

    /** The grey text in an empty search box. */
    public static String placeholder() {
        return ModernText.t("Songs, artists, albums", "搜索歌曲、歌手、专辑");
    }

    /** What a playlist row says under its name. */
    public static String songCount(NeteaseApi.PlaylistInfo list) {
        return list.trackCount() + ModernText.t(" songs", " 首");
    }

    /** The playlist standing in a row: its cover is drawn like a song's. */
    public static Track cover(NeteaseApi.PlaylistInfo list) {
        return new Track("netease-playlist:" + list.id(), list.name(), "", "", "", 0L, list.cover());
    }

    /** How many rows {@code c} lists, or {@code -1} while there is no list yet. */
    public static int size(Category c) {
        List<Track> tracks = tracks(c);
        if (tracks != null) return tracks.size();
        List<NeteaseApi.PlaylistInfo> lists = lists(c);
        return lists != null ? lists.size() : -1;
    }

    public static State state(Category c) {
        CompletableFuture<?> pending = pending(c);
        if (pending == null) return State.IDLE;
        if (!pending.isDone()) return State.LOADING;
        if (pending.isCompletedExceptionally()) return State.FAILED;
        Object value = pending.join();
        boolean empty = value instanceof ListSource source ? source.tracks().isEmpty() : value instanceof List<?> list && list.isEmpty();
        return empty ? State.EMPTY : State.READY;
    }

    /** What stands where the rows would be, for a list that has none to show. */
    public static String message(Category c) {
        return switch (state(c)) {
            case IDLE -> ModernText.t("Type a song or an artist above, then Enter", "在上方输入歌名或歌手，按回车搜索");
            case LOADING -> c.kind() == Kind.SEARCH ? ModernText.t("Searching...", "正在搜索…") : ModernText.t("Loading...", "正在载入…");
            case FAILED -> ModernText.t("Couldn't load - click to retry", "载入失败 · 点击重试");
            case EMPTY, READY -> switch (c.kind()) {
                case SEARCH -> ModernText.t("Nothing found", "没有找到相关歌曲");
                case DAILY -> ModernText.t("No picks today", "今天还没有推荐");
                case MINE -> openPlaylist == null ? ModernText.t("No playlists yet", "还没有歌单") : ModernText.t("This playlist is empty", "这个歌单是空的");
                default -> ModernText.t("Nothing here yet", "这里还没有歌曲");
            };
        };
    }

    /** The line above the list: the category, the search that was run, or the playlist that is open. */
    public static String heading(Category c) {
        return switch (c.kind()) {
            case SEARCH -> searched == null ? c.title() : ModernText.t("Search · ", "搜索 · ") + searched;
            case MINE -> openPlaylist == null ? c.title() : "‹ " + openPlaylist.name();
            default -> c.title();
        };
    }

    /** The number beside the heading: how many rows there are, or how many of an opened playlist's were loaded. */
    public static String count(Category c) {
        int size = size(c);
        if (size < 0) return "";
        if (openPlaylist != null && c.kind() == Kind.MINE && openPlaylist.trackCount() > PLAYLIST_LIMIT) {
            return ModernText.t("first ", "前 ") + size;
        }
        return String.format(Locale.ROOT, "%02d", size);
    }

    /** Whether the heading is the way back from an opened playlist. */
    public static boolean headingGoesBack(Category c) {
        return c.kind() == Kind.MINE && openPlaylist != null;
    }

    /** The heading of an opened playlist was clicked: back to the list of playlists. */
    public static void back() {
        openPlaylist = null;
    }

    // --- acting on a row ------------------------------------------------------------------------------

    /** A failed (or empty) list is asked for again; a search is simply repeated. */
    public static void retry(Category c) {
        if (c.kind() == Kind.SEARCH) {
            submit();
            return;
        }
        CompletableFuture<?> pending = pending(c);
        if (pending != null) Client.getInstance().getMusicLibrary().retry(pending);
    }

    /**
     * The row {@code index} of {@code c} was picked: a song queues its list from it and plays; a playlist opens.
     * Returns whether a playlist opened, so the caller puts its scroll back at the top.
     */
    public static boolean pick(MusicPlayer player, Category c, int index) {
        CompletableFuture<ListSource> songs = songs(c);
        if (songs != null) {
            ListSource source = ready(songs);
            if (source != null && index >= 0 && index < source.tracks().size()) player.setSource(source, index, true);
            return false;
        }
        List<NeteaseApi.PlaylistInfo> lists = lists(c);
        if (lists != null && index >= 0 && index < lists.size()) {
            openPlaylist = lists.get(index);
            return true;
        }
        return false;
    }

    /** Test cleanup, and what signing out does: an empty search on the hot chart, as a fresh session has. */
    public static void reset() {
        clearSearch();
        category = "hot";
        openPlaylist = null;
    }
}
