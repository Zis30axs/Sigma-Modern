package net.minecraft.world.level.entity;

import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import java.util.Objects;
import java.util.Spliterators;
import java.util.PrimitiveIterator.OfLong;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

// MODIFIED for porting: lithium minimal_nonvanilla.spawning EntitySectionStorageMixin
public class EntitySectionStorage<T extends EntityAccess> implements net.caffeinemc.mods.lithium.common.world.ChunkAwareEntityIterable<T> {
    public static final int CHONKY_ENTITY_SEARCH_GRACE = 2;
    public static final int MAX_NON_CHONKY_ENTITY_SIZE = 4;
    private final Class<T> entityClass;
    private final Long2ObjectFunction<Visibility> intialSectionVisibility;
    private final Long2ObjectMap<EntitySection<T>> sections = new Long2ObjectOpenHashMap<>();

    /**
     * MODIFIED for porting: was lithium's minimal_nonvanilla.spawning EntitySectionStorageMixin. Mob spawning only needs the
     * entities of the tracked sections, which is much cheaper to iterate than the level's full entity list.
     */
    @Override
    public Iterable<T> lithium$IterateEntitiesInTrackedSections() {
        it.unimi.dsi.fastutil.objects.ObjectCollection<EntitySection<T>> sections = this.sections.values();
        return () -> {
            it.unimi.dsi.fastutil.objects.ObjectIterator<EntitySection<T>> sectionsIterator = sections.iterator();
            return new com.google.common.collect.AbstractIterator<T>() {
                private java.util.Iterator<T> entityIterator;

                @Override
                protected @Nullable T computeNext() {
                    if (this.entityIterator != null && this.entityIterator.hasNext()) {
                        return this.entityIterator.next();
                    }

                    while (sectionsIterator.hasNext()) {
                        EntitySection<T> section = sectionsIterator.next();
                        if (section.getStatus().isAccessible() && !section.isEmpty()) {
                            this.entityIterator = section.getCollection().iterator();
                            if (this.entityIterator.hasNext()) {
                                return this.entityIterator.next();
                            }
                        }
                    }

                    return this.endOfData();
                }
            };
        };
    }
    private final LongSortedSet sectionIds = new LongAVLTreeSet();

    public EntitySectionStorage(final Class<T> entityClass, final Long2ObjectFunction<Visibility> intialSectionVisibility) {
        this.entityClass = entityClass;
        this.intialSectionVisibility = intialSectionVisibility;
    }

    public void forEachAccessibleNonEmptySection(final AABB bb, final AbortableIterationConsumer<EntitySection<T>> output) {
        int xMin = SectionPos.posToSectionCoord(bb.minX - 2.0);
        int yMin = SectionPos.posToSectionCoord(bb.minY - 4.0);
        int zMin = SectionPos.posToSectionCoord(bb.minZ - 2.0);
        int xMax = SectionPos.posToSectionCoord(bb.maxX + 2.0);
        int yMax = SectionPos.posToSectionCoord(bb.maxY + 0.0);
        int zMax = SectionPos.posToSectionCoord(bb.maxZ + 2.0);
        // MODIFIED for porting: lithium entity.fast_retrieval EntitySectionStorageMixin#forEachInBox. For small boxes,
        // looking the (at most 4x4 columns of) sections up directly is cheaper than walking the sorted section-id set, which
        // may iterate over hundreds of irrelevant longs. For larger boxes vanilla's scan wins, so it is kept: it becomes
        // increasingly likely that the far-away sections do not exist at all (player despawn range and so on).
        if (xMax < xMin + 4 && zMax < zMin + 4) {
            // The vanilla AVL set is sorted by ascending long value, and SectionPos packs x into the most significant bits,
            // so the packed long is negative exactly when x is negative; y and z are effectively compared as unsigned. The
            // loops below reproduce that visiting order.
            for (int x = xMin; x <= xMax; x++) {
                for (int z = Math.max(zMin, 0); z <= zMax; z++) {
                    if (this.lithium$forEachInColumn(x, yMin, yMax, z, output).shouldAbort()) {
                        return;
                    }
                }

                int zBound = Math.min(-1, zMax);
                for (int z = zMin; z <= zBound; z++) {
                    if (this.lithium$forEachInColumn(x, yMin, yMax, z, output).shouldAbort()) {
                        return;
                    }
                }
            }

            return;
        }

        for (int x = xMin; x <= xMax; x++) {
            long lowestAbsoluteSectionKey = SectionPos.asLong(x, 0, 0);
            long highestAbsoluteSectionKey = SectionPos.asLong(x, -1, -1);
            LongIterator it = this.sectionIds.subSet(lowestAbsoluteSectionKey, highestAbsoluteSectionKey + 1L).iterator();

            while (it.hasNext()) {
                long sectionKey = it.nextLong();
                int y = SectionPos.y(sectionKey);
                int z = SectionPos.z(sectionKey);
                if (y >= yMin && y <= yMax && z >= zMin && z <= zMax) {
                    EntitySection<T> entitySection = this.sections.get(sectionKey);
                    if (entitySection != null
                        && !entitySection.isEmpty()
                        && entitySection.getStatus().isAccessible()
                        && output.accept(entitySection).shouldAbort()) {
                        return;
                    }
                }
            }
        }
    }

    // MODIFIED for porting: the next two helpers were lithium's entity.fast_retrieval EntitySectionStorageMixin
    private AbortableIterationConsumer.Continuation lithium$forEachInColumn(
        final int x, final int yMin, final int yMax, final int z, final AbortableIterationConsumer<EntitySection<T>> output
    ) {
        AbortableIterationConsumer.Continuation ret = AbortableIterationConsumer.Continuation.CONTINUE;

        // y goes from negative to positive, but y is compared as unsigned in the packed long
        for (int y = Math.max(yMin, 0); y <= yMax; y++) {
            if ((ret = this.lithium$consumeSection(SectionPos.asLong(x, y, z), output)).shouldAbort()) {
                return ret;
            }
        }

        int yBound = Math.min(-1, yMax);
        for (int y = yMin; y <= yBound; y++) {
            if ((ret = this.lithium$consumeSection(SectionPos.asLong(x, y, z), output)).shouldAbort()) {
                return ret;
            }
        }

        return ret;
    }

    private AbortableIterationConsumer.Continuation lithium$consumeSection(final long pos, final AbortableIterationConsumer<EntitySection<T>> output) {
        EntitySection<T> section = this.getSection(pos);
        // size() instead of isEmpty(): util.entity_movement_tracking makes isEmpty() also consider attached listeners
        if (section != null && section.size() != 0 && section.getStatus().isAccessible()) {
            return output.accept(section);
        }

        return AbortableIterationConsumer.Continuation.CONTINUE;
    }

    public LongStream getExistingSectionPositionsInChunk(final long chunkKey) {
        int x = ChunkPos.getX(chunkKey);
        int z = ChunkPos.getZ(chunkKey);
        LongSortedSet chunkSections = this.getChunkSections(x, z);
        if (chunkSections.isEmpty()) {
            return LongStream.empty();
        }

        OfLong iterator = chunkSections.iterator();
        return StreamSupport.longStream(Spliterators.spliteratorUnknownSize(iterator, 1301), false);
    }

    private LongSortedSet getChunkSections(final int x, final int z) {
        long lowestAbsoluteSectionKey = SectionPos.asLong(x, 0, z);
        long highestAbsoluteSectionKey = SectionPos.asLong(x, -1, z);
        return this.sectionIds.subSet(lowestAbsoluteSectionKey, highestAbsoluteSectionKey + 1L);
    }

    public Stream<EntitySection<T>> getExistingSectionsInChunk(final long chunkKey) {
        return this.getExistingSectionPositionsInChunk(chunkKey).mapToObj(this.sections::get).filter(Objects::nonNull);
    }

    private static long getChunkKeyFromSectionKey(final long sectionPos) {
        return ChunkPos.pack(SectionPos.x(sectionPos), SectionPos.z(sectionPos));
    }

    public EntitySection<T> getOrCreateSection(final long key) {
        return this.sections.computeIfAbsent(key, this::createSection);
    }

    public @Nullable EntitySection<T> getSection(final long key) {
        return this.sections.get(key);
    }

    private EntitySection<T> createSection(final long sectionPos) {
        long chunkPos = getChunkKeyFromSectionKey(sectionPos);
        Visibility chunkStatus = this.intialSectionVisibility.get(chunkPos);
        this.sectionIds.add(sectionPos);
        EntitySection<T> section = new EntitySection<>(this.entityClass, chunkStatus);
        // MODIFIED for porting: lithium util.entity_section_position EntitySectionStorageMixin#rememberPos
        ((net.caffeinemc.mods.lithium.common.entity.PositionedEntityTrackingSection)section).lithium$setPos(sectionPos);
        return section;
    }

    public LongSet getAllChunksWithExistingSections() {
        LongSet chunks = new LongOpenHashSet();
        this.sections.keySet().forEach((long sectionKey) -> chunks.add(getChunkKeyFromSectionKey(sectionKey)));
        return chunks;
    }

    public void getEntities(final AABB bb, final AbortableIterationConsumer<T> output) {
        this.forEachAccessibleNonEmptySection(bb, section -> section.getEntities(bb, output));
    }

    public <U extends T> void getEntities(final EntityTypeTest<T, U> type, final AABB bb, final AbortableIterationConsumer<U> consumer) {
        this.forEachAccessibleNonEmptySection(bb, section -> section.getEntities(type, bb, consumer));
    }

    public void remove(final long sectionKey) {
        this.sections.remove(sectionKey);
        this.sectionIds.remove(sectionKey);
    }

    @VisibleForDebug
    public int count() {
        return this.sectionIds.size();
    }
}
