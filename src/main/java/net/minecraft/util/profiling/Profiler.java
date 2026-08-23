package net.minecraft.util.profiling;

import com.mojang.jtracy.TracyClient;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

public final class Profiler {
    private static final ThreadLocal<TracyZoneFiller> TRACY_FILLER = ThreadLocal.withInitial(TracyZoneFiller::new);
    private static final ThreadLocal<@Nullable ProfilerFiller> ACTIVE = new ThreadLocal<>();
    private static final AtomicInteger ACTIVE_COUNT = new AtomicInteger();
    // MODIFIED for porting: lithium profiler ProfilerMixin. The ThreadLocal lookup in get() is about 2% of server runtime;
    // typically at most two threads use the profiler (in single player: server thread and client thread), so the active
    // filler of those two threads is cached in plain static fields.
    private static final java.util.concurrent.atomic.AtomicReference<Thread> LITHIUM_THREAD_1 = new java.util.concurrent.atomic.AtomicReference<>();
    private static @Nullable ProfilerFiller lithium$profiler1;
    private static final java.util.concurrent.atomic.AtomicReference<Thread> LITHIUM_THREAD_2 = new java.util.concurrent.atomic.AtomicReference<>();
    private static @Nullable ProfilerFiller lithium$profiler2;
    private static final AtomicInteger LITHIUM_ACTIVE_PROFILER_COUNT = new AtomicInteger(0);

    private Profiler() {
    }

    public static Profiler.Scope use(final ProfilerFiller filler) {
        startUsing(filler);
        // MODIFIED for porting: lithium profiler ProfilerMixin#placeProfilerInStaticFields (RETURN of use)
        Thread thread = Thread.currentThread();
        ProfilerFiller activeProfiler = ACTIVE.get();
        if (LITHIUM_THREAD_1.compareAndSet(null, thread)) {
            lithium$profiler1 = activeProfiler;
        } else if (LITHIUM_THREAD_2.compareAndSet(null, thread)) {
            lithium$profiler2 = activeProfiler;
        }

        if (activeProfiler != null && !(activeProfiler instanceof InactiveProfiler)) {
            LITHIUM_ACTIVE_PROFILER_COUNT.incrementAndGet();
        }

        return Profiler::stopUsing;
    }

    private static void startUsing(final ProfilerFiller filler) {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("Profiler is already active");
        }

        ProfilerFiller active = decorateFiller(filler);
        ACTIVE.set(active);
        ACTIVE_COUNT.incrementAndGet();
        active.startTick();
    }

    private static void stopUsing() {
        ProfilerFiller active = ACTIVE.get();
        if (active == null) {
            throw new IllegalStateException("Profiler was not active");
        }

        ACTIVE.remove();
        ACTIVE_COUNT.decrementAndGet();
        active.endTick();
        // MODIFIED for porting: lithium profiler ProfilerMixin#removeProfilerFromStaticFields (RETURN of stopUsing)
        Thread thread = Thread.currentThread();
        ProfilerFiller cachedProfiler = null;
        if (LITHIUM_THREAD_1.get() == thread) {
            cachedProfiler = lithium$profiler1;
            lithium$profiler1 = null;
            LITHIUM_THREAD_1.set(null);
        } else if (LITHIUM_THREAD_2.get() == thread) {
            cachedProfiler = lithium$profiler2;
            lithium$profiler2 = null;
            LITHIUM_THREAD_2.set(null);
        }

        if (cachedProfiler != null && !(cachedProfiler instanceof InactiveProfiler)) {
            LITHIUM_ACTIVE_PROFILER_COUNT.decrementAndGet();
        }
    }

    private static ProfilerFiller decorateFiller(final ProfilerFiller filler) {
        return ProfilerFiller.combine(getDefaultFiller(), filler);
    }

    /**
     * MODIFIED for porting: lithium profiler ProfilerMixin#lithium$getProfiler wraps this method. When no non-inactive
     * profiler is in use at all it returns InactiveProfiler directly, otherwise it tries the two cached thread slots before
     * falling back to the vanilla lookup.
     */
    public static ProfilerFiller get() {
        if (LITHIUM_ACTIVE_PROFILER_COUNT.get() == 0) {
            return InactiveProfiler.INSTANCE;
        }

        Thread thread = Thread.currentThread();
        if (LITHIUM_THREAD_1.get() == thread) {
            return lithium$profiler1;
        } else if (LITHIUM_THREAD_2.get() == thread) {
            return lithium$profiler2;
        }

        return ACTIVE_COUNT.get() == 0 ? getDefaultFiller() : Objects.requireNonNullElseGet(ACTIVE.get(), Profiler::getDefaultFiller);
    }

    private static ProfilerFiller getDefaultFiller() {
        return TracyClient.isAvailable() ? TRACY_FILLER.get() : InactiveProfiler.INSTANCE;
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}