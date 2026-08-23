package net.minecraft.world.entity.ai.village.poi;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.SectionTracker;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.debug.DebugPoiInfo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkIOErrorReporter;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.jspecify.annotations.Nullable;

// MODIFIED for porting: implements lithium's PointOfInterestStorageExtended (ai.poi PoiManagerMixin). The POI queries
// below are rewritten without streams and only visit sections that are inside the spherical search radius.
public class PoiManager extends SectionStorage<PoiSection, PoiSection.Packed> implements net.caffeinemc.mods.lithium.common.world.interests.PointOfInterestStorageExtended {
    public static final int MAX_VILLAGE_DISTANCE = 6;
    public static final int VILLAGE_SECTION_SIZE = 1;
    private final PoiManager.DistanceTracker distanceTracker;
    private final LongSet loadedChunks = new LongOpenHashSet();
    // MODIFIED for porting: lithium ai.poi.fast_portals PoiManagerMixin @Unique fields
    private final LongSet lithium$preloadedCenterChunks = new LongOpenHashSet();
    private int lithium$preloadRadius = 0;

    public PoiManager(
        final RegionStorageInfo info,
        final Path folder,
        final DataFixer fixerUpper,
        final boolean sync,
        final RegistryAccess registryAccess,
        final ChunkIOErrorReporter errorReporter,
        final LevelHeightAccessor levelHeightAccessor
    ) {
        super(
            new SimpleRegionStorage(info, folder, fixerUpper, sync, DataFixTypes.POI_CHUNK),
            PoiSection.Packed.CODEC,
            PoiSection::pack,
            PoiSection.Packed::unpack,
            PoiSection::new,
            registryAccess,
            errorReporter,
            levelHeightAccessor
        );
        this.distanceTracker = new PoiManager.DistanceTracker();
    }

    public @Nullable PoiRecord add(final BlockPos pos, final Holder<PoiType> type) {
        return this.getOrCreate(SectionPos.asLong(pos)).add(pos, type);
    }

    public void remove(final BlockPos pos) {
        this.getOrLoad(SectionPos.asLong(pos)).ifPresent(poiSection -> poiSection.remove(pos));
    }

    public long getCountInRange(final Predicate<Holder<PoiType>> predicate, final BlockPos center, final int radius, final PoiManager.Occupancy occupancy) {
        // MODIFIED for porting: lithium ai.poi PoiManagerMixin#getCountInRange (@Overwrite) - avoid stream-heavy code
        return this.lithium$withinSquareInL2Range(predicate, center, radius, occupancy).size();
    }

    public boolean existsAtPosition(final ResourceKey<PoiType> poiType, final BlockPos blockPos) {
        return this.exists(blockPos, p -> p.is(poiType));
    }

    public Stream<PoiRecord> getInSquare(
        final Predicate<Holder<PoiType>> predicate, final BlockPos center, final int radius, final PoiManager.Occupancy occupancy
    ) {
        int chunkRadius = Math.floorDiv(radius, 16) + 1;
        return ChunkPos.rangeClosed(ChunkPos.containing(center), chunkRadius).flatMap(pos -> this.getInChunk(predicate, pos, occupancy)).filter(record -> {
            BlockPos pos = record.getPos();
            return Math.abs(pos.getX() - center.getX()) <= radius && Math.abs(pos.getZ() - center.getZ()) <= radius;
        });
    }

    /**
     * MODIFIED for porting: lithium ai.poi PoiManagerMixin#getInRange (@Overwrite). Gets all POI in the sphere around center
     * with the given radius, ordered by {@code PoiOrdering.InSquare#INSTANCE}, using a spliterator that only visits chunk
     * sections which actually hold records.
     */
    public Stream<PoiRecord> getInRange(
        final Predicate<Holder<PoiType>> predicate, final BlockPos center, final int radius, final PoiManager.Occupancy occupancy
    ) {
        return java.util.stream.StreamSupport.stream(new net.caffeinemc.mods.lithium.common.world.interests.iterator.SphereChunkOrderedPoiSetSpliterator(radius, center, this, predicate, occupancy), false);
    }

    @VisibleForDebug
    public Stream<PoiRecord> getInChunk(final Predicate<Holder<PoiType>> predicate, final ChunkPos chunkPos, final PoiManager.Occupancy occupancy) {
        return IntStream.rangeClosed(this.levelHeightAccessor.getMinSectionY(), this.levelHeightAccessor.getMaxSectionY())
            .boxed()
            .map(sectionY -> this.getOrLoad(SectionPos.of(chunkPos, sectionY).asLong()))
            .filter(Optional::isPresent)
            .flatMap(poiSection -> poiSection.get().getRecords(predicate, occupancy));
    }

    public Stream<BlockPos> findAll(
        final Predicate<Holder<PoiType>> predicate,
        final Predicate<BlockPos> filter,
        final BlockPos center,
        final int radius,
        final PoiManager.Occupancy occupancy
    ) {
        return this.getInRange(predicate, center, radius, occupancy).map(PoiRecord::getPos).filter(filter);
    }

    public Stream<Pair<Holder<PoiType>, BlockPos>> findAllWithType(
        final Predicate<Holder<PoiType>> predicate,
        final Predicate<BlockPos> filter,
        final BlockPos center,
        final int radius,
        final PoiManager.Occupancy occupancy
    ) {
        return this.getInRange(predicate, center, radius, occupancy).filter(p -> filter.test(p.getPos())).map(p -> Pair.of(p.getPoiType(), p.getPos()));
    }

    public Stream<Pair<Holder<PoiType>, BlockPos>> findAllClosestFirstWithType(
        final Predicate<Holder<PoiType>> predicate,
        final Predicate<BlockPos> filter,
        final BlockPos center,
        final int radius,
        final PoiManager.Occupancy occupancy
    ) {
        return this.findAllWithType(predicate, filter, center, radius, occupancy).sorted(Comparator.comparingDouble(p -> p.getSecond().distSqr(center)));
    }

    /**
     * MODIFIED for porting: lithium ai.poi PoiManagerMixin#find (@Overwrite), by 2No2Name. Avoid stream code and avoid
     * searching sections outside the spherical radius. The returned element is the minimal element wrt.
     * {@code PoiOrdering.InSquare#INSTANCE} that is within the spherical radius.
     */
    public Optional<BlockPos> find(
        final Predicate<Holder<PoiType>> predicate,
        final Predicate<BlockPos> filter,
        final BlockPos center,
        final int radius,
        final PoiManager.Occupancy occupancy
    ) {
        long radiusSq = (long)radius * (long)radius;
        int minChunkX = center.getX() - radius - 1 >> 4;
        int maxChunkX = center.getX() + radius + 1 >> 4;
        int minChunkZ = center.getZ() - radius - 1 >> 4;
        int maxChunkZ = center.getZ() + radius + 1 >> 4;
        int chunkX = minChunkX;
        int chunkZ = minChunkZ;

        while (chunkZ <= maxChunkZ) {
            long minChunkToBlockDistanceL2Sq = net.caffeinemc.mods.lithium.common.util.Distances.getMinChunkToBlockDistanceL2Sq(center, chunkX, chunkZ);
            if (minChunkToBlockDistanceL2Sq <= radiusSq) {
                // dY² = distance² - dX² - dZ²
                long deltaYSqMargin = radiusSq - minChunkToBlockDistanceL2Sq;
                PoiRecord firstMatch = this.<PoiType, PoiManager.Occupancy, PoiRecord>lithium$getFirstInRangeInChunkColumn(
                    chunkX,
                    chunkZ,
                    deltaYSqMargin,
                    center,
                    radiusSq,
                    (poiSection, pos, typeFilter, posPredicate, occupancyStatus, maxDistSq) -> ((net.caffeinemc.mods.lithium.common.world.interests.PointOfInterestSetExtended)poiSection)
                        .lithium$getFirstMatchingPoint(pos, maxDistSq, typeFilter, posPredicate, occupancyStatus),
                    predicate,
                    filter,
                    occupancy
                );
                if (firstMatch != null) {
                    return Optional.of(firstMatch.getPos());
                }
            }

            chunkX++;
            if (chunkX > maxChunkX) {
                chunkZ++;
                chunkX = minChunkX;
            }
        }

        return Optional.empty();
    }

    /**
     * MODIFIED for porting: lithium ai.poi PoiManagerMixin#findClosest (@Overwrite), by 2No2Name. The returned element is the
     * minimal element wrt. {@code PoiOrdering.L2ThenInSquare#INSTANCE} that is within the spherical radius.
     */
    public Optional<BlockPos> findClosest(
        final Predicate<Holder<PoiType>> predicate, final BlockPos center, final int radius, final PoiManager.Occupancy occupancy
    ) {
        return this.findClosest(predicate, null, center, radius, occupancy);
    }

    /**
     * MODIFIED for porting: lithium ai.poi PoiManagerMixin#findClosestWithType (@Overwrite), by 2No2Name. Avoid stream code,
     * search in-range sections only and search closer sections first.
     */
    public Optional<Pair<Holder<PoiType>, BlockPos>> findClosestWithType(
        final Predicate<Holder<PoiType>> predicate, final BlockPos center, final int radius, final PoiManager.Occupancy occupancy
    ) {
        int radiusSq = radius * radius;
        PoiRecord closestPoi = new net.caffeinemc.mods.lithium.common.world.interests.iterator.NearbyPointOfInterestStream(
            predicate,
            occupancy,
            null,
            center,
            radius,
            this,
            (pos, pos2) -> net.caffeinemc.mods.lithium.common.util.Distances.isWithinSphereRadius(pos, radiusSq, pos2),
            net.caffeinemc.mods.lithium.common.world.interests.iterator.NearbyPointOfInterestStream.POINT_COMPARATOR
        ).getFirst();
        return closestPoi == null ? Optional.empty() : Optional.of(Pair.of(closestPoi.getPoiType(), closestPoi.getPos()));
    }

    /**
     * MODIFIED for porting: lithium ai.poi PoiManagerMixin#findClosest (@Overwrite), by 2No2Name. Avoid stream code and avoid
     * evaluating the (possibly expensive) position predicate unnecessarily. The returned element is the minimal element wrt.
     * {@code PoiOrdering.L2ThenInSquare#INSTANCE} that is within the spherical radius.
     */
    public Optional<BlockPos> findClosest(
        final Predicate<Holder<PoiType>> predicate,
        final @Nullable Predicate<BlockPos> filter,
        final BlockPos center,
        final int radius,
        final PoiManager.Occupancy occupancy
    ) {
        int radiusSq = radius * radius;
        PoiRecord closest = new net.caffeinemc.mods.lithium.common.world.interests.iterator.NearbyPointOfInterestStream(
            predicate,
            occupancy,
            filter == null ? null : poi -> filter.test(poi.getPos()),
            center,
            radius,
            this,
            (pos, pos2) -> net.caffeinemc.mods.lithium.common.util.Distances.isWithinSphereRadius(pos, radiusSq, pos2),
            net.caffeinemc.mods.lithium.common.world.interests.iterator.NearbyPointOfInterestStream.POINT_COMPARATOR
        ).getFirst();
        return closest == null ? Optional.empty() : Optional.of(closest.getPos());
    }

    public Optional<BlockPos> take(
        final Predicate<Holder<PoiType>> predicate, final BiPredicate<Holder<PoiType>, BlockPos> filter, final BlockPos center, final int radius
    ) {
        return this.getInRange(predicate, center, radius, PoiManager.Occupancy.HAS_SPACE)
            .filter(poi -> filter.test(poi.getPoiType(), poi.getPos()))
            .findFirst()
            .map(r -> {
                r.acquireTicket();
                return r.getPos();
            });
    }

    public Optional<BlockPos> getRandom(
        final Predicate<Holder<PoiType>> predicate,
        final Predicate<BlockPos> filter,
        final PoiManager.Occupancy occupancy,
        final BlockPos center,
        final int radius,
        final RandomSource random
    ) {
        // MODIFIED for porting: lithium ai.poi PoiManagerMixin#getRandom (@Overwrite) - retrieve all points of interest in
        // one operation and shuffle in place, consuming the shuffled prefix lazily. Like vanilla the random distribution is
        // uniform, but it does not return the same point as vanilla for the same pseudo-random seed.
        List<PoiRecord> list = this.lithium$withinSquareInL2Range(predicate, center, radius, occupancy);

        for (int i = list.size() - 1; i >= 0; i--) {
            // shuffle by swapping randomly
            PoiRecord currentPOI = list.set(random.nextInt(i + 1), list.get(i));
            // move to the end of the unconsumed part of the list
            list.set(i, currentPOI);
            // consume while shuffling, abort shuffling when a result was found
            if (filter.test(currentPOI.getPos())) {
                return Optional.of(currentPOI.getPos());
            }
        }

        return Optional.empty();
    }

    /**
     * MODIFIED for porting: lithium ai.poi PoiManagerMixin#lithium$getNClosestFirstWithType. Elements are minimal N wrt.
     * {@code PoiOrdering.L2ThenInSquare#INSTANCE}.
     */
    @Override
    public java.util.Collection<Pair<Holder<PoiType>, BlockPos>> lithium$getNClosestFirstWithType(
        final Predicate<Holder<PoiType>> typeFilter,
        final Predicate<BlockPos> posFilter,
        final BlockPos center,
        final int radius,
        final PoiManager.Occupancy status,
        final long n
    ) {
        int radiusSq = radius * radius;
        net.caffeinemc.mods.lithium.common.world.interests.iterator.NearbyPointOfInterestStream poisInRange = new net.caffeinemc.mods.lithium.common.world.interests.iterator.NearbyPointOfInterestStream(
            typeFilter,
            status,
            poiRecord -> posFilter.test(poiRecord.getPos()),
            center,
            radius,
            this,
            (pos, pos2) -> net.caffeinemc.mods.lithium.common.util.Distances.isWithinSphereRadius(pos, radiusSq, pos2),
            net.caffeinemc.mods.lithium.common.world.interests.iterator.NearbyPointOfInterestStream.POINT_COMPARATOR
        );
        java.util.ArrayList<Pair<Holder<PoiType>, BlockPos>> collectedPois = new java.util.ArrayList<>();

        for (int i = 0; i < n; i++) {
            if (!poisInRange.tryAdvance(poi -> collectedPois.add(Pair.of(poi.getPoiType(), poi.getPos())))) {
                break;
            }
        }

        return collectedPois;
    }

    // MODIFIED for porting: lithium ai.poi PoiManagerMixin#lithium$takeAt
    @Override
    public Optional<BlockPos> lithium$takeAt(
        final Predicate<Holder<PoiType>> typeFilter, final BiPredicate<Holder<PoiType>, BlockPos> biPredicate, final BlockPos blockPos
    ) {
        Optional<PoiSection> poiSection = this.getOrLoad(SectionPos.asLong(blockPos));
        if (poiSection.isPresent()) {
            PoiRecord poiRecord = ((net.caffeinemc.mods.lithium.common.world.interests.PointOfInterestSetExtended)poiSection.get()).lithium$getAt(blockPos);
            if (poiRecord != null && typeFilter.test(poiRecord.getPoiType())) {
                poiRecord.acquireTicket();
                return Optional.of(poiRecord.getPos());
            }
        }

        return Optional.empty();
    }

    // MODIFIED for porting: lithium ai.poi PoiManagerMixin#lithium$findNearestForPortalLogic
    @Override
    public Optional<PoiRecord> lithium$findNearestForPortalLogic(
        final BlockPos origin,
        final int radius,
        final Holder<PoiType> type,
        final PoiManager.Occupancy status,
        final Predicate<PoiRecord> afterSortPredicate,
        final net.minecraft.world.level.border.WorldBorder worldBorder
    ) {
        boolean worldBorderIsFarAway = worldBorder == null || worldBorder.getDistanceToBorder(origin.getX(), origin.getZ()) > radius + 3;
        Predicate<PoiRecord> poiPredicateAfterSorting = worldBorderIsFarAway
            ? afterSortPredicate
            : poi -> worldBorder.isWithinBounds(poi.getPos()) && afterSortPredicate.test(poi);
        Predicate<Holder<PoiType>> typePredicate = new net.caffeinemc.mods.lithium.common.world.interests.iterator.SinglePointOfInterestTypeFilter(type);
        PoiRecord nearestPoi = new net.caffeinemc.mods.lithium.common.world.interests.iterator.NearbyPointOfInterestStream(
            typePredicate,
            status,
            poiPredicateAfterSorting,
            origin,
            radius,
            this,
            (pos, pos2) -> net.caffeinemc.mods.lithium.common.util.Distances.isWithinCubeRadius(pos, radius, pos2),
            net.caffeinemc.mods.lithium.common.world.interests.iterator.NearbyPointOfInterestStream.NEGATIVE_Y_POINT_COMPARATOR
        ).getFirst();
        return nearestPoi == null ? Optional.empty() : Optional.of(nearestPoi);
    }

    // MODIFIED for porting: lithium ai.poi PoiManagerMixin#withinSquareInL2Range
    private java.util.ArrayList<PoiRecord> lithium$withinSquareInL2Range(
        final Predicate<Holder<PoiType>> predicate, final BlockPos origin, final int radius, final PoiManager.Occupancy status
    ) {
        int radiusSq = Math.multiplyExact(radius, radius);
        int minChunkX = origin.getX() - radius - 1 >> 4;
        int minChunkZ = origin.getZ() - radius - 1 >> 4;
        int maxChunkX = origin.getX() + radius + 1 >> 4;
        int maxChunkZ = origin.getZ() + radius + 1 >> 4;
        java.util.ArrayList<PoiRecord> points = new java.util.ArrayList<>();
        java.util.function.Consumer<PoiRecord> collector = point -> {
            if (net.caffeinemc.mods.lithium.common.util.Distances.isWithinSphereRadius(origin, radiusSq, point.getPos())) {
                points.add(point);
            }
        };

        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                for (PoiSection set : this.lithium$getInChunkColumn(x, z)) {
                    ((net.caffeinemc.mods.lithium.common.world.interests.PointOfInterestSetExtended)set).lithium$collectMatchingPoints(predicate, status, collector);
                }
            }
        }

        return points;
    }

    public boolean release(final BlockPos pos) {
        return this.getOrLoad(SectionPos.asLong(pos))
            .map(section -> section.release(pos))
            .orElseThrow(() -> Util.pauseInIde(new IllegalStateException("POI never registered at " + pos)));
    }

    public boolean exists(final BlockPos pos, final Predicate<Holder<PoiType>> predicate) {
        return this.getOrLoad(SectionPos.asLong(pos)).map(s -> s.exists(pos, predicate)).orElse(false);
    }

    public Optional<Holder<PoiType>> getType(final BlockPos pos) {
        return this.getOrLoad(SectionPos.asLong(pos)).flatMap(section -> section.getType(pos));
    }

    @VisibleForDebug
    public @Nullable DebugPoiInfo getDebugPoiInfo(final BlockPos pos) {
        return this.getOrLoad(SectionPos.asLong(pos)).flatMap(section -> section.getDebugPoiInfo(pos)).orElse(null);
    }

    public int sectionsToVillage(final SectionPos sectionPos) {
        this.distanceTracker.runAllUpdates();
        return this.distanceTracker.getLevel(sectionPos.asLong());
    }

    private boolean isVillageCenter(final long sectionPos) {
        Optional<PoiSection> section = this.get(sectionPos);
        return section == null
            ? false
            : section.<Boolean>map(s -> s.getRecords(e -> e.is(PoiTypeTags.VILLAGE), PoiManager.Occupancy.IS_OCCUPIED).findAny().isPresent()).orElse(false);
    }

    @Override
    public void tick(final BooleanSupplier haveTime) {
        super.tick(haveTime);
        this.distanceTracker.runAllUpdates();
    }

    @Override
    protected void setDirty(final long sectionPos) {
        super.setDirty(sectionPos);
        this.distanceTracker.update(sectionPos, this.distanceTracker.getLevelFromSource(sectionPos), false);
    }

    @Override
    protected void onSectionLoad(final long sectionPos) {
        this.distanceTracker.update(sectionPos, this.distanceTracker.getLevelFromSource(sectionPos), false);
    }

    public void checkConsistencyWithBlocks(final SectionPos sectionPos, final LevelChunkSection blockSection) {
        Util.ifElse(this.getOrLoad(sectionPos.asLong()), section -> section.refresh(output -> {
            if (mayHavePoi(blockSection)) {
                this.updateFromSection(blockSection, sectionPos, output);
            }
        }), () -> {
            if (mayHavePoi(blockSection)) {
                PoiSection newSection = this.getOrCreate(sectionPos.asLong());
                this.updateFromSection(blockSection, sectionPos, newSection::add);
            }
        });
    }

    private static boolean mayHavePoi(final LevelChunkSection blockSection) {
        return blockSection.maybeHas(PoiTypes::hasPoi);
    }

    private void updateFromSection(final LevelChunkSection blockSection, final SectionPos pos, final BiConsumer<BlockPos, Holder<PoiType>> output) {
        pos.blocksInside()
            .forEach(
                blockPos -> {
                    BlockState state = blockSection.getBlockState(
                        SectionPos.sectionRelative(blockPos.getX()), SectionPos.sectionRelative(blockPos.getY()), SectionPos.sectionRelative(blockPos.getZ())
                    );
                    PoiTypes.forState(state).ifPresent(type -> output.accept(blockPos, (Holder<PoiType>)type));
                }
            );
    }

    /**
     * MODIFIED for porting: lithium ai.poi.fast_portals PoiManagerMixin#ensureLoadedAndValid (@Overwrite), by Crec0,
     * 2No2Name and jcw780.
     * <p>
     * Streams in this method cause unnecessary lag; rewriting it without streams gains considerable performance, noticeable
     * when a large amount of entities travel through nether portals. Caching whether all surrounding chunks are loaded is
     * more efficient than caching the state of single chunks only.
     * <p>
     * For a chunk to be loaded, the chunk must have at least one section that either has no POI section or whose POI section
     * is not valid. Vanilla iterates sections by x, y and then z; in order to use the lithium column lookup, the loads are
     * sorted by the lowest y section then x in each z row.
     */
    public void ensureLoadedAndValid(final LevelReader reader, final BlockPos center, final int radius) {
        if (this.lithium$preloadRadius != radius) {
            // Usually there is only one preload radius per PoiManager. Just in case another mod adjusts it dynamically, we
            // avoid assuming its value.
            this.lithium$preloadedCenterChunks.clear();
            this.lithium$preloadRadius = radius;
        }

        long chunkPos = ChunkPos.pack(center);
        if (this.lithium$preloadedCenterChunks.contains(chunkPos)) {
            return;
        }

        int chunkX = SectionPos.blockToSectionCoord(center.getX());
        int chunkZ = SectionPos.blockToSectionCoord(center.getZ());
        int chunkRadius = Math.floorDiv(radius, 16);
        long[] sectionsYXPacked = new long[2 * chunkRadius + 1];
        int maxYSectionIndexExclusive = net.caffeinemc.mods.lithium.common.util.Pos.SectionYIndex.getMaxYSectionIndexExclusive(reader);

        for (int z = chunkZ - chunkRadius, zMax = chunkZ + chunkRadius; z <= zMax; z++) {
            int loadingChunkCounter = 0;

            for (int x = chunkX - chunkRadius, xMax = chunkX + chunkRadius; x <= xMax; x++) {
                int lowestSection = this.lithium$getLowestEmptyOrInvalidSection(reader, x, z);
                if (lowestSection < maxYSectionIndexExclusive && this.loadedChunks.add(ChunkPos.pack(x, z))) {
                    sectionsYXPacked[loadingChunkCounter++] = lithium$packYX(lowestSection, x);
                }
            }

            // Sort by signed Y, signed X as tie-break
            it.unimi.dsi.fastutil.longs.LongArrays.quickSort(sectionsYXPacked, 0, loadingChunkCounter);

            for (int chunkIndex = 0; chunkIndex < loadingChunkCounter; chunkIndex++) {
                reader.getChunk(lithium$unpackX(sectionsYXPacked[chunkIndex]), z, ChunkStatus.EMPTY);
            }
        }

        this.lithium$preloadedCenterChunks.add(chunkPos);
    }

    // MODIFIED for porting: lithium ai.poi.fast_portals PoiManagerMixin#unpackX
    private static int lithium$unpackX(final long packedYX) {
        return (int)((packedYX & 0xFFFFFFFFL) + Integer.MIN_VALUE);
    }

    /**
     * MODIFIED for porting: lithium ai.poi.fast_portals PoiManagerMixin#packYX. Pack YX for sorting, two's complement
     * conversion applied for sorting by signed X.
     */
    private static long lithium$packYX(final long y, final long x) {
        return y << 32 | x - Integer.MIN_VALUE;
    }

    // MODIFIED for porting: lithium ai.poi.fast_portals PoiManagerMixin#lithium$getLowestEmptyOrInvalidSection
    private int lithium$getLowestEmptyOrInvalidSection(final LevelReader reader, final int x, final int z) {
        java.util.BitSet column = this.lithium$getNonEmptyPOISections(x, z);
        int lowestUnsetSection = column.nextClearBit(0);
        int setSectionIndex = -1;

        while ((setSectionIndex = column.nextSetBit(setSectionIndex + 1)) != -1 && setSectionIndex < lowestUnsetSection) {
            Optional<PoiSection> section = this.lithium$getElementAt(
                SectionPos.asLong(x, net.caffeinemc.mods.lithium.common.util.Pos.SectionYCoord.fromSectionIndex(reader, setSectionIndex), z)
            );
            if (section.isPresent() && !section.get().isValid()) {
                return setSectionIndex;
            }
        }

        return lowestUnsetSection;
    }

    private final class DistanceTracker extends SectionTracker {
        private final Long2ByteMap levels = new Long2ByteOpenHashMap();

        DistanceTracker() {
            super(7, 16, 256);
            this.levels.defaultReturnValue((byte)7);
        }

        @Override
        protected int getLevelFromSource(final long to) {
            return PoiManager.this.isVillageCenter(to) ? 0 : 7;
        }

        @Override
        protected int getLevel(final long node) {
            return this.levels.get(node);
        }

        @Override
        protected void setLevel(final long node, final int level) {
            if (level > 6) {
                this.levels.remove(node);
            } else {
                this.levels.put(node, (byte)level);
            }
        }

        public void runAllUpdates() {
            super.runUpdates(Integer.MAX_VALUE);
        }
    }

    public enum Occupancy {
        HAS_SPACE(PoiRecord::hasSpace),
        IS_OCCUPIED(PoiRecord::isOccupied),
        ANY(poiRecord -> true);

        private final Predicate<? super PoiRecord> test;

        Occupancy(final Predicate<? super PoiRecord> test) {
            this.test = test;
        }

        public Predicate<? super PoiRecord> getTest() {
            return this.test;
        }
    }
}