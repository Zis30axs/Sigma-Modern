package com.mentalfrostbyte.jello.anticheat.check;

import com.mentalfrostbyte.jello.anticheat.observe.Sample;
import com.mentalfrostbyte.jello.anticheat.observe.TrackedPlayer;
import com.mojang.logging.LogUtils;
import java.util.List;
import org.slf4j.Logger;

/**
 * Runs every enabled check on each new sample and turns their verdicts into violation levels and alerts.
 *
 * <p>A check only reports a flag or a clean stretch; keeping the level, deciding when it is high enough to
 * announce and rate-limiting the announcement all happen here, once, so every check behaves the same way.</p>
 */
public final class Detector implements Check.Verdicts {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The same player and check are announced at most this often. */
    private static final long ALERT_COOLDOWN_NANOS = 2_000_000_000L;

    private final List<Check> checks;
    private final AlertSink sink;

    private CheckSettings settings = CheckSettings.balanced();
    private long now;

    public Detector(final AlertSink sink) {
        this(List.of(new SpeedCheck(), new FlightCheck(), new GroundSpoofCheck(), new NoSlowCheck()), sink);
    }

    public Detector(final List<Check> checks, final AlertSink sink) {
        this.checks = List.copyOf(checks);
        this.sink = sink;
    }

    /** Where flags end up: the chat, the log, the suspect list. */
    public interface AlertSink {

        /** A check's level for a player reached the alert level (rate-limited per player and check). */
        void alert(TrackedPlayer player, String check, double level, String detail);

        /** A check's level for a player changed; kept up to date for anything that displays levels. */
        void levelChanged(TrackedPlayer player, String check, double level);
    }

    /** Feeds one sample through the checks. The sample must already be the newest in {@code player}. */
    public void ingest(final TrackedPlayer player, final Sample sample, final CheckSettings newSettings) {
        this.settings = newSettings;
        this.now = sample.nanos();
        for (Check check : this.checks) {
            if (!check.enabled(newSettings)) {
                continue;
            }

            try {
                check.onSample(player, sample, newSettings, this);
            } catch (final RuntimeException failure) {
                // One misbehaving check must not stop the others, or the packet handler that called us.
                LOGGER.warn("[Sigma/AntiCheat] {} failed on {}", check.name(), player.name(), failure);
            }
        }
    }

    @Override
    public void flag(final TrackedPlayer player, final String check, final double weight, final String detail) {
        ViolationBuffer buffer = player.buffer(check);
        double level = buffer.flag(weight);
        this.sink.levelChanged(player, check, level);
        if (level < this.settings.alertLevel()) {
            return;
        }

        long[] lastAlert = player.state("alertAt:" + check, () -> new long[] {Long.MIN_VALUE});
        if (lastAlert[0] != Long.MIN_VALUE && this.now - lastAlert[0] < ALERT_COOLDOWN_NANOS) {
            return;
        }

        lastAlert[0] = this.now;
        this.sink.alert(player, check, level, detail);
    }

    @Override
    public void reward(final TrackedPlayer player, final String check, final double decay) {
        ViolationBuffer buffer = player.buffer(check);
        if (buffer.level() <= 0.0) {
            return;
        }

        buffer.reward(decay);
        this.sink.levelChanged(player, check, buffer.level());
    }
}
