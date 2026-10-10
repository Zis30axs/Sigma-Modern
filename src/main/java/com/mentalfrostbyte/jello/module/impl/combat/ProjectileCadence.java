package com.mentalfrostbyte.jello.module.impl.combat;

/** A monotonic, non-catching-up burst clock. Pausing requires a fresh acquisition delay. */
final class ProjectileCadence {
    private boolean acquired;
    private long next;
    private int shots;

    boolean ready(long now, long delayMillis) {
        if (!acquired) {
            acquired = true;
            next = now + Math.max(0, delayMillis);
        }
        return now >= next;
    }

    void fired(long now, int amount, double perSecond, long delayMillis) {
        long interval = (long) Math.ceil(1000 / Math.max(1, Math.min(20, perSecond)));
        if (++shots >= Math.max(1, amount)) {
            shots = 0;
            interval = Math.max(interval, Math.max(0, delayMillis));
        }
        next = now + interval;
    }

    void reset() {
        acquired = false;
        shots = 0;
        next = 0;
    }
}
