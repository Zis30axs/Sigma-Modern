package net.minecraft.world.level.entity;

import com.mojang.logging.LogUtils;
import java.util.Collection;
import java.util.stream.Stream;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

public class EntitySection<T extends EntityAccess>
    implements net.caffeinemc.mods.lithium.mixin.util.accessors.EntitySectionAccessor<T>, // MODIFIED for porting: lithium EntitySectionAccessor
    net.caffeinemc.mods.lithium.common.entity.PositionedEntityTrackingSection, // MODIFIED for porting: lithium util.entity_section_position
    net.caffeinemc.mods.lithium.common.tracking.entity.EntityMovementTrackerSection, // MODIFIED for porting: lithium util.entity_movement_tracking
    net.caffeinemc.mods.lithium.mixin.block.hopper.EntitySectionAccessor<T>, // MODIFIED for porting: lithium block.hopper (same accessor, separate config option)
    net.caffeinemc.mods.lithium.mixin.minimal_nonvanilla.spawning.EntitySectionAccessor<T>, // MODIFIED for porting: lithium minimal_nonvanilla.spawning (same accessor)
    net.caffeinemc.mods.lithium.common.world.ClimbingMobCachingSection { // MODIFIED for porting: lithium entity.collisions.unpushable_cramming
    private static final Logger LOGGER = LogUtils.getLogger();
    private final ClassInstanceMultiMap<T> storage;
    private Visibility chunkStatus;

    public EntitySection(final Class<T> entityClass, final Visibility chunkStatus) {
        this.chunkStatus = chunkStatus;
        this.storage = new ClassInstanceMultiMap<>(entityClass);
    }

    /**
     * MODIFIED for porting: lithium entity.collisions.unpushable_cramming EntitySectionMixin. Holds the entities of this
     * section that can be pushed under some conditions. Entities that are cached to be inside a climbable block - and
     * therefore cannot be pushed, for the entity types where that is cacheable - are hidden behind the mask until the cache
     * is cleared again. The list is only created once a lookup showed that most of the visited entities were not pushable.
     */
    private net.caffeinemc.mods.lithium.common.util.collections.ReferenceMaskedList<net.minecraft.world.entity.Entity> lithium$pushableEntities;

    @Override
    public AbortableIterationConsumer.Continuation lithium$collectPushableEntities(
        final net.minecraft.world.level.Level world,
        final net.minecraft.world.entity.Entity except,
        final AABB box,
        final net.caffeinemc.mods.lithium.common.entity.pushable.EntityPushablePredicate<? super net.minecraft.world.entity.Entity> entityPushablePredicate,
        final java.util.ArrayList<net.minecraft.world.entity.Entity> entities
    ) {
        java.util.Iterator<?> entityIterator = this.lithium$pushableEntities != null ? this.lithium$pushableEntities.iterator() : this.storage.iterator();
        int visited = 0;
        int pushable = 0;

        while (entityIterator.hasNext()) {
            net.minecraft.world.entity.Entity entity = (net.minecraft.world.entity.Entity)entityIterator.next();
            if (entity.getBoundingBox().intersects(box)
                && !entity.isSpectator()
                && entity != except
                && !(entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon)) {
                visited++;
                // This predicate has side effects: it may make a FeetBlockCachingEntity cache its block and update its
                // visibility in the mask above.
                if (entityPushablePredicate.test(entity)) {
                    pushable++;
                    // The dragon piece check is skipped because dragon pieces are never pushable
                    entities.add(entity);
                }
            }
        }

        if (this.lithium$pushableEntities == null && visited >= 25 && visited >= pushable * 2) {
            this.lithium$startFilteringPushableEntities();
        }

        return AbortableIterationConsumer.Continuation.CONTINUE;
    }

    private void lithium$startFilteringPushableEntities() {
        this.lithium$pushableEntities = new net.caffeinemc.mods.lithium.common.util.collections.ReferenceMaskedList<>();

        for (T entity : this.storage) {
            this.lithium$onStartClimbingCachingEntity((net.minecraft.world.entity.Entity)entity);
        }
    }

    private void lithium$stopFilteringPushableEntities() {
        this.lithium$pushableEntities = null;
    }

    // This may be called while the world is in an inconsistent state, e.g. while the entity is in a different section than
    // the one it is registered to.
    @Override
    public void lithium$onEntityModifiedCachedBlock(final net.caffeinemc.mods.lithium.common.entity.pushable.FeetBlockCachingEntity entity, final net.minecraft.world.level.block.state.BlockState newBlockState) {
        if (this.lithium$pushableEntities == null) {
            entity.lithium$SetClimbingMobCachingSectionUpdateBehavior(false);
        } else {
            this.lithium$updatePushabilityOnCachedStateChange(entity, newBlockState);
        }
    }

    private void lithium$updatePushabilityOnCachedStateChange(
        final net.caffeinemc.mods.lithium.common.entity.pushable.FeetBlockCachingEntity entity, final net.minecraft.world.level.block.state.BlockState newBlockState
    ) {
        // The entity may be moving into this section right now without being registered yet. If it is not in the collection
        // nothing happens here; it gets the correct visibility when it is registered.
        this.lithium$pushableEntities.setVisible((net.minecraft.world.entity.Entity)entity, lithium$entityPushableHeuristic(newBlockState));
    }

    private void lithium$onStartClimbingCachingEntity(final net.minecraft.world.entity.Entity entity) {
        if (net.caffeinemc.mods.lithium.common.entity.pushable.PushableEntityClassGroup.MAYBE_PUSHABLE.contains(entity)) {
            this.lithium$pushableEntities.add(entity);
            if (net.caffeinemc.mods.lithium.common.entity.pushable.PushableEntityClassGroup.CACHABLE_UNPUSHABILITY.contains(entity)) {
                net.caffeinemc.mods.lithium.common.entity.pushable.FeetBlockCachingEntity feetBlockCachingEntity = (net.caffeinemc.mods.lithium.common.entity.pushable.FeetBlockCachingEntity)entity;
                this.lithium$updatePushabilityOnCachedStateChange(feetBlockCachingEntity, feetBlockCachingEntity.lithium$getCachedFeetBlockState());
                feetBlockCachingEntity.lithium$SetClimbingMobCachingSectionUpdateBehavior(true);
            }
        }
    }

    /**
     * Whether entities with the given feet BlockState should be considered pushable. Some entity types are not pushable while
     * they are inside climbable blocks such as ladders. Returns true for edge cases like an entity in a trapdoor (which may
     * be climbable because of a ladder below it).
     */
    private static boolean lithium$entityPushableHeuristic(final net.minecraft.world.level.block.state.BlockState cachedFeetBlockState) {
        return cachedFeetBlockState == null || !cachedFeetBlockState.is(net.minecraft.tags.BlockTags.CLIMBABLE);
    }

    // MODIFIED for porting: lithium util.entity_section_position EntitySectionMixin
    private long lithium$pos;
    // MODIFIED for porting: lithium util.entity_movement_tracking EntitySectionMixin @Unique fields
    private final it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<net.caffeinemc.mods.lithium.common.tracking.entity.SectionedEntityMovementTracker<?>> lithium$sectionVisibilityListeners =
        new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>(0);
    private final java.util.ArrayList<net.caffeinemc.mods.lithium.common.tracking.entity.SectionedEntityMovementTracker<?>>[] lithium$entityMovementListenersByType =
        new java.util.ArrayList[net.caffeinemc.mods.lithium.common.tracking.entity.MovementTrackerHelper.NUM_MOVEMENT_NOTIFYING_CLASSES];
    private final long[] lithium$lastEntityMovementByType =
        new long[net.caffeinemc.mods.lithium.common.tracking.entity.MovementTrackerHelper.NUM_MOVEMENT_NOTIFYING_CLASSES];

    @Override
    public void lithium$setPos(final long chunkSectionPos) {
        this.lithium$pos = chunkSectionPos;
    }

    @Override
    public long lithium$getPos() {
        return this.lithium$pos;
    }

    // MODIFIED for porting: was lithium's EntitySectionAccessor accessor Mixin
    @Override
    public ClassInstanceMultiMap<T> getCollection() {
        return this.storage;
    }

    public void add(final T entity) {
        this.storage.add(entity);
        // MODIFIED for porting: lithium entity.collisions.unpushable_cramming EntitySectionMixin#onEntityAdded (RETURN)
        if (this.lithium$pushableEntities != null) {
            if (!this.chunkStatus.isAccessible()) {
                this.lithium$stopFilteringPushableEntities();
            } else {
                this.lithium$onStartClimbingCachingEntity((net.minecraft.world.entity.Entity)entity);
                if (this.lithium$pushableEntities.totalSize() > this.storage.size()) {
                    // Something is leaking somewhere, possibly because of a mod compatibility issue - stop filtering
                    this.lithium$stopFilteringPushableEntities();
                }
            }
        }
    }

    public boolean remove(final T entity) {
        boolean removed = this.storage.remove(entity);
        // MODIFIED for porting: lithium entity.collisions.unpushable_cramming EntitySectionMixin#onEntityRemoved (RETURN)
        if (this.lithium$pushableEntities != null) {
            if (!this.chunkStatus.isAccessible()) {
                this.lithium$stopFilteringPushableEntities();
            } else {
                this.lithium$pushableEntities.remove((net.minecraft.world.entity.Entity)entity);
            }
        }

        return removed;
    }

    public AbortableIterationConsumer.Continuation getEntities(final AABB bb, final AbortableIterationConsumer<T> entities) {
        // MODIFIED for porting: lithium alloc.entity_iteration EntitySectionMixin#directIterator iterates the backing list
        // directly instead of going through ClassInstanceMultiMap#iterator (which wraps it in an unmodifiable iterator).
        for (T entity : ((net.caffeinemc.mods.lithium.mixin.alloc.entity_iteration.ClassInstanceMultiMapAccessor<T>)this.storage).getAllInstances()) {
            if (entity.getBoundingBox().intersects(bb) && entities.accept(entity).shouldAbort()) {
                return AbortableIterationConsumer.Continuation.ABORT;
            }
        }

        return AbortableIterationConsumer.Continuation.CONTINUE;
    }

    public <U extends T> AbortableIterationConsumer.Continuation getEntities(
        final EntityTypeTest<T, U> type, final AABB bb, final AbortableIterationConsumer<? super U> consumer
    ) {
        Collection<? extends T> foundEntities = this.storage.find(type.getBaseClass());
        if (foundEntities.isEmpty()) {
            return AbortableIterationConsumer.Continuation.CONTINUE;
        }

        for (T entity : foundEntities) {
            U maybeEntity = (U)type.tryCast(entity);
            if (maybeEntity != null && entity.getBoundingBox().intersects(bb) && consumer.accept(maybeEntity).shouldAbort()) {
                return AbortableIterationConsumer.Continuation.ABORT;
            }
        }

        return AbortableIterationConsumer.Continuation.CONTINUE;
    }

    public boolean isEmpty() {
        // MODIFIED for porting: lithium util.entity_movement_tracking EntitySectionMixin#modifyIsEmpty - a section that
        // still has visibility listeners attached must not be treated as empty (and thus must not be discarded).
        return this.storage.isEmpty() && this.lithium$sectionVisibilityListeners.isEmpty();
    }

    public Stream<T> getEntities() {
        return this.storage.stream();
    }

    public Visibility getStatus() {
        return this.chunkStatus;
    }

    public Visibility updateChunkStatus(final Visibility chunkStatus) {
        // MODIFIED for porting: lithium util.entity_movement_tracking EntitySectionMixin#swapStatus, which modified the
        // argument at HEAD purely to run this notification before the field is overwritten.
        if (this.chunkStatus.isAccessible() != chunkStatus.isAccessible() && !this.lithium$sectionVisibilityListeners.isEmpty()) {
            if (!chunkStatus.isAccessible()) {
                for (net.caffeinemc.mods.lithium.common.tracking.entity.SectionedEntityMovementTracker<?> listener : this.lithium$sectionVisibilityListeners) {
                    listener.onSectionLeftRange(this);
                }
            } else {
                for (net.caffeinemc.mods.lithium.common.tracking.entity.SectionedEntityMovementTracker<?> listener : this.lithium$sectionVisibilityListeners) {
                    listener.onSectionEnteredRange(this);
                }
            }
        }

        Visibility prev = this.chunkStatus;
        this.chunkStatus = chunkStatus;
        return prev;
    }

    // MODIFIED for porting: the following methods were lithium's util.entity_movement_tracking EntitySectionMixin
    @Override
    public void lithium$addListener(final net.caffeinemc.mods.lithium.common.tracking.entity.SectionedEntityMovementTracker<?> listener) {
        this.lithium$sectionVisibilityListeners.add(listener);
        if (this.chunkStatus.isAccessible()) {
            listener.onSectionEnteredRange(this);
        }
    }

    @Override
    public void lithium$removeListener(
        final EntitySectionStorage<?> sectionedEntityCache, final net.caffeinemc.mods.lithium.common.tracking.entity.SectionedEntityMovementTracker<?> listener
    ) {
        boolean removed = this.lithium$sectionVisibilityListeners.remove(listener);
        if (this.chunkStatus.isAccessible() && removed) {
            listener.onSectionLeftRange(this);
        }

        if (this.isEmpty()) {
            sectionedEntityCache.remove(this.lithium$getPos());
        }
    }

    @Override
    public void lithium$trackEntityMovement(final int notificationMask, final long time) {
        long[] lastEntityMovementByType = this.lithium$lastEntityMovementByType;
        int size = lastEntityMovementByType.length;
        int mask;

        for (int entityClassIndex = Integer.numberOfTrailingZeros(notificationMask); entityClassIndex < size;) {
            lastEntityMovementByType[entityClassIndex] = time;
            java.util.ArrayList<net.caffeinemc.mods.lithium.common.tracking.entity.SectionedEntityMovementTracker<?>> entityMovementListeners =
                this.lithium$entityMovementListenersByType[entityClassIndex];
            if (entityMovementListeners != null) {
                for (int listIndex = entityMovementListeners.size() - 1; listIndex >= 0; listIndex--) {
                    entityMovementListeners.remove(listIndex).emitEntityMovement(notificationMask, this);
                }
            }

            mask = 0xFFFFFFFE << entityClassIndex;
            entityClassIndex = Integer.numberOfTrailingZeros(notificationMask & mask);
        }
    }

    @Override
    public long lithium$getChangeTime(final int trackedClass) {
        return this.lithium$lastEntityMovementByType[trackedClass];
    }

    @Override
    public <S, E extends EntityAccess> void lithium$listenToMovementOnce(
        final net.caffeinemc.mods.lithium.common.tracking.entity.SectionedEntityMovementTracker<E> listener, final int trackedClass
    ) {
        if (this.lithium$entityMovementListenersByType[trackedClass] == null) {
            this.lithium$entityMovementListenersByType[trackedClass] = new java.util.ArrayList<>();
        }

        this.lithium$entityMovementListenersByType[trackedClass].add(listener);
    }

    @Override
    public <S, E extends EntityAccess> void lithium$removeListenToMovementOnce(
        final net.caffeinemc.mods.lithium.common.tracking.entity.SectionedEntityMovementTracker<E> listener, final int trackedClass
    ) {
        if (this.lithium$entityMovementListenersByType[trackedClass] != null) {
            this.lithium$entityMovementListenersByType[trackedClass].remove(listener);
        }
    }

    @VisibleForDebug
    public int size() {
        return this.storage.size();
    }
}