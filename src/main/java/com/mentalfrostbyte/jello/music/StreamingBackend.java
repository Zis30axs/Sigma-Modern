package com.mentalfrostbyte.jello.music;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plays MP3 streams: a {@link Resolver} turns a track into a URL (on a worker thread), the bytes are downloaded
 * into memory while a second thread decodes them with JLayer and writes 16-bit PCM to a Java Sound
 * {@link SourceDataLine}. On the way the samples go through the under-water filter ({@link Muffle}, as
 * {@link MusicEffects} asks), then an {@link AudioAnalyzer} for visuals, then the volume - the player's volume times
 * the game's master volume, so muting the game mutes this too, and the visuals keep moving when muted.
 *
 * <p>Nothing here blocks the caller. Each {@link #load} starts a new {@link Session} and closes the previous
 * one; sessions carry a generation number so a slow resolve for a track the user has already skipped can never
 * start playing. Seeking re-reads the in-memory stream from the start, skipping frame headers without decoding.</p>
 */
public final class StreamingBackend implements MusicBackend {
    /** Resolves a track to a stream; {@code null} when there is nothing playable. */
    @FunctionalInterface
    public interface Resolver {
        @Nullable Resolved resolve(Track track) throws IOException;
    }

    /** Where to stream from; {@code previewMs} is the clip length when only a preview is available, else 0. */
    public record Resolved(String url, long previewMs) {}

    private static final Logger LOGGER = LoggerFactory.getLogger("Sigma/Music");
    private static final int LINE_BUFFER_MS = 200;

    private final Resolver resolver;
    private final DoubleSupplier masterVolume;
    private final MusicEffects effects;
    private final ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "Sigma music");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger generation = new AtomicInteger();
    private volatile @Nullable Session session;
    private volatile boolean wantPlay;
    private volatile float volume = MusicPlayer.DEFAULT_VOLUME;

    public StreamingBackend(Resolver resolver, DoubleSupplier masterVolume, MusicEffects effects) {
        this.resolver = resolver;
        this.masterVolume = masterVolume;
        this.effects = effects;
    }

    @Override
    public boolean producesSound() {
        return true;
    }

    @Override
    public void load(Track track) {
        this.wantPlay = false;
        Session previous = this.session;
        if (previous != null) previous.cancel();
        Session next = new Session(this.generation.incrementAndGet(), track);
        this.session = next;
        this.threads.execute(next::download);
        this.threads.execute(next::play);
    }

    @Override
    public void play() {
        this.wantPlay = true;
        Session current = this.session;
        if (current != null) current.wake();
    }

    @Override
    public void pause() {
        this.wantPlay = false;
        Session current = this.session;
        if (current != null) current.stopLine();
    }

    @Override
    public boolean isPlaying() {
        return this.wantPlay;
    }

    @Override
    public boolean isBuffering() {
        Session current = this.session;
        return current != null && current.buffering;
    }

    @Override
    public long positionMs() {
        Session current = this.session;
        return current == null ? 0L : current.positionMs();
    }

    @Override
    public void seek(long positionMs) {
        Session current = this.session;
        if (current != null) current.seek(positionMs);
    }

    @Override
    public void setVolume(float volume) {
        this.volume = volume;
    }

    @Override
    public long durationMs() {
        Session current = this.session;
        return current == null || current.previewMs <= 0L ? -1L : current.previewMs;
    }

    @Override
    public boolean hasEnded() {
        Session current = this.session;
        return current != null && current.ended;
    }

    @Override
    public @Nullable String failure() {
        Session current = this.session;
        return current == null ? null : current.failure;
    }

    @Override
    public @Nullable Spectrum spectrum(long positionMs) {
        Session current = this.session;
        AudioAnalyzer analyzer = current == null ? null : current.analyzer;
        return analyzer == null ? null : analyzer.at(positionMs);
    }

    @Override
    public boolean isPreview() {
        Session current = this.session;
        return current != null && current.previewMs > 0L;
    }

    @Override
    public void close() {
        this.wantPlay = false;
        Session current = this.session;
        this.session = null;
        if (current != null) current.cancel();
        this.threads.shutdownNow();
    }

    /** One loaded track: its download, its decode loop and its audio line. */
    private final class Session {
        final int id;
        final Track track;
        final GrowingBuffer buffer = new GrowingBuffer();
        final Object lock = new Object();
        volatile boolean cancelled, ended, buffering = true;
        volatile @Nullable String failure;
        volatile long previewMs;
        volatile long pendingSeekMs = -1L;
        volatile @Nullable SourceDataLine line;
        volatile @Nullable HttpURLConnection connection;
        // Position = baseMs + frames the line has played since lineFrameBase.
        volatile long baseMs;
        volatile long lineFrameBase;
        volatile float sampleRate = 44_100F;
        // Created with the line, once the stream's rate and channels are known.
        volatile @Nullable AudioAnalyzer analyzer;
        private @Nullable Muffle muffle;
        private float[] work = new float[0];

        Session(int id, Track track) {
            this.id = id;
            this.track = track;
        }

        private boolean current() {
            return !this.cancelled && StreamingBackend.this.generation.get() == this.id;
        }

        void download() {
            try {
                Resolved resolved = StreamingBackend.this.resolver.resolve(this.track);
                if (!current()) return;
                if (resolved == null) {
                    fail("unavailable");
                    return;
                }
                this.previewMs = resolved.previewMs();
                HttpURLConnection conn = (HttpURLConnection)URI.create(resolved.url()).toURL().openConnection();
                this.connection = conn;
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(15_000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                conn.setRequestProperty("Referer", "https://music.163.com/");
                int status = conn.getResponseCode();
                if (status / 100 != 2) {
                    fail("http " + status);
                    return;
                }
                try (InputStream in = conn.getInputStream()) {
                    byte[] chunk = new byte[64 * 1024];
                    int read;
                    while (current() && (read = in.read(chunk)) >= 0) this.buffer.append(chunk, 0, read);
                }
                this.buffer.finish();
            } catch (IOException | RuntimeException e) {
                if (!current()) return;
                // A download that dies mid-song still lets what arrived play out.
                if (this.buffer.size() > 64 * 1024) this.buffer.finish();
                else fail("network");
            } finally {
                HttpURLConnection conn = this.connection;
                if (conn != null) conn.disconnect();
            }
        }

        private void fail(String reason) {
            if (this.failure != null) return;
            this.failure = reason;
            this.buffering = false;
            this.buffer.close();
            LOGGER.info("Sigma music: cannot play '{}' ({})", this.track.title(), reason);
        }

        void play() {
            Bitstream bitstream = null;
            try {
                bitstream = new Bitstream(this.buffer.stream());
                Decoder decoder = new Decoder();
                int badFrames = 0;
                // Where the decoder is in the track. A double: MP3 frames last a fraction over 26 ms, and rounding
                // each one down drifted the clock by almost a second a few minutes in (lyrics ran early after a seek).
                double positionMs = 0.0;
                while (current()) {
                    long seekTo = this.pendingSeekMs;
                    if (seekTo >= 0L) {
                        this.pendingSeekMs = -1L;
                        this.buffering = true;
                        SourceDataLine line = this.line;
                        if (line != null) line.flush();
                        bitstream.close();
                        bitstream = new Bitstream(this.buffer.stream());
                        // JLayer's Decoder binds its Layer III decoder to the first Bitstream it sees: a new
                        // stream needs a new decoder, or it keeps reading the closed one.
                        decoder = new Decoder();
                        badFrames = 0;
                        positionMs = skip(bitstream, seekTo);
                        this.baseMs = Math.round(positionMs);
                        AudioAnalyzer analyzer = this.analyzer;
                        if (analyzer != null) analyzer.reset();
                        this.lineFrameBase = line == null ? 0L : line.getLongFramePosition();
                        this.ended = false;
                        continue;
                    }
                    if (this.ended || this.failure != null) {
                        // Finished (or failed): nothing more to decode until a seek or a new load.
                        waitForChange();
                        continue;
                    }
                    if (!StreamingBackend.this.wantPlay) {
                        stopLine();
                        waitForChange();
                        continue;
                    }
                    Header header = bitstream.readFrame();
                    if (header == null) {
                        // End of stream: let the line play out what it holds, then report the end.
                        drainWhilePlaying();
                        this.buffering = false;
                        if (current() && this.pendingSeekMs < 0L) {
                            if (this.line == null && this.failure == null) fail("unsupported");
                            else this.ended = true;
                        }
                        waitForChange();
                        continue;
                    }
                    try {
                        SampleBuffer samples = (SampleBuffer)decoder.decodeFrame(header, bitstream);
                        write(samples, decoder, positionMs);
                        badFrames = 0;
                    } catch (DecoderException | ArrayIndexOutOfBoundsException e) {
                        if (++badFrames > 32) {
                            fail("undecodable");
                            return;
                        }
                    }
                    positionMs += header.ms_per_frame();
                    bitstream.closeFrame();
                    if (this.previewMs > 0L && positionMs >= this.previewMs + 1_000L) {
                        // Preview clips occasionally run on past their advertised end; stop at it.
                        drainWhilePlaying();
                        this.ended = true;
                    }
                }
            } catch (BitstreamException | LineUnavailableException | RuntimeException e) {
                if (current()) {
                    LOGGER.warn("Sigma music: playback of '{}' stopped", this.track.title(), e);
                    fail(e instanceof LineUnavailableException ? "no audio device" : "undecodable");
                }
            } finally {
                if (bitstream != null) {
                    try {
                        bitstream.close();
                    } catch (BitstreamException ignored) {
                        // Closing.
                    }
                }
                SourceDataLine line = this.line;
                this.line = null;
                if (line != null) line.close();
            }
        }

        /**
         * Lets the line play out what it holds. Polls rather than {@code drain()}, which never returns if the line
         * is paused meanwhile.
         */
        private void drainWhilePlaying() {
            SourceDataLine line = this.line;
            if (line == null) return;
            while (current() && this.pendingSeekMs < 0L && line.available() < line.getBufferSize()) {
                if (!StreamingBackend.this.wantPlay) {
                    waitForChange();
                    continue;
                }
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        /** Reads (without decoding) frames until {@code targetMs}; returns the position actually reached. */
        private double skip(Bitstream bitstream, long targetMs) throws BitstreamException {
            double position = 0.0;
            while (current() && position + 13.0 < targetMs) {
                Header header = bitstream.readFrame();
                if (header == null) break;
                position += header.ms_per_frame();
                bitstream.closeFrame();
            }
            return position;
        }

        /** Writes one decoded frame, heard {@code startMs} into the track: filtered, analysed, then turned to volume. */
        private void write(SampleBuffer samples, Decoder decoder, double startMs) throws LineUnavailableException {
            SourceDataLine line = this.line;
            int channels = decoder.getOutputChannels();
            if (line == null) {
                int rate = decoder.getOutputFrequency();
                AudioFormat format = new AudioFormat(rate, 16, channels, true, false);
                line = AudioSystem.getSourceDataLine(format);
                line.open(format, Math.max(4096, rate * channels * 2 * LINE_BUFFER_MS / 1000));
                this.sampleRate = rate;
                this.lineFrameBase = line.getLongFramePosition();
                // Starts where the game is: a track begun under water begins muffled.
                this.muffle = new Muffle(channels, StreamingBackend.this.effects.muffleTarget());
                this.analyzer = new AudioAnalyzer(rate);
                this.line = line;
                LOGGER.info("Sigma music: playing '{}' ({} Hz, {} ch{})", this.track.title(), rate, channels, this.previewMs > 0L ? ", preview" : "");
            }
            if (!line.isRunning() && StreamingBackend.this.wantPlay) line.start();
            this.buffering = false;
            short[] pcm = samples.getBuffer();
            int count = samples.getBufferLength();
            if (this.work.length < count) this.work = new float[count];
            float[] work = this.work;
            for (int i = 0; i < count; i++) work[i] = pcm[i] / 32768F;
            Muffle muffle = this.muffle;
            if (muffle != null) muffle.process(work, count, channels, this.sampleRate, StreamingBackend.this.effects.muffleTarget());
            AudioAnalyzer analyzer = this.analyzer;
            if (analyzer != null) analyzer.feed(work, count, channels, startMs);
            float gain = (float)Math.max(0.0, Math.min(1.0, StreamingBackend.this.volume * StreamingBackend.this.masterVolume.getAsDouble()));
            byte[] bytes = new byte[count * 2];
            for (int i = 0; i < count; i++) {
                int value = Math.max(-32768, Math.min(32767, Math.round(work[i] * 32767F * gain)));
                bytes[i * 2] = (byte)value;
                bytes[i * 2 + 1] = (byte)(value >> 8);
            }
            line.write(bytes, 0, bytes.length);
        }

        long positionMs() {
            SourceDataLine line = this.line;
            long pending = this.pendingSeekMs;
            if (pending >= 0L) return pending;
            if (line == null) return this.baseMs;
            return this.baseMs + (long)((line.getLongFramePosition() - this.lineFrameBase) * 1000L / this.sampleRate);
        }

        void seek(long positionMs) {
            this.pendingSeekMs = Math.max(0L, positionMs);
            this.ended = false;
            SourceDataLine line = this.line;
            // Unblocks a write stuck on a full, paused line so the decode loop sees the seek.
            if (line != null) line.flush();
            wake();
        }

        void stopLine() {
            SourceDataLine line = this.line;
            if (line != null && line.isRunning()) line.stop();
        }

        void wake() {
            synchronized (this.lock) {
                this.lock.notifyAll();
            }
            SourceDataLine line = this.line;
            if (line != null && StreamingBackend.this.wantPlay && !line.isRunning()) line.start();
        }

        private void waitForChange() {
            synchronized (this.lock) {
                try {
                    this.lock.wait(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    this.cancelled = true;
                }
            }
        }

        void cancel() {
            this.cancelled = true;
            this.buffer.close();
            HttpURLConnection conn = this.connection;
            if (conn != null) conn.disconnect();
            SourceDataLine line = this.line;
            if (line != null) {
                line.stop();
                line.flush();
            }
            wake();
        }
    }
}
