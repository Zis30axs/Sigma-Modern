package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTextField;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.gui.modern.ModernCovers;
import com.mentalfrostbyte.jello.gui.modern.ModernLyricSweep;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Jello's music window in its ClickGUI: an 800 x 600 sheet with a black rail of sources on the left, a grid of covers
 * on the right, and a dark bar along the foot with the transport, the progress line and the volume. The old one drove
 * the old music manager; this one drives {@link MusicPlayer} and {@link MusicLibrary}, the same backend SigmaModern's
 * music window uses, so what plays here is what plays there.
 *
 * <p>It is laid out in the old client's pixels ({@code W} x {@code H} below) and scaled down as a whole when the
 * window is smaller. Sources are the NetEase charts and an artist, the search, and - signed in from SigmaModern's
 * window - the daily picks and the account's playlists. Picking a cover queues its list from that song.</p>
 */
final class JelloMusicPanel {
    static final int W = 800;
    static final int H = 600;
    private static final int RAIL = 250;
    private static final int BAR = 94;
    private static final int TAB_TOP = 78;
    private static final int TAB_H = 40;
    private static final int COLUMNS = 3;
    private static final int CARD_W = 166;
    private static final int CARD_H = 214;
    private static final int GRID_X = RAIL + 20;
    private static final int GRID_GAP = 16;
    private static final int GRID_TOP = 78;
    private static final int SEARCH_TOP = 20;
    private static final int WHITE = 0xFFFEFEFE;
    private static final int INK = 0xFF010101;
    private static final int BLUE = 0xFF29A6FF;
    private static final int SHEET = 0xFF262626;
    private static final int PLAYLIST_LIMIT = 200;
    private static final int MAX_QUERY = 60;

    private enum Kind { SEARCH, PLAYLIST, ARTIST, DAILY, MINE }

    private record Category(String en, String cn, Kind kind, long id, boolean needsLogin) {
        String label() {
            return JelloMusicPanel.zh() ? this.cn : this.en;
        }
    }

    private static final List<Category> CATEGORIES = List.of(
        new Category("Search", "搜索", Kind.SEARCH, 0L, false),
        new Category("Hot", "热歌", Kind.PLAYLIST, NeteaseApi.CHART_HOT, false),
        new Category("New", "新歌", Kind.PLAYLIST, NeteaseApi.CHART_NEW, false),
        new Category("Rising", "飙升", Kind.PLAYLIST, NeteaseApi.CHART_SOARING, false),
        new Category("Original", "原创", Kind.PLAYLIST, NeteaseApi.CHART_ORIGINAL, false),
        new Category("Wuthering Waves", "鸣潮", Kind.ARTIST, 61908633L, false),
        new Category("Daily", "日推", Kind.DAILY, 0L, true),
        new Category("My playlists", "我的歌单", Kind.MINE, 0L, true));

    // What the window shows outlives any one ClickGUI, like the old one's state did.
    private static @Nullable Boolean openState;
    private static int category = 1;
    private static NeteaseApi.@Nullable PlaylistInfo openList;
    private static @Nullable String searched;
    // -Dsigma.debug.jelloMusic=demo: the window starts out, listing a made-up set of songs (captures, with no network).
    private static final boolean DEBUG_DEMO = "demo".equals(System.getProperty("sigma.debug.jelloMusic"));
    private static @Nullable CompletableFuture<ListSource> results;
    private static String remembered = "";

    private final MusicPlayer player = Client.getInstance().getMusicPlayer();
    private final LegacyTextField search = new LegacyTextField(
        LegacyTextField.Style.JELLO, GRID_X, SEARCH_TOP, W - GRID_X - 20, 36, Face.JELLO_LIGHT, 20, zh() ? "搜索…" : "Search...");
    private final Animation slide = new Animation(260, 200, Animation.Direction.BACKWARDS);
    // Where the window was drawn last frame, in framebuffer pixels: what a click is measured against.
    private float originX;
    private float originY;
    private float scale = 1.0F;
    private float scrollPx;
    private float scrollTarget;
    private boolean seeking;
    private boolean adjustingVolume;

    JelloMusicPanel() {
        this.search.setMaxLength(MAX_QUERY);
        this.search.setInk(WHITE);
        this.search.setText(remembered);
        this.search.onChange(field -> remembered = field.text());
    }

    // ------------------------------------------------------------------ open and close

    /** Whether the window is out. It starts out when the screen is wide enough to show it beside a row of cards. */
    boolean isOpen(final int screenWidth) {
        if (openState == null) {
            openState = DEBUG_DEMO || screenWidth >= 1690;
            this.slide.setProgress(openState ? 1.0F : 0.0F);
        }

        return openState;
    }

    void toggle() {
        openState = !Boolean.TRUE.equals(openState);
        this.search.setFocused(false);
    }

    boolean typing() {
        return this.search.focused() && this.visible();
    }

    private boolean visible() {
        return this.slide.calcPercent() > 0.0F;
    }

    // ------------------------------------------------------------------ drawing

    void draw(final LegacyCanvas c, final double mx, final double my, final float alpha, final float dt) {
        int screenWidth = c.width();
        int screenHeight = c.height();
        boolean open = this.isOpen(screenWidth);
        this.slide.changeDirection(open ? Animation.Direction.FORWARDS : Animation.Direction.BACKWARDS);
        float progress = this.slide.calcPercent();
        if (progress <= 0.0F) {
            return;
        }

        this.scale = Math.min(1.0F, Math.min((screenWidth - 40) / (float) W, (screenHeight - 40) / (float) H));
        this.originX = screenWidth - W * this.scale - 20 + (1.0F - progress) * 80.0F;
        this.originY = (screenHeight - H * this.scale) / 2.0F;
        double lx = (mx - this.originX) / this.scale;
        double ly = (my - this.originY) / this.scale;
        float a = alpha * progress;
        this.scrollPx = smooth(this.scrollPx, this.scrollTarget, dt);

        c.push();
        try {
            c.translate(this.originX, this.originY);
            c.scale(this.scale, this.scale);
            this.sheet(c, a);
            this.rail(c, lx, ly, a);
            this.content(c, lx, ly, a);
            this.bar(c, lx, ly, a);
        } finally {
            c.pop();
        }
    }

    private void sheet(final LegacyCanvas c, final float a) {
        c.outerGlow(0, 0, W, H, 14.0F, 0.8F * a);
        c.rounded(0, 0, W, H, 14, LegacyCanvas.alpha(SHEET, 0.8F * a));
        // The rail: black, with its right edge and its foot square, since the sheet and the bar carry the corners.
        int rail = LegacyCanvas.alpha(INK, 0.95F * a);
        c.rounded(0, 0, RAIL, H - BAR, 14, rail);
        c.fill(RAIL - 14, 0, RAIL, H - BAR, rail);
        c.fill(0, H - BAR - 14, RAIL, H - BAR, rail);
        // The foot bar: a dark wash over the picture the old client kept there.
        c.rounded(0, H - BAR, W, BAR, 14, LegacyCanvas.alpha(0xFF1A0A1C, 0.95F * a));
        c.image(LegacyTexture.MUSIC_BAR, 0, H - BAR, W, BAR - 14, LegacyCanvas.alpha(WHITE, a * a));
        c.fill(0, H - BAR, W, H - 5, LegacyCanvas.alpha(INK, 0.43F * a));
        c.fill(0, H - 5, RAIL, H, LegacyCanvas.alpha(INK, 0.43F * a));
    }

    private void rail(final LegacyCanvas c, final double lx, final double ly, final float a) {
        c.text(Face.JELLO_LIGHT, 40.0F, "Jello", 55, 20, LegacyCanvas.alpha(WHITE, a));
        c.text(Face.JELLO_LIGHT, 20.0F, "music", 135, 40, LegacyCanvas.alpha(WHITE, a));

        List<Category> categories = this.categories();
        int selected = this.selected(categories);
        c.scissor(0, TAB_TOP, RAIL, H - 170);
        try {
            for (int i = 0; i < categories.size(); i++) {
                int y = TAB_TOP + i * TAB_H;
                boolean hover = lx >= 0 && lx < RAIL && ly >= y && ly < y + TAB_H && ly < H - 170;
                if (i == selected) {
                    c.fill(0, y, RAIL, y + TAB_H, LegacyCanvas.alpha(WHITE, 0.12F * a));
                    c.fill(0, y, 3, y + TAB_H, LegacyCanvas.alpha(BLUE, a));
                } else if (hover) {
                    c.fill(0, y, RAIL, y + TAB_H, LegacyCanvas.alpha(WHITE, 0.06F * a));
                }
                Face face = i == selected ? Face.JELLO_MEDIUM : Face.JELLO_LIGHT;
                float textY = y + TAB_H / 2.0F - c.textHeight(face, 16.0F) / 2.0F;
                c.text(face, 16.0F, categories.get(i).label(), 30, textY, LegacyCanvas.alpha(WHITE, a * (i == selected || hover ? 1.0F : 0.75F)));
            }
        } finally {
            c.unscissor();
        }

        // What is playing: its cover across the rail and the bar, then its name and who made it.
        Track track = this.player.current();
        int coverX = (RAIL - 114) / 2;
        ModernCovers.draw(c.graphics(), track, coverX, H - 170, 114.0F, 0.12F, LegacyCanvas.alpha(WHITE, a));
        String title = track == null ? "Jello Music" : track.title();
        String line = track == null ? null : ModernLyricSweep.sungLine(this.player);
        c.textCentered(Face.JELLO_LIGHT, 18.0F, this.fit(c, Face.JELLO_LIGHT, 18.0F, line != null ? line : title, RAIL - 40), RAIL / 2.0F, H - 42.0F,
            LegacyCanvas.alpha(WHITE, a));
        if (track != null && !track.artist().isEmpty()) {
            c.textCentered(Face.JELLO_LIGHT, 14.0F, this.fit(c, Face.JELLO_LIGHT, 14.0F, track.artist(), RAIL - 40), RAIL / 2.0F, H - 20.0F,
                LegacyCanvas.alpha(WHITE, 0.6F * a));
        }
    }

    // ---- the grid

    private void content(final LegacyCanvas c, final double lx, final double ly, final float a) {
        Category current = this.current();
        MusicLibrary library = Client.getInstance().getMusicLibrary();
        int top = GRID_TOP;
        if (current.kind() == Kind.SEARCH) {
            this.search.draw(c, a);
            top = GRID_TOP + 6;
        } else {
            String label = current.kind() == Kind.MINE && openList != null ? "‹ " + openList.name() : current.label();
            c.text(Face.JELLO_LIGHT, 25.0F, this.fit(c, Face.JELLO_LIGHT, 25.0F, label, W - GRID_X - 90), GRID_X, 24, LegacyCanvas.alpha(WHITE, a));
        }

        CompletableFuture<ListSource> songs = this.songs(current, library);
        CompletableFuture<List<NeteaseApi.PlaylistInfo>> lists = this.playlists(current, library);
        ListSource source = ready(songs);
        List<NeteaseApi.PlaylistInfo> playlists = ready(lists);
        int size = source != null ? source.tracks().size() : playlists != null ? playlists.size() : -1;
        if (size >= 0) {
            String count = String.format(Locale.ROOT, "%02d", size);
            c.text(Face.JELLO_LIGHT, 16.0F, count, W - 22 - c.textWidth(Face.JELLO_LIGHT, 16.0F, count), current.kind() == Kind.SEARCH ? top - 20 : 30,
                LegacyCanvas.alpha(WHITE, 0.5F * a));
        }

        int gridBottom = H - BAR;
        c.scissor(RAIL, top, W, gridBottom);
        try {
            if (size > 0) {
                this.grid(c, lx, ly, a, top, gridBottom, source, playlists);
            } else {
                CompletableFuture<?> pending = songs != null ? songs : lists;
                this.message(c, a, current, pending, (top + gridBottom) / 2.0F);
            }
        } finally {
            c.unscissor();
        }
    }

    private void grid(final LegacyCanvas c, final double lx, final double ly, final float a, final int top, final int bottom,
                      final @Nullable ListSource source, final @Nullable List<NeteaseApi.PlaylistInfo> playlists) {
        int size = source != null ? source.tracks().size() : playlists.size();
        int rows = (size + COLUMNS - 1) / COLUMNS;
        float max = Math.max(0.0F, rows * CARD_H - (bottom - top) + 12);
        this.scrollTarget = Math.max(0.0F, Math.min(max, this.scrollTarget));
        Track playing = this.player.current();
        for (int i = 0; i < size; i++) {
            int col = i % COLUMNS;
            int row = i / COLUMNS;
            float x = GRID_X + col * (CARD_W + GRID_GAP);
            float y = top + 6 + row * CARD_H - this.scrollPx;
            if (y + CARD_H < top || y > bottom) {
                continue;
            }

            Track track;
            String title;
            String detail;
            if (source != null) {
                track = source.tracks().get(i);
                title = track.title();
                detail = track.artist();
            } else {
                NeteaseApi.PlaylistInfo list = playlists.get(i);
                track = new Track("netease-playlist:" + list.id(), list.name(), "", "", "", 0L, list.cover());
                title = list.name();
                detail = list.trackCount() + (zh() ? " 首" : " songs");
            }

            boolean hover = lx >= x && lx < x + CARD_W && ly >= y && ly < y + CARD_H && ly >= top && ly < bottom;
            boolean current = source != null && playing != null && playing.id().equals(track.id());
            ModernCovers.draw(c.graphics(), track, x, y, CARD_W, 0.08F, LegacyCanvas.alpha(WHITE, a));
            if (hover) {
                c.rounded(Math.round(x), Math.round(y), CARD_W, CARD_W, 13, LegacyCanvas.alpha(WHITE, 0.1F * a));
            }
            c.text(Face.JELLO_LIGHT, 16.0F, this.fit(c, Face.JELLO_LIGHT, 16.0F, title, CARD_W), x, y + CARD_W + 6,
                LegacyCanvas.alpha(current ? BLUE : WHITE, a));
            c.text(Face.JELLO_LIGHT, 14.0F, this.fit(c, Face.JELLO_LIGHT, 14.0F, detail, CARD_W), x, y + CARD_W + 28, LegacyCanvas.alpha(WHITE, 0.55F * a));
        }

        if (max > 0.0F) {
            float trackH = bottom - top - 12;
            float thumb = Math.max(24.0F, trackH * trackH / (trackH + max));
            float thumbY = top + 6 + (trackH - thumb) * this.scrollPx / max;
            c.rounded(W - 12, Math.round(thumbY), 3, Math.round(thumb), 1, LegacyCanvas.alpha(WHITE, 0.3F * a));
        }
    }

    private void message(final LegacyCanvas c, final float a, final Category current, final @Nullable CompletableFuture<?> pending, final float middle) {
        String text;
        if (pending == null) {
            text = zh() ? "在上方输入歌名或歌手，按回车搜索" : "Type a song or an artist above, then Enter";
        } else if (!pending.isDone()) {
            text = zh() ? "正在载入…" : "Loading...";
        } else if (pending.isCompletedExceptionally()) {
            text = zh() ? "载入失败 · 点击重试" : "Couldn't load - click to retry";
        } else {
            text = switch (current.kind()) {
                case SEARCH -> zh() ? "没有找到相关歌曲" : "Nothing found";
                case DAILY -> zh() ? "今天还没有推荐" : "No picks today";
                case MINE -> zh() ? "这里还没有歌曲" : "Nothing here yet";
                default -> zh() ? "这里还没有歌曲" : "Nothing here yet";
            };
        }

        c.textCentered(Face.JELLO_LIGHT, 20.0F, text, GRID_X + (W - GRID_X - 20) / 2.0F, middle, LegacyCanvas.alpha(WHITE, 0.6F * a));
    }

    // ---- the foot bar

    private void bar(final LegacyCanvas c, final double lx, final double ly, final float a) {
        int barTop = H - BAR;
        int centre = RAIL + (W - RAIL - 38) / 2;
        boolean playing = this.player.isPlaying();
        LegacyTexture playIcon = playing ? LegacyTexture.MUSIC_PAUSE : LegacyTexture.MUSIC_PLAY;
        float dim = this.player.current() == null ? 0.5F : 1.0F;
        c.image(LegacyTexture.MUSIC_PREVIOUS, centre - 114, barTop + 23, 46, 46, LegacyCanvas.alpha(WHITE, a * dim * this.hoverAlpha(lx, ly, centre - 114, barTop + 23, 46)));
        c.image(playIcon, centre, barTop + 27, 38, 38, LegacyCanvas.alpha(WHITE, a * dim * this.hoverAlpha(lx, ly, centre, barTop + 27, 38)));
        c.image(LegacyTexture.MUSIC_NEXT, centre + 114, barTop + 23, 46, 46, LegacyCanvas.alpha(WHITE, a * dim * this.hoverAlpha(lx, ly, centre + 114, barTop + 23, 46)));

        // The volume: a short vertical line at the right, filled from the bottom.
        int vx = W - 19;
        int vy = barTop + 14;
        c.fill(vx, vy, vx + 4, vy + 40, LegacyCanvas.alpha(WHITE, 0.25F * a));
        int filled = Math.round(40.0F * this.player.volume());
        c.fill(vx, vy + 40 - filled, vx + 4, vy + 40, LegacyCanvas.alpha(WHITE, 0.9F * a));

        // The progress line and the two times above it.
        c.fill(RAIL, H - 5, W, H, LegacyCanvas.alpha(WHITE, 0.2F * a));
        c.fill(RAIL, H - 5, RAIL + Math.round((W - RAIL) * this.player.progress()), H, LegacyCanvas.alpha(WHITE, 0.9F * a));
        String elapsed = time(this.player.positionMs());
        String total = time(this.player.durationMs());
        c.text(Face.JELLO_LIGHT, 14.0F, elapsed, RAIL + 14, H - 32, LegacyCanvas.alpha(WHITE, a * a));
        c.text(Face.JELLO_LIGHT, 14.0F, total, W - 14 - c.textWidth(Face.JELLO_LIGHT, 14.0F, total), H - 32, LegacyCanvas.alpha(WHITE, a * a));
    }

    private float hoverAlpha(final double lx, final double ly, final int x, final int y, final int size) {
        return lx >= x && lx < x + size && ly >= y && ly < y + size ? 1.0F : 0.75F;
    }

    // ------------------------------------------------------------------ what is listed

    private List<Category> categories() {
        boolean signedIn = signedIn();
        List<Category> shown = new ArrayList<>();
        for (Category candidate : CATEGORIES) {
            if (signedIn || !candidate.needsLogin()) {
                shown.add(candidate);
            }
        }

        return shown;
    }

    private int selected(final List<Category> categories) {
        int index = Math.min(category, CATEGORIES.size() - 1);
        Category wanted = CATEGORIES.get(index);
        int found = categories.indexOf(wanted);
        return found >= 0 ? found : 1;
    }

    private Category current() {
        List<Category> categories = this.categories();
        return categories.get(this.selected(categories));
    }

    private @Nullable CompletableFuture<ListSource> songs(final Category current, final MusicLibrary library) {
        if (DEBUG_DEMO && current.kind() != Kind.SEARCH && current.kind() != Kind.MINE) {
            List<Track> made = new ArrayList<>();
            for (int i = 1; i <= 11; i++) {
                made.add(new Track("demo-" + i, "Song number " + i + " with a longer name", "Artist " + i, "", "", 200_000L + i * 1000L, null));
            }
            return CompletableFuture.completedFuture(new ListSource("Demo", made));
        }

        return switch (current.kind()) {
            case SEARCH -> searched == null ? null : results;
            case PLAYLIST -> library.playlist(current.id(), current.label(), 100);
            case ARTIST -> library.artist(current.id(), current.label());
            case DAILY -> library.daily();
            case MINE -> openList == null ? null : library.playlist(openList.id(), openList.name(), PLAYLIST_LIMIT);
        };
    }

    private @Nullable CompletableFuture<List<NeteaseApi.PlaylistInfo>> playlists(final Category current, final MusicLibrary library) {
        return current.kind() == Kind.MINE && openList == null ? library.myPlaylists() : null;
    }

    private static <T> @Nullable T ready(final @Nullable CompletableFuture<T> future) {
        return future != null && future.isDone() && !future.isCompletedExceptionally() ? future.join() : null;
    }

    private static boolean signedIn() {
        NeteaseAccount account = Client.getInstance().getMusicLibrary().account();
        return account != null && account.state().phase() == NeteaseAccount.Phase.SIGNED_IN;
    }

    private void submit() {
        String query = this.search.text().strip();
        this.scrollTarget = 0.0F;
        this.scrollPx = 0.0F;
        if (query.isEmpty()) {
            searched = null;
            results = null;
            return;
        }

        searched = query;
        // Replacing the future is what drops an earlier, slower search: only this one is ever read.
        results = Client.getInstance().getMusicLibrary().search(query);
    }

    // ------------------------------------------------------------------ input

    /** Whether a point (in framebuffer pixels) is over the window. */
    boolean contains(final double mx, final double my) {
        if (!this.visible()) {
            return false;
        }

        double lx = (mx - this.originX) / this.scale;
        double ly = (my - this.originY) / this.scale;
        return lx >= 0 && lx < W && ly >= 0 && ly < H;
    }

    boolean mouseClicked(final double mx, final double my, final int button) {
        if (!this.contains(mx, my)) {
            this.search.setFocused(false);
            return false;
        }

        double lx = (mx - this.originX) / this.scale;
        double ly = (my - this.originY) / this.scale;
        if (button != 0) {
            return true;
        }

        Category current = this.current();
        if (current.kind() == Kind.SEARCH && this.search.mouseClicked(lx, ly)) {
            return true;
        }
        this.search.setFocused(false);

        int barTop = H - BAR;
        if (ly >= barTop) {
            this.barClick(lx, ly);
            return true;
        }

        if (lx < RAIL) {
            List<Category> categories = this.categories();
            int index = (int) ((ly - TAB_TOP) / TAB_H);
            if (ly >= TAB_TOP && ly < H - 170 && index >= 0 && index < categories.size()) {
                Category picked = categories.get(index);
                if (picked != current || picked.kind() == Kind.MINE) {
                    category = CATEGORIES.indexOf(picked);
                    openList = null;
                    this.scrollTarget = 0.0F;
                    this.scrollPx = 0.0F;
                    this.search.setFocused(picked.kind() == Kind.SEARCH);
                }
            }
            return true;
        }

        this.gridClick(current, lx, ly);
        return true;
    }

    private void barClick(final double lx, final double ly) {
        int barTop = H - BAR;
        int centre = RAIL + (W - RAIL - 38) / 2;
        if (ly >= H - 12 && lx >= RAIL) {
            this.seeking = true;
            this.seekTo(lx);
        } else if (lx >= W - 30 && ly >= barTop + 8 && ly < barTop + 62) {
            this.adjustingVolume = true;
            this.volumeTo(ly);
        } else if (this.player.current() != null) {
            if (hit(lx, ly, centre, barTop + 27, 38)) {
                this.player.toggle();
            } else if (hit(lx, ly, centre + 114, barTop + 23, 46)) {
                this.player.next();
            } else if (hit(lx, ly, centre - 114, barTop + 23, 46)) {
                this.player.previous();
            }
        } else if (hit(lx, ly, centre, barTop + 27, 38)) {
            this.player.toggle();
        }
    }

    private static boolean hit(final double lx, final double ly, final int x, final int y, final int size) {
        return lx >= x && lx < x + size && ly >= y && ly < y + size;
    }

    private void gridClick(final Category current, final double lx, final double ly) {
        MusicLibrary library = Client.getInstance().getMusicLibrary();
        CompletableFuture<ListSource> songs = this.songs(current, library);
        CompletableFuture<List<NeteaseApi.PlaylistInfo>> lists = this.playlists(current, library);
        CompletableFuture<?> pending = songs != null ? songs : lists;
        if (pending == null || !pending.isDone()) {
            return;
        }

        if (pending.isCompletedExceptionally() || isEmpty(pending)) {
            // A failed or empty list is asked for again; a search is simply repeated.
            if (current.kind() == Kind.SEARCH) {
                this.submit();
            } else {
                library.retry(pending);
            }
            return;
        }

        int top = GRID_TOP + (current.kind() == Kind.SEARCH ? 6 : 0);
        for (int i = 0; i < (songs != null ? songs.join().tracks().size() : lists.join().size()); i++) {
            float x = GRID_X + (i % COLUMNS) * (CARD_W + GRID_GAP);
            float y = top + 6 + (i / COLUMNS) * CARD_H - this.scrollPx;
            if (lx >= x && lx < x + CARD_W && ly >= Math.max(y, top) && ly < y + CARD_H && ly < H - BAR) {
                if (songs != null) {
                    this.player.setSource(songs.join(), i, true);
                } else {
                    openList = lists.join().get(i);
                    this.scrollTarget = 0.0F;
                    this.scrollPx = 0.0F;
                }
                return;
            }
        }

        // The label of an opened playlist takes you back to the list of them.
        if (current.kind() == Kind.MINE && openList != null && ly < GRID_TOP) {
            openList = null;
        }
    }

    private static boolean isEmpty(final CompletableFuture<?> done) {
        Object value = done.join();
        return value instanceof ListSource source ? source.tracks().isEmpty() : value instanceof List<?> list && list.isEmpty();
    }

    void mouseDragged(final double mx, final double my) {
        double lx = (mx - this.originX) / this.scale;
        double ly = (my - this.originY) / this.scale;
        if (this.seeking) {
            this.seekTo(lx);
        } else if (this.adjustingVolume) {
            this.volumeTo(ly);
        } else if (this.search.focused()) {
            this.search.mouseDragged(lx);
        }
    }

    void mouseReleased() {
        this.seeking = false;
        this.adjustingVolume = false;
    }

    boolean mouseScrolled(final double mx, final double my, final double amount) {
        if (!this.contains(mx, my)) {
            return false;
        }

        double lx = (mx - this.originX) / this.scale;
        if (lx >= RAIL) {
            this.scrollTarget = Math.max(0.0F, this.scrollTarget - (float) amount * 70.0F);
        }

        return true;
    }

    boolean keyPressed(final KeyEvent event) {
        if (!this.typing()) {
            return false;
        }

        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.search.setFocused(false);
        } else if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            this.submit();
        } else {
            this.search.keyPressed(event);
        }

        // A key the field does not use still must not reach the screen's own shortcuts while typing.
        return true;
    }

    boolean charTyped(final CharacterEvent event) {
        return this.typing() && this.search.charTyped(event);
    }

    private void seekTo(final double lx) {
        if (this.player.current() != null) {
            this.player.seekFraction((float) Math.max(0.0, Math.min(1.0, (lx - RAIL) / (W - RAIL))));
        }
    }

    private void volumeTo(final double ly) {
        float volume = (float) (1.0 - (ly - (H - BAR + 14)) / 40.0);
        this.player.setVolume(Math.max(0.0F, Math.min(1.0F, volume)));
    }

    // ------------------------------------------------------------------ text

    /** {@code text} cut with an ellipsis to fit {@code width} pixels. */
    private String fit(final LegacyCanvas c, final Face face, final float size, final String text, final float width) {
        if (text == null || c.textWidth(face, size, text) <= width) {
            return text == null ? "" : text;
        }

        String cut = text;
        while (!cut.isEmpty() && c.textWidth(face, size, cut + "…") > width) {
            cut = cut.substring(0, cut.length() - 1);
        }

        return cut + "…";
    }

    static String time(final long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
    }

    private static boolean zh() {
        return Minecraft.getInstance().getLanguageManager().getSelected().toLowerCase(Locale.ROOT).startsWith("zh");
    }

    /** The scroll eases toward where the wheel put it. */
    private static float smooth(final float current, final float target, final float dt) {
        return current + (target - current) * (1.0F - (float) Math.exp(-14.0F * dt));
    }
}
