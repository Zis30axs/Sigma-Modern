package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.music.ListSource;
import com.mentalfrostbyte.jello.music.MusicLibrary;
import com.mentalfrostbyte.jello.music.MusicPlayer;
import com.mentalfrostbyte.jello.music.Spectrum;
import com.mentalfrostbyte.jello.music.Track;
import com.mentalfrostbyte.jello.music.netease.NeteaseAccount;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.PreeditEvent;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The contents of SigmaModern's music window ({@link ModernMusicDrawer}): a "Jello music" title bar (the NetEase
 * account, settings and an equalizer), a rail of categories down the left - the player, search, the charts, an
 * artist and, signed in, the daily picks and one's playlists - and the page the rail points at beside it.
 *
 * <p>The player page is laid out like the reference design's player column: the album art (album and mood
 * lettered over it), the track title and number, a timeline, the transport, the queue and the volume. It lays
 * itself out from the region it is given - the same numbers for drawing and for input, so clicks work even before
 * a frame has been drawn. Short on height it drops the art first, then the queue, and in a very short window it
 * puts the transport beside the title. With no audio backend connected the subtitle says so.</p>
 *
 * <p>Online, the queue starts as NetEase's hot chart, fetched the first time the window is shown. Clicking the
 * art turns it over to the lyrics ({@link ModernLyricsPanel}); when there is no room for the art, the line being
 * sung takes the subtitle's place instead. The subtitle also carries the stream's state: a preview-only track, or
 * why the last one couldn't play. The other pages: {@link ModernMusicBrowser} (search and every category),
 * {@link ModernMusicAccount} (QR sign-in) and {@link ModernMusicSettings} (how lyrics are shown).</p>
 */
final class ModernMusicView {
    static final int HEADER_H = 22;
    private static final int RAIL_W = 54, RAIL_ITEM = 20, RAIL_GAP = 2;
    private static final int ROW_H = 17;
    private static final int QUEUE_LABEL_H = 20;

    record Box(int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
        }
    }

    /** The window's fixed parts: the title bar's buttons, the rail and the content area the pages lay out in. */
    private record Frame(int headerY, int rx, int rw, Box account, Box settings, Box rail, Box content) {}

    private record Layout(Box panel, boolean compact, Box art, int rx, int rw, int titleY, Box seek, int timelineY,
                          Box prev, Box play, Box next, int queueY, int listY, int rows, Box list, Box volume, int volumeY) {}

    private record RailItem(String key, String label) {}

    private enum Drag { NONE, SEEK, VOLUME }

    private enum Page { PLAYER, BROWSE, ACCOUNT, SETTINGS }

    // Shared by every window: where it is and what it shows are the window's own state, not the screen's.
    private static boolean chartWanted;
    private static boolean lyricsShown = "lyrics".equalsIgnoreCase(System.getProperty("sigma.debug.musicPage"));
    private static Page page = switch (String.valueOf(System.getProperty("sigma.debug.musicPage")).toLowerCase(Locale.ROOT)) {
        case "browse" -> Page.BROWSE;
        case "account" -> Page.ACCOUNT;
        case "settings", "fx" -> Page.SETTINGS;
        default -> Page.PLAYER;
    };
    private static float railScroll;

    private final MusicPlayer player;
    private final ModernLyricsPanel lyricsPanel = new ModernLyricsPanel();
    private final ModernMusicBrowser browser;
    private final ModernMusicAccount accountPage = new ModernMusicAccount();
    private final ModernMusicSettings settingsPage = new ModernMusicSettings();
    private final Map<String, Float> anim = new HashMap<>();
    private final float[] bars = new float[4];
    private Layout layout;
    // The content area last drawn or clicked, for drags that carry no region of their own.
    private @Nullable Box content;
    private Drag drag = Drag.NONE;
    private float dragFraction;
    private int listScroll;
    private int shownIndex = -1;
    private float dt, time;

    ModernMusicView(MusicPlayer player) {
        this.player = player;
        this.browser = new ModernMusicBrowser(player);
    }

    private static Frame frame(int x, int y, int w, int h) {
        int rx = x + 12, rw = w - 24, headerY = y + 8;
        // Right to left along the title bar: the equalizer, settings, the account.
        Box settings = new Box(rx + rw - 17 - 7 - 18, headerY + 2, 18, 18);
        Box account = new Box(settings.x() - 3 - 18, headerY + 2, 18, 18);
        int top = headerY + HEADER_H + 7;
        Box rail = new Box(x + 8, top, RAIL_W, y + h - 8 - top);
        int cx = rail.x() + RAIL_W + 10;
        Box content = new Box(cx, top, x + w - 12 - cx, y + h - 10 - top);
        return new Frame(headerY, rx, rw, account, settings, rail, content);
    }

    /** The player page inside the content area. */
    private Layout layout(Box c) {
        int x = c.x(), y = c.y(), w = c.w(), h = c.h();
        int rx = x, rw = w;
        boolean compact = h < 150;
        if (compact) {
            // The title with the transport beside it, the timeline and the volume.
            int titleY = y + 2;
            Box next = new Box(x + w - 22, titleY + 1, 22, 22);
            Box play = new Box(next.x() - 4 - 24, titleY, 24, 24);
            Box prev = new Box(play.x() - 4 - 22, titleY + 1, 22, 22);
            int timelineY = titleY + 38;
            int volumeY = Math.max(timelineY + 24, y + h - 4);
            Box volume = new Box(rx + 14, volumeY - 5, Math.max(20, rw - 14 - 34), 13);
            // Whatever room is left between the timeline and the volume goes to the queue (without its label).
            int listY = timelineY + 20;
            int rows = Math.max(0, Math.min(this.player.queue().size(), (volumeY - 12 - listY) / ROW_H));
            Box list = new Box(rx - 4, listY, rw + 8, rows * ROW_H);
            return new Layout(c, true, new Box(rx, titleY, 0, 0), rx, rw, titleY, new Box(rx - 2, timelineY - 5, rw + 4, 13),
                timelineY, prev, play, next, listY - QUEUE_LABEL_H, listY, rows, list, volume, volumeY);
        }

        int playSize = h < 250 ? 28 : 34;
        int fixed = 38 + 26 + playSize + 14 + 22 + 8;
        // The art is the page's centrepiece: it keeps room for three queue rows if it can, gives rows up one by one
        // down to a single one rather than disappear, and only goes when even that leaves it tiny.
        int artSize = 0;
        int queued = this.player.queue().size();
        for (int keep = Math.min(3, queued); keep >= Math.min(1, queued); keep--) {
            int queueNeed = keep == 0 ? 0 : QUEUE_LABEL_H + keep * ROW_H + 6;
            int candidate = Math.min(rw, h - fixed - queueNeed - 10);
            if (candidate >= 72) {
                artSize = candidate;
                break;
            }
        }

        int cy = y;
        Box art = new Box(x + (w - artSize) / 2, cy, artSize, artSize);
        if (artSize > 0) cy += artSize + 12;
        int titleY = cy;
        cy += 38;
        int timelineY = cy + 4;
        Box seek = new Box(rx - 2, timelineY - 5, rw + 4, 13);
        cy += 26;
        int center = x + w / 2;
        Box play = new Box(center - playSize / 2, cy, playSize, playSize);
        Box prev = new Box(center - playSize / 2 - 14 - 26, cy + playSize / 2 - 13, 26, 26);
        Box next = new Box(center + playSize / 2 + 14, cy + playSize / 2 - 13, 26, 26);
        cy += playSize + 14;

        int volumeY = y + h - 6;
        Box volume = new Box(rx + 14, volumeY - 5, Math.max(20, rw - 14 - 34), 13);
        int queueY = cy;
        int listY = queueY + QUEUE_LABEL_H;
        int rows = Math.max(0, Math.min(this.player.queue().size(), (volumeY - 12 - listY) / ROW_H));
        Box list = new Box(rx - 4, listY, rw + 8, rows * ROW_H);
        return new Layout(c, false, art, rx, rw, titleY, seek, timelineY, prev, play, next, queueY, listY, rows, list, volume, volumeY);
    }

    /** The title bar - where the window can be grabbed and dragged, as well as by its tab. */
    static Box header(int x, int y, int w) {
        return new Box(x, y, w, 8 + HEADER_H);
    }

    /** The rail: the player, then every category the browser has (the signed-in ones only when signed in). */
    private static List<RailItem> railItems() {
        List<RailItem> items = new ArrayList<>();
        items.add(new RailItem("player", ModernText.t("Playing", "播放")));
        if (Client.getInstance().getMusicLibrary().isOnline()) {
            for (ModernMusicBrowser.Category category : ModernMusicBrowser.categories(signedIn())) {
                items.add(new RailItem(category.key(), category.label()));
            }
        }
        return items;
    }

    static boolean signedIn() {
        NeteaseAccount account = Client.getInstance().getMusicLibrary().account();
        return account != null && account.state().phase() == NeteaseAccount.Phase.SIGNED_IN;
    }

    private static Box railItem(Frame f, int index) {
        return new Box(f.rail().x(), f.rail().y() + index * (RAIL_ITEM + RAIL_GAP) - Math.round(railScroll), f.rail().w(), RAIL_ITEM);
    }

    private static @Nullable String activeRailKey() {
        return switch (page) {
            case PLAYER -> "player";
            case BROWSE -> ModernMusicBrowser.category();
            default -> null;
        };
    }

    // --- rendering ------------------------------------------------------------------------------------

    void render(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my, float dt, float time) {
        this.dt = dt;
        this.time = time;
        ensureChart();
        Frame f = frame(x, y, w, h);
        boolean playing = this.player.isPlaying();

        // The reference's navy glass panel, a faint sheen at the top, darkening towards the foot.
        // Nearly opaque: it's a window over the page, and a big bright clock behind it mustn't read through.
        ModernStyle.darkGlass(g, x, y, w, h, 13, 0xF7152A3E);
        // Across the whole inner surface, corners included (inset past the corners, they left a strip down each side).
        ModernStyle.roundedGradient(g, x + 1, y + 1, w - 2, h - 2, 12, y + 1, y + h / 4, 0x14FFFFFF, 0x00FFFFFF);
        ModernStyle.roundedGradient(g, x + 1, y + 1, w - 2, h - 2, 12, y + h / 2, y + h - 1, 0x000A1422, 0x400A1422);
        drawTitleBar(g, f, playing, mx, my);
        drawRail(g, f, mx, my);

        Box c = this.content = f.content();
        switch (page) {
            case BROWSE -> this.browser.render(g, c, mx, my, dt, time);
            case ACCOUNT -> this.accountPage.render(g, c, mx, my, dt, time);
            case SETTINGS -> this.settingsPage.render(g, c, this.player, mx, my);
            case PLAYER -> renderPlayer(g, c, mx, my, playing);
        }
    }

    private void renderPlayer(GuiGraphicsExtractor g, Box c, int mx, int my, boolean playing) {
        Layout l = this.layout = layout(c);
        Track track = this.player.current();
        float playLight = animate("playing", playing ? 1F : 0F, 6F);
        keepCurrentVisible(l);
        if (track == null) {
            drawEmpty(g, c);
            return;
        }
        if (l.art().w() > 0) drawArt(g, l, track, playLight, mx, my);
        drawTitle(g, l, track);
        drawTimeline(g, l, mx, my);
        drawTransport(g, l, mx, my, playing);
        if (l.rows() > 0) drawQueue(g, l, mx, my, !l.compact());
        drawVolume(g, l, mx, my);
    }

    private void drawTitleBar(GuiGraphicsExtractor g, Frame f, boolean playing, int mx, int my) {
        float y = f.headerY();
        MusicLibrary library = Client.getInstance().getMusicLibrary();
        if (library.isOnline()) {
            iconButton(g, "settings", f.settings(), ModernIcons.Icon.GEAR, mx, my, page == Page.SETTINGS);
            iconButton(g, "account", f.account(), ModernIcons.Icon.PERSON, mx, my, page == Page.ACCOUNT);
            if (signedIn()) {
                // Signed in: a small lit dot on the person.
                Box a = f.account();
                ModernStyle.halo(g, a.x() + a.w() - 6, a.y() + 3, 4, 4, 2, ModernStyle.GLOW, 0.6F);
                ModernStyle.rounded(g, a.x() + a.w() - 6, a.y() + 3, 4, 4, 2, ModernRows.GOOD);
            }
        }
        float jelloW = ModernTypography.width(ModernTypography.Face.DISPLAY, "Jello", 1.45F);
        ModernTypography.draw(g, ModernTypography.Face.DISPLAY, "Jello", f.rx(), y + 1F, 1.45F, 0xFFF0F6FC);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, "music", f.rx() + jelloW + 4F, y + 7F, 0.88F, 0xFFC1D1E0);
        soundBars(g, this.bars, f.rx() + f.rw() - 17F, y + 11F, 14F, 2F, 3F, 0xFF8FCBEF, playing, this.dt, this.time,
            levels(this.player.spectrum(), this.bars.length));
        ModernStyle.fill(g, f.rx(), Math.round(y + HEADER_H), f.rx() + f.rw(), Math.round(y + HEADER_H) + 1, 0x14B8D3E7);
    }

    /** The categories, one per row; the one on show is lit with an ice bar. Scrolls when there are more than fit. */
    private void drawRail(GuiGraphicsExtractor g, Frame f, int mx, int my) {
        Box rail = f.rail();
        List<RailItem> items = railItems();
        clampRail(rail, items.size());
        String active = activeRailKey();
        g.enableScissor(rail.x(), rail.y(), rail.x() + rail.w(), rail.y() + rail.h());
        try {
            for (int i = 0; i < items.size(); i++) {
                RailItem item = items.get(i);
                Box box = railItem(f, i);
                if (box.y() + box.h() < rail.y() || box.y() > rail.y() + rail.h()) continue;
                boolean on = item.key().equals(active);
                float hover = animate("rail-" + item.key(), box.contains(mx, my) && rail.contains(mx, my) ? 1F : 0F, 16F);
                float lit = animate("rail-on-" + item.key(), on ? 1F : 0F, 14F);
                if (lit > 0.01F) {
                    ModernStyle.rounded(g, box.x(), box.y(), box.w(), box.h(), 7, ModernTypography.fade(0x2ECDEBFF, lit));
                    ModernStyle.rounded(g, box.x() + 2, box.y() + 5, 2, box.h() - 10, 1, ModernTypography.fade(0xFF9FDCFF, lit));
                } else if (hover > 0.01F) {
                    ModernStyle.rounded(g, box.x(), box.y(), box.w(), box.h(), 7, Math.round(0x14 * hover) << 24 | 0xCDEBFF);
                }
                String label = ModernTypography.wrap(ModernTypography.Face.TEXT, item.label(), 0.86F, box.w() - 14F, 1).getFirst();
                int color = ModernStyle.mix(ModernStyle.mix(0xFFA3BDD0, 0xFFE3F0F8, hover), 0xFFF4FAFF, lit);
                ModernTypography.draw(g, ModernTypography.Face.TEXT, label, box.x() + 9F, box.y() + 5.5F, 0.86F, color);
            }
        } finally {
            g.disableScissor();
        }
        int sx = rail.x() + rail.w() + 4;
        ModernStyle.fill(g, sx, rail.y(), sx + 1, rail.y() + rail.h(), 0x14B8D3E7);
    }

    private static void clampRail(Box rail, int items) {
        float max = Math.max(0, items * (RAIL_ITEM + RAIL_GAP) - RAIL_GAP - rail.h());
        railScroll = Math.max(0F, Math.min(railScroll, max));
    }

    /** The empty queue: the chart on its way, the chart failed (click to retry), or simply nothing queued. */
    private void drawEmpty(GuiGraphicsExtractor g, Box c) {
        MusicLibrary library = Client.getInstance().getMusicLibrary();
        CompletableFuture<ListSource> pending = library.isOnline() && chartWanted ? library.chart() : null;
        String message = pending == null ? ModernText.t("No tracks in this session", "当前没有曲目")
            : !pending.isDone() ? ModernText.t("Loading the chart...", "正在载入热歌榜…")
            : ModernText.t("Couldn't load the chart - click to retry", "热歌榜载入失败 · 点击重试");
        float scale = 1.05F;
        float cy = c.y() + c.h() / 2F - 8F;
        for (String line : ModernTypography.wrap(ModernTypography.Face.DISPLAY_ITALIC, message, scale, c.w() - 8F, 2)) {
            float mw = ModernTypography.width(ModernTypography.Face.DISPLAY_ITALIC, line, scale);
            ModernTypography.draw(g, ModernTypography.Face.DISPLAY_ITALIC, line, c.x() + (c.w() - mw) / 2F, cy, scale, 0xFF9DBCD0);
            cy += 13F;
        }
        if (pending != null && !pending.isDone()) spinner(g, c.x() + c.w() / 2F, cy + 10F, 6F, this.time, 0xFFB6E5FF);
    }

    private void drawArt(GuiGraphicsExtractor g, Layout l, Track track, float playLight, int mx, int my) {
        Box art = l.art();
        float lyrics = animate("lyrics", lyricsShown ? 1F : 0F, 9F);
        float hover = animate("art-hover", art.contains(mx, my) ? 1F : 0F, 12F);
        // The sleeve comes forward a touch while playing, as in the reference (not while it shows the lyrics).
        float size = art.w() * (1F + playLight * 0.03F * (1F - lyrics));
        float ax = art.x() - (size - art.w()) / 2F, ay = art.y() - (size - art.h()) / 2F;
        if (playLight > 0.01F) ModernStyle.halo(g, art.x(), art.y(), art.w(), art.h(), 8, ModernStyle.GLOW, 0.4F * playLight * (1F - lyrics));
        ModernStyle.dropShadow(g, art.x(), art.y() + 4, art.w(), art.h(), 7, 0.7F);
        if (lyrics < 0.995F) ModernCovers.draw(g, track, ax, ay, size, 0.04F, 0xFFFFFFFF);
        try (var letters = ModernStyle.alphaScope(1F - lyrics)) {
            if (!track.album().isEmpty()) {
                String album = ModernTypography.wrap(ModernTypography.Face.TEXT, ModernStyle.spaced(track.album().toUpperCase(Locale.ROOT)),
                    0.6F, art.w() - 18F, 1).getFirst();
                ModernTypography.draw(g, ModernTypography.Face.TEXT, album, art.x() + 9F, art.y() + 9F, 0.6F, 0xE6CDE2F1);
            }
            if (!track.tag().isEmpty()) {
                String tag = ModernStyle.spaced(track.tag().toUpperCase(Locale.ROOT));
                ModernTypography.draw(g, ModernTypography.Face.TEXT, tag,
                    art.x() + art.w() - 9F - ModernTypography.width(ModernTypography.Face.TEXT, tag, 0.56F), art.y() + art.h() - 14F, 0.56F, 0xD9B6CBDC);
            }
        }
        if (lyrics > 0.01F) {
            try (var over = ModernStyle.alphaScope(lyrics)) {
                // An opaque card of its own over the cover exactly as drawn - mid-switch, while playing, the sleeve is
                // still popped out past the art box - a pixel wider all round so the cover's soft rim can't show.
                int ox = (int)Math.floor(ax) - 1, oy = (int)Math.floor(ay) - 1;
                int os = (int)Math.ceil(ax + size) + 1 - ox, radius = (int)Math.floor(size * 0.04F);
                ModernStyle.rounded(g, ox, oy, os, os, radius + 1, 0x33CDEBFF);
                ModernStyle.rounded(g, ox + 1, oy + 1, os - 2, os - 2, radius, 0xFF0D1B29);
                this.lyricsPanel.render(g, art.x(), art.y(), art.w(), art.h(), this.player, track, this.dt);
            }
        }
        if (hover > 0.01F) {
            // Says what a click will turn the art over to.
            String label = lyricsShown ? ModernText.t("Cover", "封面") : ModernText.t("Lyrics", "歌词");
            float tw = ModernTypography.width(ModernTypography.Face.TEXT, label, 0.78F);
            int cw = Math.round(tw) + 22, ch = 15, cx = art.x() + 7, cy = art.y() + art.h() - 7 - ch;
            try (var chip = ModernStyle.alphaScope(hover)) {
                ModernStyle.rounded(g, cx, cy, cw, ch, 7, 0xCC0B1826);
                ModernIcons.draw(g, lyricsShown ? ModernIcons.Icon.EYE : ModernIcons.Icon.NOTE, cx + 5F, cy + 3.5F, 8F, 0xFFD6ECFA);
                ModernTypography.draw(g, ModernTypography.Face.TEXT, label, cx + 16F, cy + 3.5F, 0.78F, 0xFFE3F2FA);
            }
        }
    }

    private void drawTitle(GuiGraphicsExtractor g, Layout l, Track track) {
        int rx = l.rx(), rw = l.rw();
        float titleRoom;
        if (l.compact()) {
            // The transport sits where the track number would be.
            titleRoom = l.prev().x() - rx - 8F;
        } else {
            String number = String.format(Locale.ROOT, "%02d", this.player.index() + 1);
            float numberW = ModernTypography.width(ModernTypography.Face.DISPLAY, number, 1.8F);
            ModernTypography.draw(g, ModernTypography.Face.DISPLAY, number, rx + rw - numberW, l.titleY() - 1F, 1.8F, 0xFF7E9AB1);
            titleRoom = rw - numberW - 10F;
        }
        float titleScale = l.compact() ? 1.3F : 1.5F;
        String title = ModernTypography.wrap(ModernTypography.Face.DISPLAY, track.title(), titleScale, titleRoom, 1).getFirst();
        ModernTypography.draw(g, ModernTypography.Face.DISPLAY, title, rx, l.titleY(), titleScale, 0xFFF0F6FC);

        float sy = l.titleY() + (l.compact() ? 18F : 20F);
        String problem = this.player.problem();
        if (problem != null) {
            String text = ModernTypography.wrap(ModernTypography.Face.TEXT, problemText(problem), 0.84F, titleRoom, 1).getFirst();
            ModernTypography.draw(g, ModernTypography.Face.TEXT, text, rx, sy, 0.84F, ModernRows.BAD);
            return;
        }
        // Without the art there's no lyrics panel to turn to: the line being sung goes here.
        ModernLyricSweep.Now now = l.art().w() == 0 ? ModernLyricSweep.now(this.player) : null;
        if (now != null) {
            String text = now.line().text().strip();
            String line = ModernTypography.wrap(ModernTypography.Face.TEXT, text, 0.88F, titleRoom, 1).getFirst();
            float lit = now.progress() * text.length() / Math.max(1, line.length());
            ModernLyricSweep.draw(g, ModernTypography.Face.TEXT, line, rx, sy, 0.88F, lit, 0xFF8FA9BD, 0xFFF0F8FF);
            return;
        }
        // The artist (the album is lettered on the art), then the stream's state or the track's tag.
        String album = !track.artist().isEmpty() ? track.artist() : track.album();
        if (album.isEmpty()) album = this.player.sourceName();
        String extra;
        int color;
        if (!this.player.producesSound()) {
            extra = ModernText.t("preview - no audio source", "预览 · 未接入音源");
            color = ModernRows.FAIR;
        } else if (this.player.isPreview()) {
            extra = ModernText.t("preview clip", "试听片段");
            color = ModernRows.FAIR;
        } else {
            extra = track.tag();
            color = 0xFFA3B9CC;
        }
        String shown = ModernTypography.wrap(ModernTypography.Face.TEXT, album, 0.88F, titleRoom, 1).getFirst();
        float albumW = ModernTypography.width(ModernTypography.Face.TEXT, shown, 0.88F);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, shown, rx, sy, 0.88F, 0xFFBCCDDD);
        if (!extra.isEmpty() && titleRoom - albumW > 40F) {
            // wrap() strips the edges, so the gap before the separator is a fixed offset rather than a space.
            String more = ModernTypography.wrap(ModernTypography.Face.TEXT, "· " + extra, 0.8F, titleRoom - albumW - 4F, 1).getFirst();
            ModernTypography.draw(g, ModernTypography.Face.TEXT, more, rx + albumW + 4F, sy + 0.6F, 0.8F, color);
        }
    }

    /** The backend's failure reasons, for people. */
    static String problemText(String reason) {
        return switch (reason) {
            case "unavailable" -> ModernText.t("Not available (copyright or VIP only)", "暂无音源（版权或会员限制）");
            case "network" -> ModernText.t("Network error", "网络错误");
            case "undecodable" -> ModernText.t("Can't decode this stream", "无法解码音频");
            case "no audio device" -> ModernText.t("No audio output device", "没有可用的音频输出设备");
            case "unsupported" -> ModernText.t("Unsupported audio format", "不支持的音频格式");
            default -> reason.startsWith("http ") ? ModernText.t("Stream error ", "音源错误 ") + reason.substring(5) : reason;
        };
    }

    private void drawTimeline(GuiGraphicsExtractor g, Layout l, int mx, int my) {
        boolean hot = this.drag == Drag.SEEK || l.seek().contains(mx, my);
        float fraction = this.drag == Drag.SEEK ? this.dragFraction : this.player.progress();
        range(g, l.rx(), l.timelineY(), l.rw(), fraction, 0xFFB6E5FF, 0x33BED8EB, animate("seek-hot", hot ? 1F : 0F, 14F));
        long duration = this.player.durationMs();
        long elapsed = this.drag == Drag.SEEK ? Math.round(duration * this.dragFraction) : this.player.positionMs();
        String left = time(elapsed), right = time(duration);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, left, l.rx(), l.timelineY() + 6F, 0.76F, 0xFFAEC4D6);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, right,
            l.rx() + l.rw() - ModernTypography.width(ModernTypography.Face.TEXT, right, 0.76F), l.timelineY() + 6F, 0.76F, 0xFFAEC4D6);
    }

    private void drawTransport(GuiGraphicsExtractor g, Layout l, int mx, int my, boolean playing) {
        iconButton(g, "prev", l.prev(), ModernIcons.Icon.SKIP_PREV, mx, my, false);
        iconButton(g, "next", l.next(), ModernIcons.Icon.SKIP_NEXT, mx, my, false);
        Box play = l.play();
        float hover = animate("play", play.contains(mx, my) ? 1F : 0F, 16F);
        float size = play.w() * (1F + 0.045F * hover);
        float cx = play.x() + play.w() / 2F, cy = play.y() + play.h() / 2F;
        int s = Math.round(size), px = Math.round(cx - size / 2F), py = Math.round(cy - size / 2F);
        ModernStyle.halo(g, px, py, s, s, s / 2, ModernStyle.GLOW, 0.3F + 0.35F * hover);
        // The reference's pale-ice disc: a light top fading into ice blue.
        ModernStyle.rounded(g, px, py, s, s, s / 2, 0xFFC4E7FC);
        ModernStyle.rounded(g, px + 2, py + 1, s - 4, s / 2 + 2, s / 2 - 2, 0x99F1FAFF);
        float icon = play.w() * 0.42F;
        ModernIcons.draw(g, playing ? ModernIcons.Icon.PAUSE : ModernIcons.Icon.PLAY,
            cx - icon / 2F + (playing ? 0F : icon * 0.06F), cy - icon / 2F, icon, 0xFF253D4F);
        float buffering = animate("buffering", this.player.isBuffering() ? 1F : 0F, 8F);
        if (buffering > 0.01F) {
            try (var ring = ModernStyle.alphaScope(buffering)) {
                spinner(g, cx, cy, size / 2F + 4F, this.time, 0xFFCDEEFF);
            }
        }
    }

    /** Eight dots in a ring, a bright one chasing round: waiting on the network. */
    static void spinner(GuiGraphicsExtractor g, float cx, float cy, float radius, float time, int color) {
        for (int i = 0; i < 8; i++) {
            double angle = i / 8.0 * Math.PI * 2.0 - Math.PI / 2.0;
            float phase = ((time * 1.4F - i / 8F) % 1F + 1F) % 1F;
            float alpha = 0.2F + 0.8F * (1F - phase) * (1F - phase);
            int dx = Math.round(cx + (float)Math.cos(angle) * radius - 1F), dy = Math.round(cy + (float)Math.sin(angle) * radius - 1F);
            ModernStyle.rounded(g, dx, dy, 2, 2, 1, ModernTypography.fade(color, alpha));
        }
    }

    private void iconButton(GuiGraphicsExtractor g, String key, Box box, ModernIcons.Icon icon, int mx, int my, boolean on) {
        float hover = animate(key, box.contains(mx, my) || on ? 1F : 0F, 16F);
        if (hover > 0.01F) ModernStyle.rounded(g, box.x(), box.y(), box.w(), box.h(), box.w() / 2, Math.round(0x1E * hover) << 24 | 0xCDEBFF);
        float size = Math.min(13F, box.w() * 0.5F);
        ModernIcons.draw(g, icon, box.x() + (box.w() - size) / 2F, box.y() + (box.h() - size) / 2F, size, ModernStyle.mix(0xFFDAE8F3, 0xFFFFFFFF, hover));
    }

    private void drawQueue(GuiGraphicsExtractor g, Layout l, int mx, int my, boolean label) {
        int rx = l.rx(), rw = l.rw();
        if (label) {
            ModernStyle.fill(g, rx, l.queueY(), rx + rw, l.queueY() + 1, 0x1CB8D3E7);
            String count = String.format(Locale.ROOT, "%02d", this.player.queue().size());
            String name = this.player.sourceName();
            // Latin names get the reference's tracked capitals; CJK ones are left alone.
            if (name.chars().allMatch(c -> c < 0x80)) name = ModernStyle.spaced(name.toUpperCase(Locale.ROOT));
            name = ModernTypography.wrap(ModernTypography.Face.TEXT, name, 0.68F,
                rw - ModernTypography.width(ModernTypography.Face.TEXT, count, 0.68F) - 10F, 1).getFirst();
            ModernTypography.draw(g, ModernTypography.Face.TEXT, name, rx, l.queueY() + 7F, 0.68F, 0xFFA9BFD2);
            ModernTypography.draw(g, ModernTypography.Face.TEXT, count, rx + rw - ModernTypography.width(ModernTypography.Face.TEXT, count, 0.68F),
                l.queueY() + 7F, 0.68F, 0xFFA9BFD2);
        }

        List<Track> queue = this.player.queue();
        for (int r = 0; r < l.rows(); r++) {
            int i = this.listScroll + r;
            if (i >= queue.size()) break;
            Track track = queue.get(i);
            int y = l.listY() + r * ROW_H;
            boolean current = i == this.player.index();
            Box row = new Box(rx - 4, y, rw + 8, ROW_H);
            float hover = animate("row-" + i, row.contains(mx, my) ? 1F : 0F, 16F);
            if (hover > 0.01F) ModernStyle.rounded(g, row.x(), row.y(), row.w(), row.h(), 4, Math.round(0x10 * hover) << 24 | 0xBEDEF1);
            float ty = y + ROW_H / 2F - 5F;
            ModernTypography.draw(g, ModernTypography.Face.TEXT, String.format(Locale.ROOT, "%02d", i + 1), rx, ty + 0.8F, 0.78F, 0xFF7F9EB5);
            int titleColor = current ? 0xFFDEF2FF : ModernStyle.mix(0xFFB1C7D8, 0xFFFFFFFF, hover);
            String length = time(track.durationMs());
            float lengthW = ModernTypography.width(ModernTypography.Face.TEXT, length, 0.78F);
            float titleX = rx + 20F;
            String title = ModernTypography.wrap(ModernTypography.Face.TEXT, track.title(), 0.95F, rw - 20 - lengthW - 20, 1).getFirst();
            ModernTypography.draw(g, ModernTypography.Face.TEXT, title, titleX, ty, 0.95F, titleColor);
            if (current) {
                float dotX = titleX + ModernTypography.width(ModernTypography.Face.TEXT, title, 0.95F) + 7F;
                ModernStyle.halo(g, Math.round(dotX), Math.round(ty + 3F), 4, 4, 2, ModernStyle.GLOW, 0.6F);
                ModernStyle.rounded(g, Math.round(dotX), Math.round(ty + 3F), 4, 4, 2, 0xFF93D8FF);
            }
            ModernTypography.draw(g, ModernTypography.Face.TEXT, length, rx + rw - lengthW, ty + 0.8F, 0.78F, 0xFF91AEC4);
        }
    }

    private void drawVolume(GuiGraphicsExtractor g, Layout l, int mx, int my) {
        int rx = l.rx(), rw = l.rw();
        ModernStyle.fill(g, rx, l.volumeY() - 9, rx + rw, l.volumeY() - 8, 0x1CB8D3E7);
        ModernIcons.draw(g, ModernIcons.Icon.SPEAKER, rx, l.volumeY() - 5F, 10F, 0xFF9DB9CF);
        Box bar = l.volume();
        boolean hot = this.drag == Drag.VOLUME || bar.contains(mx, my);
        range(g, bar.x() + 2, l.volumeY(), bar.w() - 4, this.player.volume(), 0xFFB6E5FF, 0x33BED8EB, animate("volume-hot", hot ? 1F : 0F, 14F));
        String percent = Math.round(this.player.volume() * 100F) + "%";
        ModernTypography.draw(g, ModernTypography.Face.TEXT, percent,
            rx + rw - ModernTypography.width(ModernTypography.Face.TEXT, percent, 0.78F), l.volumeY() - 4F, 0.78F, 0xFF9DB9CF);
    }

    /** A 3px track filled to {@code fraction}, with a small knob that swells and glows under the pointer. */
    static void range(GuiGraphicsExtractor g, int x, int y, int w, float fraction, int fill, int track, float hot) {
        fraction = Math.max(0F, Math.min(1F, fraction));
        ModernStyle.rounded(g, x, y - 1, w, 3, 1, track);
        int filled = Math.round(w * fraction);
        if (filled > 0) ModernStyle.rounded(g, x, y - 1, filled, 3, 1, fill);
        int knob = 7 + Math.round(2 * hot);
        int kx = x + filled - knob / 2, ky = y - knob / 2;
        ModernStyle.halo(g, kx, ky, knob, knob, knob / 2, ModernStyle.GLOW, 0.25F + 0.5F * hot);
        ModernStyle.rounded(g, kx, ky, knob, knob, knob / 2, 0xFFDCF2FF);
    }

    /**
     * An equalizer of {@code heights.length} bars centred on {@code cy}: they breathe while playing and settle
     * to short stubs when paused. {@code heights} is the caller's own state, so each place animates independently.
     */
    static void soundBars(GuiGraphicsExtractor g, float[] heights, float x, float cy, float maxH, float barW, float gap,
                          int color, boolean playing, float dt, float time) {
        soundBars(g, heights, x, cy, maxH, barW, gap, color, playing, dt, time, null);
    }

    /** As above, but following {@code levels} (0..1 per bar, from the music's {@link Spectrum}) when there are any. */
    static void soundBars(GuiGraphicsExtractor g, float[] heights, float x, float cy, float maxH, float barW, float gap,
                          int color, boolean playing, float dt, float time, float @Nullable [] levels) {
        float[] shape = {0.45F, 1F, 0.65F, 0.85F, 0.55F};
        for (int i = 0; i < heights.length; i++) {
            float wave = 0.5F + 0.5F * (float)Math.sin(time * (6.5F + i * 1.3F) + i * 1.9F);
            float target = !playing ? 3F
                : levels != null ? Math.max(3F, maxH * (0.18F + 0.82F * levels[i % levels.length]))
                : maxH * shape[i % shape.length] * (0.45F + 0.55F * wave);
            heights[i] = ModernStyle.smooth(heights[i] <= 0F ? 3F : heights[i], target, dt, 14F);
            int h = Math.max(2, Math.round(heights[i]));
            int bx = Math.round(x + i * (barW + gap));
            ModernStyle.rounded(g, bx, Math.round(cy - h / 2F), Math.round(barW), h, 1,
                playing ? color : ModernTypography.fade(color, 0.55F));
        }
    }

    /** The music's spectrum folded into {@code count} levels (bass to treble), for small equalizers; null when unknown. */
    static float @Nullable [] levels(@Nullable Spectrum spectrum, int count) {
        if (spectrum == null) return null;
        float[] bands = spectrum.bands(), out = new float[count];
        // The lowest and highest bands say little: spread the bars over the musical middle.
        int from = 2, to = bands.length - 6;
        for (int i = 0; i < count; i++) {
            int a = from + (to - from) * i / count, b = Math.max(a + 1, from + (to - from) * (i + 1) / count);
            float sum = 0F;
            for (int k = a; k < b; k++) sum += bands[k];
            out[i] = sum / (b - a);
        }
        return out;
    }

    static String time(long ms) {
        long seconds = Math.max(0L, ms) / 1000L;
        return seconds / 60L + ":" + String.format(Locale.ROOT, "%02d", seconds % 60L);
    }

    /** Online with nothing queued: fetch the hot chart once, and queue it (paused) when it arrives. */
    private void ensureChart() {
        MusicLibrary library = Client.getInstance().getMusicLibrary();
        if (!library.isOnline()) return;
        if (!chartWanted) {
            if (!this.player.queue().isEmpty()) return;
            chartWanted = true;
        }
        CompletableFuture<ListSource> pending = library.chart();
        if (pending.isDone() && !pending.isCompletedExceptionally() && this.player.queue().isEmpty()) {
            ListSource source = pending.join();
            if (!source.tracks().isEmpty()) this.player.setSource(source, 0, false);
        }
    }

    private void keepCurrentVisible(Layout l) {
        int index = this.player.index();
        if (index != this.shownIndex) {
            this.shownIndex = index;
            if (l.rows() > 0 && index >= 0) {
                if (index < this.listScroll) this.listScroll = index;
                else if (index >= this.listScroll + l.rows()) this.listScroll = index - l.rows() + 1;
            }
        }
        this.listScroll = Math.max(0, Math.min(this.listScroll, Math.max(0, this.player.queue().size() - l.rows())));
    }

    private float animate(String key, float target, float speed) {
        float current = this.anim.getOrDefault(key, target);
        float next = ModernStyle.smooth(current, target, this.dt, speed);
        this.anim.put(key, next);
        return next;
    }

    // --- input ------------------------------------------------------------------------------------

    /** A click inside the window's region; true when it landed on the window at all. */
    boolean mouseClicked(int x, int y, int w, int h, double mx, double my, int button) {
        if (!new Box(x, y, w, h).contains(mx, my)) return false;
        Frame f = frame(x, y, w, h);
        boolean online = Client.getInstance().getMusicLibrary().isOnline();
        if (button == 0 && online && f.account().contains(mx, my)) {
            go(page == Page.ACCOUNT ? Page.PLAYER : Page.ACCOUNT);
            return true;
        }
        if (button == 0 && online && f.settings().contains(mx, my)) {
            go(page == Page.SETTINGS ? Page.PLAYER : Page.SETTINGS);
            return true;
        }
        if (f.rail().contains(mx, my)) {
            if (button != 0) return true;
            List<RailItem> items = railItems();
            for (int i = 0; i < items.size(); i++) {
                if (!railItem(f, i).contains(mx, my)) continue;
                String key = items.get(i).key();
                if (key.equals("player")) {
                    go(Page.PLAYER);
                } else {
                    go(Page.BROWSE);
                    this.browser.select(key);
                }
                return true;
            }
            return true;
        }

        Box c = f.content();
        switch (page) {
            case BROWSE -> {
                if (this.browser.mouseClicked(c, mx, my, button) == ModernMusicBrowser.Click.PLAYER) go(Page.PLAYER);
            }
            case ACCOUNT -> this.accountPage.mouseClicked(c, mx, my, button);
            case SETTINGS -> this.settingsPage.mouseClicked(c, mx, my, button);
            case PLAYER -> {
                this.browser.blur();
                clickPlayer(c, mx, my, button);
            }
        }
        return true;
    }

    private void go(Page next) {
        page = next;
        this.browser.blur();
    }

    private void clickPlayer(Box c, double mx, double my, int button) {
        Layout l = this.layout = layout(c);
        if (button != 0 || !c.contains(mx, my)) return;
        if (this.player.current() == null) {
            MusicLibrary library = Client.getInstance().getMusicLibrary();
            if (chartWanted && library.chart().isDone()) library.retry(library.chart());
            return;
        }
        if (l.art().w() > 0 && l.art().contains(mx, my)) lyricsShown = !lyricsShown;
        else if (l.play().contains(mx, my)) this.player.toggle();
        else if (l.prev().contains(mx, my)) this.player.previous();
        else if (l.next().contains(mx, my)) this.player.next();
        else if (l.seek().contains(mx, my)) {
            this.drag = Drag.SEEK;
            this.dragFraction = fraction(mx, l.rx(), l.rw());
        } else if (l.volume().contains(mx, my)) {
            this.drag = Drag.VOLUME;
            this.player.setVolume(fraction(mx, l.volume().x() + 2, l.volume().w() - 4));
        } else if (l.list().contains(mx, my)) {
            int i = this.listScroll + (int)((my - l.listY()) / ROW_H);
            if (i >= 0 && i < this.player.queue().size()) this.player.select(i, true);
        }
    }

    boolean mouseDragged(double mx, double my) {
        if (page == Page.SETTINGS && this.content != null) return this.settingsPage.mouseDragged(this.content, mx, my);
        Layout l = this.layout;
        if (l == null || this.drag == Drag.NONE) return false;
        if (this.drag == Drag.SEEK) this.dragFraction = fraction(mx, l.rx(), l.rw());
        else this.player.setVolume(fraction(mx, l.volume().x() + 2, l.volume().w() - 4));
        return true;
    }

    /** Seeking is previewed while dragging and applied on release, which a streaming backend will want. */
    void mouseReleased() {
        this.settingsPage.mouseReleased();
        if (this.drag == Drag.SEEK) this.player.seekFraction(this.dragFraction);
        this.drag = Drag.NONE;
    }

    void mouseScrolled(int x, int y, int w, int h, double mx, double my, double amount) {
        Frame f = frame(x, y, w, h);
        if (f.rail().contains(mx, my)) {
            railScroll -= (float)amount * (RAIL_ITEM + RAIL_GAP);
            clampRail(f.rail(), railItems().size());
            return;
        }
        Box c = f.content();
        switch (page) {
            case BROWSE -> this.browser.mouseScrolled(c, mx, my, amount);
            case SETTINGS -> this.settingsPage.mouseScrolled(c, amount);
            case ACCOUNT -> {
                // Nothing scrolls there.
            }
            case PLAYER -> {
                Layout l = this.layout = layout(c);
                if (l.list().contains(mx, my) && this.player.queue().size() > l.rows()) {
                    this.listScroll = Math.max(0, Math.min(this.player.queue().size() - l.rows(), this.listScroll - (int)Math.signum(amount)));
                } else {
                    this.player.setVolume(this.player.volume() + (float)amount * 0.05F);
                }
            }
        }
    }

    /** Space plays/pauses, left/right seek five seconds, up/down change the volume; the search field gets first go. */
    boolean keyPressed(KeyEvent e) {
        if (page == Page.BROWSE && this.browser.isFocused()) return this.browser.keyPressed(e);
        if (page != Page.PLAYER && e.isEscape()) {
            go(Page.PLAYER);
            return true;
        }
        switch (e.key()) {
            case GLFW.GLFW_KEY_SPACE -> this.player.toggle();
            case GLFW.GLFW_KEY_LEFT -> this.player.seek(this.player.positionMs() - 5_000L);
            case GLFW.GLFW_KEY_RIGHT -> this.player.seek(this.player.positionMs() + 5_000L);
            case GLFW.GLFW_KEY_UP -> this.player.setVolume(this.player.volume() + 0.05F);
            case GLFW.GLFW_KEY_DOWN -> this.player.setVolume(this.player.volume() - 0.05F);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** The search field has focus: every key and character belongs to it. */
    boolean isTyping() {
        return page == Page.BROWSE && this.browser.isFocused();
    }

    boolean charTyped(CharacterEvent e) {
        return isTyping() && this.browser.charTyped(e);
    }

    boolean preeditUpdated(@Nullable PreeditEvent e) {
        return isTyping() && this.browser.preeditUpdated(e);
    }

    /**
     * Whether the title bar has a control under the pointer (the account and settings buttons) - there a press is
     * a click, not the start of dragging the window.
     */
    boolean headerControlAt(int x, int y, int w, int h, double mx, double my) {
        Frame f = frame(x, y, w, h);
        return Client.getInstance().getMusicLibrary().isOnline() && (f.account().contains(mx, my) || f.settings().contains(mx, my));
    }

    /** Test seam: which page the window is on (PLAYER, BROWSE, ACCOUNT or SETTINGS). */
    static String pageName() {
        return page.name();
    }

    /** Test cleanup: back to the player page with an empty search, as a fresh session would be. */
    static void resetPages() {
        page = Page.PLAYER;
        railScroll = 0F;
        ModernMusicBrowser.reset();
    }

    /** Takes focus away from the search field (a click elsewhere, the window shutting). */
    void blur() {
        this.browser.blur();
    }

    private static float fraction(double mx, int x, int w) {
        return Math.max(0F, Math.min(1F, (float)((mx - x) / Math.max(1, w))));
    }

    /**
     * Test seam: the on-screen bounds of a control in a window region, as {x, y, w, h} - the player's
     * {@code play}/{@code prev}/{@code next}/{@code art}, the title bar's {@code account}/{@code settings}, or a
     * rail entry as {@code rail:<key>} ({@code search} is the rail's search entry).
     */
    int[] control(String name, int x, int y, int w, int h) {
        Frame f = frame(x, y, w, h);
        Box box;
        if (name.equals("account")) box = f.account();
        else if (name.equals("settings")) box = f.settings();
        else if (name.equals("search") || name.startsWith("rail:")) {
            String key = name.equals("search") ? "search" : name.substring(5);
            List<RailItem> items = railItems();
            int index = -1;
            for (int i = 0; i < items.size(); i++) if (items.get(i).key().equals(key)) index = i;
            if (index < 0) throw new IllegalArgumentException(name);
            box = railItem(f, index);
        } else {
            Layout l = layout(f.content());
            box = switch (name) {
                case "play" -> l.play();
                case "prev" -> l.prev();
                case "next" -> l.next();
                case "art" -> l.art();
                default -> throw new IllegalArgumentException(name);
            };
        }
        return new int[]{box.x(), box.y(), box.w(), box.h()};
    }
}
