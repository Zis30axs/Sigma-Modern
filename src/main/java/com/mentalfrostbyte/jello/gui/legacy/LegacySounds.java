package com.mentalfrostbyte.jello.gui.legacy;

import com.mentalfrostbyte.Client;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/**
 * The short cues the old client played: Jello's two-tone switch sounds as a module goes on and off.
 *
 * <p>They are MP3s, decoded once with JLayer (the decoder the music player already uses) into PCM and played through
 * Java Sound on a background thread, so a toggle never waits on the sound device. With no device - a headless
 * machine - the cue is skipped and the reason logged once.</p>
 */
public final class LegacySounds {
    public enum Cue {
        ACTIVATE("activate"),
        DEACTIVATE("deactivate"),
        POP("pop");

        private final String resource;

        Cue(final String name) {
            this.resource = "/assets/minecraft/sigma/sounds/" + name + ".mp3";
        }
    }

    record Pcm(AudioFormat format, byte[] data) {}

    private static final Map<Cue, Pcm> DECODED = new EnumMap<>(Cue.class);
    private static final ExecutorService PLAYER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Sigma sound cues");
        thread.setDaemon(true);
        return thread;
    });
    private static boolean warned;

    private LegacySounds() {}

    /** Plays {@code cue} without blocking the caller. */
    public static void play(final Cue cue) {
        PLAYER.execute(() -> {
            try {
                Pcm pcm = DECODED.get(cue);
                if (pcm == null) {
                    pcm = decode(cue.resource);
                    DECODED.put(cue, pcm);
                }

                Clip clip = AudioSystem.getClip();
                clip.addLineListener(event -> {
                    if (event.getType() == LineEvent.Type.STOP) {
                        clip.close();
                    }
                });
                clip.open(pcm.format(), pcm.data(), 0, pcm.data().length);
                clip.start();
            } catch (Exception | LinkageError failure) {
                if (!warned) {
                    warned = true;
                    Client.logger.warn("Sigma: could not play the {} cue: {}", cue, failure.toString());
                }
            }
        });
    }

    /** An MP3 on the classpath as 16-bit little-endian PCM, with the format it was encoded in. */
    static Pcm decode(final String resource) throws IOException, BitstreamException, DecoderException {
        try (InputStream in = LegacySounds.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing " + resource);
            }

            Bitstream bitstream = new Bitstream(in);
            Decoder decoder = new Decoder();
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            Header header;
            while ((header = bitstream.readFrame()) != null) {
                SampleBuffer samples = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                short[] buffer = samples.getBuffer();
                for (int i = 0; i < samples.getBufferLength(); i++) {
                    pcm.write(buffer[i] & 0xFF);
                    pcm.write(buffer[i] >> 8 & 0xFF);
                }
                bitstream.closeFrame();
            }

            AudioFormat format = new AudioFormat(decoder.getOutputFrequency(), 16, decoder.getOutputChannels(), true, false);
            return new Pcm(format, pcm.toByteArray());
        }
    }
}
