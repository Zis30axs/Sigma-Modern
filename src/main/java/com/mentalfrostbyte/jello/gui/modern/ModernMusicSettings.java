package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.music.MusicEffects;
import com.mentalfrostbyte.jello.music.MusicPlayer;
import com.mentalfrostbyte.jello.music.Track;
import com.mentalfrostbyte.jello.music.lyrics.Lyrics;
import com.mentalfrostbyte.jello.music.lyrics.LyricsService;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The music window's settings page (the gear in its title bar), for lyrics:
 * <ul>
 *   <li>the mode - {@code AUTO} word by word whenever the lyrics are word-timed and line by line otherwise,
 *       {@code LINE} always line by line, {@code WORD} only word-timed lyrics;</li>
 *   <li>the language - the original only, with its Chinese translation or its romanization under it, or the
 *       translation instead of it (for the lines that have one).</li>
 * </ul>
 * With what the current track's lyrics are, so a choice can be seen working. Which channel lyrics come from is
 * chosen in search's advanced options; this page says which it is.
 *
 * <p>Its second tab is the effects ({@link MusicEffects}): the under-water sound, the spectrum along the bottom
 * of the screen, beat particles, and tinting the island with the cover's colour. Choices are saved with the Sigma
 * config.</p>
 */
final class ModernMusicSettings {
    // Which tab is open is the window's state, like its page: -Dsigma.debug.musicPage=fx opens on the effects.
    private static boolean effectsTab = "fx".equalsIgnoreCase(System.getProperty("sigma.debug.musicPage"));
    private static float fxScroll;
    private final java.util.Map<String, Float> anim = new java.util.HashMap<>();
    private long lastFrame;
    private float dt;
    private int dragging = -1;

    private static ModernMusicView.Box tabs(ModernMusicView.Box c) {
        return new ModernMusicView.Box(c.x() + c.w() - 100, c.y() - 1, 100, 17);
    }

    /**
     * Where everything on the effects tab goes (scrolled by {@link #fxScroll}), the same numbers for drawing, clicks
     * and drags: rows 0-2 are the switches (under water, spectrum, cover colour), then the spectrum's three dials
     * (height, intensity, opacity) sit under its switch, and the particles' choice comes last.
     */
    private record FxLayout(int top, int[] rowY, ModernMusicView.Box[] switches, int[] sliderY, ModernMusicView.Box[] sliders,
                            int particlesY, ModernMusicView.Box particles, int length) {}

    private static FxLayout fxLayout(ModernMusicView.Box c) {
        int top = c.y() + 22, y = top + 4 - Math.round(fxScroll), start = y;
        int[] rowY = new int[3], sliderY = new int[3];
        ModernMusicView.Box[] switches = new ModernMusicView.Box[3], sliders = new ModernMusicView.Box[3];
        rowY[0] = y;
        y += 36;
        rowY[1] = y;
        y += 32;
        for (int i = 0; i < 3; i++) {
            sliderY[i] = y;
            sliders[i] = new ModernMusicView.Box(c.x() + 6, y + 10, c.w() - 12, 11);
            y += 24;
        }
        y += 6;
        rowY[2] = y;
        y += 36;
        for (int i = 0; i < 3; i++) switches[i] = new ModernMusicView.Box(c.x() + c.w() - 28, rowY[i] + 3, 28, 14);
        int particlesY = y;
        ModernMusicView.Box particles = new ModernMusicView.Box(c.x(), y + 14, c.w(), 18);
        y += 50;
        return new FxLayout(top, rowY, switches, sliderY, sliders, particlesY, particles, y - start);
    }

    private static void clampScroll(ModernMusicView.Box c) {
        FxLayout l = fxLayout(new ModernMusicView.Box(c.x(), c.y(), c.w(), c.h()));
        float max = Math.max(0, l.length() + Math.round(fxScroll) - (c.y() + c.h() - l.top() - 4));
        fxScroll = Math.max(0F, Math.min(fxScroll, max));
    }
    /** In {@link LyricsService.Mode} order. */
    private static String[] modeLabels() {
        return new String[]{ModernText.t("Auto", "自动"), ModernText.t("Line", "逐行"), ModernText.t("Word", "逐词")};
    }

    /** In {@link LyricsService.Language} order. */
    private static String[] languageLabels() {
        return new String[]{ModernText.t("Original", "原文"), ModernText.t("+Chinese", "译文"), ModernText.t("+Roman", "音译"),
            ModernText.t("Chinese", "仅译文")};
    }

    private static ModernMusicView.Box modes(ModernMusicView.Box c) {
        return new ModernMusicView.Box(c.x(), c.y() + 38, c.w(), 20);
    }

    private static ModernMusicView.Box languages(ModernMusicView.Box c) {
        return new ModernMusicView.Box(c.x(), c.y() + 104, c.w(), 20);
    }

    void render(GuiGraphicsExtractor g, ModernMusicView.Box c, MusicPlayer player, int mx, int my) {
        long now = System.nanoTime();
        this.dt = this.lastFrame == 0L ? 0F : Math.min(0.05F, (now - this.lastFrame) / 1_000_000_000F);
        this.lastFrame = now;
        LyricsService lyrics = Client.getInstance().getMusicLibrary().lyrics();
        // A short window cuts the page off at its foot rather than spilling past the window.
        g.enableScissor(c.x() - 2, c.y() - 2, c.x() + c.w() + 2, c.y() + c.h());
        try {
            ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernText.t("Settings", "设置"), c.x(), c.y() + 2F, 1F, 0xFFF0F6FC);
            segmented(g, tabs(c), new String[]{ModernText.t("Lyrics", "歌词"), ModernText.t("Effects", "动效")}, effectsTab ? 1 : 0, mx, my);
            if (effectsTab) {
                clampScroll(c);
                // The tab scrolls under its title rather than over it.
                g.enableScissor(c.x() - 2, c.y() + 22, c.x() + c.w() + 2, c.y() + c.h());
                try {
                    drawEffects(g, c, mx, my);
                } finally {
                    g.disableScissor();
                }
            } else {
                draw(g, c, player, lyrics, mx, my);
            }
        } finally {
            g.disableScissor();
        }
    }

    private void drawEffects(GuiGraphicsExtractor g, ModernMusicView.Box c, int mx, int my) {
        MusicEffects fx = Client.getInstance().getMusicEffects();
        FxLayout l = fxLayout(c);
        fxRow(g, c, l, 0, ModernText.t("Under water", "水下音效"),
            ModernText.t("Music goes muffled while your head is in water or lava (only for you).", "头部没入水或岩浆时音乐变得沉闷（仅本地）"), fx.underwaterSound());
        fxRow(g, c, l, 1, ModernText.t("Spectrum", "底部频谱"),
            ModernText.t("Bars along the bottom of the screen, moving with the music.", "游戏中屏幕底部随音乐跳动的频谱条"), fx.spectrum());
        // The spectrum's dials; faint while it's off.
        try (var dials = ModernStyle.alphaScope(fx.spectrum() ? 1F : 0.45F)) {
            slider(g, l, 0, mx, my, ModernText.t("Height", "高度"), Math.round(fx.spectrumHeight() * 100F) + "%",
                (fx.spectrumHeight() - MusicEffects.MIN_HEIGHT) / (MusicEffects.MAX_HEIGHT - MusicEffects.MIN_HEIGHT));
            slider(g, l, 1, mx, my, ModernText.t("Intensity", "强度"), String.format(java.util.Locale.ROOT, "%.1f×", fx.spectrumIntensity()),
                (fx.spectrumIntensity() - MusicEffects.MIN_INTENSITY) / (MusicEffects.MAX_INTENSITY - MusicEffects.MIN_INTENSITY));
            slider(g, l, 2, mx, my, ModernText.t("Opacity", "不透明度"), Math.round(fx.spectrumOpacity() * 100F) + "%",
                (fx.spectrumOpacity() - MusicEffects.MIN_OPACITY) / (MusicEffects.MAX_OPACITY - MusicEffects.MIN_OPACITY));
        }
        fxRow(g, c, l, 2, ModernText.t("Cover colour", "封面取色"),
            ModernText.t("Tints the island, the spectrum and the sparks with the album cover.", "按专辑封面的颜色染色灵动岛、频谱和粒子"), fx.islandColor());
        float y = l.particlesY();
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernText.t("Beat particles", "节奏粒子"), c.x(), y + 2F, 0.86F, 0xFFE3F0F8);
        segmented(g, l.particles(), new String[]{ModernText.t("Off", "关"), ModernText.t("Soft", "柔和"), ModernText.t("Strong", "强烈")},
            fx.particles().ordinal(), mx, my);
        String about = switch (fx.particles()) {
            case OFF -> ModernText.t("No sparks.", "不显示粒子。");
            case SOFT -> ModernText.t("A few sparks rise from the bottom on each beat.", "每个节拍从屏幕底部升起少量光点。");
            case STRONG -> ModernText.t("A burst on each beat, and the bottom edge flares with it.", "每个节拍迸发大量光点，底部边缘随之闪亮。");
        };
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.wrap(ModernTypography.Face.TEXT, about, 0.66F, c.w(), 1).getFirst(),
            c.x(), y + 38F, 0.66F, 0xFF9DB6C9);
    }

    /** One of the spectrum's dials: its name, its value, and the player's own slider below them. */
    private void slider(GuiGraphicsExtractor g, FxLayout l, int i, int mx, int my, String label, String value, float fraction) {
        ModernMusicView.Box bar = l.sliders()[i];
        float y = l.sliderY()[i];
        ModernTypography.draw(g, ModernTypography.Face.TEXT, label, bar.x(), y, 0.76F, 0xFFC9DCEA);
        ModernTypography.draw(g, ModernTypography.Face.TEXT, value,
            bar.x() + bar.w() - ModernTypography.width(ModernTypography.Face.TEXT, value, 0.76F), y, 0.76F, 0xFFE3F0F8);
        boolean hot = this.dragging == i || bar.contains(mx, my);
        float lit = this.anim.getOrDefault("dial" + i, 0F);
        lit = ModernStyle.smooth(lit, hot ? 1F : 0F, this.dt, 14F);
        this.anim.put("dial" + i, lit);
        ModernMusicView.range(g, bar.x(), bar.y() + 5, bar.w(), fraction, 0xFFB6E5FF, 0x33BED8EB, lit);
    }

    private void fxRow(GuiGraphicsExtractor g, ModernMusicView.Box c, FxLayout l, int row, String label, String about, boolean on) {
        float y = l.rowY()[row];
        ModernTypography.draw(g, ModernTypography.Face.TEXT, label, c.x(), y + 2F, 0.86F, 0xFFE3F0F8);
        float ly = y + 14F;
        for (String line : ModernTypography.wrap(ModernTypography.Face.TEXT, about, 0.66F, c.w() - 36F, 2)) {
            ModernTypography.draw(g, ModernTypography.Face.TEXT, line, c.x(), ly, 0.66F, 0xFF9DB6C9);
            ly += 9F;
        }
        ModernMusicView.Box s = l.switches()[row];
        float lit = this.anim.getOrDefault("fx" + row, on ? 1F : 0F);
        lit = ModernStyle.smooth(lit, on ? 1F : 0F, this.dt, 14F);
        this.anim.put("fx" + row, lit);
        ModernStyle.toggle(g, s.x(), s.y(), s.w(), s.h(), lit);
    }

    private static void draw(GuiGraphicsExtractor g, ModernMusicView.Box c, MusicPlayer player, LyricsService lyrics, int mx, int my) {

        section(g, c, c.y() + 25F, ModernText.t("LYRICS", "歌词模式"));
        segmented(g, modes(c), modeLabels(), lyrics.mode().ordinal(), mx, my);
        about(g, c, c.y() + 63F, switch (lyrics.mode()) {
            case AUTO -> ModernText.t("Word by word when the lyrics are word-timed, otherwise line by line.", "有逐词歌词时逐词显示，否则逐行显示。");
            case LINE -> ModernText.t("Always line by line - word-timed lyrics light a whole line at a time.", "始终逐行显示，逐词歌词也按整行点亮。");
            case WORD -> ModernText.t("Only word-timed lyrics; tracks with only line-timed ones show none.", "只显示逐词歌词；只有逐行歌词的歌不显示。");
        });

        section(g, c, c.y() + 91F, ModernText.t("LANGUAGE", "歌词语言"));
        segmented(g, languages(c), languageLabels(), lyrics.language().ordinal(), mx, my);
        about(g, c, c.y() + 129F, switch (lyrics.language()) {
            case ORIGINAL -> ModernText.t("Only the original lyrics.", "只显示原文。");
            case TRANSLATION -> ModernText.t("The Chinese translation under each line, for songs that have one.", "外语歌在每行原文下方显示中文译文。");
            case ROMANIZATION -> ModernText.t("The romanization under each line (Japanese, Korean...).", "在每行原文下方显示罗马音（日语、韩语等）。");
            case TRANSLATION_ONLY -> ModernText.t("Translated lines show only their translation; the rest stay original.", "有译文的行只显示译文，其余显示原文。");
        });

        float y = c.y() + 156F;
        ModernStyle.fill(g, c.x(), Math.round(y), c.x() + c.w(), Math.round(y) + 1, 0x14B8D3E7);
        y += 8F;
        section(g, c, y, ModernText.t("CHANNEL", "歌词渠道"));
        String channel = ModernMusicBrowser.channelLabels()[lyrics.channel().ordinal()];
        String where = ModernText.t(channel + " - change it in search's advanced options", channel + " · 可在搜索的高级选项中更改");
        for (String line : ModernTypography.wrap(ModernTypography.Face.TEXT, where, 0.72F, c.w(), 2)) {
            y += 11F;
            ModernTypography.draw(g, ModernTypography.Face.TEXT, line, c.x(), y, 0.72F, 0xFFB4CADB);
        }

        // What the current track has, so the choices can be seen at work.
        Track track = player.current();
        if (track == null) return;
        y += 20F;
        section(g, c, y, ModernText.t("NOW PLAYING", "当前歌曲"));
        Lyrics shown = lyrics.get(track);
        LyricsService.Provider provider = lyrics.provider(track);
        String state;
        if (shown.hasLines()) {
            String kind = shown.kind() == Lyrics.Kind.WORD ? ModernText.t("word by word", "逐词") : ModernText.t("line by line", "逐行");
            state = provider == null ? kind : (provider == LyricsService.Provider.QQ ? ModernText.t("QQ Music", "QQ 音乐") : ModernText.t("NetEase", "网易云")) + " · " + kind;
            boolean translated = shown.lines().stream().anyMatch(l -> l.translation() != null);
            boolean romanized = shown.lines().stream().anyMatch(l -> l.romanization() != null);
            state += translated && romanized ? ModernText.t(" · translation, romanization", " · 有译文和音译")
                : translated ? ModernText.t(" · translation", " · 有译文")
                : romanized ? ModernText.t(" · romanization", " · 有音译")
                : ModernText.t(" · no translation", " · 无译文");
        } else {
            state = ModernLyricsPanel.message(lyrics.why(track), lyrics.channel());
        }
        y += 11F;
        String title = ModernTypography.wrap(ModernTypography.Face.TEXT, track.title(), 0.8F, c.w(), 1).getFirst();
        ModernTypography.draw(g, ModernTypography.Face.TEXT, title, c.x(), y, 0.8F, 0xFFE3F0F8);
        y += 11F;
        ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.wrap(ModernTypography.Face.TEXT, state, 0.72F, c.w(), 1).getFirst(),
            c.x(), y, 0.72F, shown.kind() == Lyrics.Kind.WORD ? ModernRows.GOOD : 0xFFB4CADB);
    }

    private static void section(GuiGraphicsExtractor g, ModernMusicView.Box c, float y, String label) {
        ModernTypography.draw(g, ModernTypography.Face.TEXT, label, c.x(), y, 0.7F, 0xFF9DB9CF);
    }

    /** What the chosen option does, in at most two lines. */
    private static void about(GuiGraphicsExtractor g, ModernMusicView.Box c, float y, String text) {
        for (String line : ModernTypography.wrap(ModernTypography.Face.TEXT, text, 0.72F, c.w(), 2)) {
            ModernTypography.draw(g, ModernTypography.Face.TEXT, line, c.x(), y, 0.72F, 0xFFB4CADB);
            y += 10F;
        }
    }

    void mouseClicked(ModernMusicView.Box c, double mx, double my, int button) {
        if (button != 0) return;
        int tab = segmentAt(tabs(c), 2, mx, my);
        if (tab >= 0) {
            effectsTab = tab == 1;
            return;
        }
        if (effectsTab) {
            MusicEffects fx = Client.getInstance().getMusicEffects();
            FxLayout l = fxLayout(c);
            if (my < l.top()) return;
            for (int i = 0; i < 3; i++) {
                ModernMusicView.Box bar = l.sliders()[i];
                if (my >= l.sliderY()[i] && my < bar.y() + bar.h() + 2 && mx >= bar.x() - 4 && mx < bar.x() + bar.w() + 4) {
                    this.dragging = i;
                    dial(fx, i, bar, mx);
                    return;
                }
            }
            // A row's label counts as well as its switch.
            for (int row = 0; row < 3; row++) {
                ModernMusicView.Box s = l.switches()[row];
                boolean hit = s.contains(mx, my) || (my >= l.rowY()[row] && my < l.rowY()[row] + 12 && mx >= c.x() && mx < c.x() + c.w());
                if (!hit) continue;
                switch (row) {
                    case 0 -> fx.setUnderwaterSound(!fx.underwaterSound());
                    case 1 -> fx.setSpectrum(!fx.spectrum());
                    default -> fx.setIslandColor(!fx.islandColor());
                }
                Client.getInstance().saveConfig();
                return;
            }
            int picked = segmentAt(l.particles(), 3, mx, my);
            if (picked >= 0) {
                fx.setParticles(MusicEffects.Particles.values()[picked]);
                Client.getInstance().saveConfig();
            }
            return;
        }
        LyricsService lyrics = Client.getInstance().getMusicLibrary().lyrics();
        int mode = segmentAt(modes(c), 3, mx, my);
        int language = segmentAt(languages(c), 4, mx, my);
        if (mode >= 0) lyrics.setMode(LyricsService.Mode.values()[mode]);
        else if (language >= 0) lyrics.setLanguage(LyricsService.Language.values()[language]);
        else return;
        Client.getInstance().saveConfig();
    }

    /** Sets dial {@code i} (height, intensity, opacity) from where the pointer is along its bar. */
    private static void dial(MusicEffects fx, int i, ModernMusicView.Box bar, double mx) {
        float f = (float)Math.max(0.0, Math.min(1.0, (mx - bar.x()) / Math.max(1, bar.w())));
        switch (i) {
            case 0 -> fx.setSpectrumHeight(MusicEffects.MIN_HEIGHT + f * (MusicEffects.MAX_HEIGHT - MusicEffects.MIN_HEIGHT));
            case 1 -> fx.setSpectrumIntensity(Math.round((MusicEffects.MIN_INTENSITY + f * (MusicEffects.MAX_INTENSITY - MusicEffects.MIN_INTENSITY)) * 10F) / 10F);
            default -> fx.setSpectrumOpacity(MusicEffects.MIN_OPACITY + f * (MusicEffects.MAX_OPACITY - MusicEffects.MIN_OPACITY));
        }
    }

    /** A dial being dragged follows the pointer; true while one is. */
    boolean mouseDragged(ModernMusicView.Box c, double mx, double my) {
        if (this.dragging < 0 || !effectsTab) return false;
        dial(Client.getInstance().getMusicEffects(), this.dragging, fxLayout(c).sliders()[this.dragging], mx);
        return true;
    }

    /** Letting go of a dial saves where it was left. */
    void mouseReleased() {
        if (this.dragging < 0) return;
        this.dragging = -1;
        Client.getInstance().saveConfig();
    }

    /** The effects tab scrolls when the window is too short for it. */
    void mouseScrolled(ModernMusicView.Box c, double amount) {
        if (!effectsTab) return;
        fxScroll -= (float)amount * 18F;
        clampScroll(c);
    }

    // --- a segmented control, shared with search's advanced options -------------------------------------

    /** Equal segments in a rounded track; the chosen one is a lit ice pill with dark text. */
    static void segmented(GuiGraphicsExtractor g, ModernMusicView.Box box, String[] labels, int selected, int mx, int my) {
        int radius = box.h() / 2;
        ModernStyle.rounded(g, box.x(), box.y(), box.w(), box.h(), radius, 0x26CDEBFF);
        ModernStyle.rounded(g, box.x() + 1, box.y() + 1, box.w() - 2, box.h() - 2, radius - 1, 0xF00E2031);
        float scale = box.h() >= 20 ? 0.84F : 0.78F;
        for (int i = 0; i < labels.length; i++) {
            ModernMusicView.Box seg = segment(box, labels.length, i);
            boolean on = i == selected, hover = seg.contains(mx, my);
            if (on) {
                ModernStyle.halo(g, seg.x() + 1, seg.y() + 1, seg.w() - 2, seg.h() - 2, radius - 1, ModernStyle.GLOW, 0.3F);
                ModernStyle.rounded(g, seg.x() + 1, seg.y() + 1, seg.w() - 2, seg.h() - 2, radius - 1, 0xFFCDEBFF);
            } else if (hover) {
                ModernStyle.rounded(g, seg.x() + 1, seg.y() + 1, seg.w() - 2, seg.h() - 2, radius - 1, 0x1FCDEBFF);
            }
            String label = ModernTypography.wrap(ModernTypography.Face.TEXT, labels[i], scale, seg.w() - 6F, 1).getFirst();
            float tw = ModernTypography.width(ModernTypography.Face.TEXT, label, scale);
            ModernTypography.draw(g, ModernTypography.Face.TEXT, label, seg.x() + (seg.w() - tw) / 2F, seg.y() + (seg.h() - 9F * scale) / 2F - 0.5F, scale,
                on ? 0xFF12283A : hover ? 0xFFF0F8FF : 0xFFB9D2E4);
        }
    }

    /** Which segment of {@code count} is under the pointer, or -1. */
    static int segmentAt(ModernMusicView.Box box, int count, double mx, double my) {
        if (!box.contains(mx, my)) return -1;
        for (int i = 0; i < count; i++) if (segment(box, count, i).contains(mx, my)) return i;
        return -1;
    }

    private static ModernMusicView.Box segment(ModernMusicView.Box box, int count, int i) {
        int x0 = box.x() + box.w() * i / count, x1 = box.x() + box.w() * (i + 1) / count;
        return new ModernMusicView.Box(x0, box.y(), x1 - x0, box.h());
    }
}
