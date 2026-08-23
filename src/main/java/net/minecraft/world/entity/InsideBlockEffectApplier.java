package net.minecraft.world.entity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.util.Util;

public interface InsideBlockEffectApplier {
    InsideBlockEffectApplier NOOP = new InsideBlockEffectApplier() {
        @Override
        public void apply(final InsideBlockEffectType type) {
        }

        @Override
        public void runBefore(final InsideBlockEffectType type, final Consumer<Entity> effect) {
        }

        @Override
        public void runAfter(final InsideBlockEffectType type, final Consumer<Entity> effect) {
        }
    };

    void apply(InsideBlockEffectType type);

    void runBefore(InsideBlockEffectType type, Consumer<Entity> effect);

    void runAfter(InsideBlockEffectType type, Consumer<Entity> effect);

    class StepBasedCollector implements InsideBlockEffectApplier {
        private static final InsideBlockEffectType[] APPLY_ORDER = InsideBlockEffectType.values();
        private static final int NO_STEP = -1;
        /*
         * MODIFIED for porting: lithium entity.collisions.block_effects
         * InsideBlockEffectApplier$StepBasedCollectorMixin. Every entity that moves allocates one of these collectors, but
         * almost none of them ever collect a block effect (freeze, fire, extinguish, ...). All four collections therefore
         * start out null and are created on first use; the code below tolerates null everywhere and once created they are
         * never dropped again. Upstream expresses exactly this with a set of WrapOperation/ModifyReceiver/WrapWithCondition
         * handlers that turn the allocations into null and null-guard every later access.
         */
        private @org.jspecify.annotations.Nullable Set<InsideBlockEffectType> effectsInStep = null;
        private @org.jspecify.annotations.Nullable Map<InsideBlockEffectType, List<Consumer<Entity>>> beforeEffectsInStep = null;
        private @org.jspecify.annotations.Nullable Map<InsideBlockEffectType, List<Consumer<Entity>>> afterEffectsInStep = null;
        private @org.jspecify.annotations.Nullable List<Consumer<Entity>> finalEffects = null;
        private int lastStep = -1;

        public void advanceStep(final int step) {
            if (this.lastStep != step) {
                this.lastStep = step;
                this.flushStep();
            }
        }

        public void applyAndClear(final Entity entity) {
            this.flushStep();

            // MODIFIED for porting: lithium block_effects mixin (#replaceNull / #isNotNull)
            for (Consumer<Entity> effect : this.finalEffects == null ? java.util.Collections.<Consumer<Entity>>emptyList() : this.finalEffects) {
                if (!entity.isAlive()) {
                    break;
                }

                effect.accept(entity);
            }

            if (this.finalEffects != null) {
                this.finalEffects.clear();
            }

            this.lastStep = -1;
        }

        private void flushStep() {
            // MODIFIED for porting: lithium block_effects mixin#trySkip - nothing was collected, so there is nothing to flush
            if ((this.effectsInStep == null || this.effectsInStep.isEmpty())
                && (this.beforeEffectsInStep == null || this.beforeEffectsInStep.isEmpty())
                && (this.afterEffectsInStep == null || this.afterEffectsInStep.isEmpty())) {
                return;
            }

            if (this.finalEffects == null) {
                this.finalEffects = new ArrayList<>();
            }

            for (InsideBlockEffectType type : APPLY_ORDER) {
                // MODIFIED for porting: lithium block_effects mixin (#getOrNull / #addAllNonNull / #removeOrFalse / #isNotNull2)
                List<Consumer<Entity>> beforeEffects = this.beforeEffectsInStep == null ? null : this.beforeEffectsInStep.get(type);
                if (beforeEffects != null && !beforeEffects.isEmpty()) {
                    this.finalEffects.addAll(beforeEffects);
                }

                if (beforeEffects != null) {
                    beforeEffects.clear();
                }

                if (this.effectsInStep != null && this.effectsInStep.remove(type)) {
                    this.finalEffects.add(type.effect());
                }

                List<Consumer<Entity>> afterEffects = this.afterEffectsInStep == null ? null : this.afterEffectsInStep.get(type);
                if (afterEffects != null && !afterEffects.isEmpty()) {
                    this.finalEffects.addAll(afterEffects);
                }

                if (afterEffects != null) {
                    afterEffects.clear();
                }
            }
        }

        @Override
        public void apply(final InsideBlockEffectType type) {
            // MODIFIED for porting: lithium block_effects mixin#init (HEAD of apply)
            if (this.effectsInStep == null) {
                this.effectsInStep = EnumSet.noneOf(InsideBlockEffectType.class);
            }

            this.effectsInStep.add(type);
        }

        @Override
        public void runBefore(final InsideBlockEffectType type, final Consumer<Entity> effect) {
            // MODIFIED for porting: lithium block_effects mixin#initBeforeAndGet
            if (this.beforeEffectsInStep == null) {
                this.beforeEffectsInStep = new java.util.EnumMap<>(InsideBlockEffectType.class);
            }

            this.beforeEffectsInStep.computeIfAbsent(type, k -> new ArrayList<>()).add(effect);
        }

        @Override
        public void runAfter(final InsideBlockEffectType type, final Consumer<Entity> effect) {
            // MODIFIED for porting: lithium block_effects mixin#initAfterAndGet
            if (this.afterEffectsInStep == null) {
                this.afterEffectsInStep = new java.util.EnumMap<>(InsideBlockEffectType.class);
            }

            this.afterEffectsInStep.computeIfAbsent(type, k -> new ArrayList<>()).add(effect);
        }
    }
}