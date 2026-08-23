package net.minecraft.world.level.chunk.storage;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.OptionalDynamic;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

// MODIFIED for porting: implements lithium's RegionBasedStorageSectionExtended (ai.poi SectionStorageMixin), which
// tracks per chunk column which of its sections hold an entry, so POI lookups do not have to probe every section.
public class SectionStorage<R, P> implements AutoCloseable, net.caffeinemc.mods.lithium.common.world.interests.RegionBasedStorageSectionExtended<R> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SECTIONS_TAG = "Sections";
    private final SimpleRegionStorage simpleRegionStorage;
    // MODIFIED for porting: lithium ai.poi SectionStorageMixin replaces this map with a listening one
    // (@Mutable @Shadow @Final), so it lost its `final`.
    private Long2ObjectMap<Optional<R>> storage = new Long2ObjectOpenHashMap<>();
    // MODIFIED for porting: lithium ai.poi SectionStorageMixin @Unique field - for each chunk column, the set of
    // section indices that currently hold a present entry.
    private Long2ObjectOpenHashMap<java.util.BitSet> lithium$columns;
    private final LongLinkedOpenHashSet dirtyChunks = new LongLinkedOpenHashSet();
    private final Codec<P> codec;
    private final Function<R, P> packer;
    private final BiFunction<P, Runnable, R> unpacker;
    private final Function<Runnable, R> factory;
    private final RegistryAccess registryAccess;
    private final ChunkIOErrorReporter errorReporter;
    protected final LevelHeightAccessor levelHeightAccessor;
    private final LongSet loadedChunks = new LongOpenHashSet();
    private final Long2ObjectMap<CompletableFuture<Optional<SectionStorage.PackedChunk<P>>>> pendingLoads = new Long2ObjectOpenHashMap<>();
    private final Object loadLock = new Object();

    public SectionStorage(
        final SimpleRegionStorage simpleRegionStorage,
        final Codec<P> codec,
        final Function<R, P> packer,
        final BiFunction<P, Runnable, R> unpacker,
        final Function<Runnable, R> factory,
        final RegistryAccess registryAccess,
        final ChunkIOErrorReporter errorReporter,
        final LevelHeightAccessor levelHeightAccessor
    ) {
        this.simpleRegionStorage = simpleRegionStorage;
        this.codec = codec;
        this.packer = packer;
        this.unpacker = unpacker;
        this.factory = factory;
        this.registryAccess = registryAccess;
        this.errorReporter = errorReporter;
        this.levelHeightAccessor = levelHeightAccessor;
        // MODIFIED for porting: lithium ai.poi SectionStorageMixin#init (<init> RETURN)
        this.lithium$columns = new Long2ObjectOpenHashMap<>();
        this.storage = new net.caffeinemc.mods.lithium.common.util.collections.ListeningLong2ObjectOpenHashMap<>(this::lithium$onEntryAdded, this::lithium$onEntryRemoved);
    }

    // MODIFIED for porting: lithium ai.poi SectionStorageMixin#onEntryRemoved
    private void lithium$onEntryRemoved(final long key, final Optional<R> value) {
        int y = net.caffeinemc.mods.lithium.common.util.Pos.SectionYIndex.fromSectionCoord(this.levelHeightAccessor, SectionPos.y(key));
        // We only care about items belonging to a valid sub-chunk
        if (y < 0 || y >= net.caffeinemc.mods.lithium.common.util.Pos.SectionYIndex.getNumYSections(this.levelHeightAccessor)) {
            return;
        }

        long pos = ChunkPos.pack(SectionPos.x(key), SectionPos.z(key));
        java.util.BitSet flags = this.lithium$columns.get(pos);
        if (flags != null) {
            flags.clear(y);
            if (flags.isEmpty()) {
                this.lithium$columns.remove(pos);
            }
        }
    }

    // MODIFIED for porting: lithium ai.poi SectionStorageMixin#onEntryAdded
    private void lithium$onEntryAdded(final long key, final Optional<R> value) {
        int y = net.caffeinemc.mods.lithium.common.util.Pos.SectionYIndex.fromSectionCoord(this.levelHeightAccessor, SectionPos.y(key));
        // We only care about items belonging to a valid sub-chunk
        if (y < 0 || y >= net.caffeinemc.mods.lithium.common.util.Pos.SectionYIndex.getNumYSections(this.levelHeightAccessor)) {
            return;
        }

        long pos = ChunkPos.pack(SectionPos.x(key), SectionPos.z(key));
        java.util.BitSet flags = this.lithium$columns.get(pos);
        if (flags == null) {
            this.lithium$columns.put(pos, flags = new java.util.BitSet(net.caffeinemc.mods.lithium.common.util.Pos.SectionYIndex.getNumYSections(this.levelHeightAccessor)));
        }

        flags.set(y, value.isPresent());
    }

    // MODIFIED for porting: lithium ai.poi SectionStorageMixin#lithium$getFirstInRangeInChunkColumn
    @Override
    public <S, T, U> U lithium$getFirstInRangeInChunkColumn(
        final int chunkX,
        final int chunkZ,
        final long deltaYSqMargin,
        final net.minecraft.core.BlockPos center,
        final long radiusSq,
        final net.caffeinemc.mods.lithium.common.util.functions.FunLongAnd5<
            R,
            net.minecraft.core.BlockPos,
            java.util.function.Predicate<net.minecraft.core.Holder<S>>,
            java.util.function.Predicate<net.minecraft.core.BlockPos>,
            T,
            U
        > sectionMapper,
        final java.util.function.Predicate<net.minecraft.core.Holder<S>> predicate,
        final java.util.function.Predicate<net.minecraft.core.BlockPos> filter,
        final T status
    ) {
        java.util.BitSet sectionsWithPOI = this.lithium$getNonEmptyPOISections(chunkX, chunkZ);
        if (sectionsWithPOI.isEmpty()) {
            return null;
        }

        int minYSection = net.caffeinemc.mods.lithium.common.util.Pos.SectionYCoord.getMinYSection(this.levelHeightAccessor);

        for (int chunkYIndex = sectionsWithPOI.nextSetBit(0); chunkYIndex != -1; chunkYIndex = sectionsWithPOI.nextSetBit(chunkYIndex + 1)) {
            int chunkY = chunkYIndex + minYSection;
            long minYDistance = net.caffeinemc.mods.lithium.common.util.Distances.getClosestBlockCoordInSection(center.getY(), chunkY) - center.getY();
            if (minYDistance * minYDistance <= deltaYSqMargin) {
                R r = this.storage.get(SectionPos.asLong(chunkX, chunkY, chunkZ)).orElse(null);
                U result = sectionMapper.apply(r, center, predicate, filter, status, radiusSq);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    // MODIFIED for porting: lithium ai.poi SectionStorageMixin#lithium$getInChunkColumn - fast path for collecting all items
    // in a chunk column, avoiding a lookup per sub-chunk
    @Override
    public Iterable<R> lithium$getInChunkColumn(final int chunkX, final int chunkZ) {
        java.util.BitSet sectionsWithPOI = this.lithium$getNonEmptyPOISections(chunkX, chunkZ);
        // No items are present in this column
        if (sectionsWithPOI.isEmpty()) {
            return java.util.Collections::emptyIterator;
        }

        Long2ObjectMap<Optional<R>> loadedElements = this.storage;
        LevelHeightAccessor world = this.levelHeightAccessor;
        return () -> new com.google.common.collect.AbstractIterator<>() {
            private int nextBit = sectionsWithPOI.nextSetBit(0);

            @Override
            protected R computeNext() {
                // If the next bit is <0, that means that no remaining set bits exist
                while (this.nextBit >= 0) {
                    Optional<R> next = loadedElements.get(
                        SectionPos.asLong(chunkX, net.caffeinemc.mods.lithium.common.util.Pos.SectionYCoord.fromSectionIndex(world, this.nextBit), chunkZ)
                    );
                    // Find and advance to the next set bit
                    this.nextBit = sectionsWithPOI.nextSetBit(this.nextBit + 1);
                    if (next.isPresent()) {
                        return next.get();
                    }
                }

                return this.endOfData();
            }
        };
    }

    // MODIFIED for porting: lithium ai.poi SectionStorageMixin#lithium$getNonEmptyPOISections
    @Override
    public java.util.BitSet lithium$getNonEmptyPOISections(final int chunkX, final int chunkZ) {
        long pos = ChunkPos.pack(chunkX, chunkZ);
        java.util.BitSet flags = this.lithium$columns.get(pos);
        if (flags != null) {
            return flags;
        }

        this.unpackChunk(ChunkPos.unpack(pos));
        return java.util.Objects.requireNonNull(this.lithium$columns.get(pos), "Failed to load POI section data!");
    }

    // MODIFIED for porting: lithium ai.poi SectionStorageMixin#lithium$getElementAt
    @Override
    public Optional<R> lithium$getElementAt(final long sectionPos) {
        return this.storage.get(sectionPos);
    }

    // MODIFIED for porting: lithium ai.poi SectionStorageMixin#lithium$getChunkYMin
    @Override
    public int lithium$getChunkYMin() {
        return net.caffeinemc.mods.lithium.common.util.Pos.SectionYCoord.getMinYSection(this.levelHeightAccessor);
    }

    // MODIFIED for porting: lithium ai.poi SectionStorageMixin#lithium$getChunkYMaxInclusive
    @Override
    public int lithium$getChunkYMaxInclusive() {
        return net.caffeinemc.mods.lithium.common.util.Pos.SectionYCoord.getMaxYSectionInclusive(this.levelHeightAccessor);
    }

    protected void tick(final BooleanSupplier haveTime) {
        LongIterator iterator = this.dirtyChunks.iterator();

        while (iterator.hasNext() && haveTime.getAsBoolean()) {
            ChunkPos chunkPos = ChunkPos.unpack(iterator.nextLong());
            iterator.remove();
            this.writeChunk(chunkPos);
        }

        this.unpackPendingLoads();
    }

    private void unpackPendingLoads() {
        synchronized (this.loadLock) {
            Iterator<Entry<CompletableFuture<Optional<SectionStorage.PackedChunk<P>>>>> iterator = Long2ObjectMaps.fastIterator(this.pendingLoads);

            while (iterator.hasNext()) {
                Entry<CompletableFuture<Optional<SectionStorage.PackedChunk<P>>>> entry = iterator.next();
                Optional<SectionStorage.PackedChunk<P>> chunk = entry.getValue().getNow(null);
                if (chunk != null) {
                    long chunkKey = entry.getLongKey();
                    this.unpackChunk(ChunkPos.unpack(chunkKey), chunk.orElse(null));
                    iterator.remove();
                    this.loadedChunks.add(chunkKey);
                }
            }
        }
    }

    public void flushAll() {
        if (!this.dirtyChunks.isEmpty()) {
            this.dirtyChunks.forEach(pos -> this.writeChunk(ChunkPos.unpack(pos)));
            this.dirtyChunks.clear();
        }
    }

    public boolean hasWork() {
        return !this.dirtyChunks.isEmpty();
    }

    protected @Nullable Optional<R> get(final long sectionPos) {
        return this.storage.get(sectionPos);
    }

    public Optional<R> getOrLoad(final long sectionPos) { // MODIFIED for porting: lithium.accesswidener widened access
        if (this.outsideStoredRange(sectionPos)) {
            return Optional.empty();
        } else {
            Optional<R> r = this.get(sectionPos);
            if (r != null) {
                return r;
            } else {
                this.unpackChunk(SectionPos.of(sectionPos).chunk());
                r = this.get(sectionPos);
                if (r == null) {
                    throw (IllegalStateException)Util.pauseInIde(new IllegalStateException());
                } else {
                    return r;
                }
            }
        }
    }

    protected boolean outsideStoredRange(final long sectionPos) {
        int y = SectionPos.sectionToBlockCoord(SectionPos.y(sectionPos));
        return this.levelHeightAccessor.isOutsideBuildHeight(y);
    }

    protected R getOrCreate(final long sectionPos) {
        if (this.outsideStoredRange(sectionPos)) {
            throw (IllegalArgumentException)Util.pauseInIde(new IllegalArgumentException("sectionPos out of bounds"));
        }

        Optional<R> r = this.getOrLoad(sectionPos);
        if (r.isPresent()) {
            return r.get();
        }

        R newR = this.factory.apply(() -> this.setDirty(sectionPos));
        this.storage.put(sectionPos, Optional.of(newR));
        return newR;
    }

    public CompletableFuture<?> prefetch(final ChunkPos chunkPos) {
        synchronized (this.loadLock) {
            long chunkKey = chunkPos.pack();
            return this.loadedChunks.contains(chunkKey)
                ? CompletableFuture.completedFuture(null)
                : this.pendingLoads.computeIfAbsent(chunkKey, k -> this.tryRead(chunkPos));
        }
    }

    private void unpackChunk(final ChunkPos chunkPos) {
        long chunkKey = chunkPos.pack();
        CompletableFuture<Optional<SectionStorage.PackedChunk<P>>> future;
        synchronized (this.loadLock) {
            if (!this.loadedChunks.add(chunkKey)) {
                return;
            }

            future = this.pendingLoads.computeIfAbsent(chunkKey, k -> this.tryRead(chunkPos));
        }

        this.unpackChunk(chunkPos, future.join().orElse(null));
        synchronized (this.loadLock) {
            this.pendingLoads.remove(chunkKey);
        }
    }

    private CompletableFuture<Optional<SectionStorage.PackedChunk<P>>> tryRead(final ChunkPos chunkPos) {
        RegistryOps<Tag> registryOps = this.registryAccess.createSerializationContext(NbtOps.INSTANCE);
        return this.simpleRegionStorage
            .read(chunkPos)
            .thenApplyAsync(
                result -> result.map(tag -> SectionStorage.PackedChunk.parse(this.codec, registryOps, tag, this.simpleRegionStorage, this.levelHeightAccessor)),
                Util.backgroundExecutor().forName("parseSection")
            )
            .exceptionally(throwable -> {
                if (throwable instanceof CompletionException) {
                    throwable = throwable.getCause();
                }

                if (throwable instanceof IOException e) {
                    LOGGER.error("Error reading chunk {} data from disk", chunkPos, e);
                    this.errorReporter.reportChunkLoadFailure(e, this.simpleRegionStorage.storageInfo(), chunkPos);
                    return Optional.empty();
                } else {
                    throw new CompletionException(throwable);
                }
            });
    }

    private void unpackChunk(final ChunkPos pos, final SectionStorage.@Nullable PackedChunk<P> packedChunk) {
        if (packedChunk == null) {
            for (int sectionY = this.levelHeightAccessor.getMinSectionY(); sectionY <= this.levelHeightAccessor.getMaxSectionY(); sectionY++) {
                this.storage.put(getKey(pos, sectionY), Optional.empty());
            }
        } else {
            boolean versionChanged = packedChunk.versionChanged();

            for (int sectionY = this.levelHeightAccessor.getMinSectionY(); sectionY <= this.levelHeightAccessor.getMaxSectionY(); sectionY++) {
                long key = getKey(pos, sectionY);
                Optional<R> section = Optional.ofNullable(packedChunk.sectionsByY.get(sectionY))
                    .map(packed -> this.unpacker.apply((P)packed, () -> this.setDirty(key)));
                this.storage.put(key, section);
                section.ifPresent(s -> {
                    this.onSectionLoad(key);
                    if (versionChanged) {
                        this.setDirty(key);
                    }
                });
            }
        }
    }

    private void writeChunk(final ChunkPos chunkPos) {
        RegistryOps<Tag> registryOps = this.registryAccess.createSerializationContext(NbtOps.INSTANCE);
        Dynamic<Tag> tag = this.writeChunk(chunkPos, registryOps);
        Tag value = tag.getValue();
        if (value instanceof CompoundTag compoundTag) {
            this.simpleRegionStorage.write(chunkPos, compoundTag).exceptionally(throwable -> {
                this.errorReporter.reportChunkSaveFailure(throwable, this.simpleRegionStorage.storageInfo(), chunkPos);
                return null;
            });
        } else {
            LOGGER.error("Expected compound tag, got {}", value);
        }
    }

    private <T> Dynamic<T> writeChunk(final ChunkPos chunkPos, final DynamicOps<T> ops) {
        Map<T, T> sections = Maps.newHashMap();

        for (int sectionY = this.levelHeightAccessor.getMinSectionY(); sectionY <= this.levelHeightAccessor.getMaxSectionY(); sectionY++) {
            long key = getKey(chunkPos, sectionY);
            Optional<R> r = this.storage.get(key);
            if (r != null && !r.isEmpty()) {
                DataResult<T> serializedSection = this.codec.encodeStart(ops, this.packer.apply(r.get()));
                String yName = Integer.toString(sectionY);
                serializedSection.resultOrPartial(LOGGER::error).ifPresent(s -> sections.put(ops.createString(yName), (T)s));
            }
        }

        return new Dynamic<>(
            ops,
            ops.createMap(
                ImmutableMap.of(
                    ops.createString("Sections"),
                    ops.createMap(sections),
                    ops.createString("DataVersion"),
                    ops.createInt(SharedConstants.getCurrentVersion().dataVersion().version())
                )
            )
        );
    }

    private static long getKey(final ChunkPos chunkPos, final int sectionY) {
        return SectionPos.asLong(chunkPos.x(), sectionY, chunkPos.z());
    }

    protected void onSectionLoad(final long sectionPos) {
    }

    protected void setDirty(final long sectionPos) {
        Optional<R> r = this.storage.get(sectionPos);
        if (r != null && !r.isEmpty()) {
            this.dirtyChunks.add(ChunkPos.pack(SectionPos.x(sectionPos), SectionPos.z(sectionPos)));
        } else {
            LOGGER.warn("No data for position: {}", SectionPos.of(sectionPos));
        }
    }

    public void flush(final ChunkPos chunkPos) {
        if (this.dirtyChunks.remove(chunkPos.pack())) {
            this.writeChunk(chunkPos);
        }
    }

    @Override
    public void close() throws IOException {
        this.simpleRegionStorage.close();
    }

    private record PackedChunk<T>(Int2ObjectMap<T> sectionsByY, boolean versionChanged) {
        public static <T> SectionStorage.PackedChunk<T> parse(
            final Codec<T> codec,
            final DynamicOps<Tag> ops,
            final Tag tag,
            final SimpleRegionStorage simpleRegionStorage,
            final LevelHeightAccessor levelHeightAccessor
        ) {
            Dynamic<Tag> originalTag = new Dynamic<>(ops, tag);
            Dynamic<Tag> fixedTag = simpleRegionStorage.upgradeChunkTag(originalTag, 1945);
            boolean versionChanged = originalTag != fixedTag;
            OptionalDynamic<Tag> sections = fixedTag.get("Sections");
            Int2ObjectMap<T> sectionsByY = new Int2ObjectOpenHashMap<>();

            for (int sectionY = levelHeightAccessor.getMinSectionY(); sectionY <= levelHeightAccessor.getMaxSectionY(); sectionY++) {
                Optional<T> section = sections.get(Integer.toString(sectionY))
                    .result()
                    .flatMap(sectionData -> codec.parse((Dynamic<Tag>)sectionData).resultOrPartial(SectionStorage.LOGGER::error));
                if (section.isPresent()) {
                    sectionsByY.put(sectionY, section.get());
                }
            }

            return new SectionStorage.PackedChunk<>(sectionsByY, versionChanged);
        }
    }
}