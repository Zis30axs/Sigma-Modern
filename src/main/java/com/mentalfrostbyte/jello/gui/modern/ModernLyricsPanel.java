package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.music.MusicPlayer;
import com.mentalfrostbyte.jello.music.Track;
import com.mentalfrostbyte.jello.music.lyrics.Lyrics;
import com.mentalfrostbyte.jello.music.lyrics.LyricsService;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The music window's lyrics, shown in place of the album art: the lines scroll so the one being sung sits a
 * little above the middle, lit with a karaoke sweep ({@link ModernLyricSweep}) for word-timed lyrics or all at
 * once for line-timed ones; lines further from it fade out. A line's translation or romanization (as the lyric
 * language setting asks) goes under it, smaller. Without lyrics it says why - still looking, none found, or an
 * instrumental.
 */
final class ModernLyricsPanel {
    private static final ModernTypography.Face FACE = ModernTypography.Face.TEXT;
    private static final float SCALE = 0.95F, ROW = 12F, GAP = 7F, EXTRA_SCALE = 0.78F, EXTRA_ROW = 10F;
    private static final int LIT = 0xFFFFFFFF, UNSUNG = 0x8CD3E6F3, OTHER = 0xA6B4CCDD, EXTRA_LIT = 0xD9D7E8F4, EXTRA = 0x99B4CCDD;

    private Lyrics laidOut;
    private int laidWidth;
    private LyricsService.Language laidLanguage;
    private List<List<String>> rows = List.of(), extras = List.of();
    private float[] tops = new float[0], heights = new float[0];
    private float scroll = Float.NaN;

    void render(GuiGraphicsExtractor g, int x, int y, int w, int h, MusicPlayer player, Track track, float dt) {
        LyricsService service = Client.getInstance().getMusicLibrary().lyrics();
        Lyrics lyrics = service.get(track);
        if (!lyrics.hasLines()) {
            String message = message(service.why(track), service.channel());
            float my = y + h / 2F - 5F;
            for (String line : ModernTypography.wrap(FACE, message, SCALE, w - 24F, 2)) {
                ModernTypography.draw(g, FACE, line, x + (w - ModernTypography.width(FACE, line, SCALE)) / 2F, my, SCALE, OTHER);
                my += ROW;
            }
            this.scroll = Float.NaN;
            return;
        }
        layout(lyrics, w - 20, service.language());

        long position = player.positionMs();
        int index = lyrics.indexAt(position);
        int focus = Math.max(0, index);
        float target = this.tops[focus] + this.heights[focus] / 2F;
        // Glide to the next line; a seek or a new song jumps there.
        if (Float.isNaN(this.scroll) || Math.abs(target - this.scroll) > h) this.scroll = target;
        else this.scroll = ModernStyle.smooth(this.scroll, target, dt, 9F);

        float center = y + h * 0.44F;
        g.enableScissor(x, y, x + w, y + h);
        try {
            for (int i = 0; i < this.rows.size(); i++) {
                List<String> rows = this.rows.get(i);
                float top = center - this.scroll + this.tops[i];
                if (top > y + h + 4F || top + this.heights[i] < y - 4F) continue;
                float mid = top + this.heights[i] / 2F;
                float fade = 1F - Math.min(1F, Math.abs(mid - center) / (h * 0.56F));
                if (fade <= 0.02F) continue;
                float progress = i == index ? ModernLyricSweep.progress(lyrics, i, position) : -1F;
                drawLine(g, rows, x, w, top, progress >= 0F, progress, fade);
                // The translation or romanization, under the line: brighter while it's sung, never swept.
                float ey = top + rows.size() * ROW + 2F;
                for (String row : this.extras.get(i)) {
                    ModernTypography.draw(g, FACE, row, x + (w - ModernTypography.width(FACE, row, EXTRA_SCALE)) / 2F, ey, EXTRA_SCALE,
                        progress >= 0F ? ModernTypography.fade(EXTRA_LIT, fade) : ModernTypography.fade(EXTRA, fade * fade));
                    ey += EXTRA_ROW;
                }
            }
        } finally {
            g.disableScissor();
        }
        // Who supplied them, and how they're shown: word by word (NetEase YRC / QQ QRC) or line by line.
        String kind = lyrics.kind() == Lyrics.Kind.WORD ? ModernText.t("WORD", "逐词") : ModernText.t("LINE", "逐行");
        LyricsService.Provider provider = service.provider(track);
        if (provider != null) {
            kind = (provider == LyricsService.Provider.QQ ? ModernText.t("QQ Music", "QQ 音乐") : ModernText.t("NetEase", "网易云")) + " · " + kind;
        }
        ModernTypography.draw(g, FACE, kind, x + w - 8F - ModernTypography.width(FACE, kind, 0.56F), y + h - 13F, 0.56F, 0x99B6CBDC);
    }

    /** Why nothing is shown, naming the channel when a strict one came up empty. */
    static String message(LyricsService.Why why, LyricsService.Channel channel) {
        return switch (why) {
            case SEARCHING -> ModernText.t("Looking for lyrics...", "正在查找歌词…");
            case INSTRUMENTAL -> ModernText.t("Instrumental - enjoy", "纯音乐，请欣赏");
            case NO_WORD_TIMING -> ModernText.t("No word-timed lyrics for this track (word mode)", "这首歌没有逐词歌词（逐词模式）");
            case NONE -> switch (channel) {
                case QQ -> ModernText.t("QQ Music has no lyrics for this track", "QQ 音乐暂无此歌歌词");
                case NETEASE -> ModernText.t("NetEase has no lyrics for this track", "网易云暂无此歌歌词");
                case MIX -> ModernText.t("No lyrics for this track", "暂无歌词");
            };
        };
    }

    private static void drawLine(GuiGraphicsExtractor g, List<String> rows, int x, int w, float top, boolean current, float progress, float fade) {
        int total = 0;
        for (String row : rows) total += row.length();
        float sung = progress * total;
        int before = 0;
        for (int r = 0; r < rows.size(); r++) {
            String row = rows.get(r);
            float rx = x + (w - ModernTypography.width(FACE, row, SCALE)) / 2F, ry = top + r * ROW;
            if (current) {
                float lit = row.isEmpty() ? 1F : (sung - before) / row.length();
                ModernLyricSweep.draw(g, FACE, row, rx, ry, SCALE, lit, ModernTypography.fade(UNSUNG, fade), ModernTypography.fade(LIT, fade));
            } else {
                ModernTypography.draw(g, FACE, row, rx, ry, SCALE, ModernTypography.fade(OTHER, fade * fade));
            }
            before += row.length();
        }
    }

    /** Wraps every line (and what goes under it) to the panel's width once per lyrics, width and language, and stacks them. */
    private void layout(Lyrics lyrics, int width, LyricsService.Language language) {
        if (lyrics == this.laidOut && width == this.laidWidth && language == this.laidLanguage) return;
        this.laidOut = lyrics;
        this.laidWidth = width;
        this.laidLanguage = language;
        int n = lyrics.lines().size();
        this.rows = new ArrayList<>(n);
        this.extras = new ArrayList<>(n);
        this.tops = new float[n];
        this.heights = new float[n];
        float y = 0F;
        for (int i = 0; i < n; i++) {
            Lyrics.Line line = lyrics.lines().get(i);
            String text = line.text().strip();
            List<String> wrapped = text.isEmpty() ? List.of("") : ModernTypography.wrap(FACE, text, SCALE, width, 3);
            String extra = LyricsService.extra(line, language);
            List<String> under = extra == null || extra.isBlank() ? List.of() : ModernTypography.wrap(FACE, extra.strip(), EXTRA_SCALE, width, 2);
            this.rows.add(wrapped);
            this.extras.add(under);
            this.tops[i] = y;
            this.heights[i] = wrapped.size() * ROW + (under.isEmpty() ? 0F : 2F + under.size() * EXTRA_ROW);
            y += this.heights[i] + GAP;
        }
        this.scroll = Float.NaN;
    }
}
