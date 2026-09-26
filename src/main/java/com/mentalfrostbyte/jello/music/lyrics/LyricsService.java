package com.mentalfrostbyte.jello.music.lyrics;

import com.mentalfrostbyte.jello.music.Track;
import com.mentalfrostbyte.jello.music.netease.NeteaseApi;
import com.mentalfrostbyte.jello.music.qq.QQMusicApi;
import com.mentalfrostbyte.jello.music.qq.QQMusicMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds lyrics for tracks in the background, from the chosen {@link Channel}:
 * <ul>
 *   <li>{@link Channel#MIX} (default), best first: NetEase YRC (word-timed), else the NetEase LRC (line-timed) shown
 *       at once and replaced if QQ Music has a QRC (word-timed) for the QQ result the {@link QQMusicMatcher}
 *       accepts;</li>
 *   <li>{@link Channel#NETEASE}: NetEase only - YRC, else LRC;</li>
 *   <li>{@link Channel#QQ}: QQ Music's QRC only.</li>
 * </ul>
 * The {@link Mode} then decides how they are shown: {@link Mode#AUTO} word by word when the lyrics are word-timed
 * and line by line otherwise, {@link Mode#LINE} always line by line, {@link Mode#WORD} only word-timed lyrics.
 * The {@link Language} decides what goes with each line: nothing, its translation or its romanization under it,
 * or the translation instead of it. Translations come from the lyrics' own service first (NetEase {@code tlyric} /
 * {@code romalrc}, QQ {@code contentts} / {@code contentroma}), matched to lines by time; in the mixed channel the
 * other service fills the gaps.
 * {@link #get} never blocks: it returns what is known so far and starts the lookup the first time a track is asked
 * about. Changing the channel looks everything up again; changing the mode or language only changes what is shown.
 */
public final class LyricsService {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sigma/Lyrics");
    private static final int CACHE = 64;

    /** Where lyrics come from. */
    public enum Channel { MIX, QQ, NETEASE }

    /** How they are shown. */
    public enum Mode { AUTO, LINE, WORD }

    /** What goes with each line. */
    public enum Language { ORIGINAL, TRANSLATION, ROMANIZATION, TRANSLATION_ONLY }

    /** Who supplied a track's lyrics. */
    public enum Provider { NETEASE, QQ }

    /** Why there's nothing to show. */
    public enum Why { SEARCHING, NONE, INSTRUMENTAL, NO_WORD_TIMING }

    private final @Nullable NeteaseApi netease;
    // -Dsigma.debug.musicLyrics=<file>: every track gets this YRC/QRC/LRC file's lyrics (offline captures and tests).
    private final @Nullable Lyrics override = readOverride(System.getProperty("sigma.debug.musicLyrics"));
    private final ExecutorService threads = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "Sigma lyrics");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Slot> cache = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Slot> eldest) {
            return size() > CACHE;
        }
    };
    private volatile Channel channel = Channel.MIX;
    private volatile Mode mode = Mode.AUTO;
    private volatile Language language = Language.TRANSLATION;

    private static final class Slot {
        volatile Lyrics raw = Lyrics.NONE;
        volatile @Nullable Provider provider;
        volatile boolean done;
        // The last raw lyrics shown in a mode, so a frame doesn't rebuild them.
        volatile @Nullable Shown shown;
    }

    private record Shown(Lyrics raw, Mode mode, Language language, Lyrics lyrics) {}

    /** @param netease {@code null} when there is no online source (offline previews): every track has none */
    public LyricsService(@Nullable NeteaseApi netease) {
        this.netease = netease;
    }

    public Channel channel() {
        return this.channel;
    }

    /** Looks every track's lyrics up again from {@code channel}. */
    public void setChannel(Channel channel) {
        if (channel == this.channel) return;
        this.channel = channel;
        synchronized (this.cache) {
            this.cache.clear();
        }
    }

    public Mode mode() {
        return this.mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Language language() {
        return this.language;
    }

    public void setLanguage(Language language) {
        this.language = language;
    }

    /** What goes under {@code line} in {@code language}: its translation, its romanization, or nothing. */
    public static @Nullable String extra(Lyrics.Line line, Language language) {
        return switch (language) {
            case TRANSLATION -> line.translation();
            case ROMANIZATION -> line.romanization();
            case ORIGINAL, TRANSLATION_ONLY -> null;
        };
    }

    /** {@code track}'s lyrics as the mode shows them (so far: word-timed ones may still replace line-timed ones). */
    public Lyrics get(@Nullable Track track) {
        if (track == null) return Lyrics.NONE;
        if (this.override != null) return show(this.override, this.mode, this.language);
        Slot slot = slot(track);
        if (slot == null) return Lyrics.NONE;
        Mode mode = this.mode;
        Language language = this.language;
        Lyrics raw = slot.raw;
        Shown shown = slot.shown;
        if (shown == null || shown.raw() != raw || shown.mode() != mode || shown.language() != language) {
            slot.shown = shown = new Shown(raw, mode, language, show(raw, mode, language));
        }
        return shown.lyrics();
    }

    /** Who supplied {@code track}'s lyrics, or {@code null} when nobody (yet). */
    public @Nullable Provider provider(@Nullable Track track) {
        if (track == null || this.override != null) return null;
        Slot slot = slot(track);
        return slot == null ? null : slot.provider;
    }

    /** When {@link #get} has no lines: still looking, none found, an instrumental, or only line-timed in word mode. */
    public Why why(@Nullable Track track) {
        Lyrics raw;
        if (track != null && this.override != null) {
            raw = this.override;
        } else {
            Slot slot = track == null ? null : slot(track);
            if (slot == null) return Why.NONE;
            if (!slot.done && !slot.raw.hasLines()) return Why.SEARCHING;
            raw = slot.raw;
        }
        if (raw.kind() == Lyrics.Kind.INSTRUMENTAL) return Why.INSTRUMENTAL;
        if (this.mode == Mode.WORD && raw.kind() == Lyrics.Kind.LINE) return Why.NO_WORD_TIMING;
        return Why.NONE;
    }

    /** What {@code mode} makes of {@code raw}: word-timed lines lose their words for LINE; WORD drops line-timed ones. */
    static Lyrics show(Lyrics raw, Mode mode) {
        return switch (mode) {
            case AUTO -> raw;
            case LINE -> raw.kind() == Lyrics.Kind.WORD ? asLines(raw) : raw;
            case WORD -> raw.kind() == Lyrics.Kind.LINE ? Lyrics.NONE : raw;
        };
    }

    /** {@link #show(Lyrics, Mode)}, then for TRANSLATION_ONLY each translated line reads as its translation (lit whole). */
    static Lyrics show(Lyrics raw, Mode mode, Language language) {
        Lyrics shown = show(raw, mode);
        if (language != Language.TRANSLATION_ONLY || !shown.hasLines()) return shown;
        List<Lyrics.Line> lines = new ArrayList<>(shown.lines().size());
        boolean changed = false;
        for (Lyrics.Line line : shown.lines()) {
            if (line.translation() == null) {
                lines.add(line);
            } else {
                lines.add(new Lyrics.Line(line.startMs(), line.endMs(), line.translation(), List.of(), line.translation(), line.romanization()));
                changed = true;
            }
        }
        return changed ? new Lyrics(shown.kind(), List.copyOf(lines)) : shown;
    }

    private static Lyrics asLines(Lyrics raw) {
        List<Lyrics.Line> lines = new ArrayList<>(raw.lines().size());
        for (Lyrics.Line line : raw.lines()) {
            lines.add(new Lyrics.Line(line.startMs(), line.endMs(), line.text(), List.of(), line.translation(), line.romanization()));
        }
        return new Lyrics(Lyrics.Kind.LINE, List.copyOf(lines));
    }

    /** The cache slot for {@code track}, starting its lookup the first time; {@code null} when it can't have lyrics. */
    private @Nullable Slot slot(Track track) {
        if (this.netease == null || !track.id().startsWith(NeteaseApi.TRACK_PREFIX)) return null;
        Slot slot;
        boolean fresh = false;
        synchronized (this.cache) {
            slot = this.cache.get(track.id());
            if (slot == null) {
                slot = new Slot();
                this.cache.put(track.id(), slot);
                fresh = true;
            }
        }
        if (fresh) {
            Slot target = slot;
            Channel channel = this.channel;
            this.threads.execute(() -> resolve(track, target, channel));
        }
        return slot;
    }

    private void resolve(Track track, Slot slot, Channel channel) {
        try {
            if (channel == Channel.QQ) {
                Lyrics qrc = fromQq(track);
                if (qrc != null) found(slot, qrc, Provider.QQ);
                return;
            }
            NeteaseApi.LyricTexts texts = this.netease.lyrics(NeteaseApi.songId(track));
            Lyrics translation = LyricsParser.lrc(texts.translation()), romanization = LyricsParser.lrc(texts.romanization());
            Lyrics yrc = LyricsParser.yrc(texts.yrc());
            if (yrc.kind() == Lyrics.Kind.WORD) {
                found(slot, LyricsParser.attach(yrc, translation, romanization), Provider.NETEASE);
                return;
            }
            Lyrics lrc = LyricsParser.lrc(texts.lrc());
            if (lrc.hasLines()) {
                found(slot, LyricsParser.attach(lrc, translation, romanization), Provider.NETEASE);
            } else if (texts.instrumental()) {
                found(slot, Lyrics.INSTRUMENTAL, Provider.NETEASE);
                return;
            }
            if (channel == Channel.MIX) {
                Lyrics qrc = fromQq(track);
                // QQ's own translation first; NetEase's fills any line QQ left without one.
                if (qrc != null) found(slot, LyricsParser.attach(qrc, translation, romanization), Provider.QQ);
            }
        } catch (Exception e) {
            LOGGER.info("Sigma lyrics: none for '{}' ({})", track.title(), e.getMessage());
        } finally {
            slot.done = true;
            // Which source won - NetEase's word timing comes and goes by song (and by day), so this is worth seeing.
            if (slot.raw.hasLines()) LOGGER.info("Sigma lyrics: '{}' - {} ({}, {})", track.title(), slot.provider, slot.raw.kind(), channel);
        }
    }

    private static void found(Slot slot, Lyrics lyrics, Provider provider) {
        slot.provider = provider;
        slot.raw = lyrics;
    }

    private static @Nullable Lyrics fromQq(Track track) {
        try {
            String artist = track.artist().split(" / ")[0];
            List<QQMusicApi.QQTrack> candidates = QQMusicApi.search(track.title() + " " + artist, 8);
            QQMusicMatcher.Match match = QQMusicMatcher.match(candidates, track.title(), artist, track.durationMs());
            if (match == null) return null;
            QQMusicApi.QQLyrics found = QQMusicApi.fetchLyrics(match.track().songId());
            if (found == null || found.qrc() == null) return null;
            Lyrics lyrics = LyricsParser.qrc(found.qrc());
            if (lyrics.kind() != Lyrics.Kind.WORD) return null;
            Lyrics translation = found.translation() == null ? null : LyricsParser.lrc(found.translation());
            Lyrics romanization = found.romanization() == null ? null : LyricsParser.qrc(found.romanization());
            return LyricsParser.attach(lyrics, translation, romanization);
        } catch (Exception e) {
            return null;
        }
    }

    private static @Nullable Lyrics readOverride(@Nullable String file) {
        if (file == null || file.isBlank()) return null;
        try {
            String text = java.nio.file.Files.readString(java.nio.file.Path.of(file));
            Lyrics lyrics = text.contains("LyricContent") ? LyricsParser.qrc(text)
                : text.lines().anyMatch(line -> line.matches("^\\[\\d+,\\d+\\].*")) ? LyricsParser.yrc(text) : LyricsParser.lrc(text);
            LOGGER.info("Sigma lyrics: debug override from {} ({} lines, {})", file, lyrics.lines().size(), lyrics.kind());
            return lyrics;
        } catch (Exception e) {
            LOGGER.warn("Sigma lyrics: can't read debug override {}", file, e);
            return null;
        }
    }

    public void close() {
        this.threads.shutdownNow();
    }
}
