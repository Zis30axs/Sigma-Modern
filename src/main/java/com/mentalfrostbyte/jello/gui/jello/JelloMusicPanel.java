package com.mentalfrostbyte.jello.gui.jello;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.gui.base.animations.Animation;
import com.mentalfrostbyte.jello.gui.legacy.LegacyCanvas;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTextField;
import com.mentalfrostbyte.jello.gui.legacy.LegacyTexture;
import com.mentalfrostbyte.jello.gui.modern.LegacyFonts.Face;
import com.mentalfrostbyte.jello.gui.modern.ModernCovers;
import com.mentalfrostbyte.jello.gui.modern.ModernLyricSweep;
import com.mentalfrostbyte.jello.gui.modern.MusicBrowse;
import com.mentalfrostbyte.jello.music.MusicPlayer;
import com.mentalfrostbyte.jello.music.Track;
import com.mentalfrostbyte.jello.music.netease.NeteaseApi;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Jello's music window in its ClickGUI: an 800 x 600 sheet with a black rail of sources on the left, a grid of covers
 * on the right, and a dark bar along the foot with the transport, the progress line and the volume. The old one drove
 * the old music manager; this one is a look over {@link MusicBrowse}, the model SigmaModern's music window browses
 * with, and drives {@link MusicPlayer}, so what is listed, searched and played here is what is in that window: the
 * same categories, the same search text and results, the same click on a row.
 *
 * <p>It is laid out in the old client's pixels ({@code W} x {@code H} below) and scaled down as a whole when the
 * window is smaller. The rail is {@link MusicBrowse#CATEGORIES} - the search, the NetEase charts, an artist and,
 * signed in from SigmaModern's window, the daily picks and the account's playlists. Picking a cover queues its list
 * from that song.</p>
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
    private static final int MAX_QUERY = 60;

    // Whether the window is out outlives any one ClickGUI, like the old one's state did; what it lists is MusicBrowse's.
    private static @Nullable Boolean openState;

    private final MusicPlayer player = Client.getInstance().getMusicPlayer();
    private final LegacyTextField search = new LegacyTextField(
        LegacyTextField.Style.JELLO, GRID_X, SEARCH_TOP, W - GRID_X - 20, 36, Face.JELLO_LIGHT, 20, MusicBrowse.placeholder());
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
        this.search.setText(MusicBrowse.query());
        this.search.onChange(field -> MusicBrowse.setQuery(field.text()));
    }

    // ------------------------------------------------------------------ open and close

    /** Whether the window is out. It starts out when the screen is wide enough to show it beside a row of cards. */
    boolean isOpen(final int screenWidth) {
        if (openState == null) {
            openState = MusicBrowse.demo() || screenWidth >= 1690;
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

        List<MusicBrowse.Category> categories = MusicBrowse.categories(MusicBrowse.signedIn());
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
        c.textCentered(Face.JELLO_LIGHT, 18.0F, c.fit(Face.JELLO_LIGHT, 18.0F, line != null ? line : title, RAIL - 40), RAIL / 2.0F, H - 42.0F,
            LegacyCanvas.alpha(WHITE, a));
        if (track != null && !track.artist().isEmpty()) {
            c.textCentered(Face.JELLO_LIGHT, 14.0F, c.fit(Face.JELLO_LIGHT, 14.0F, track.artist(), RAIL - 40), RAIL / 2.0F, H - 20.0F,
                LegacyCanvas.alpha(WHITE, 0.6F * a));
        }
    }

    // ---- the grid

    private void content(final LegacyCanvas c, final double lx, final double ly, final float a) {
        MusicBrowse.Category current = MusicBrowse.current();
        boolean searching = current.kind() == MusicBrowse.Kind.SEARCH;
        int top = GRID_TOP;
        if (searching) {
            this.search.draw(c, a);
            top = GRID_TOP + 6;
        } else {
            String label = MusicBrowse.heading(current);
            c.text(Face.JELLO_LIGHT, 25.0F, c.fit(Face.JELLO_LIGHT, 25.0F, label, W - GRID_X - 90), GRID_X, 24, LegacyCanvas.alpha(WHITE, a));
        }

        List<Track> songs = MusicBrowse.tracks(current);
        List<NeteaseApi.PlaylistInfo> playlists = MusicBrowse.lists(current);
        String count = MusicBrowse.count(current);
        if (!count.isEmpty()) {
            c.text(Face.JELLO_LIGHT, 16.0F, count, W - 22 - c.textWidth(Face.JELLO_LIGHT, 16.0F, count), searching ? top - 20 : 30,
                LegacyCanvas.alpha(WHITE, 0.5F * a));
        }

        int gridBottom = H - BAR;
        c.scissor(RAIL, top, W, gridBottom);
        try {
            if (songs != null && !songs.isEmpty() || playlists != null && !playlists.isEmpty()) {
                this.grid(c, lx, ly, a, top, gridBottom, songs, playlists);
            } else {
                c.textCentered(Face.JELLO_LIGHT, 20.0F, MusicBrowse.message(current), GRID_X + (W - GRID_X - 20) / 2.0F, (top + gridBottom) / 2.0F,
                    LegacyCanvas.alpha(WHITE, 0.6F * a));
            }
        } finally {
            c.unscissor();
        }
    }

    private void grid(final LegacyCanvas c, final double lx, final double ly, final float a, final int top, final int bottom,
                      final @Nullable List<Track> songs, final @Nullable List<NeteaseApi.PlaylistInfo> playlists) {
        int size = songs != null ? songs.size() : playlists.size();
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
            if (songs != null) {
                track = songs.get(i);
                title = track.title();
                detail = track.artist();
            } else {
                NeteaseApi.PlaylistInfo list = playlists.get(i);
                track = MusicBrowse.cover(list);
                title = list.name();
                detail = MusicBrowse.songCount(list);
            }

            boolean hover = lx >= x && lx < x + CARD_W && ly >= y && ly < y + CARD_H && ly >= top && ly < bottom;
            boolean current = songs != null && playing != null && playing.id().equals(track.id());
            ModernCovers.draw(c.graphics(), track, x, y, CARD_W, 0.08F, LegacyCanvas.alpha(WHITE, a));
            if (hover) {
                c.rounded(Math.round(x), Math.round(y), CARD_W, CARD_W, 13, LegacyCanvas.alpha(WHITE, 0.1F * a));
            }
            c.text(Face.JELLO_LIGHT, 16.0F, c.fit(Face.JELLO_LIGHT, 16.0F, title, CARD_W), x, y + CARD_W + 6,
                LegacyCanvas.alpha(current ? BLUE : WHITE, a));
            c.text(Face.JELLO_LIGHT, 14.0F, c.fit(Face.JELLO_LIGHT, 14.0F, detail, CARD_W), x, y + CARD_W + 28, LegacyCanvas.alpha(WHITE, 0.55F * a));
        }

        if (max > 0.0F) {
            float trackH = bottom - top - 12;
            float thumb = Math.max(24.0F, trackH * trackH / (trackH + max));
            float thumbY = top + 6 + (trackH - thumb) * this.scrollPx / max;
            c.rounded(W - 12, Math.round(thumbY), 3, Math.round(thumb), 1, LegacyCanvas.alpha(WHITE, 0.3F * a));
        }
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

    /** Where the open category stands on the rail, which shows the signed-in ones only when signed in. */
    private int selected(final List<MusicBrowse.Category> categories) {
        String key = MusicBrowse.category();
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).key().equals(key)) {
                return i;
            }
        }

        return 0;
    }

    private void toTop() {
        this.scrollTarget = 0.0F;
        this.scrollPx = 0.0F;
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

        MusicBrowse.Category current = MusicBrowse.current();
        if (current.kind() == MusicBrowse.Kind.SEARCH && this.search.mouseClicked(lx, ly)) {
            return true;
        }
        this.search.setFocused(false);

        int barTop = H - BAR;
        if (ly >= barTop) {
            this.barClick(lx, ly);
            return true;
        }

        if (lx < RAIL) {
            List<MusicBrowse.Category> categories = MusicBrowse.categories(MusicBrowse.signedIn());
            int index = (int) ((ly - TAB_TOP) / TAB_H);
            if (ly >= TAB_TOP && ly < H - 170 && index >= 0 && index < categories.size()) {
                MusicBrowse.Category picked = categories.get(index);
                if (MusicBrowse.select(picked.key())) {
                    this.toTop();
                }
                this.search.setFocused(picked.kind() == MusicBrowse.Kind.SEARCH);
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

    private void gridClick(final MusicBrowse.Category current, final double lx, final double ly) {
        // The heading of an opened playlist takes you back to the list of them.
        if (MusicBrowse.headingGoesBack(current) && ly < GRID_TOP) {
            MusicBrowse.back();
            this.toTop();
            return;
        }

        MusicBrowse.State state = MusicBrowse.state(current);
        if (state == MusicBrowse.State.FAILED || state == MusicBrowse.State.EMPTY) {
            MusicBrowse.retry(current);
            return;
        }

        if (state != MusicBrowse.State.READY) {
            return;
        }

        int top = GRID_TOP + (current.kind() == MusicBrowse.Kind.SEARCH ? 6 : 0);
        int size = MusicBrowse.size(current);
        for (int i = 0; i < size; i++) {
            float x = GRID_X + (i % COLUMNS) * (CARD_W + GRID_GAP);
            float y = top + 6 + (i / COLUMNS) * CARD_H - this.scrollPx;
            if (lx >= x && lx < x + CARD_W && ly >= Math.max(y, top) && ly < y + CARD_H && ly < H - BAR) {
                if (MusicBrowse.pick(this.player, current, i)) {
                    this.toTop();
                }
                return;
            }
        }
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
            this.toTop();
            MusicBrowse.submit();
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

    static String time(final long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
    }

    /** The scroll eases toward where the wheel put it. */
    private static float smooth(final float current, final float target, final float dt) {
        return current + (target - current) * (1.0F - (float) Math.exp(-14.0F * dt));
    }
}
