package net.minecraft.world.entity.ai.village.poi;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.debug.DebugPoiInfo;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

// MODIFIED for porting: implements lithium's PointOfInterestSetExtended (ai.poi PoiSectionMixin), a set of stream-free
// lookups over the records of this section.
// Note: byType must stay a HashMap of HashSets - the iteration order is detectable in game.
public class PoiSection implements net.caffeinemc.mods.lithium.common.world.interests.PointOfInterestSetExtended {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Short2ObjectMap<PoiRecord> records = new Short2ObjectOpenHashMap<>();
    private final Map<Holder<PoiType>, Set<PoiRecord>> byType = Maps.newHashMap();
    private final Runnable setDirty;
    private boolean isValid;

    // MODIFIED for porting: everything below was lithium's ai.poi PoiSectionMixin
    @Override
    public void lithium$collectMatchingPoints(
        final Predicate<Holder<PoiType>> type, final PoiManager.Occupancy status, final Consumer<PoiRecord> consumer
    ) {
        if (type instanceof net.caffeinemc.mods.lithium.common.world.interests.iterator.SinglePointOfInterestTypeFilter singleTypeFilter) {
            this.lithium$collectWithSingleTypeFilter(singleTypeFilter.getType(), status, consumer);
        } else {
            this.lithium$collectWithDynamicTypeFilter(type, status, consumer);
        }
    }

    @Override
    public @Nullable PoiRecord lithium$getL2ClosestMatchingPoint(
        final BlockPos center, final Predicate<Holder<PoiType>> typeFilter, final Predicate<? super PoiRecord> poiPredicate
    ) {
        if (typeFilter instanceof net.caffeinemc.mods.lithium.common.world.interests.iterator.SinglePointOfInterestTypeFilter singleTypeFilter) {
            return this.lithium$getL2ClosestMatchingPoint(center, singleTypeFilter.getType(), poiPredicate);
        } else {
            return this.lithium$getL2ClosestMatchingPointDynamic(center, typeFilter, poiPredicate);
        }
    }

    private @Nullable PoiRecord lithium$getL2ClosestMatchingPoint(
        final BlockPos center, final Holder<PoiType> type, final Predicate<? super PoiRecord> poiPredicate
    ) {
        Set<PoiRecord> poiRecords = this.byType.get(type);
        if (poiRecords == null || poiRecords.isEmpty()) {
            return null;
        }

        PoiRecord closest = null;
        long closestDistanceSq = Long.MAX_VALUE;

        for (PoiRecord poiRecord : poiRecords) {
            long distanceSq = net.caffeinemc.mods.lithium.common.util.Distances.distanceSq(center, poiRecord.getPos());
            if (distanceSq < closestDistanceSq && poiPredicate.test(poiRecord)) {
                closestDistanceSq = distanceSq;
                closest = poiRecord;
            }
        }

        return closest;
    }

    private @Nullable PoiRecord lithium$getL2ClosestMatchingPointDynamic(
        final BlockPos center, final Predicate<Holder<PoiType>> typeFilter, final Predicate<? super PoiRecord> poiPredicate
    ) {
        PoiRecord closest = null;
        long closestDistanceSq = Long.MAX_VALUE;

        for (Map.Entry<Holder<PoiType>, Set<PoiRecord>> entry : this.byType.entrySet()) {
            if (!typeFilter.test(entry.getKey()) || entry.getValue().isEmpty()) {
                continue;
            }

            for (PoiRecord poiRecord : entry.getValue()) {
                long distanceSq = net.caffeinemc.mods.lithium.common.util.Distances.distanceSq(center, poiRecord.getPos());
                if (distanceSq < closestDistanceSq && poiPredicate.test(poiRecord)) {
                    closestDistanceSq = distanceSq;
                    closest = poiRecord;
                }
            }
        }

        return closest;
    }

    @Override
    public void lithium$collectMatchingPointsL2Limited(
        final BlockPos center,
        final long maxDistanceSq,
        final Predicate<Holder<PoiType>> typeFilter,
        final Predicate<? super PoiRecord> poiPredicate,
        final Consumer<PoiRecord> consumer,
        final int limit
    ) {
        if (typeFilter instanceof net.caffeinemc.mods.lithium.common.world.interests.iterator.SinglePointOfInterestTypeFilter singleTypeFilter) {
            this.lithium$collectMatchingPointsL2Limited(center, maxDistanceSq, singleTypeFilter.getType(), poiPredicate, consumer, limit);
        } else {
            this.lithium$collectMatchingPointsL2LimitedDynamic(center, maxDistanceSq, typeFilter, poiPredicate, consumer, limit);
        }
    }

    private void lithium$collectMatchingPointsL2Limited(
        final BlockPos center,
        final long maxDistanceSq,
        final Holder<PoiType> type,
        final Predicate<? super PoiRecord> poiPredicate,
        final Consumer<PoiRecord> consumer,
        int limit
    ) {
        Set<PoiRecord> poiRecords = this.byType.get(type);
        if (poiRecords == null || poiRecords.isEmpty()) {
            return;
        }

        for (PoiRecord poiRecord : poiRecords) {
            long distanceSq = net.caffeinemc.mods.lithium.common.util.Distances.distanceSq(center, poiRecord.getPos());
            if (distanceSq <= maxDistanceSq && poiPredicate.test(poiRecord)) {
                consumer.accept(poiRecord);
                if (--limit == 0) {
                    return;
                }
            }
        }
    }

    private void lithium$collectMatchingPointsL2LimitedDynamic(
        final BlockPos center,
        final long maxDistanceSq,
        final Predicate<Holder<PoiType>> typeFilter,
        final Predicate<? super PoiRecord> poiPredicate,
        final Consumer<PoiRecord> consumer,
        int limit
    ) {
        for (Map.Entry<Holder<PoiType>, Set<PoiRecord>> entry : this.byType.entrySet()) {
            if (!typeFilter.test(entry.getKey()) || entry.getValue().isEmpty()) {
                continue;
            }

            for (PoiRecord poiRecord : entry.getValue()) {
                long distanceSq = net.caffeinemc.mods.lithium.common.util.Distances.distanceSq(center, poiRecord.getPos());
                if (distanceSq <= maxDistanceSq && poiPredicate.test(poiRecord)) {
                    consumer.accept(poiRecord);
                    if (--limit == 0) {
                        return;
                    }
                }
            }
        }
    }

    @Override
    public @Nullable PoiRecord lithium$getFirstMatchingPoint(
        final BlockPos pos,
        final long maxDistSq,
        final Predicate<Holder<PoiType>> typeFilter,
        final Predicate<BlockPos> posPredicate,
        final PoiManager.Occupancy status
    ) {
        if (typeFilter instanceof net.caffeinemc.mods.lithium.common.world.interests.iterator.SinglePointOfInterestTypeFilter singleTypeFilter) {
            return this.lithium$getFirstMatchingPoint(pos, maxDistSq, singleTypeFilter.getType(), posPredicate, status);
        } else {
            return this.lithium$getFirstMatchingPointDynamic(pos, maxDistSq, typeFilter, posPredicate, status);
        }
    }

    private @Nullable PoiRecord lithium$getFirstMatchingPoint(
        final BlockPos pos,
        final long maxDistSq,
        final Holder<PoiType> type,
        final Predicate<BlockPos> posPredicate,
        final PoiManager.Occupancy status
    ) {
        Set<PoiRecord> poiRecords = this.byType.get(type);
        if (poiRecords == null || poiRecords.isEmpty()) {
            return null;
        }

        Predicate<? super PoiRecord> statusPredicate = status.getTest();

        for (PoiRecord poiRecord : poiRecords) {
            long distanceSq = net.caffeinemc.mods.lithium.common.util.Distances.distanceSq(pos, poiRecord.getPos());
            if (distanceSq <= maxDistSq && posPredicate.test(poiRecord.getPos()) && statusPredicate.test(poiRecord)) {
                return poiRecord;
            }
        }

        return null;
    }

    private @Nullable PoiRecord lithium$getFirstMatchingPointDynamic(
        final BlockPos pos,
        final long maxDistSq,
        final Predicate<Holder<PoiType>> typeFilter,
        final Predicate<BlockPos> posPredicate,
        final PoiManager.Occupancy status
    ) {
        Predicate<? super PoiRecord> statusPredicate = status.getTest();

        for (Map.Entry<Holder<PoiType>, Set<PoiRecord>> entry : this.byType.entrySet()) {
            if (!typeFilter.test(entry.getKey()) || entry.getValue().isEmpty()) {
                continue;
            }

            for (PoiRecord poiRecord : entry.getValue()) {
                long distanceSq = net.caffeinemc.mods.lithium.common.util.Distances.distanceSq(pos, poiRecord.getPos());
                if (distanceSq <= maxDistSq && posPredicate.test(poiRecord.getPos()) && statusPredicate.test(poiRecord)) {
                    return poiRecord;
                }
            }
        }

        return null;
    }

    private void lithium$collectWithDynamicTypeFilter(
        final Predicate<Holder<PoiType>> typeFilter, final PoiManager.Occupancy status, final Consumer<PoiRecord> consumer
    ) {
        for (Map.Entry<Holder<PoiType>, Set<PoiRecord>> entry : this.byType.entrySet()) {
            if (!typeFilter.test(entry.getKey()) || entry.getValue().isEmpty()) {
                continue;
            }

            for (PoiRecord poi : entry.getValue()) {
                if (status.getTest().test(poi)) {
                    consumer.accept(poi);
                }
            }
        }
    }

    private void lithium$collectWithSingleTypeFilter(
        final Holder<PoiType> type, final PoiManager.Occupancy status, final Consumer<PoiRecord> consumer
    ) {
        Set<PoiRecord> entries = this.byType.get(type);
        if (entries == null || entries.isEmpty()) {
            return;
        }

        for (PoiRecord poi : entries) {
            if (status.getTest().test(poi)) {
                consumer.accept(poi);
            }
        }
    }

    @Override
    public @Nullable PoiRecord lithium$getAt(final BlockPos pos) {
        return this.getPoiRecord(pos).orElse(null);
    }

    @Override
    public java.util.Iterator<PoiRecord> lithium$iterate(final Predicate<Holder<PoiType>> typeFilter) {
        if (typeFilter instanceof net.caffeinemc.mods.lithium.common.world.interests.iterator.SinglePointOfInterestTypeFilter singleTypeFilter) {
            return this.lithium$iterateWithSingleTypeFilter(singleTypeFilter.getType());
        } else {
            return this.lithium$iterateWithDynamicTypeFilter(typeFilter);
        }
    }

    private java.util.Iterator<PoiRecord> lithium$iterateWithSingleTypeFilter(final Holder<PoiType> type) {
        Set<PoiRecord> entries = this.byType.get(type);
        if (entries == null || entries.isEmpty()) {
            return java.util.Collections.emptyIterator();
        }

        return entries.iterator();
    }

    private java.util.Iterator<PoiRecord> lithium$iterateWithDynamicTypeFilter(final Predicate<Holder<PoiType>> typeFilter) {
        java.util.Iterator<Map.Entry<Holder<PoiType>, Set<PoiRecord>>> entryIterator = this.byType.entrySet().iterator();
        return new com.google.common.collect.AbstractIterator<>() {
            private java.util.Iterator<PoiRecord> currentSetIterator = java.util.Collections.emptyIterator();

            @Override
            protected PoiRecord computeNext() {
                while (true) {
                    if (this.currentSetIterator.hasNext()) {
                        return this.currentSetIterator.next();
                    } else if (entryIterator.hasNext()) {
                        Map.Entry<Holder<PoiType>, Set<PoiRecord>> entry = entryIterator.next();
                        if (typeFilter.test(entry.getKey())) {
                            this.currentSetIterator = entry.getValue().iterator();
                        }
                    } else {
                        return this.endOfData();
                    }
                }
            }
        };
    }

    public PoiSection(final Runnable setDirty) {
        this(setDirty, true, ImmutableList.of());
    }

    private PoiSection(final Runnable setDirty, final boolean isValid, final List<PoiRecord> records) {
        this.setDirty = setDirty;
        this.isValid = isValid;
        records.forEach(this::add);
    }

    public PoiSection.Packed pack() {
        return new PoiSection.Packed(this.isValid, this.records.values().stream().map(PoiRecord::pack).toList());
    }

    public Stream<PoiRecord> getRecords(final Predicate<Holder<PoiType>> predicate, final PoiManager.Occupancy occupancy) {
        return this.byType.entrySet().stream().filter(e -> predicate.test(e.getKey())).flatMap(e -> e.getValue().stream()).filter(occupancy.getTest());
    }

    public @Nullable PoiRecord add(final BlockPos blockPos, final Holder<PoiType> type) {
        PoiRecord record = new PoiRecord(blockPos, type, this.setDirty);
        if (this.add(record)) {
            LOGGER.debug("Added POI of type {} @ {}", type.getRegisteredName(), blockPos);
            this.setDirty.run();
            return record;
        } else {
            return null;
        }
    }

    private boolean add(final PoiRecord record) {
        BlockPos blockPos = record.getPos();
        Holder<PoiType> poiType = record.getPoiType();
        short key = SectionPos.sectionRelativePos(blockPos);
        PoiRecord oldRecord = this.records.get(key);
        if (oldRecord != null) {
            if (poiType.equals(oldRecord.getPoiType())) {
                return false;
            }

            Util.logAndPauseIfInIde("POI data mismatch: already registered at " + blockPos);
        }

        this.records.put(key, record);
        this.byType.computeIfAbsent(poiType, k -> Sets.newHashSet()).add(record);
        return true;
    }

    public void remove(final BlockPos pos) {
        PoiRecord poiRecord = this.records.remove(SectionPos.sectionRelativePos(pos));
        if (poiRecord == null) {
            LOGGER.error("POI data mismatch: never registered at {}", pos);
        } else {
            this.byType.get(poiRecord.getPoiType()).remove(poiRecord);
            LOGGER.debug("Removed POI of type {} @ {}", LogUtils.defer(poiRecord::getPoiType), LogUtils.defer(poiRecord::getPos));
            this.setDirty.run();
        }
    }

    @Deprecated
    @VisibleForDebug
    public int getFreeTickets(final BlockPos pos) {
        return this.getPoiRecord(pos).map(PoiRecord::getFreeTickets).orElse(0);
    }

    public boolean release(final BlockPos pos) {
        PoiRecord record = this.records.get(SectionPos.sectionRelativePos(pos));
        if (record == null) {
            throw (IllegalStateException)Util.pauseInIde(new IllegalStateException("POI never registered at " + pos));
        }

        boolean success = record.releaseTicket();
        this.setDirty.run();
        return success;
    }

    public boolean exists(final BlockPos pos, final Predicate<Holder<PoiType>> predicate) {
        return this.getType(pos).filter(predicate).isPresent();
    }

    public Optional<Holder<PoiType>> getType(final BlockPos pos) {
        return this.getPoiRecord(pos).map(PoiRecord::getPoiType);
    }

    private Optional<PoiRecord> getPoiRecord(final BlockPos pos) {
        return Optional.ofNullable(this.records.get(SectionPos.sectionRelativePos(pos)));
    }

    public Optional<DebugPoiInfo> getDebugPoiInfo(final BlockPos pos) {
        return this.getPoiRecord(pos).map(DebugPoiInfo::new);
    }

    public void refresh(final Consumer<BiConsumer<BlockPos, Holder<PoiType>>> updater) {
        if (!this.isValid) {
            Short2ObjectMap<PoiRecord> oldRecords = new Short2ObjectOpenHashMap<>(this.records);
            this.clear();
            updater.accept((blockPos, poiType) -> {
                short key = SectionPos.sectionRelativePos(blockPos);
                PoiRecord newRecord = oldRecords.computeIfAbsent(key, k -> new PoiRecord(blockPos, poiType, this.setDirty));
                this.add(newRecord);
            });
            this.isValid = true;
            this.setDirty.run();
        }
    }

    private void clear() {
        this.records.clear();
        this.byType.clear();
    }

    public boolean isValid() {
        return this.isValid;
    }

    public record Packed(boolean isValid, List<PoiRecord.Packed> records) {
        public static final Codec<PoiSection.Packed> CODEC = RecordCodecBuilder.create(
            i -> i.group(
                    Codec.BOOL.lenientOptionalFieldOf("Valid", false).forGetter(PoiSection.Packed::isValid),
                    PoiRecord.Packed.CODEC.listOf().fieldOf("Records").forGetter(PoiSection.Packed::records)
                )
                .apply(i, PoiSection.Packed::new)
        );

        public PoiSection unpack(final Runnable setDirty) {
            return new PoiSection(setDirty, this.isValid, this.records.stream().map(record -> record.unpack(setDirty)).toList());
        }
    }
}