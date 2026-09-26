package com.mentalfrostbyte.jello.anticheat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.anticheat.check.Check;
import com.mentalfrostbyte.jello.anticheat.check.CheckSettings;
import com.mentalfrostbyte.jello.anticheat.check.Detector;
import com.mentalfrostbyte.jello.anticheat.check.ViolationBuffer;
import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DetectorTest {

    private static final class Recording implements Detector.AlertSink {
        final List<String> alerts = new ArrayList<>();
        final List<Double> levels = new ArrayList<>();

        @Override
        public void alert(final TrackedPlayer player, final String check, final double level, final String detail) {
            this.alerts.add(check + ":" + detail);
        }

        @Override
        public void levelChanged(final TrackedPlayer player, final String check, final double level) {
            this.levels.add(level);
        }
    }

    /** Flags with a fixed weight on every sample. */
    private record Flagger(String name, double weight) implements Check {
        @Override
        public boolean enabled(final CheckSettings settings) {
            return true;
        }

        @Override
        public void onSample(final TrackedPlayer player, final Sample sample, final CheckSettings settings, final Verdicts verdicts) {
            verdicts.flag(player, this.name, this.weight, "detail");
        }
    }

    private static Sample sample(final long nanos) {
        return new Sample(nanos, 0, 0, 0, true, 0, 0.1, 0.42, 0.08, false, 0.6, true, false, true, false);
    }

    private static TrackedPlayer player() {
        return new TrackedPlayer(1, UUID.randomUUID(), "Steve", 0L);
    }

    @Test
    void aFlagBelowTheAlertLevelIsRecordedButNotAnnounced() {
        Recording sink = new Recording();
        Detector detector = new Detector(List.of(new Flagger("A", 1.0)), sink);
        TrackedPlayer player = player();
        detector.ingest(player, sample(0), CheckSettings.balanced());
        assertTrue(sink.alerts.isEmpty());
        assertEquals(1.0, player.buffer("A").level(), 1.0E-9);
        assertEquals(List.of(1.0), sink.levels);
    }

    @Test
    void crossingTheAlertLevelAnnounces() {
        Recording sink = new Recording();
        Detector detector = new Detector(List.of(new Flagger("A", 2.0)), sink);
        TrackedPlayer player = player();
        detector.ingest(player, sample(0), CheckSettings.balanced());
        assertTrue(sink.alerts.isEmpty(), "level 2 is under the default alert level of 3");
        detector.ingest(player, sample(100_000_000L), CheckSettings.balanced());
        assertEquals(1, sink.alerts.size());
    }

    @Test
    void theSamePlayerAndCheckAreAnnouncedAtMostEveryTwoSeconds() {
        Recording sink = new Recording();
        Detector detector = new Detector(List.of(new Flagger("A", 5.0)), sink);
        TrackedPlayer player = player();
        for (int i = 0; i < 50; i++) {
            detector.ingest(player, sample(i * 100_000_000L), CheckSettings.balanced());
        }

        // Five seconds of samples every 100 ms: one alert at 0 s, then every 2 s.
        assertEquals(3, sink.alerts.size(), sink.alerts.toString());
    }

    @Test
    void differentChecksAreRateLimitedIndependently() {
        Recording sink = new Recording();
        Detector detector = new Detector(List.of(new Flagger("A", 5.0), new Flagger("B", 5.0)), sink);
        detector.ingest(player(), sample(0), CheckSettings.balanced());
        assertEquals(2, sink.alerts.size());
    }

    @Test
    void aCheckThatThrowsDoesNotStopTheOthers() {
        Recording sink = new Recording();
        Check broken = new Check() {
            @Override
            public String name() {
                return "Broken";
            }

            @Override
            public boolean enabled(final CheckSettings settings) {
                return true;
            }

            @Override
            public void onSample(final TrackedPlayer player, final Sample sample, final CheckSettings settings, final Verdicts verdicts) {
                throw new IllegalStateException("boom");
            }
        };
        Detector detector = new Detector(List.of(broken, new Flagger("A", 5.0)), sink);
        detector.ingest(player(), sample(0), CheckSettings.balanced());
        assertEquals(1, sink.alerts.size());
    }

    @Test
    void aDisabledCheckIsNotRun() {
        Recording sink = new Recording();
        Detector detector = new Detector(List.of(new Check() {
            @Override
            public String name() {
                return "Off";
            }

            @Override
            public boolean enabled(final CheckSettings settings) {
                return false;
            }

            @Override
            public void onSample(final TrackedPlayer player, final Sample sample, final CheckSettings settings, final Verdicts verdicts) {
                verdicts.flag(player, "Off", 9.0, "should not run");
            }
        }), sink);
        detector.ingest(player(), sample(0), CheckSettings.balanced());
        assertTrue(sink.alerts.isEmpty());
    }

    @Test
    void rewardsDrainTheLevelAndNeverGoBelowZero() {
        ViolationBuffer buffer = new ViolationBuffer();
        buffer.flag(1.0);
        buffer.reward(0.4);
        assertEquals(0.6, buffer.level(), 1.0E-9);
        buffer.reward(5.0);
        assertEquals(0.0, buffer.level(), 1.0E-9);
    }

    @Test
    void aCleanStretchTakesTheLevelDownAndTellsTheSink() {
        Recording sink = new Recording();
        Detector detector = new Detector(List.of(new Flagger("A", 1.0)), sink);
        TrackedPlayer player = player();
        detector.ingest(player, sample(0), CheckSettings.balanced());
        detector.reward(player, "A", 0.5);
        assertEquals(0.5, player.buffer("A").level(), 1.0E-9);
        assertEquals(0.5, sink.levels.get(sink.levels.size() - 1), 1.0E-9);
    }
}
