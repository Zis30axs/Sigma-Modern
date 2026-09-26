package com.mentalfrostbyte.jello.selfcheck.engine.grim;

import ac.grim.grimac.api.plugin.GrimPlugin;
import ac.grim.grimac.platform.api.entity.GrimEntity;
import ac.grim.grimac.platform.api.scheduler.AsyncScheduler;
import ac.grim.grimac.platform.api.scheduler.EntityScheduler;
import ac.grim.grimac.platform.api.scheduler.GlobalRegionScheduler;
import ac.grim.grimac.platform.api.scheduler.PlatformScheduler;
import ac.grim.grimac.platform.api.scheduler.RegionScheduler;
import ac.grim.grimac.platform.api.scheduler.TaskHandle;
import ac.grim.grimac.platform.api.world.PlatformWorld;
import ac.grim.grimac.utils.math.Location;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Grim's schedulers for one connection. A server has a main thread that ticks 20 times a second and a pool for async
 * work; here one thread plays the main thread (the global, entity and region schedulers all run on it, as they do on
 * a non-Folia server) and a small pool does the async work. Every task is guarded: a periodic task that throws once
 * would otherwise never run again, and Grim's tick would stop without a word.
 */
final class SigmaScheduler implements PlatformScheduler {

    private static final long TICK_MILLIS = 50L;

    private final ScheduledExecutorService main;
    private final ScheduledExecutorService async;
    private final Consumer<Throwable> failures;
    private final Set<Task> tasks = ConcurrentHashMap.newKeySet();

    SigmaScheduler(final String name, final Consumer<Throwable> failures) {
        this.main = Executors.newSingleThreadScheduledExecutor(threads(name + " tick"));
        this.async = Executors.newScheduledThreadPool(2, threads(name + " async"));
        this.failures = failures;
    }

    private static ThreadFactory threads(final String name) {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + " #" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Stops every thread; called when the connection ends. */
    void shutdown() {
        this.main.shutdownNow();
        this.async.shutdownNow();
        this.tasks.clear();
    }

    private Runnable guarded(final Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError error) {
                    throw error;
                }
                this.failures.accept(t);
            }
        };
    }

    private Task submit(final ScheduledExecutorService executor, final boolean sync, final Runnable task, final long delay,
                        final long period, final TimeUnit unit) {
        Task handle = new Task(sync);
        Runnable body = this.guarded(task);
        if (executor.isShutdown()) {
            handle.cancel();
            return handle;
        }
        handle.future = period > 0
                ? executor.scheduleAtFixedRate(body, Math.max(0, delay), period, unit)
                : executor.schedule(() -> {
                    body.run();
                    this.tasks.remove(handle);
                }, Math.max(0, delay), unit);
        this.tasks.add(handle);
        return handle;
    }

    private void cancelAll(final boolean sync) {
        for (Task task : this.tasks) {
            if (task.sync == sync) {
                task.cancel();
            }
        }
    }

    @Override
    public AsyncScheduler getAsyncScheduler() {
        return this.asyncScheduler;
    }

    @Override
    public GlobalRegionScheduler getGlobalRegionScheduler() {
        return this.globalScheduler;
    }

    @Override
    public EntityScheduler getEntityScheduler() {
        return this.entityScheduler;
    }

    @Override
    public RegionScheduler getRegionScheduler() {
        return this.regionScheduler;
    }

    private final class Task implements TaskHandle {
        private final boolean sync;
        private volatile ScheduledFuture<?> future;
        private volatile boolean cancelled;

        private Task(final boolean sync) {
            this.sync = sync;
        }

        @Override
        public boolean isSync() {
            return this.sync;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public void cancel() {
            this.cancelled = true;
            ScheduledFuture<?> current = this.future;
            if (current != null) {
                current.cancel(false);
            }
            SigmaScheduler.this.tasks.remove(this);
        }
    }

    private final AsyncScheduler asyncScheduler = new AsyncScheduler() {
        @Override
        public TaskHandle runNow(final GrimPlugin plugin, final Runnable task) {
            return SigmaScheduler.this.submit(SigmaScheduler.this.async, false, task, 0, 0, TimeUnit.MILLISECONDS);
        }

        @Override
        public TaskHandle runDelayed(final GrimPlugin plugin, final Runnable task, final long delay, final TimeUnit unit) {
            return SigmaScheduler.this.submit(SigmaScheduler.this.async, false, task, delay, 0, unit);
        }

        @Override
        public TaskHandle runAtFixedRate(final GrimPlugin plugin, final Runnable task, final long delay, final long period, final TimeUnit unit) {
            return SigmaScheduler.this.submit(SigmaScheduler.this.async, false, task, delay, Math.max(1, period), unit);
        }

        @Override
        public TaskHandle runAtFixedRate(final GrimPlugin plugin, final Runnable task, final long initialDelayTicks, final long periodTicks) {
            return SigmaScheduler.this.submit(SigmaScheduler.this.async, false, task, initialDelayTicks * TICK_MILLIS,
                    Math.max(1, periodTicks) * TICK_MILLIS, TimeUnit.MILLISECONDS);
        }

        @Override
        public void cancel(final GrimPlugin plugin) {
            SigmaScheduler.this.cancelAll(false);
        }
    };

    private final GlobalRegionScheduler globalScheduler = new GlobalRegionScheduler() {
        @Override
        public void execute(final GrimPlugin plugin, final Runnable task) {
            this.run(plugin, task);
        }

        @Override
        public TaskHandle run(final GrimPlugin plugin, final Runnable task) {
            return SigmaScheduler.this.submit(SigmaScheduler.this.main, true, task, 0, 0, TimeUnit.MILLISECONDS);
        }

        @Override
        public TaskHandle runDelayed(final GrimPlugin plugin, final Runnable task, final long delayTicks) {
            return SigmaScheduler.this.submit(SigmaScheduler.this.main, true, task, delayTicks * TICK_MILLIS, 0, TimeUnit.MILLISECONDS);
        }

        @Override
        public TaskHandle runAtFixedRate(final GrimPlugin plugin, final Runnable task, final long initialDelayTicks, final long periodTicks) {
            return SigmaScheduler.this.submit(SigmaScheduler.this.main, true, task, initialDelayTicks * TICK_MILLIS,
                    Math.max(1, periodTicks) * TICK_MILLIS, TimeUnit.MILLISECONDS);
        }

        @Override
        public void cancel(final GrimPlugin plugin) {
            SigmaScheduler.this.cancelAll(true);
        }
    };

    private final EntityScheduler entityScheduler = new EntityScheduler() {
        @Override
        public void execute(final GrimEntity entity, final GrimPlugin plugin, final Runnable run, final Runnable retired, final long delay) {
            SigmaScheduler.this.globalScheduler.runDelayed(plugin, run, delay);
        }

        @Override
        public TaskHandle run(final GrimEntity entity, final GrimPlugin plugin, final Runnable task, final Runnable retired) {
            return SigmaScheduler.this.globalScheduler.run(plugin, task);
        }

        @Override
        public TaskHandle runDelayed(final GrimEntity entity, final GrimPlugin plugin, final Runnable task, final Runnable retired, final long delayTicks) {
            return SigmaScheduler.this.globalScheduler.runDelayed(plugin, task, delayTicks);
        }

        @Override
        public TaskHandle runAtFixedRate(final GrimEntity entity, final GrimPlugin plugin, final Runnable task, final Runnable retired,
                                         final long initialDelayTicks, final long periodTicks) {
            return SigmaScheduler.this.globalScheduler.runAtFixedRate(plugin, task, initialDelayTicks, periodTicks);
        }
    };

    private final RegionScheduler regionScheduler = new RegionScheduler() {
        @Override
        public void execute(final GrimPlugin plugin, final PlatformWorld world, final int chunkX, final int chunkZ, final Runnable run) {
            SigmaScheduler.this.globalScheduler.run(plugin, run);
        }

        @Override
        public void execute(final GrimPlugin plugin, final Location location, final Runnable run) {
            SigmaScheduler.this.globalScheduler.run(plugin, run);
        }

        @Override
        public TaskHandle run(final GrimPlugin plugin, final PlatformWorld world, final int chunkX, final int chunkZ, final Runnable task) {
            return SigmaScheduler.this.globalScheduler.run(plugin, task);
        }

        @Override
        public TaskHandle run(final GrimPlugin plugin, final Location location, final Runnable task) {
            return SigmaScheduler.this.globalScheduler.run(plugin, task);
        }

        @Override
        public TaskHandle runDelayed(final GrimPlugin plugin, final PlatformWorld world, final int chunkX, final int chunkZ, final Runnable task,
                                     final long delayTicks) {
            return SigmaScheduler.this.globalScheduler.runDelayed(plugin, task, delayTicks);
        }

        @Override
        public TaskHandle runDelayed(final GrimPlugin plugin, final Location location, final Runnable task, final long delayTicks) {
            return SigmaScheduler.this.globalScheduler.runDelayed(plugin, task, delayTicks);
        }

        @Override
        public TaskHandle runAtFixedRate(final GrimPlugin plugin, final PlatformWorld world, final int chunkX, final int chunkZ,
                                         final Runnable task, final long initialDelayTicks, final long periodTicks) {
            return SigmaScheduler.this.globalScheduler.runAtFixedRate(plugin, task, initialDelayTicks, periodTicks);
        }

        @Override
        public TaskHandle runAtFixedRate(final GrimPlugin plugin, final Location location, final Runnable task,
                                         final long initialDelayTicks, final long periodTicks) {
            return SigmaScheduler.this.globalScheduler.runAtFixedRate(plugin, task, initialDelayTicks, periodTicks);
        }
    };
}
