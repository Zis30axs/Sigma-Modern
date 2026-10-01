package com.mentalfrostbyte.jello.gui.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The cues are MP3s bundled with the client; they have to decode to something a sound device can play. */
class LegacySoundsTest {

    @Test
    void everyCueDecodesToSixteenBitAudioOfAPlausibleLength() throws Exception {
        for (LegacySounds.Cue cue : LegacySounds.Cue.values()) {
            LegacySounds.Pcm pcm = LegacySounds.decode("/assets/minecraft/sigma/sounds/" + cue.name().toLowerCase() + ".mp3");
            assertEquals(16, pcm.format().getSampleSizeInBits(), cue.toString());
            assertTrue(pcm.format().getSampleRate() >= 22050, cue + " sample rate");
            double seconds = pcm.data().length / (double) (pcm.format().getFrameSize() * pcm.format().getFrameRate());
            assertTrue(seconds > 0.05 && seconds < 3.0, cue + " lasts " + seconds + " s");
        }
    }
}
