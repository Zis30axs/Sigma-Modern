package com.mentalfrostbyte.jello.module.impl.combat;

import java.util.Objects;
import java.util.random.RandomGenerator;

/** Monotonic-millisecond click timing, independent of input bindings and the Minecraft client. */
final class AutoClickScheduler {
    record AutoSettings(double minCps, double maxCps, double zitterCps, double jitterMs,
                        long driftIntervalMs, double randomPercent) {
        AutoSettings {
            minCps = bounded(minCps, 8, 1, 1000);
            maxCps = bounded(maxCps, 12, 1, 1000);
            if (minCps > maxCps) {
                double swap = minCps;
                minCps = maxCps;
                maxCps = swap;
            }
            zitterCps = bounded(zitterCps, 0, 0, 5);
            jitterMs = bounded(jitterMs, 0, 0, 30);
            driftIntervalMs = Math.clamp(driftIntervalMs, 50, 2000);
            randomPercent = bounded(randomPercent, 0, 0, 100);
        }

        private static double bounded(double value, double fallback, double min, double max) {
            return Math.clamp(Double.isFinite(value) ? value : fallback, min, max);
        }
    }

    private final RandomGenerator random;
    private AutoSettings autoSettings;
    private long nextAuto;
    private long nextDrift;
    private double driftCps;
    private int helperCredits;
    private long helperInterval;
    private long nextHelper;
    private long helperExpires;
    private boolean hasTime;
    private long lastTime;

    AutoClickScheduler(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Native repeats and older queued clicks are not new physical presses and must not rearm timing. */
    boolean consumedNativeClick(long nowMs, boolean physicalPress, boolean helper, AutoSettings settings,
                                int extraClicks, long helperIntervalMs) {
        if (!physicalPress) return false;
        if (helper) triggerHelper(nowMs, extraClicks, helperIntervalMs);
        else beginAuto(nowMs, settings);
        return true;
    }

    /** Call when the native click is actually consumed, so its first supplement is spaced from it. */
    void beginAuto(long nowMs, AutoSettings settings) {
        Objects.requireNonNull(settings, "settings");
        observeTime(nowMs);
        helperCredits = 0;
        if (!settings.equals(autoSettings)) {
            autoSettings = settings;
            driftCps = 0;
            nextDrift = nowMs + settings.driftIntervalMs();
        }
        nextAuto = nowMs + sampleInterval(nowMs);
    }

    /** Late frames produce one click and reschedule from the current time, never a catch-up burst. */
    boolean pollAuto(long nowMs, AutoSettings settings) {
        Objects.requireNonNull(settings, "settings");
        observeTime(nowMs);
        if (!settings.equals(autoSettings)) {
            reset();
            return false;
        }
        if (nowMs - nextAuto < 0) return false;
        nextAuto = nowMs + sampleInterval(nowMs);
        return true;
    }

    /** A new physical click replaces, rather than accumulates, the previous click's supplements. */
    void triggerHelper(long nowMs, int extraClicks, long intervalMs) {
        observeTime(nowMs);
        autoSettings = null;
        driftCps = 0;
        helperCredits = Math.clamp(extraClicks, 1, 5);
        helperInterval = Math.clamp(intervalMs, 5, 250);
        nextHelper = nowMs + helperInterval;
        // Brief taps can finish their bounded burst; a paused or stalled client cannot revive it later.
        helperExpires = nowMs + Math.max(250L, helperCredits * helperInterval * 2L);
    }

    boolean pollHelper(long nowMs) {
        observeTime(nowMs);
        if (helperCredits == 0) return false;
        if (nowMs - helperExpires >= 0) {
            helperCredits = 0;
            return false;
        }
        if (nowMs - nextHelper < 0) return false;
        helperCredits--;
        nextHelper = nowMs + helperInterval;
        return true;
    }

    void reset() {
        autoSettings = null;
        helperCredits = 0;
        driftCps = 0;
        hasTime = false;
    }

    private long sampleInterval(long nowMs) {
        AutoSettings settings = autoSettings;
        if (nowMs - nextDrift >= 0) {
            if (settings.zitterCps() > 0) {
                // A correlated bounded walk changes pace only at the configured MS cadence.
                driftCps = driftCps * 0.7 + signedRandom() * settings.zitterCps() * 0.3;
            }
            nextDrift = nowMs + settings.driftIntervalMs();
        }
        double midpoint = (settings.minCps() + settings.maxCps()) * 0.5;
        double cps = midpoint + driftCps;
        if (settings.randomPercent() > 0) {
            cps += signedRandom() * (settings.maxCps() - settings.minCps()) * 0.5
                    * settings.randomPercent() / 100.0;
        }
        cps = Math.clamp(cps, settings.minCps(), settings.maxCps());
        double interval = 1000.0 / cps;
        if (settings.jitterMs() > 0) interval += signedRandom() * settings.jitterMs();
        long shortest = (long) Math.ceil(1000.0 / settings.maxCps());
        // Narrow/equal CPS bounds may contain no whole millisecond. Prefer respecting max CPS.
        long longest = Math.max(shortest, (long) Math.floor(1000.0 / settings.minCps()));
        return Math.clamp(Math.round(interval), shortest, longest);
    }

    private double signedRandom() {
        return random.nextDouble() * 2.0 - 1.0;
    }

    private void observeTime(long nowMs) {
        if (hasTime && nowMs - lastTime < 0) reset();
        lastTime = nowMs;
        hasTime = true;
    }
}
