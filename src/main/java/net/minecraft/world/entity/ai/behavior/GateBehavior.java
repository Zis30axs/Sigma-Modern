package net.minecraft.world.entity.ai.behavior;

import com.mojang.datafixers.util.Pair;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;

public class GateBehavior<E extends LivingEntity> implements BehaviorControl<E> {
    private final Map<MemoryModuleType<?>, MemoryStatus> entryCondition;
    private final Set<MemoryModuleType<?>> exitErasedMemories;
    private final GateBehavior.OrderPolicy orderPolicy;
    private final GateBehavior.RunningPolicy runningPolicy;
    private final ShufflingList<BehaviorControl<? super E>> behaviors = new ShufflingList<>();
    private Behavior.Status status = Behavior.Status.STOPPED;

    public GateBehavior(
        final Map<MemoryModuleType<?>, MemoryStatus> entryCondition,
        final Set<MemoryModuleType<?>> exitErasedMemories,
        final GateBehavior.OrderPolicy orderPolicy,
        final GateBehavior.RunningPolicy runningPolicy,
        final List<Pair<? extends BehaviorControl<? super E>, Integer>> behaviors
    ) {
        this.entryCondition = entryCondition;
        this.exitErasedMemories = exitErasedMemories;
        this.orderPolicy = orderPolicy;
        this.runningPolicy = runningPolicy;
        behaviors.forEach(entry -> this.behaviors.add(entry.getFirst(), entry.getSecond()));
    }

    @Override
    public Behavior.Status getStatus() {
        return this.status;
    }

    @Override
    public Set<MemoryModuleType<?>> getRequiredMemories() {
        Set<MemoryModuleType<?>> memories = new HashSet<>(this.entryCondition.keySet());

        for (BehaviorControl<? super E> behavior : this.behaviors) {
            memories.addAll(behavior.getRequiredMemories());
        }

        return memories;
    }

    private boolean hasRequiredMemories(final E body) {
        for (Entry<MemoryModuleType<?>, MemoryStatus> entry : this.entryCondition.entrySet()) {
            MemoryModuleType<?> memoryType = entry.getKey();
            MemoryStatus requiredStatus = entry.getValue();
            if (!body.getBrain().checkMemory(memoryType, requiredStatus)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public final boolean tryStart(final ServerLevel level, final E body, final long timestamp) {
        if (this.hasRequiredMemories(body)) {
            this.status = Behavior.Status.RUNNING;
            this.orderPolicy.apply(this.behaviors);
            // MODIFIED for porting: lithium ai.task.replace_streams GateBehaviorMixin#tryStart (@WrapOperation) - the
            // running policies implement a stream-free variant. RunningPolicy is an enum, so every instance has it.
            this.runningPolicy.lithium$apply(this.behaviors, level, body, timestamp);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public final void tickOrStop(final ServerLevel level, final E body, final long timestamp) {
        // MODIFIED for porting: lithium ai.task.replace_streams GateBehaviorMixin#tickOrStop (@Overwrite) - replace the
        // stream code (which also walked the list twice) with a single traditional iteration.
        boolean hasOneTaskRunning = false;

        for (BehaviorControl<? super E> task : this.behaviors) {
            if (task.getStatus() == Behavior.Status.RUNNING) {
                task.tickOrStop(level, body, timestamp);
                hasOneTaskRunning |= task.getStatus() == Behavior.Status.RUNNING;
            }
        }

        if (!hasOneTaskRunning) {
            this.doStop(level, body, timestamp);
        }
    }

    @Override
    public final void doStop(final ServerLevel level, final E body, final long timestamp) {
        // MODIFIED for porting: lithium ai.task.replace_streams GateBehaviorMixin#doStop (@Overwrite) - replace stream code
        // with traditional iteration
        this.status = Behavior.Status.STOPPED;

        for (BehaviorControl<? super E> task : this.behaviors) {
            if (task.getStatus() == Behavior.Status.RUNNING) {
                task.doStop(level, body, timestamp);
            }
        }

        net.minecraft.world.entity.ai.Brain<?> brain = body.getBrain();

        for (MemoryModuleType<?> module : this.exitErasedMemories) {
            brain.eraseMemory(module);
        }
    }

    @Override
    public String debugString() {
        Set<String> runningBehaviours = this.behaviors
            .stream()
            .filter(goal -> goal.getStatus() == Behavior.Status.RUNNING)
            .map(b -> b.getClass().getSimpleName())
            .collect(Collectors.toSet());
        return this.getClass().getSimpleName() + ": " + runningBehaviours;
    }

    public enum OrderPolicy {
        ORDERED(t -> {}),
        SHUFFLED(ShufflingList::shuffle);

        private final Consumer<ShufflingList<?>> consumer;

        OrderPolicy(final Consumer<ShufflingList<?>> consumer) {
            this.consumer = consumer;
        }

        public void apply(final ShufflingList<?> list) {
            this.consumer.accept(list);
        }
    }

    // MODIFIED for porting: lithium ai.task.replace_streams RunningPolicyRunOneMixin / RunningPolicyTryAllMixin target
    // the anonymous subclasses GateBehavior$RunningPolicy$1 and $2, i.e. the two enum constant bodies below.
    public enum RunningPolicy implements net.caffeinemc.mods.lithium.common.ai.brain.RunningPolicyNoStream {
        RUN_ONE {
            @Override
            public <E extends LivingEntity> void apply(
                final Stream<BehaviorControl<? super E>> behaviors, final ServerLevel level, final E body, final long timestamp
            ) {
                behaviors.filter(goal -> goal.getStatus() == Behavior.Status.STOPPED).filter(goal -> goal.tryStart(level, body, timestamp)).findFirst();
            }

            // MODIFIED for porting: lithium ai.task.replace_streams RunningPolicyRunOneMixin
            @Override
            public <E extends LivingEntity> void lithium$apply(
                final ShufflingList<BehaviorControl<? super E>> behaviors, final ServerLevel level, final E body, final long timestamp
            ) {
                for (BehaviorControl<? super E> behaviorControl : behaviors) {
                    if (behaviorControl.getStatus() == Behavior.Status.STOPPED && behaviorControl.tryStart(level, body, timestamp)) {
                        return;
                    }
                }
            }
        },
        TRY_ALL {
            @Override
            public <E extends LivingEntity> void apply(
                final Stream<BehaviorControl<? super E>> behaviors, final ServerLevel level, final E body, final long timestamp
            ) {
                behaviors.filter(goal -> goal.getStatus() == Behavior.Status.STOPPED).forEach(goal -> goal.tryStart(level, body, timestamp));
            }

            // MODIFIED for porting: lithium ai.task.replace_streams RunningPolicyTryAllMixin
            @Override
            public <E extends LivingEntity> void lithium$apply(
                final ShufflingList<BehaviorControl<? super E>> behaviors, final ServerLevel level, final E body, final long timestamp
            ) {
                for (BehaviorControl<? super E> behaviorControl : behaviors) {
                    if (behaviorControl.getStatus() == Behavior.Status.STOPPED) {
                        behaviorControl.tryStart(level, body, timestamp);
                    }
                }
            }
        };

        public abstract <E extends LivingEntity> void apply(
            final Stream<BehaviorControl<? super E>> behaviors, final ServerLevel level, final E body, final long timestamp
        );
    }
}