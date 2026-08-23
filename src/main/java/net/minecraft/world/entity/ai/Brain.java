package net.minecraft.world.entity.ai;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.ExpirableValue;
import net.minecraft.world.entity.ai.memory.MemoryMap;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemorySlot;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public class Brain<E extends LivingEntity> implements net.caffeinemc.mods.lithium.mixin.ai.useless_sensors.BrainAccessor<E>, // MODIFIED for porting: lithium ai.useless_sensors BrainAccessor
    net.caffeinemc.mods.lithium.common.ai.brain.memories.BrainExtended, // MODIFIED for porting: lithium client_tick.entity.unused_brain BrainMixin
    net.caffeinemc.mods.lithium.common.ai.brain.memories.MemoryModificationCounter { // MODIFIED for porting: lithium ai.task.memory_changes BrainMixin
    private static final int SCHEDULE_UPDATE_DELAY = 20;
    /**
     * MODIFIED for porting: lithium ai.task.memory_changes BrainMixin. Tracks changes to the presence of memory values,
     * possibly including false positives (equals() is never called). Changes to the time to live are not tracked. Behaviors
     * use this counter to cache the result of their required-memory check.
     */
    private long memoryModCount = 1;

    // MODIFIED for porting: lithium ai.task.memory_changes BrainMixin
    @Override
    public long lithium$getMemoryValueModCount() {
        return this.memoryModCount;
    }

    // MODIFIED for porting: lithium ai.task.memory_changes BrainMixin
    @Override
    public void lithium$onMemoryModified() {
        this.memoryModCount++;
    }
    // MODIFIED for porting: lithium collections.brain BrainMixin swaps three of the brain's collections for fastutil ones. Upstream replaces the
    // maps in an @Inject at the RETURN of the 5-argument constructor; setting the field initializers reaches the same
    // state (and additionally covers the no-argument constructor, which behaves identically with either map type -
    // MemoryModuleType/SensorType/Activity are registry singletons, so reference and equals comparison agree).
    // MODIFIED for porting: lithium client_tick.entity.unused_brain BrainMixin needs to replace this map
    // (@Mutable @Shadow @Final), so it lost its `final`.
    private Map<MemoryModuleType<?>, MemorySlot<?>> memories = new it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<>();

    /**
     * MODIFIED for porting: was lithium's client_tick.entity.unused_brain BrainMixin. Makes every memory lookup return the
     * shared dummy memory slot instead of null, so the dummy brain used on the client behaves as if all memory types were
     * registered. Writes to that slot are ignored, see {@link MemorySlot#set(Object, long)}.
     */
    @Override
    public void lithium$pretendAllMemoryTypesRegistered() {
        if (this.memories instanceof it.unimi.dsi.fastutil.objects.AbstractReference2ObjectFunction<?, ?> memoryCollection) {
            ((it.unimi.dsi.fastutil.objects.AbstractReference2ObjectFunction<MemoryModuleType<?>, MemorySlot<?>>)memoryCollection)
                .defaultReturnValue(net.caffeinemc.mods.lithium.common.client.SharedFields.DUMMY_SLOT);
        } else {
            it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<MemoryModuleType<?>, MemorySlot<?>> memoryCollection =
                new it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<>(this.memories);
            memoryCollection.defaultReturnValue(net.caffeinemc.mods.lithium.common.client.SharedFields.DUMMY_SLOT);
            this.memories = memoryCollection;
        }
    }
    private final Map<SensorType<? extends Sensor<? super E>>, Sensor<? super E>> sensors = new it.unimi.dsi.fastutil.objects.Reference2ReferenceLinkedOpenHashMap<>();

    // MODIFIED for porting: was lithium's ai.useless_sensors BrainAccessor accessor Mixin
    @Override
    public Map<SensorType<? extends Sensor<? super E>>, Sensor<? super E>> getSensors() {
        return this.sensors;
    }
    private final Map<Integer, Map<Activity, Set<BehaviorControl<? super E>>>> availableBehaviorsByPriority = Maps.newTreeMap();
    private @Nullable EnvironmentAttribute<Activity> schedule;
    // MODIFIED for porting: lithium collections.brain BrainMixin (see above)
    private final Map<Activity, Set<Pair<MemoryModuleType<?>, MemoryStatus>>> activityRequirements = new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>();
    private final Map<Activity, Set<MemoryModuleType<?>>> activityMemoriesToEraseWhenStopped = Maps.newHashMap();
    private Set<Activity> coreActivities = Sets.newHashSet();
    private final Set<Activity> activeActivities = Sets.newHashSet();
    /**
     * MODIFIED for porting: lithium ai.task.launch BrainMixin. Cached collections that avoid walking the whole
     * priority/activity/behavior structure on every brain tick: {@code lithium$possibleTasks} holds the behaviors of all
     * currently active activities, {@code lithium$runningTasks} is a masked view over all behaviors where only the currently
     * running ones are visible. Both are invalidated lazily.
     */
    private java.util.@org.jspecify.annotations.Nullable ArrayList<BehaviorControl<? super E>> lithium$possibleTasks;

    private net.caffeinemc.mods.lithium.common.util.collections.@org.jspecify.annotations.Nullable MaskedList<BehaviorControl<? super E>> lithium$runningTasks;

    // MODIFIED for porting: lithium ai.task.launch BrainMixin#onTasksChanged
    private void lithium$onTasksChanged() {
        this.lithium$runningTasks = null;
        this.lithium$onPossibleActivitiesChanged();
    }

    // MODIFIED for porting: lithium ai.task.launch BrainMixin#onPossibleActivitiesChanged
    private void lithium$onPossibleActivitiesChanged() {
        this.lithium$possibleTasks = null;
    }

    // MODIFIED for porting: lithium ai.task.launch BrainMixin#initPossibleTasks
    private void lithium$initPossibleTasks() {
        this.lithium$possibleTasks = new java.util.ArrayList<>();

        for (Map<Activity, Set<BehaviorControl<? super E>>> map : this.availableBehaviorsByPriority.values()) {
            for (Entry<Activity, Set<BehaviorControl<? super E>>> entry : map.entrySet()) {
                Activity activity = entry.getKey();
                if (this.activeActivities.contains(activity)) {
                    for (BehaviorControl<? super E> task : entry.getValue()) {
                        this.lithium$possibleTasks.add(task);
                    }
                }
            }
        }
    }

    // MODIFIED for porting: lithium ai.task.launch BrainMixin#getPossibleTasks
    private java.util.ArrayList<BehaviorControl<? super E>> lithium$getPossibleTasks() {
        if (this.lithium$possibleTasks == null) {
            this.lithium$initPossibleTasks();
        }

        return this.lithium$possibleTasks;
    }

    // MODIFIED for porting: lithium ai.task.launch BrainMixin#getCurrentlyRunningTasks
    private net.caffeinemc.mods.lithium.common.util.collections.MaskedList<BehaviorControl<? super E>> lithium$getCurrentlyRunningTasks() {
        if (this.lithium$runningTasks == null) {
            this.lithium$initCurrentlyRunningTasks();
        }

        return this.lithium$runningTasks;
    }

    // MODIFIED for porting: lithium ai.task.launch BrainMixin#initCurrentlyRunningTasks
    private void lithium$initCurrentlyRunningTasks() {
        net.caffeinemc.mods.lithium.common.util.collections.MaskedList<BehaviorControl<? super E>> list = new net.caffeinemc.mods.lithium.common.util.collections.MaskedList<>(new ObjectArrayList<>(), false);

        for (Map<Activity, Set<BehaviorControl<? super E>>> map : this.availableBehaviorsByPriority.values()) {
            for (Set<BehaviorControl<? super E>> set : map.values()) {
                for (BehaviorControl<? super E> task : set) {
                    list.addOrSet(task, task.getStatus() == Behavior.Status.RUNNING);
                }
            }
        }

        this.lithium$runningTasks = list;
    }
    private Activity defaultActivity = Activity.IDLE;
    private long lastScheduleUpdate = -9999L;

    public static <E extends LivingEntity> Brain.Provider<E> provider(final Collection<? extends SensorType<? extends Sensor<? super E>>> sensorTypes) {
        return new Brain.Provider<>(ImmutableList.of(), sensorTypes, var0 -> List.of());
    }

    public static <E extends LivingEntity> Brain.Provider<E> provider(
        final Collection<? extends SensorType<? extends Sensor<? super E>>> sensorTypes, final Brain.ActivitySupplier<E> activities
    ) {
        return new Brain.Provider<>(ImmutableList.of(), sensorTypes, activities);
    }

    @Deprecated
    public static <E extends LivingEntity> Brain.Provider<E> provider(
        final Collection<? extends MemoryModuleType<?>> memoryTypes,
        final Collection<? extends SensorType<? extends Sensor<? super E>>> sensorTypes,
        final Brain.ActivitySupplier<E> activities
    ) {
        return new Brain.Provider<>(memoryTypes, sensorTypes, activities);
    }

    @VisibleForTesting
    protected Brain(
        final Collection<? extends MemoryModuleType<?>> memoryTypes,
        final Collection<? extends SensorType<? extends Sensor<? super E>>> sensorTypes,
        final List<ActivityData<E>> activities,
        final MemoryMap memories,
        final RandomSource randomSource
    ) {
        for (MemoryModuleType<?> memoryType : memoryTypes) {
            this.registerMemory(memoryType);
        }

        for (SensorType<? extends Sensor<? super E>> sensorType : sensorTypes) {
            Sensor<? super E> newSensor = (Sensor<? super E>)sensorType.create();
            newSensor.randomlyDelayStart(randomSource);
            this.sensors.put(sensorType, newSensor);

            for (MemoryModuleType<?> type : newSensor.requires()) {
                this.registerMemory(type);
            }
        }

        for (ActivityData<E> activity : activities) {
            this.addActivity(activity.activityType(), activity.behaviorPriorityPairs(), activity.conditions(), activity.memoriesToEraseWhenStopped());
        }

        for (MemoryMap.Value<?> memory : memories) {
            this.setMemoryInternal(memory);
        }

        this.setCoreActivities(ImmutableSet.of(Activity.CORE));
        this.useDefaultActivity();
        // MODIFIED for porting: lithium ai.task.launch BrainMixin#reinitializeBrainCollections (<init> RETURN)
        this.lithium$onTasksChanged();
    }

    private void registerMemory(final MemoryModuleType<?> memoryType) {
        this.memories.putIfAbsent(memoryType, MemorySlot.create());
        // MODIFIED for porting: lithium ai.task.memory_changes BrainMixin#increaseMemoryModCount (RETURN)
        this.lithium$onMemoryModified();
    }

    public Brain() {
        this.setCoreActivities(ImmutableSet.of(Activity.CORE));
        this.useDefaultActivity();
    }

    public Brain.Packed pack() {
        final MemoryMap.Builder builder = new MemoryMap.Builder();
        this.forEach(new Brain.Visitor() {
            @Override
            public <U> void acceptEmpty(final MemoryModuleType<U> type) {
            }

            @Override
            public <U> void accept(final MemoryModuleType<U> type, final U value, final long timeToLive) {
                if (type.canSerialize()) {
                    builder.add(type, ExpirableValue.of(value, timeToLive));
                }
            }

            @Override
            public <U> void accept(final MemoryModuleType<U> type, final U value) {
                if (type.canSerialize()) {
                    builder.add(type, ExpirableValue.of(value));
                }
            }
        });
        return new Brain.Packed(builder.build());
    }

    private <T> @Nullable MemorySlot<T> getMemorySlotIfPresent(final MemoryModuleType<T> memoryType) {
        return (MemorySlot<T>)this.memories.get(memoryType);
    }

    private <T> MemorySlot<T> getMemorySlot(final MemoryModuleType<T> memoryType) {
        MemorySlot<T> result = this.getMemorySlotIfPresent(memoryType);
        if (result == null) {
            throw new IllegalStateException("Unregistered memory fetched: " + memoryType);
        } else {
            return result;
        }
    }

    public boolean hasMemoryValue(final MemoryModuleType<?> type) {
        return this.checkMemory(type, MemoryStatus.VALUE_PRESENT);
    }

    public void clearMemories() {
        this.memories.values().forEach(MemorySlot::clear);
        // MODIFIED for porting: lithium ai.task.memory_changes BrainMixin#increaseMemoryModCount (RETURN)
        this.lithium$onMemoryModified();
    }

    public <U> void eraseMemory(final MemoryModuleType<U> type) {
        MemorySlot<U> slot = this.getMemorySlotIfPresent(type);
        if (slot != null) {
            // MODIFIED for porting: lithium ai.task.memory_changes BrainMixin#increaseMemoryModCount (INVOKE MemorySlot#clear)
            if (slot.hasValue()) {
                this.lithium$onMemoryModified();
            }

            slot.clear();
        }
    }

    public <U> void setMemory(final MemoryModuleType<U> type, final @Nullable U value) {
        this.setMemoryInternal(type, value);
    }

    public <U> void setMemoryWithExpiry(final MemoryModuleType<U> type, final U value, final long timeToLive) {
        this.setMemoryInternal(type, value, timeToLive);
    }

    public <U> void setMemory(final MemoryModuleType<U> type, final Optional<? extends U> optionalValue) {
        this.setMemoryInternal(type, (U)optionalValue.orElse(null));
    }

    private <U> void setMemoryInternal(final MemoryMap.Value<U> value) {
        ExpirableValue<U> expirableValue = value.value();
        if (expirableValue.timeToLive().isPresent()) {
            this.setMemoryInternal(value.type(), expirableValue.value(), expirableValue.timeToLive().get());
        } else {
            this.setMemoryInternal(value.type(), expirableValue.value());
        }
    }

    private <U> void setMemoryInternal(final MemoryModuleType<U> type, U value, final long tileToLive) {
        MemorySlot<U> slot = this.getMemorySlotIfPresent(type);
        if (slot != null) {
            if (isEmptyCollection(value)) {
                value = null;
            }

            // MODIFIED for porting: lithium ai.task.memory_changes BrainMixin#clearTrackingChanges /
            // #setTrackingChanges (@WrapOperation around MemorySlot#clear / MemorySlot#set) - only changes of the
            // presence of a value have to bump the counter.
            if (value == null) {
                if (slot.hasValue()) {
                    this.lithium$onMemoryModified();
                }

                slot.clear();
            } else {
                if (!slot.hasValue()) {
                    this.lithium$onMemoryModified();
                }

                slot.set(value, tileToLive);
            }
        }
    }

    private <U> void setMemoryInternal(final MemoryModuleType<U> type, @Nullable U value) {
        MemorySlot<U> slot = this.getMemorySlotIfPresent(type);
        if (slot != null) {
            if (value != null && isEmptyCollection(value)) {
                value = null;
            }

            // MODIFIED for porting: lithium ai.task.memory_changes BrainMixin#clearTrackingChanges /
            // #setTrackingChanges (@WrapOperation around MemorySlot#clear / MemorySlot#set)
            if (value == null) {
                if (slot.hasValue()) {
                    this.lithium$onMemoryModified();
                }

                slot.clear();
            } else {
                if (!slot.hasValue()) {
                    this.lithium$onMemoryModified();
                }

                slot.set(value);
            }
        }
    }

    public <U> Optional<U> getMemory(final MemoryModuleType<U> type) {
        return Optional.ofNullable(this.getMemorySlot(type).value());
    }

    public <U> @Nullable Optional<U> getMemoryInternal(final MemoryModuleType<U> type) {
        MemorySlot<U> slot = this.getMemorySlotIfPresent(type);
        return slot == null ? null : Optional.ofNullable(slot.value());
    }

    public <U> long getTimeUntilExpiry(final MemoryModuleType<U> type) {
        return this.getMemorySlot(type).timeToLive();
    }

    public void forEach(final Brain.Visitor visitor) {
        this.memories.forEach((memoryModuleType, slot) -> callVisitor(visitor, (MemoryModuleType<?>)memoryModuleType, (MemorySlot<?>)slot));
    }

    private static <U> void callVisitor(final Brain.Visitor visitor, final MemoryModuleType<U> memoryModuleType, final MemorySlot<?> slot) {
        ((MemorySlot<U>)slot).visit(memoryModuleType, visitor);
    }

    public <U> boolean isMemoryValue(final MemoryModuleType<U> memoryType, final U value) {
        MemorySlot<U> slot = this.getMemorySlotIfPresent(memoryType);
        return slot != null && Objects.equals(value, slot.value());
    }

    public boolean checkMemory(final MemoryModuleType<?> type, final MemoryStatus status) {
        MemorySlot<?> slot = this.getMemorySlotIfPresent(type);
        return slot == null
            ? false
            : status == MemoryStatus.REGISTERED
                || status == MemoryStatus.VALUE_PRESENT && slot.hasValue()
                || status == MemoryStatus.VALUE_ABSENT && !slot.hasValue();
    }

    public void setSchedule(final EnvironmentAttribute<Activity> schedule) {
        this.schedule = schedule;
    }

    public void setCoreActivities(final Set<Activity> activities) {
        this.coreActivities = activities;
    }

    @Deprecated
    @VisibleForDebug
    public Set<Activity> getActiveActivities() {
        return this.activeActivities;
    }

    @Deprecated
    @VisibleForDebug
    public List<BehaviorControl<? super E>> getRunningBehaviors() {
        // MODIFIED for porting: lithium ai.task.launch BrainMixin#getRunningBehaviors (@Overwrite) - use the cached
        // masked collection instead of scanning every registered behavior
        return this.lithium$getCurrentlyRunningTasks();
    }

    public void useDefaultActivity() {
        this.setActiveActivity(this.defaultActivity);
    }

    public Optional<Activity> getActiveNonCoreActivity() {
        for (Activity activity : this.activeActivities) {
            if (!this.coreActivities.contains(activity)) {
                return Optional.of(activity);
            }
        }

        return Optional.empty();
    }

    public void setActiveActivityIfPossible(final Activity activity) {
        if (this.activityRequirementsAreMet(activity)) {
            this.setActiveActivity(activity);
        } else {
            this.useDefaultActivity();
        }
    }

    private void setActiveActivity(final Activity activity) {
        if (!this.isActive(activity)) {
            this.eraseMemoriesForOtherActivitesThan(activity);
            this.activeActivities.clear();
            this.activeActivities.addAll(this.coreActivities);
            this.activeActivities.add(activity);
            // MODIFIED for porting: lithium ai.task.launch BrainMixin#onPossibleActivitiesChanged (INVOKE Set#add, AFTER)
            this.lithium$onPossibleActivitiesChanged();
        }
    }

    private void eraseMemoriesForOtherActivitesThan(final Activity activity) {
        for (Activity oldActivity : this.activeActivities) {
            if (oldActivity != activity) {
                Set<MemoryModuleType<?>> memoryModuleTypes = this.activityMemoriesToEraseWhenStopped.get(oldActivity);
                if (memoryModuleTypes != null) {
                    for (MemoryModuleType<?> memoryModuleType : memoryModuleTypes) {
                        this.eraseMemory(memoryModuleType);
                    }
                }
            }
        }
    }

    public void updateActivityFromSchedule(final EnvironmentAttributeSystem environmentAttributes, final long gameTime, final Vec3 pos) {
        if (gameTime - this.lastScheduleUpdate > 20L) {
            this.lastScheduleUpdate = gameTime;
            Activity scheduledActivity = this.schedule != null ? environmentAttributes.getValue(this.schedule, pos) : Activity.IDLE;
            if (!this.activeActivities.contains(scheduledActivity)) {
                this.setActiveActivityIfPossible(scheduledActivity);
            }
        }
    }

    public void setActiveActivityToFirstValid(final List<Activity> activities) {
        for (Activity activity : activities) {
            if (this.activityRequirementsAreMet(activity)) {
                this.setActiveActivity(activity);
                break;
            }
        }
    }

    public void setDefaultActivity(final Activity activity) {
        this.defaultActivity = activity;
    }

    public void addActivity(
        final Activity activity,
        final ImmutableList<? extends Pair<Integer, ? extends BehaviorControl<? super E>>> behaviorPriorityPairs,
        final Set<Pair<MemoryModuleType<?>, MemoryStatus>> conditions,
        final Set<MemoryModuleType<?>> memoriesToEraseWhenStopped
    ) {
        this.activityRequirements.put(activity, conditions);
        if (!memoriesToEraseWhenStopped.isEmpty()) {
            this.activityMemoriesToEraseWhenStopped.put(activity, memoriesToEraseWhenStopped);
        }

        for (Pair<Integer, ? extends BehaviorControl<? super E>> pair : behaviorPriorityPairs) {
            BehaviorControl<? super E> behavior = (BehaviorControl<? super E>)pair.getSecond();
            // MODIFIED for porting: lithium ai.useless_behaviors BrainMixin#filterSentinels (@WrapOperation around the
            // iterator of the behavior list) - behaviors that are known to be useless are replaced by this sentinel and must
            // not be registered at all.
            if (behavior == net.caffeinemc.mods.lithium.common.ai.useless_behaviors.LithiumEmptyBehavior.EMPTY_BEHAVIOR_SENTINEL) {
                continue;
            }

            for (MemoryModuleType<?> requiredMemory : behavior.getRequiredMemories()) {
                this.registerMemory(requiredMemory);
            }

            this.availableBehaviorsByPriority
                .computeIfAbsent(pair.getFirst(), key -> Maps.newHashMap())
                .computeIfAbsent(activity, key -> Sets.newLinkedHashSet())
                .add(behavior);
        }

        // MODIFIED for porting: lithium ai.task.launch BrainMixin#reinitializeTasksSorted (RETURN)
        this.lithium$onTasksChanged();
    }

    @VisibleForTesting
    public void removeAllBehaviors() {
        this.availableBehaviorsByPriority.clear();
        // MODIFIED for porting: lithium ai.task.launch BrainMixin#reinitializeTasksSorted (RETURN)
        this.lithium$onTasksChanged();
    }

    public boolean isActive(final Activity activity) {
        return this.activeActivities.contains(activity);
    }

    public void tick(final ServerLevel level, final E body) {
        this.forgetOutdatedMemories();
        this.tickSensors(level, body);
        this.startEachNonRunningBehavior(level, body);
        this.tickEachRunningBehavior(level, body);
    }

    private void tickSensors(final ServerLevel level, final E body) {
        for (Sensor<? super E> sensor : this.sensors.values()) {
            sensor.tick(level, body);
        }
    }

    private void forgetOutdatedMemories() {
        // MODIFIED for porting: lithium ai.task.memory_changes BrainMixin#tickExpiringSlotsTrackingChanges (@ModifyArg
        // replacing the MemorySlot::tick consumer) - only slots that can expire need to be ticked, and an expiry has to bump
        // the modification counter.
        for (MemorySlot<?> slot : this.memories.values()) {
            // canExpire implies hasValue
            if (slot.canExpire()) {
                slot.tick();
                if (!slot.hasValue()) {
                    // Expired memory was deleted during tick
                    this.lithium$onMemoryModified();
                }
            }
        }
    }

    public void stopAll(final ServerLevel level, final E body) {
        long timestamp = body.level().getGameTime();

        for (BehaviorControl<? super E> behavior : this.getRunningBehaviors()) {
            // MODIFIED for porting: lithium ai.task.launch BrainMixin#removeStoppedTask (INVOKE doStop)
            if (this.lithium$runningTasks != null) {
                this.lithium$runningTasks.setVisible(behavior, false);
            }

            behavior.doStop(level, body, timestamp);
        }
    }

    private void startEachNonRunningBehavior(final ServerLevel level, final E body) {
        // MODIFIED for porting: lithium ai.task.launch BrainMixin#startEachNonRunningBehavior (@Overwrite) - iterate the
        // cached list of behaviors belonging to the currently active activities. The block after tryStart was upstream's
        // #addStartedTasks (@ModifyVariable after the tryStart call).
        long time = level.getGameTime();

        for (BehaviorControl<? super E> task : this.lithium$getPossibleTasks()) {
            if (task.getStatus() == Behavior.Status.STOPPED) {
                task.tryStart(level, body, time);
                if (this.lithium$runningTasks != null && task.getStatus() == Behavior.Status.RUNNING) {
                    this.lithium$runningTasks.setVisible(task, true);
                }
            }
        }
    }

    private void tickEachRunningBehavior(final ServerLevel level, final E body) {
        long timestamp = level.getGameTime();

        for (BehaviorControl<? super E> behavior : this.getRunningBehaviors()) {
            behavior.tickOrStop(level, body, timestamp);
            // MODIFIED for porting: lithium ai.task.launch BrainMixin#removeTaskIfStopped (INVOKE tickOrStop, AFTER)
            if (this.lithium$runningTasks != null && behavior.getStatus() != Behavior.Status.RUNNING) {
                this.lithium$runningTasks.setVisible(behavior, false);
            }
        }
    }

    private boolean activityRequirementsAreMet(final Activity activity) {
        if (!this.activityRequirements.containsKey(activity)) {
            return false;
        }

        for (Pair<MemoryModuleType<?>, MemoryStatus> memoryRequirement : this.activityRequirements.get(activity)) {
            MemoryModuleType<?> memoryType = memoryRequirement.getFirst();
            MemoryStatus memoryStatus = memoryRequirement.getSecond();
            if (!this.checkMemory(memoryType, memoryStatus)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isEmptyCollection(final Object object) {
        return object instanceof Collection<?> collection && collection.isEmpty();
    }

    public boolean isBrainDead() {
        return this.memories.isEmpty() && this.sensors.isEmpty() && this.availableBehaviorsByPriority.isEmpty();
    }

    @FunctionalInterface
    public interface ActivitySupplier<E extends LivingEntity> {
        List<ActivityData<E>> createActivities(E body);
    }

    public record Packed(MemoryMap memories) {
        public static final Brain.Packed EMPTY = new Brain.Packed(MemoryMap.EMPTY);
        public static final Codec<Brain.Packed> CODEC = RecordCodecBuilder.create(
            i -> i.group(MemoryMap.CODEC.fieldOf("memories").forGetter(Brain.Packed::memories)).apply(i, Brain.Packed::new)
        );
    }

    public static final class Provider<E extends LivingEntity> {
        private final Collection<? extends MemoryModuleType<?>> memoryTypes;
        private final Collection<? extends SensorType<? extends Sensor<? super E>>> sensorTypes;
        private final Brain.ActivitySupplier<E> activities;

        private Provider(
            final Collection<? extends MemoryModuleType<?>> memoryTypes,
            final Collection<? extends SensorType<? extends Sensor<? super E>>> sensorTypes,
            final Brain.ActivitySupplier<E> activities
        ) {
            this.memoryTypes = memoryTypes;
            this.sensorTypes = sensorTypes;
            this.activities = activities;
        }

        public Brain<E> makeBrain(final E body, final Brain.Packed packed) {
            List<ActivityData<E>> activities = this.activities.createActivities(body);
            return new Brain<>(this.memoryTypes, this.sensorTypes, activities, packed.memories, body.getRandom());
        }
    }

    public interface Visitor {
        <U> void acceptEmpty(MemoryModuleType<U> type);

        <U> void accept(MemoryModuleType<U> type, U value);

        <U> void accept(MemoryModuleType<U> type, U value, long timeToLive);
    }
}
