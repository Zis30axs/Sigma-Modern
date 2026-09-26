package com.mentalfrostbyte.jello.gui.modern;

import com.mentalfrostbyte.Client;
import com.mentalfrostbyte.jello.event.EventTarget;
import com.mentalfrostbyte.jello.event.impl.client.EventModuleToggle;
import com.mentalfrostbyte.jello.music.MusicPlayer;
import com.mentalfrostbyte.jello.music.Track;
import com.mentalfrostbyte.jello.music.lyrics.LyricCredits;
import com.mentalfrostbyte.jello.music.lyrics.Lyrics;
import com.mentalfrostbyte.jello.music.lyrics.LyricsService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * SigmaModern's in-game "dynamic island", after the reference design: a light glass pill at the top of the
 * screen showing what the {@link MusicPlayer} is doing - the cover, the track, playing or paused, and an
 * equalizer that moves while it plays. It eases in when the game view comes back, and when a module is
 * switched in game it grows a second row naming the module and its new state for a moment.
 *
 * <p>The reference also expands on click into full transport controls. In game there is no pointer to click
 * with, so this island draws no expand affordance; the controls live in the ClickGUI's player panel.</p>
 */
public final class ModernIsland {
    private static final long ACTIVITY_NANOS = 1_600_000_000L;
    private static final boolean DEBUG_ACTIVITY = Boolean.getBoolean("sigma.debug.islandActivity");
    private static final int ROW_H = 30, ACTIVITY_H = 19, EXTRA_H = 11;

    private static final float[] BARS = new float[5];
    private static @Nullable String activityName;
    private static boolean activityOn;
    private static long activityAt;
    private static long lastDraw, appearStart;
    private static float width = -1F, extraOpen, activityOpen, time;
    // Whether the lyrics on show have any translation (or romanization) at all - worked out once per lyrics.
    private static @Nullable Lyrics extrasFor;
    private static LyricsService.@Nullable Language extrasLanguage;
    private static boolean extrasAny;
    // The last translation shown, kept so it can fade out with its row instead of vanishing first.
    private static @Nullable String lastExtra;
    // The translation row, once a translated line has opened it: kept open while lines keep coming.
    private static boolean rowLatched;
    // "作词 X · 作曲 Y" for the lyrics on show, worked out once per lyrics (null when they name neither).
    private static @Nullable Lyrics creditsFor;
    private static @Nullable String creditsText;

    private ModernIsland() {}

    /**
     * Records module toggles made in game (not from an open interface, and not while loading) for the island
     * to show. Registered on the event bus by {@code Client}; recording is all it does.
     */
    public static final class ActivityListener {
        @EventTarget
        public void onToggle(EventModuleToggle event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.gui.screen() != null) return;
            activityName = event.getModule().getName();
            activityOn = event.isEnabled();
            activityAt = System.nanoTime();
        }
    }

    static void render(GuiGraphicsExtractor g) {
        MusicPlayer player = Client.getInstance().getMusicPlayer();
        long now = System.nanoTime();
        float dt = lastDraw == 0L ? 0F : Math.min(0.05F, (now - lastDraw) / 1_000_000_000F);
        // A gap means the game view just came back (a screen closed, a world loaded): ease in again.
        if (now - lastDraw > 250_000_000L) appearStart = now;
        lastDraw = now;
        time += dt;
        float appear = ModernStyle.easeOut((now - appearStart) / 1_000_000_000F / 0.38F);

        Track track = player.current();
        boolean playing = player.isPlaying();
        String title = track == null ? ModernText.t("No music", "没有音乐") : track.title();
        // With nothing being sung (the intro, a long break, a song without lyrics): who sings it, and the album unless
        // it's just the song's own name; paused, that and by whom.
        String by = track == null || track.artist().isEmpty() ? player.sourceName() : track.artist();
        String details = track == null || track.album().isEmpty() || track.album().equals(track.title()) ? by : by + " · 《" + track.album() + "》";
        String status = playing ? details : ModernText.t("Paused", "已暂停") + " · " + by;
        // While a line is being sung it takes the status row, swept word by word when the lyrics are word-timed.
        ModernLyricSweep.Now lyric = playing ? ModernLyricSweep.now(player) : null;
        String line = lyric == null ? null : lyric.line().text().strip();
        if (line != null) status = line;
        // Bilingual: a second row for the sung line's translation (or romanization). Only a line that has one opens it
        // - so the credit lines lyrics start with ("title - artist", "作词 : ...") don't, nor does the intro - and once
        // open it stays open while lines keep coming, over untranslated ones and the short pauses between lines (the
        // lyric line's own hold), so it doesn't pump. A long break, a pause or the next song closes it.
        LyricsService lyrics = Client.getInstance().getMusicLibrary().lyrics();
        LyricsService.Language language = lyrics.language();
        Lyrics shownLyrics = lyrics.get(track);
        boolean songHasExtras = lyric != null && hasExtras(shownLyrics, language);
        String extra = songHasExtras ? LyricsService.extra(lyric.line(), language) : null;
        boolean lineHasExtra = extra != null && !extra.isBlank();
        if (!songHasExtras) rowLatched = false;
        else if (lineHasExtra) rowLatched = true;
        boolean bilingual = rowLatched;
        // Between sung lines the same row names the lyricist and composer, when the lyrics' credits do.
        String credits = playing && lyric == null ? credits(shownLyrics) : null;
        boolean rowOpen = bilingual || credits != null;
        if (lineHasExtra) lastExtra = extra.strip();
        else if (credits != null) lastExtra = credits;
        // Closing, the last text goes with the row; open over an untranslated line, the row stays empty.
        String extraShown = credits != null ? credits : bilingual ? (lineHasExtra ? extra.strip() : null) : lastExtra;
        boolean activity = DEBUG_ACTIVITY || (activityName != null && now - activityAt < ACTIVITY_NANOS);
        String name = DEBUG_ACTIVITY && activityName == null ? "Fullbright" : activityName;
        boolean on = DEBUG_ACTIVITY && activityName == null || activityOn;

        float textW = Math.max(ModernTypography.width(ModernTypography.Face.TEXT, title, 1F),
            ModernTypography.width(ModernTypography.Face.TEXT, status, line != null ? 0.84F : 0.78F));
        if (credits != null) textW = Math.max(textW, ModernTypography.width(ModernTypography.Face.TEXT, credits, 0.72F));
        // While lyrics run the pill keeps one wide size rather than resizing with every line.
        float targetW = line != null ? 236F : Math.max(150F, Math.min(236F, 32F + textW + 42F));
        width = width < 0F ? targetW : ModernStyle.smooth(width, targetW, dt, 10F);
        // Eased rather than snapped: about 0.4 s to open or close.
        extraOpen = ModernStyle.smooth(extraOpen, rowOpen ? 1F : 0F, dt, 7F);
        if (extraOpen < 0.01F && !rowOpen) lastExtra = null;
        activityOpen = ModernStyle.smooth(activityOpen, activity ? 1F : 0F, dt, 13F);
        float body = ROW_H + extraOpen * EXTRA_H;
        float height = body + activityOpen * ACTIVITY_H;

        // The cover's colours, when that's on (ice otherwise, or while a cover has none).
        ModernCoverColors.update(track, Client.getInstance().getMusicEffects().islandColor());
        float tint = ModernCoverColors.tint();
        int accent = ModernCoverColors.accent();
        int rim = ModernTypography.fade(ModernStyle.mix(0xFFEDFAFF, accent, 0.45F * tint), 0.5F);
        int glass = 0x9C000000 | (ModernCoverColors.deep() & 0xFFFFFF);
        int glow = ModernStyle.mix(0xFF000000 | ModernStyle.GLOW, accent, tint) & 0xFFFFFF;
        int barColor = ModernStyle.mix(0xFFC4ECFF, ModernStyle.mix(accent, 0xFFFFFFFF, 0.35F), tint);

        int w = Math.round(width), h = Math.round(height);
        int x = (g.guiWidth() - w) / 2, y = 8;
        int radius = Math.min(15, h / 2);
        float scale = 0.93F + 0.07F * appear;
        g.pose().pushMatrix();
        try (var fade = ModernStyle.alphaScope(appear)) {
            g.pose().translate(g.guiWidth() / 2F, y - (1F - appear) * 10F);
            g.pose().scale(scale, scale);
            g.pose().translate(-g.guiWidth() / 2F, -y);

            // Light glass over the world: a bright rim, a translucent navy body, a sheen from the top-left.
            ModernStyle.dropShadow(g, x, y + 3, w, h, radius, 0.8F);
            ModernStyle.rounded(g, x, y, w, h, radius, rim);
            ModernStyle.rounded(g, x + 1, y + 1, w - 2, h - 2, radius - 1, glass);
            // Light catching the top-left of the glass (a soft spot, not a band - a band's ends showed as hard edges).
            ModernIcons.draw(g, ModernIcons.Icon.SOFT_DOT, x + w * 0.26F - 44F, y - 14F, 88F, 0x24F5FEFF);

            if (playing) ModernStyle.halo(g, x + 4, y + 4, 22, 22, 7, glow, 0.35F);
            ModernCovers.draw(g, track, x + 4F, y + 4F, 22F, 0.3F, 0xFFFFFFFF);
            float textRoom = w - 32F - 34F;
            ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.wrap(ModernTypography.Face.TEXT, title, 1F, textRoom, 1).getFirst(),
                x + 32F, y + 5F, 1F, 0xFFF3FAFF);
            if (line != null) {
                String shown = ModernTypography.wrap(ModernTypography.Face.TEXT, line, 0.84F, textRoom, 1).getFirst();
                float lit = lyric.progress() * line.length() / Math.max(1, shown.length());
                ModernLyricSweep.draw(g, ModernTypography.Face.TEXT, shown, x + 32F, y + 16.5F, 0.84F, lit, 0xB3C9DDEA, 0xFFFFFFFF);
            } else {
                ModernTypography.draw(g, ModernTypography.Face.TEXT, ModernTypography.wrap(ModernTypography.Face.TEXT, status, 0.78F, textRoom, 1).getFirst(),
                    x + 32F, y + 17F, 0.78F, 0xFFDFEDF5);
            }
            ModernMusicView.soundBars(g, BARS, x + w - 12F - 18F, y + 15F, 13F, 2F, 2F, barColor, playing, dt, time,
                ModernMusicView.levels(player.spectrum(), BARS.length));

            if (extraOpen > 0.02F && extraShown != null) {
                // The sung line's translation or romanization, under it; never swept. It fades in behind the opening
                // row (squared), so text never shows in a gap too narrow for it.
                g.enableScissor(x, y, x + w, y + h);
                try (var row = ModernStyle.alphaScope(extraOpen * extraOpen)) {
                    String shown = ModernTypography.wrap(ModernTypography.Face.TEXT, extraShown, 0.72F, textRoom, 1).getFirst();
                    ModernTypography.draw(g, ModernTypography.Face.TEXT, shown, x + 32F, y + ROW_H - 2.5F, 0.72F, 0xD9D2E6F2);
                } finally {
                    g.disableScissor();
                }
            }

            float reveal = Math.max(0F, Math.min(1F, activityOpen));
            if (reveal > 0.02F && name != null) {
                g.enableScissor(x, y, x + w, y + h);
                try (var row = ModernStyle.alphaScope(reveal)) {
                    int top = Math.round(y + body);
                    int dotX = x + 14, dotY = top + 3;
                    if (on) ModernStyle.halo(g, dotX, dotY, 7, 7, 3, 0x5CD3FF, 0.7F);
                    ModernStyle.rounded(g, dotX, dotY, 7, 7, 3, on ? 0xFF5CD3FF : 0xFF7B96A8);
                    float nameW = ModernTypography.width(ModernTypography.Face.TEXT, name, 0.95F);
                    ModernTypography.draw(g, ModernTypography.Face.TEXT, name, x + 27F, top + 1.5F, 0.95F, 0xFFEEF8FF);
                    ModernTypography.draw(g, ModernTypography.Face.TEXT, on ? ModernText.t("Enabled", "已启用") : ModernText.t("Disabled", "已关闭"),
                        x + 27F + nameW + 6F, top + 2.5F, 0.8F, 0xFFC9E2F0);
                } finally {
                    g.disableScissor();
                }
            }
        } finally {
            g.pose().popMatrix();
        }
    }

    /** "作词 X · 作曲 Y" from {@code lyrics}' credit lines, or {@code null} when they name neither; remembered per lyrics. */
    private static @Nullable String credits(Lyrics lyrics) {
        if (lyrics != creditsFor) {
            creditsFor = lyrics;
            LyricCredits.Credits credits = LyricCredits.credits(lyrics);
            String lyricist = shortNames(credits.lyricist()), composer = shortNames(credits.composer());
            StringBuilder text = new StringBuilder();
            if (lyricist != null && lyricist.equals(composer)) {
                // One writer for both: "词曲 米津玄師".
                text.append(ModernText.t("Words & music ", "词曲 ")).append(lyricist);
            } else {
                if (lyricist != null) text.append(ModernText.t("Words ", "作词 ")).append(lyricist);
                if (composer != null) text.append(text.isEmpty() ? "" : " · ").append(ModernText.t("Music ", "作曲 ")).append(composer);
            }
            creditsText = text.isEmpty() ? null : text.toString();
        }
        return creditsText;
    }

    /** A credit's names, the first two of a longer list ("Max Martin/Oscar Holter 等"), so a row can hold them. */
    private static @Nullable String shortNames(@Nullable String names) {
        if (names == null) return null;
        String[] parts = names.split("\\s*[/、,，]\\s*");
        if (parts.length <= 2) return names.strip();
        return parts[0] + "/" + parts[1] + ModernText.t(" etc.", " 等");
    }

    /** Whether any line of {@code lyrics} has something to show under it in {@code language}; remembered per lyrics. */
    private static boolean hasExtras(Lyrics lyrics, LyricsService.Language language) {
        if (lyrics != extrasFor || language != extrasLanguage) {
            extrasFor = lyrics;
            extrasLanguage = language;
            extrasAny = false;
            for (Lyrics.Line line : lyrics.lines()) {
                String extra = LyricsService.extra(line, language);
                if (extra != null && !extra.isBlank()) {
                    extrasAny = true;
                    break;
                }
            }
        }
        return extrasAny;
    }
}
