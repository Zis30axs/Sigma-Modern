package net.minecraft.world.ticks;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ReferenceAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.caffeinemc.mods.lithium.common.world.scheduler.OrderedTickQueue;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * MODIFIED for porting: this whole class is lithium's world.tick_scheduler LevelChunkTicksMixin. Vanilla keeps the pending
 * ticks of a chunk in a {@link java.util.PriorityQueue} plus a hash set of (pos, type) pairs; lithium replaces both with a
 * tree map of per-(time, priority) bucket queues and an {@link IntOpenHashSet} of ticks packed into a single int.
 * <p>
 * Upstream cannot delete the two vanilla collections, so it nulls them in an @Inject at the RETURN of both constructors and
 * overwrites every method that used them. In a source-level port the fields are simply gone - they were private and only
 * ever touched by the methods lithium overwrites - together with {@code SUB_TICK_ORDERING} and {@code scheduleUnchecked}.
 */
public class LevelChunkTicks<T> implements TickContainerAccess<T>, SerializableTickContainer<T> {
    private static volatile Reference2IntOpenHashMap<Object> TYPE_2_INDEX;

    static {
        TYPE_2_INDEX = new Reference2IntOpenHashMap<>();
        TYPE_2_INDEX.defaultReturnValue(-1);
    }

    private final Long2ReferenceAVLTreeMap<OrderedTickQueue<T>> tickQueuesByTimeAndPriority = new Long2ReferenceAVLTreeMap<>();
    private @Nullable OrderedTickQueue<T> nextTickQueue;
    private final IntOpenHashSet allTicks = new IntOpenHashSet();
    private @Nullable List<SavedTick<T>> pendingTicks;
    private @Nullable BiConsumer<LevelChunkTicks<T>, ScheduledTick<T>> onTickAdded;

    public LevelChunkTicks() {
    }

    public LevelChunkTicks(final List<SavedTick<T>> pendingTicks) {
        this.pendingTicks = pendingTicks;

        for (SavedTick<T> pendingTick : pendingTicks) {
            this.allTicks.add(tickToInt(pendingTick.pos(), pendingTick.type()));
        }
    }

    private static int tickToInt(final BlockPos pos, final Object type) {
        // The Y coordinate is 12 bits (BlockPos.toLong), X and Z are 4 bits each (this scheduler covers a single chunk), so
        // 20 bits are used for the position and 12 bits remain for the type: up to 4096 distinct tickable blocks/fluids
        // (not block states).
        int typeIndex = TYPE_2_INDEX.getInt(type);
        if (typeIndex == -1) {
            typeIndex = fixMissingType2Index(type);
        }

        int ret = (pos.getX() & 0xF) << 16 | (pos.getY() & 0xFFF) << 4 | pos.getZ() & 0xF;
        return ret | typeIndex << 20;
    }

    // This method must be synchronized, otherwise type -> int assignments could be overwritten and therefore change.
    // Clone + volatile store ensures only fully initialized maps are published, and all threads share one mapping.
    private static synchronized int fixMissingType2Index(final Object type) {
        int typeIndex = TYPE_2_INDEX.getInt(type);
        if (typeIndex == -1) {
            Reference2IntOpenHashMap<Object> clonedType2Index = TYPE_2_INDEX.clone();
            clonedType2Index.put(type, typeIndex = clonedType2Index.size());
            TYPE_2_INDEX = clonedType2Index;
            if (typeIndex >= 4096) {
                throw new IllegalStateException(
                    "Lithium Tick Scheduler assumes at most 4096 different block types that receive scheduled ticks exist!"
                );
            }
        }

        return typeIndex;
    }

    // Computes a timestamped key including the tick's priority. Keys sort in ascending order of execution.
    // 60 time bits, 4 priority bits.
    private static long getBucketKey(final long time, final TickPriority priority) {
        // priority.ordinal() is used instead of priority.index because it is never negative
        return time << 4L | priority.ordinal() & 15;
    }

    private void updateNextTickQueue(final boolean checkEmpty) {
        if (checkEmpty && this.nextTickQueue != null && this.nextTickQueue.isEmpty()) {
            OrderedTickQueue<T> removed = this.tickQueuesByTimeAndPriority.remove(this.tickQueuesByTimeAndPriority.firstLongKey());
            if (removed != this.nextTickQueue) {
                throw new IllegalStateException("Next tick queue doesn't have the lowest key!");
            }
        }

        if (this.tickQueuesByTimeAndPriority.isEmpty()) {
            this.nextTickQueue = null;
            return;
        }

        this.nextTickQueue = this.tickQueuesByTimeAndPriority.get(this.tickQueuesByTimeAndPriority.firstLongKey());
    }

    private void queueTick(final ScheduledTick<T> tick) {
        OrderedTickQueue<T> tickQueue = this.tickQueuesByTimeAndPriority
            .computeIfAbsent(getBucketKey(tick.triggerTick(), tick.priority()), key -> new OrderedTickQueue<>());
        if (tickQueue.isEmpty()) {
            this.updateNextTickQueue(false);
        }

        tickQueue.offer(tick);
        if (this.onTickAdded != null) {
            this.onTickAdded.accept(this, tick);
        }
    }

    public void setOnTickAdded(final @Nullable BiConsumer<LevelChunkTicks<T>, ScheduledTick<T>> onTickAdded) {
        this.onTickAdded = onTickAdded;
    }

    public @Nullable ScheduledTick<T> peek() {
        return this.nextTickQueue == null ? null : this.nextTickQueue.peek();
    }

    public @Nullable ScheduledTick<T> poll() {
        ScheduledTick<T> tick = this.nextTickQueue.poll();
        if (tick != null) {
            if (this.nextTickQueue.isEmpty()) {
                this.updateNextTickQueue(true);
            }

            this.allTicks.remove(tickToInt(tick.pos(), tick.type()));
            return tick;
        }

        return null;
    }

    @Override
    public void schedule(final ScheduledTick<T> tick) {
        int intTick = tickToInt(tick.pos(), tick.type());
        if (this.allTicks.add(intTick)) {
            this.queueTick(tick);
        }
    }

    @Override
    public boolean hasScheduledTick(final BlockPos pos, final T type) {
        return this.allTicks.contains(tickToInt(pos, type));
    }

    public void removeIf(final Predicate<ScheduledTick<T>> test) {
        for (ObjectIterator<OrderedTickQueue<T>> tickQueueIterator = this.tickQueuesByTimeAndPriority.values().iterator(); tickQueueIterator.hasNext();) {
            OrderedTickQueue<T> nextTickQueue = tickQueueIterator.next();
            nextTickQueue.sort();
            boolean removed = false;

            for (int i = 0; i < nextTickQueue.size(); i++) {
                ScheduledTick<T> nextTick = nextTickQueue.getTickAtIndex(i);
                if (test.test(nextTick)) {
                    nextTickQueue.setTickAtIndex(i, null);
                    this.allTicks.remove(tickToInt(nextTick.pos(), nextTick.type()));
                    removed = true;
                }
            }

            if (removed) {
                nextTickQueue.removeNullsAndConsumed();
            }

            if (nextTickQueue.isEmpty()) {
                tickQueueIterator.remove();
            }
        }

        this.updateNextTickQueue(false);
    }

    public Stream<ScheduledTick<T>> getAll() {
        return this.tickQueuesByTimeAndPriority.values().stream().flatMap(Collection::stream);
    }

    @Override
    public int count() {
        return this.allTicks.size();
    }

    @Override
    public List<SavedTick<T>> pack(final long currentTick) {
        List<SavedTick<T>> ticks = new ArrayList<>(this.count());
        if (this.pendingTicks != null) {
            ticks.addAll(this.pendingTicks);
        }

        for (OrderedTickQueue<T> nextTickQueue : this.tickQueuesByTimeAndPriority.values()) {
            for (ScheduledTick<T> tick : nextTickQueue) {
                ticks.add(tick.toSavedTick(currentTick));
            }
        }

        return ticks;
    }

    public void unpack(final long currentTick) {
        if (this.pendingTicks != null) {
            int subTickBase = -this.pendingTicks.size();

            for (SavedTick<T> pendingTick : this.pendingTicks) {
                this.queueTick(pendingTick.unpack(currentTick, subTickBase++));
            }
        }

        this.pendingTicks = null;
    }
}
