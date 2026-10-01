package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.music.MusicPlayer;
import com.mentalfrostbyte.jello.music.Track;
import com.mentalfrostbyte.jello.music.lyrics.LyricsService;
import com.mentalfrostbyte.jello.music.netease.NeteaseApi;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.PreeditEvent;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The music window's browse page: whichever category the rail points at - search, a NetEase chart, an artist,
 * the signed-in account's daily picks or playlists - as a list with covers, and a strip at the foot with what's
 * playing. Picking a song queues that list from the song and plays it.
 *
 * <p>The search category has the field (with its advanced options: which channel lyrics come from) above its
 * results. Typing goes to the field only while it has focus - it takes focus when the category opens and loses it
 * on Escape or a click anywhere else - so the host screen's own shortcuts keep working the rest of the time. Only
 * the most recent search's results are ever shown. The field reports focus to Minecraft the way a vanilla
 * {@code EditBox} does, which is what lets the system IME switch on; its composition text shows in vanilla's own
 * pre-edit overlay.</p>
 */
final class ModernMusicBrowser {
    private static final int ROW_H = 26;

    enum Click { NONE, PLAYER }

    // What the page lists - the category, the search, an opened playlist - is MusicBrowse's, shared with Jello's
    // window; the field's focus and the IME are this window's own.
    private static boolean focused, advanced;
    private static @Nullable IMEPreeditOverlay preedit;
    // What Minecraft's text-input bookkeeping sees of the field: pre-edit events are delivered to it.
    private static final GuiEventListener IME_TARGET = new GuiEventListener() {
        @Override
        public void setFocused(boolean value) {}

        @Override
        public boolean isFocused() {
            return focused;
        }

        @Override
        public boolean preeditUpdated(@Nullable PreeditEvent event) {
            preedit = event == null ? null : new IMEPreeditOverlay(event, Minecraft.getInstance().font, 10);
            return true;
        }
    };

    private record Layout(ModernMusicView.Box field, ModernMusicView.Box advancedButton, ModernMusicView.Box clear,
                          ModernMusicView.@Nullable Box channel, int rx, int rw, int labelY, ModernMusicView.Box label, int listY, int rows,
                          ModernMusicView.Box list, ModernMusicView.@Nullable Box mini, ModernMusicView.@Nullable Box miniPlay) {}

    private final MusicPlayer player;
    private final Map<String, Float> anim = new HashMap<>();
    private int scroll;
    private float dt, time;

    ModernMusicBrowser(MusicPlayer player) {
        this.player = player;
        MusicBrowse.applyDebug();
    }

    /** The rail chose {@code key}: search takes the keyboard; choosing "playlists" again goes back to their list. */
    void select(String key) {
        if (MusicBrowse.select(key)) this.scroll = 0;
        setFocused(key.equals("search"));
    }

    private Layout layout(ModernMusicView.Box c) {
        int rx = c.x(), rw = c.w(), top = c.y();
        ModernMusicView.Box none = new ModernMusicView.Box(0, 0, 0, 0);
        ModernMusicView.Box field = none, advancedButton = none, clear = none, channel = null;
        if (MusicBrowse.current().kind() == MusicBrowse.Kind.SEARCH) {
            field = new ModernMusicView.Box(rx, top, rw - 23, 20);
            advancedButton = new ModernMusicView.Box(rx + rw - 20, top + 1, 18, 18);
            clear = new ModernMusicView.Box(field.x() + field.w() - 19, field.y() + 2, 16, 16);
            top += 25;
            if (advanced) {
                channel = new ModernMusicView.Box(rx + 48, top, rw - 48, 18);
                top += 24;
            }
        }
        ModernMusicView.Box label = new ModernMusicView.Box(rx - 2, top - 2, rw + 4, 14);
        ModernMusicView.Box mini = null, miniPlay = null;
        int listBottom = c.y() + c.h();
        if (c.h() >= 150) {
            mini = new ModernMusicView.Box(rx - 3, c.y() + c.h() - 30, rw + 6, 30);
            miniPlay = new ModernMusicView.Box(mini.x() + mini.w() - 5 - 20, mini.y() + 5, 20, 20);
            listBottom = mini.y() - 4;
        }
        int listY = top + 15;
        int rows = Math.max(0, (listBottom - listY) / ROW_H);
        return new Layout(field, advancedButton, clear, channel, rx, rw, top + 1, label, listY, rows,
            new ModernMusicView.Box(rx - 4, listY, rw + 8, rows * ROW_H), mini, miniPlay);
    }

    // --- rendering ------------------------------------------------------------------------------------

    void render(GuiGraphicsExtractor g, ModernMusicView.Box c, int mx, int my, float dt, float time) {
        this.dt = dt;
        this.time = time;
        MusicBrowse.Category cat = MusicBrowse.current();
        Layout l = layout(c);
        if (cat.kind() == MusicBrowse.Kind.SEARCH) drawSearchBar(g, l, mx, my);

        List<Track> songs = MusicBrowse.tracks(cat);
        List<NeteaseApi.PlaylistInfo> playlists = MusicBrowse.lists(cat);
        String label = MusicBrowse.heading(cat);
        String count = MusicBrowse.count(cat);
        boolean labelIsBack = MusicBrowse.headingGoesBack(cat);
        float labelHover = labelIsBack ? animate("label", l.label().contains(mx, my) ? 1F : 0F, 16F) : 0F;
        if (labelHover > 0.01F) ModernStyle.rounded(g, l.label().x(), l.label().y(), l.label().w(), l.label().h(), 5, Math.round(0x14 * labelHover) << 24 | 0xCDEBFF);
        float countW = ModernTypography.width(ModernTypography.Face.TEXT, count, 0.68F);
        ModernTypography.draw(g, ModernTypography.Face.TEXT,
            ModernTypography.wrap(ModernTypography.Face.TEXT, label, 0.68F, l.rw() - countW - 10F, 1).getFirst(), l.rx(), l.labelY(), 0.68F,
            ModernStyle.mix(0xFFA9BFD2, 0xFFE3F2FA, labelHover));
        ModernTypography.draw(g, ModernTypography.Face.TEXT, count, l.rx() + l.rw() - countW, l.labelY(), 0.68F, 0xFFA9BFD2);

        if (songs != null && !songs.isEmpty()) {
            drawTracks(g, l, songs, mx, my);
        } else if (playlists != null && !playlists.isEmpty()) {
            drawPlaylists(g, l, playlists, mx, my);
        } else {
            MusicBrowse.State state = MusicBrowse.state(cat);
            String message = MusicBrowse.message(cat);
            float cy = l.listY() + Math.max(16, l.rows() * ROW_H / 2F - 14F);
            for (String line : ModernTypography.wrap(ModernTypography.Face.DISPLAY_ITALIC, message, 0.95F, l.rw() - 6F, 2)) {
                ModernTypography.draw(g, ModernTypography.Face.DISPLAY_ITALIC, line,
                    l.rx() + (l.rw() - ModernTypography.width(ModernTypography.Face.DISPLAY_ITALIC, line, 0.95F)) / 2F, cy, 0.95F, 0xFF9DBCD0);
                cy += 12F;
            }
            if (state == MusicBrowse.State.LOADING) ModernMusicView.spinner(g, l.rx() + l.rw() / 2F, cy + 10F, 6F, time, 0xFFB6E5FF);
        }
        if (l.mini() != null) drawMini(g, l, mx, my);
    }

    private void drawSearchBar(GuiGraphicsExtractor g, Layout l, int mx, int my) {
        String query = MusicBrowse.query();
        ModernMusicView.Box f = l.field();
        float focus = animate("focus", focused ? 1F : 0F, 12F);
        if (focus > 0.01F) ModernStyle.halo(g, f.x(), f.y(), f.w(), f.h(), 10, ModernStyle.GLOW, 0.3F * focus);
        ModernStyle.rounded(g, f.x(), f.y(), f.w(), f.h(), 10, ModernStyle.mix(0x3DB8D3E7, 0x99B6E5FF, focus));
        ModernStyle.rounded(g, f.x() + 1, f.y() + 1, f.w() - 2, f.h() - 2, 9, 0xF20D1F30);
        ModernIcons.draw(g, ModernIcons.Icon.SEARCH, f.x() + 7F, f.y() + 5.5F, 9F, ModernStyle.mix(0xFF8FAEC4, 0xFFD8EEFB, focus));

        float textX = f.x() + 21F, textY = f.y() + 6F, scale = 0.9F;
        float room = f.w() - 21F - (query.isEmpty() ? 8F : 22F);
        if (query.isEmpty()) {
            String placeholder = ModernTypography.wrap(ModernTypography.Face.TEXT, MusicBrowse.placeholder(), scale, room, 1).getFirst();
            ModernTypography.draw(g, ModernTypography.Face.TEXT, placeholder, textX, textY, scale, 0xFF6F8CA2);
        }
        // Long queries scroll left so the end - where the caret is - stays in view.
        float textW = ModernTypography.width(ModernTypography.Face.TEXT, query, scale);
        float shift = Math.max(0F, textW - room);
        g.enableScissor(Math.round(textX) - 1, f.y() + 1, Math.round(textX + room) + 1, f.y() + f.h() - 1);
        try {
            ModernTypography.draw(g, ModernTypography.Face.TEXT, query, textX - shift, textY, scale, 0xFFF0F8FF);
            if (focused && (this.time % 1.06F) < 0.6F) {
                int caretX = Math.round(textX - shift + textW + 1F);
                ModernStyle.fill(g, caretX, f.y() + 5, caretX + 1, f.y() + f.h() - 5, 0xFFCDEEFF);
            }
        } finally {
            g.disableScissor();
        }
        IMEPreeditOverlay composing = preedit;
        if (focused && composing != null) {
            // Drawn at the end of the frame, over everything; it also places the IME's candidate window.
            composing.updateInputPosition(Math.round(textX - shift + textW + 1F), f.y() + 2);
            g.setPreeditOverlay(composing);
        }
        if (!query.isEmpty()) {
            ModernMusicView.Box c = l.clear();
            float hover = animate("clear", c.contains(mx, my) ? 1F : 0F, 16F);
            if (hover > 0.01F) ModernStyle.rounded(g, c.x(), c.y(), c.w(), c.h(), 8, Math.round(0x22 * hover) << 24 | 0xCDEBFF);
            ModernIcons.draw(g, ModernIcons.Icon.CLOSE, c.x() + 4.5F, c.y() + 4.5F, 7F, ModernStyle.mix(0xFF8FAEC4, 0xFFFFFFFF, hover));
        }

        // Advanced options: which channel lyrics come from.
        ModernMusicView.Box a = l.advancedButton();
        float hover = animate("advanced", a.contains(mx, my) || advanced ? 1F : 0F, 16F);
        if (hover > 0.01F) ModernStyle.rounded(g, a.x(), a.y(), a.w(), a.h(), 9, Math.round((advanced ? 0x33 : 0x1E) * hover) << 24 | 0xCDEBFF);
        ModernIcons.draw(g, ModernIcons.Icon.LAYERS, a.x() + 4.5F, a.y() + 4.5F, 9F, ModernStyle.mix(0xFFB9D2E4, 0xFFFFFFFF, hover));
        ModernMusicView.Box channel = l.channel();
        if (channel != null) {
            ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernText.t("Lyrics", "歌词渠道"), l.rx(), channel.y() + 5F, 0.7F, 0xFF9DB9CF);
            ModernMusicSettings.segmented(g, channel, channelLabels(), Client.getInstance().getMusicLibrary().lyrics().channel().ordinal(), mx, my);
        }
    }

    /** In {@link LyricsService.Channel} order. */
    static String[] channelLabels() {
        return new String[]{ModernText.t("Mixed", "混合"), "QQ", ModernText.t("NetEase", "网易云")};
    }

    private void drawTracks(GuiGraphicsExtractor g, Layout l, List<Track> tracks, int mx, int my) {
        this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, tracks.size() - l.rows())));
        Track playing = this.player.current();
        int rx = l.rx(), rw = l.rw();
        for (int r = 0; r < l.rows(); r++) {
            int i = this.scroll + r;
            if (i >= tracks.size()) break;
            Track track = tracks.get(i);
            int y = l.listY() + r * ROW_H;
            ModernMusicView.Box row = new ModernMusicView.Box(rx - 4, y, rw + 8, ROW_H);
            float hover = animate("row-" + i, row.contains(mx, my) ? 1F : 0F, 16F);
            if (hover > 0.01F) ModernStyle.rounded(g, row.x(), row.y() + 1, row.w(), row.h() - 2, 5, Math.round(0x12 * hover) << 24 | 0xBEDEF1);
            boolean current = playing != null && playing.id().equals(track.id());
            ModernCovers.draw(g, track, rx, y + 3F, 20F, 0.2F, 0xFFFFFFFF);

            String length = ModernMusicView.time(track.durationMs());
            float lengthW = ModernTypography.width(ModernTypography.Face.TEXT, length, 0.7F);
            float room = rw - 27F - lengthW - 8F;
            int titleColor = current ? 0xFF9FDCFF : ModernStyle.mix(0xFFD3E2EE, 0xFFFFFFFF, hover);
            ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.wrap(ModernTypography.Face.TEXT, track.title(), 0.9F, room, 1).getFirst(),
                rx + 27F, y + 3.5F, 0.9F, titleColor);
            ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.wrap(ModernTypography.Face.TEXT, track.artist(), 0.7F, room, 1).getFirst(),
                rx + 27F, y + 14.5F, 0.7F, 0xFF86A3B8);
            ModernTypography.draw(g, ModernTypography.Face.TEXT, length, rx + rw - lengthW, y + 4.5F, 0.7F, 0xFF8AA7BC);
            if (!track.tag().isEmpty()) {
                // VIP: only a preview without a signed-in member account. PAID: a single bought on its own.
                float tagW = ModernTypography.width(ModernTypography.Face.TEXT, track.tag(), 0.52F);
                ModernTypography.draw(g, ModernTypography.Face.TEXT, track.tag(), rx + rw - tagW, y + 15F, 0.52F, ModernRows.FAIR);
            }
        }
        drawThumb(g, l, tracks.size());
    }

    private void drawPlaylists(GuiGraphicsExtractor g, Layout l, List<NeteaseApi.PlaylistInfo> playlists, int mx, int my) {
        this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, playlists.size() - l.rows())));
        int rx = l.rx(), rw = l.rw();
        for (int r = 0; r < l.rows(); r++) {
            int i = this.scroll + r;
            if (i >= playlists.size()) break;
            NeteaseApi.PlaylistInfo list = playlists.get(i);
            int y = l.listY() + r * ROW_H;
            ModernMusicView.Box row = new ModernMusicView.Box(rx - 4, y, rw + 8, ROW_H);
            float hover = animate("list-" + i, row.contains(mx, my) ? 1F : 0F, 16F);
            if (hover > 0.01F) ModernStyle.rounded(g, row.x(), row.y() + 1, row.w(), row.h() - 2, 5, Math.round(0x12 * hover) << 24 | 0xBEDEF1);
            ModernCovers.draw(g, MusicBrowse.cover(list), rx, y + 3F, 20F, 0.2F, 0xFFFFFFFF);
            float room = rw - 27F - 10F;
            ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.wrap(ModernTypography.Face.TEXT, list.name(), 0.9F, room, 1).getFirst(),
                rx + 27F, y + 3.5F, 0.9F, ModernStyle.mix(0xFFD3E2EE, 0xFFFFFFFF, hover));
            ModernTypography.draw(g, ModernTypography.Face.TEXT, MusicBrowse.songCount(list), rx + 27F, y + 14.5F, 0.7F, 0xFF86A3B8);
            ModernIcons.drawRotated(g, ModernIcons.Icon.CHEVRON_UP, rx + rw - 5F, y + ROW_H / 2F, 7F, (float)(Math.PI / 2), 0xFF7F9CB2);
        }
        drawThumb(g, l, playlists.size());
    }

    private void drawThumb(GuiGraphicsExtractor g, Layout l, int size) {
        if (size <= l.rows() || l.rows() <= 0) return;
        // A thin scroll thumb along the right edge.
        int trackH = l.rows() * ROW_H;
        int thumbH = Math.max(12, trackH * l.rows() / size);
        int thumbY = l.listY() + (trackH - thumbH) * this.scroll / Math.max(1, size - l.rows());
        ModernStyle.rounded(g, l.rx() + l.rw() + 4, thumbY, 2, thumbH, 1, 0x40CDEBFF);
    }

    private void drawMini(GuiGraphicsExtractor g, Layout l, int mx, int my) {
        ModernMusicView.Box m = l.mini(), play = l.miniPlay();
        Track track = this.player.current();
        float hover = animate("mini", m.contains(mx, my) && !play.contains(mx, my) ? 1F : 0F, 14F);
        ModernStyle.rounded(g, m.x(), m.y(), m.w(), m.h(), 9, ModernStyle.mix(0x33091522, 0x4D1B3550, hover));
        ModernStyle.fill(g, m.x() + 8, m.y(), m.x() + m.w() - 8, m.y() + 1, 0x1CB8D3E7);
        if (track == null) return;
        ModernCovers.draw(g, track, m.x() + 5F, m.y() + 5F, 20F, 0.2F, 0xFFFFFFFF);
        float room = play.x() - (m.x() + 31F) - 6F;
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.wrap(ModernTypography.Face.TEXT, track.title(), 0.84F, room, 1).getFirst(),
            m.x() + 31F, m.y() + 5F, 0.84F, 0xFFF0F6FC);
        ModernLyricSweep.Now now = this.player.isPlaying() ? ModernLyricSweep.now(this.player) : null;
        if (now != null) {
            String text = now.line().text().strip();
            String line = ModernTypography.wrap(ModernTypography.Face.TEXT, text, 0.66F, room, 1).getFirst();
            ModernLyricSweep.draw(g, ModernTypography.Face.TEXT, line, m.x() + 31F, m.y() + 16.5F, 0.66F,
                now.progress() * text.length() / Math.max(1, line.length()), 0xFF7F9CB2, 0xFFE6F4FF);
        } else {
            ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.wrap(ModernTypography.Face.TEXT, track.artist(), 0.66F, room, 1).getFirst(),
                m.x() + 31F, m.y() + 16.5F, 0.66F, 0xFF8FAABF);
        }

        float playHover = animate("mini-play", play.contains(mx, my) ? 1F : 0F, 16F);
        float cx = play.x() + play.w() / 2F, cy = play.y() + play.h() / 2F;
        ModernStyle.halo(g, play.x(), play.y(), play.w(), play.h(), play.w() / 2, ModernStyle.GLOW, 0.2F + 0.3F * playHover);
        ModernStyle.rounded(g, play.x(), play.y(), play.w(), play.h(), play.w() / 2, ModernStyle.mix(0xFFB9E1F9, 0xFFD6EFFF, playHover));
        boolean playing = this.player.isPlaying();
        ModernIcons.draw(g, playing ? ModernIcons.Icon.PAUSE : ModernIcons.Icon.PLAY, cx - 4F + (playing ? 0F : 0.6F), cy - 4F, 8F, 0xFF253D4F);
        if (this.player.isBuffering()) ModernMusicView.spinner(g, cx, cy, play.w() / 2F + 3.5F, this.time, 0xFFCDEEFF);
    }

    private float animate(String key, float target, float speed) {
        float current = this.anim.getOrDefault(key, target);
        float next = ModernStyle.smooth(current, target, this.dt, speed);
        this.anim.put(key, next);
        return next;
    }

    // --- input ------------------------------------------------------------------------------------

    /** A click in the content area; {@link Click#PLAYER} when it asks to go to the player (the strip at the foot). */
    Click mouseClicked(ModernMusicView.Box c, double mx, double my, int button) {
        MusicBrowse.Category cat = MusicBrowse.current();
        Layout l = layout(c);
        if (button != 0) return Click.NONE;
        if (cat.kind() == MusicBrowse.Kind.SEARCH) {
            if (!MusicBrowse.query().isEmpty() && l.clear().contains(mx, my)) {
                MusicBrowse.clearSearch();
                this.scroll = 0;
                setFocused(true);
                return Click.NONE;
            }
            if (l.field().contains(mx, my)) {
                setFocused(true);
                return Click.NONE;
            }
            if (l.advancedButton().contains(mx, my)) {
                advanced = !advanced;
                return Click.NONE;
            }
            ModernMusicView.Box channel = l.channel();
            if (channel != null) {
                int picked = ModernMusicSettings.segmentAt(channel, 3, mx, my);
                if (picked >= 0) {
                    Client.getInstance().getMusicLibrary().lyrics().setChannel(LyricsService.Channel.values()[picked]);
                    Client.getInstance().saveConfig();
                    return Click.NONE;
                }
            }
        }
        setFocused(false);
        if (l.miniPlay() != null && l.miniPlay().contains(mx, my)) {
            this.player.toggle();
            return Click.NONE;
        }
        if (l.mini() != null && l.mini().contains(mx, my)) return Click.PLAYER;
        if (MusicBrowse.headingGoesBack(cat) && l.label().contains(mx, my)) {
            MusicBrowse.back();
            this.scroll = 0;
            return Click.NONE;
        }

        MusicBrowse.State state = MusicBrowse.state(cat);
        if (state == MusicBrowse.State.FAILED || state == MusicBrowse.State.EMPTY) {
            // A failed (or empty) list is asked for again; a search is simply repeated.
            if (my >= l.listY()) MusicBrowse.retry(cat);
            return Click.NONE;
        }
        if (state != MusicBrowse.State.READY || !l.list().contains(mx, my)) return Click.NONE;
        int i = this.scroll + (int)((my - l.listY()) / ROW_H);
        if (MusicBrowse.pick(this.player, cat, i)) this.scroll = 0;
        return Click.NONE;
    }

    void mouseScrolled(ModernMusicView.Box c, double mx, double my, double amount) {
        Layout l = layout(c);
        int size = Math.max(0, MusicBrowse.size(MusicBrowse.current()));
        this.scroll = Math.max(0, Math.min(Math.max(0, size - l.rows()), this.scroll - (int)Math.signum(amount) * 2));
    }

    static void reset() {
        MusicBrowse.reset();
        advanced = false;
        setFocused(false);
    }

    boolean isFocused() {
        return focused;
    }

    void focus() {
        setFocused(true);
    }

    void blur() {
        setFocused(false);
    }

    /** The one place focus changes, so Minecraft's text input (and with it the IME) follows the field. */
    private static void setFocused(boolean value) {
        if (focused == value) return;
        focused = value;
        if (!value) preedit = null;
        Minecraft.getInstance().onTextInputFocusChange(IME_TARGET, value);
    }

    /** The IME's composition changed; only the focused field takes it. */
    boolean preeditUpdated(@Nullable PreeditEvent event) {
        if (!focused) return false;
        IME_TARGET.preeditUpdated(event);
        return true;
    }

    /**
     * Keys while the field has focus: Enter searches, Backspace deletes, Ctrl+V pastes, Escape lets go of the
     * field. Every other key is swallowed so it can't reach the host's shortcuts; the text itself arrives
     * through {@link #charTyped}.
     */
    boolean keyPressed(KeyEvent e) {
        if (!focused) return false;
        if (e.isEscape()) {
            setFocused(false);
        } else if (e.key() == GLFW.GLFW_KEY_ENTER || e.key() == GLFW.GLFW_KEY_KP_ENTER) {
            this.scroll = 0;
            MusicBrowse.submit();
        } else if (e.key() == GLFW.GLFW_KEY_BACKSPACE) {
            String query = MusicBrowse.query();
            if (!query.isEmpty()) {
                MusicBrowse.setQuery(e.hasControlDown() ? "" : query.substring(0, query.offsetByCodePoints(query.length(), -1)));
            }
        } else if (e.isPaste()) {
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard().replaceAll("[\\r\\n\\t]+", " ");
            type(clip);
        }
        return true;
    }

    boolean charTyped(CharacterEvent e) {
        if (!focused) return false;
        if (e.isAllowedChatCharacter()) type(e.codepointAsString());
        return true;
    }

    private static void type(String text) {
        MusicBrowse.setQuery(MusicBrowse.query() + text);
    }
}
