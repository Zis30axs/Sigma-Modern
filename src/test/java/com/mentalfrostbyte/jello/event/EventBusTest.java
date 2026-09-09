package com.mentalfrostbyte.jello.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.mentalfrostbyte.jello.module.Module;
import com.mentalfrostbyte.jello.module.ModuleCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Event types are private to this file so these tests cannot interfere with any other test class's
 * subscriptions - {@link EventBus}'s subscriber map is static and keyed by exact event class, with no
 * reset between tests.
 */
class EventBusTest {

    /**
     * Stress test (F2): before this fix, {@code unregister()} read the current listener array and later
     * called {@code entry.setValue(...)}, so a concurrent {@code register()}/{@code unregister()} pair on
     * the same event type could lose whichever update lost the race. This does not prove the absence of a
     * race in general - it exercises it under load and asserts nothing was lost across many iterations.
     */
    @Test
    void unregisterCannotOverwriteConcurrentRegistration() throws Exception {
        final int permanentCount = 50;
        final int threads = 8;
        final int iterationsPerThread = 500;

        List<Probe> permanent = new ArrayList<>();
        for (int i = 0; i < permanentCount; i++) {
            Probe probe = new Probe();
            permanent.add(probe);
            EventBus.register(probe);
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    await(go);
                    for (int i = 0; i < iterationsPerThread; i++) {
                        Probe transientSubscriber = new Probe();
                        EventBus.register(transientSubscriber);
                        EventBus.unregister(transientSubscriber);
                    }
                }));
            }

            ready.await();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        EventBus.call(new StressPing());
        for (Probe probe : permanent) {
            assertEquals(1, probe.hits, "a permanent subscription was lost to a concurrent register/unregister race");
            EventBus.unregister(probe);
        }
    }

    /**
     * F4: dispatch iterates a fixed snapshot array taken at the start of {@link EventBus#call}, so a
     * subscription belonging to a module disabled after that snapshot was taken could otherwise still run.
     * This reproduces that ordering directly: a higher-priority listener disables the module in the middle
     * of the very call whose snapshot already contains the module's subscription, and the module must still
     * not be invoked.
     */
    @Test
    void disabledModuleIsSkippedEvenFromAStaleSnapshot() {
        ProbeModule module = new ProbeModule("StaleSnapshotProbe");
        module.setEnabled(true);

        AtomicBoolean triggered = new AtomicBoolean();
        Object disabler = new Object() {
            @EventTarget(EventPriority.HIGHEST)
            public void onFirst(final RacePing event) {
                if (triggered.compareAndSet(false, true)) {
                    module.setEnabled(false);
                }
            }
        };

        EventBus.register(disabler);
        try {
            EventBus.call(new RacePing());
        } finally {
            EventBus.unregister(disabler);
        }

        assertFalse(module.isEnabled());
        assertEquals(0, module.hits,
                "the module's subscription was still in the dispatch snapshot when it disabled itself mid-call");
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class StressPing extends Event {
    }

    private static final class RacePing extends Event {
    }

    private static final class Probe {
        volatile int hits;

        @EventTarget
        void onStressPing(final StressPing event) {
            this.hits++;
        }
    }

    private static final class ProbeModule extends Module {
        int hits;

        ProbeModule(final String name) {
            super(ModuleCategory.MISC, name, "test");
        }

        @EventTarget
        void onRacePing(final RacePing event) {
            this.hits++;
        }
    }
}
