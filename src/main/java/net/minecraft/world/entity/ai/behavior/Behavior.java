package net.minecraft.world.entity.ai.behavior;

import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain; // MODIFIED for porting: lithium ai.task.memory_changes BehaviorMixin
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;

public abstract class Behavior<E extends LivingEntity> implements BehaviorControl<E> {
    public static final int DEFAULT_DURATION = 60;
    // MODIFIED for porting: lithium ai.task.memory_changes BehaviorMixin replaces this map with a fastutil one
    // (@Mutable @Shadow @Final), so it lost its `final`.
    protected Map<MemoryModuleType<?>, MemoryStatus> entryCondition;
    // MODIFIED for porting: lithium ai.task.memory_changes BehaviorMixin @Unique fields - cache of the last
    // hasRequiredMemories result, valid as long as the brain's memory modification counter is unchanged.
    private long cachedMemoryModCount = -1;
    private boolean cachedHasRequiredMemoryState;
    private Behavior.Status status = Behavior.Status.STOPPED;
    private long endTimestamp;
    private final int minDuration;
    private final int maxDuration;

    public Behavior(final Map<MemoryModuleType<?>, MemoryStatus> entryCondition) {
        this(entryCondition, 60);
    }

    public Behavior(final Map<MemoryModuleType<?>, MemoryStatus> entryCondition, final int timeOutDuration) {
        this(entryCondition, timeOutDuration, timeOutDuration);
    }

    public Behavior(final Map<MemoryModuleType<?>, MemoryStatus> entryCondition, final int minDuration, final int maxDuration) {
        this.minDuration = minDuration;
        this.maxDuration = maxDuration;
        // MODIFIED for porting: lithium ai.task.memory_changes BehaviorMixin#init (<init> RETURN) - a fastutil map allows the
        // fast entry iteration used by hasRequiredMemories below.
        this.entryCondition = new it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<>(entryCondition);
    }

    @Override
    public Behavior.Status getStatus() {
        return this.status;
    }

    @Override
    public Set<MemoryModuleType<?>> getRequiredMemories() {
        return this.entryCondition.keySet();
    }

    @Override
    public final boolean tryStart(final ServerLevel level, final E body, final long timestamp) {
        if (this.hasRequiredMemories(body) && this.checkExtraStartConditions(level, body)) {
            this.status = Behavior.Status.RUNNING;
            int duration = this.minDuration + level.getRandom().nextInt(this.maxDuration + 1 - this.minDuration);
            this.endTimestamp = timestamp + duration;
            this.start(level, body, timestamp);
            return true;
        } else {
            return false;
        }
    }

    protected void start(final ServerLevel level, final E body, final long timestamp) {
    }

    @Override
    public final void tickOrStop(final ServerLevel level, final E body, final long timestamp) {
        if (!this.timedOut(timestamp) && this.canStillUse(level, body, timestamp)) {
            this.tick(level, body, timestamp);
        } else {
            this.doStop(level, body, timestamp);
        }
    }

    protected void tick(final ServerLevel level, final E body, final long timestamp) {
    }

    @Override
    public final void doStop(final ServerLevel level, final E body, final long timestamp) {
        this.status = Behavior.Status.STOPPED;
        this.stop(level, body, timestamp);
    }

    protected void stop(final ServerLevel level, final E body, final long timestamp) {
    }

    protected boolean canStillUse(final ServerLevel level, final E body, final long timestamp) {
        return false;
    }

    protected boolean timedOut(final long timestamp) {
        return timestamp > this.endTimestamp;
    }

    protected boolean checkExtraStartConditions(final ServerLevel level, final E body) {
        return true;
    }

    @Override
    public String debugString() {
        return this.getClass().getSimpleName();
    }

    protected boolean hasRequiredMemories(final E body) {
        // MODIFIED for porting: lithium ai.task.memory_changes BehaviorMixin#hasRequiredMemories (@Overwrite) - reuse the
        // cached result while the brain's memories did not change their presence.
        Brain<?> brain = body.getBrain();
        long modCount = ((net.caffeinemc.mods.lithium.common.ai.brain.memories.MemoryModificationCounter)brain).lithium$getMemoryValueModCount();
        if (this.cachedMemoryModCount == modCount) {
            return this.cachedHasRequiredMemoryState;
        }

        this.cachedMemoryModCount = modCount;
        it.unimi.dsi.fastutil.objects.ObjectIterator<it.unimi.dsi.fastutil.objects.Reference2ObjectMap.Entry<MemoryModuleType<?>, MemoryStatus>> fastIterator =
            ((it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<MemoryModuleType<?>, MemoryStatus>)this.entryCondition).reference2ObjectEntrySet().fastIterator();

        while (fastIterator.hasNext()) {
            it.unimi.dsi.fastutil.objects.Reference2ObjectMap.Entry<MemoryModuleType<?>, MemoryStatus> entry = fastIterator.next();
            if (!brain.checkMemory(entry.getKey(), entry.getValue())) {
                return this.cachedHasRequiredMemoryState = false;
            }
        }

        return this.cachedHasRequiredMemoryState = true;
    }

    public enum Status {
        STOPPED,
        RUNNING;
    }
}