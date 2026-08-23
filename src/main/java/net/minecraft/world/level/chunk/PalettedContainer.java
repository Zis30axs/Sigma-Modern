package net.minecraft.world.level.chunk;

import com.google.common.annotations.VisibleForTesting;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.LongStream;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.BitStorage;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ThreadingDetector;
import net.minecraft.util.ZeroBitStorage;
import org.jspecify.annotations.Nullable;

// MODIFIED for porting: implements sodium's PalettedContainerROExtension (core.world.chunk PalettedContainerMixin), which
// lets sodium copy a whole container into a flat array in one pass while cloning a chunk section.
public class PalettedContainer<T> implements PaletteResize<T>, PalettedContainerRO<T>,
    net.caffeinemc.mods.sodium.client.world.PalettedContainerROExtension<T> {
    // MODIFIED for porting: everything in this block was sodium's core.world.chunk PalettedContainerMixin
    @Override
    public void sodium$unpack(final T[] values) {
        Strategy<T> strategy = java.util.Objects.requireNonNull(this.strategy);
        if (values.length != strategy.entryCount()) {
            throw new IllegalArgumentException("Array is wrong size");
        }

        PalettedContainer.Data<T> data = java.util.Objects.requireNonNull(this.data, "PalettedContainer must have data");
        ((net.caffeinemc.mods.sodium.client.world.BitStorageExtension)data.storage()).sodium$unpack(values, data.palette());
    }

    @Override
    public void sodium$unpack(final T[] values, final int minX, final int minY, final int minZ, final int maxX, final int maxY, final int maxZ) {
        Strategy<T> strategy = java.util.Objects.requireNonNull(this.strategy);
        if (values.length != strategy.entryCount()) {
            throw new IllegalArgumentException("Array is wrong size");
        }

        PalettedContainer.Data<T> data = java.util.Objects.requireNonNull(this.data, "PalettedContainer must have data");
        net.minecraft.util.BitStorage storage = data.storage();
        Palette<T> palette = data.palette();

        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    int localBlockIndex = strategy.getIndex(x, y, z);
                    values[localBlockIndex] = palette.valueFor(storage.get(localBlockIndex));
                }
            }
        }
    }

    @Override
    public PalettedContainerRO<T> sodium$copy() {
        return this.copy();
    }

    private static final int MIN_PALETTE_BITS = 0;
    private volatile PalettedContainer.Data<T> data;
    private final Strategy<T> strategy;
    /**
     * MODIFIED for porting: lithium chunk.no_locking PalettedContainerMixin removes the concurrent-modification detection.
     * In practice it never triggers - it is a left-over from the development of off-thread chunk generation - while the
     * locking itself is measurable during world generation. Upstream nulls the field in an @Inject at the RETURN of every
     * constructor; initializing it to null here is the same thing.
     * (This is also why FerriteCore's `useSmallThreadingDetector` module is not ported: upstream disables it whenever
     * Lithium is present, exactly because of this change.)
     */
    private final ThreadingDetector threadingDetector = null;

    // MODIFIED for porting: lithium chunk.no_locking PalettedContainerMixin - do not check the container's lock
    public void acquire() {
    }

    // MODIFIED for porting: lithium chunk.no_locking PalettedContainerMixin - do not check the container's lock
    public void release() {
    }

    public static <T> Codec<PalettedContainer<T>> codecRW(final Codec<T> elementCodec, final Strategy<T> strategy, final T defaultValue) {
        PalettedContainerRO.Unpacker<T, PalettedContainer<T>> unpacker = PalettedContainer::unpack;
        return codec(elementCodec, strategy, defaultValue, unpacker);
    }

    public static <T> Codec<PalettedContainerRO<T>> codecRO(final Codec<T> elementCodec, final Strategy<T> strategy, final T defaultValue) {
        PalettedContainerRO.Unpacker<T, PalettedContainerRO<T>> unpacker = (s, data) -> unpack(s, data).map(e -> (PalettedContainerRO<T>)e);
        return codec(elementCodec, strategy, defaultValue, unpacker);
    }

    private static <T, C extends PalettedContainerRO<T>> Codec<C> codec(
        final Codec<T> elementCodec, final Strategy<T> strategy, final T defaultValue, final PalettedContainerRO.Unpacker<T, C> unpacker
    ) {
        return RecordCodecBuilder.<PalettedContainerRO.PackedData>create(
                i -> i.group(
                        elementCodec.mapResult(ExtraCodecs.orElsePartial(defaultValue))
                            .listOf()
                            .fieldOf("palette")
                            .forGetter(PalettedContainerRO.PackedData::paletteEntries),
                        Codec.LONG_STREAM.lenientOptionalFieldOf("data").forGetter(PalettedContainerRO.PackedData::storage)
                    )
                    .apply(i, PalettedContainerRO.PackedData::new)
            )
            .comapFlatMap(
                discData -> unpacker.read(strategy, (PalettedContainerRO.PackedData<T>)discData), palettedContainer -> palettedContainer.pack(strategy)
            );
    }

    private PalettedContainer(final Strategy<T> strategy, final Configuration dataConfiguration, final BitStorage storage, final Palette<T> palette) {
        this.strategy = strategy;
        this.data = new PalettedContainer.Data<>(dataConfiguration, storage, palette);
    }

    private PalettedContainer(final PalettedContainer<T> source) {
        this.strategy = source.strategy;
        this.data = source.data.copy();
    }

    public PalettedContainer(final T initialValue, final Strategy<T> strategy) {
        this.strategy = strategy;
        this.data = this.createOrReuseData(null, 0);
        this.data.palette.idFor(initialValue, this);
    }

    private PalettedContainer.Data<T> createOrReuseData(final PalettedContainer.@Nullable Data<T> oldData, final int targetBits) {
        Configuration dataConfiguration = this.strategy.getConfigurationForBitCount(targetBits);
        if (oldData != null && dataConfiguration.equals(oldData.configuration())) {
            return oldData;
        }

        BitStorage storage = dataConfiguration.bitsInMemory() == 0
            ? new ZeroBitStorage(this.strategy.entryCount())
            : new SimpleBitStorage(dataConfiguration.bitsInMemory(), this.strategy.entryCount());
        Palette<T> palette = dataConfiguration.createPalette(this.strategy, List.of());
        return new PalettedContainer.Data<>(dataConfiguration, storage, palette);
    }

    @Override
    public int onResize(final int bits, final T lastAddedValue) {
        PalettedContainer.Data<T> oldData = this.data;
        PalettedContainer.Data<T> newData = this.createOrReuseData(oldData, bits);
        newData.copyFrom(oldData.palette, oldData.storage);
        this.data = newData;
        return newData.palette.idFor(lastAddedValue, PaletteResize.noResizeExpected());
    }

    public T getAndSet(final int x, final int y, final int z, final T value) {
        this.acquire();

        try {
            return this.getAndSet(this.strategy.getIndex(x, y, z), value);
        } finally {
            this.release();
        }
    }

    public T getAndSetUnchecked(final int x, final int y, final int z, final T value) {
        return this.getAndSet(this.strategy.getIndex(x, y, z), value);
    }

    private T getAndSet(final int index, final T value) {
        int id = this.data.palette.idFor(value, this);
        int oldId = this.data.storage.getAndSet(index, id);
        return this.data.palette.valueFor(oldId);
    }

    public void set(final int x, final int y, final int z, final T value) {
        this.acquire();

        try {
            this.set(this.strategy.getIndex(x, y, z), value);
        } finally {
            this.release();
        }
    }

    private void set(final int index, final T value) {
        int id = this.data.palette.idFor(value, this);
        this.data.storage.set(index, id);
    }

    @Override
    public T get(final int x, final int y, final int z) {
        return this.get(this.strategy.getIndex(x, y, z));
    }

    protected T get(final int index) {
        PalettedContainer.Data<T> data = this.data;
        return data.palette.valueFor(data.storage.get(index));
    }

    @Override
    public void getAll(final Consumer<T> consumer) {
        Palette<T> palette = this.data.palette();
        IntSet allExistingEntries = new IntArraySet();
        this.data.storage.getAll(allExistingEntries::add);
        allExistingEntries.forEach(state -> consumer.accept(palette.valueFor(state)));
    }

    public void read(final FriendlyByteBuf buffer) {
        this.acquire();

        try {
            int newBits = buffer.readByte();
            PalettedContainer.Data<T> newData = this.createOrReuseData(this.data, newBits);
            newData.palette.read(buffer, this.strategy.globalMap());
            buffer.readFixedSizeLongArray(newData.storage.getRaw());
            this.data = newData;
        } finally {
            this.release();
        }
    }

    @Override
    public void write(final FriendlyByteBuf buffer) {
        this.acquire();

        try {
            this.data.write(buffer, this.strategy.globalMap());
        } finally {
            this.release();
        }
    }

    @VisibleForTesting
    public static <T> DataResult<PalettedContainer<T>> unpack(final Strategy<T> strategy, final PalettedContainerRO.PackedData<T> discData) {
        List<T> paletteEntries = discData.paletteEntries();
        int entryCount = strategy.entryCount();
        Configuration storedConfiguration = strategy.getConfigurationForPaletteSize(paletteEntries.size());
        int bitsOnDisc = storedConfiguration.bitsInStorage();
        if (discData.bitsPerEntry() != -1 && bitsOnDisc != discData.bitsPerEntry()) {
            return DataResult.error(() -> "Invalid bit count, calculated " + bitsOnDisc + ", but container declared " + discData.bitsPerEntry());
        }

        BitStorage storage;
        Palette<T> palette;
        if (storedConfiguration.bitsInMemory() == 0) {
            palette = storedConfiguration.createPalette(strategy, paletteEntries);
            storage = new ZeroBitStorage(entryCount);
        } else {
            Optional<LongStream> dataOpt = discData.storage();
            if (dataOpt.isEmpty()) {
                return DataResult.error(() -> "Missing values for non-zero storage");
            }

            long[] data = dataOpt.get().toArray();

            try {
                if (!storedConfiguration.alwaysRepack() && storedConfiguration.bitsInMemory() == bitsOnDisc) {
                    palette = storedConfiguration.createPalette(strategy, paletteEntries);
                    storage = new SimpleBitStorage(storedConfiguration.bitsInMemory(), entryCount, data);
                } else {
                    Palette<T> oldPalette = new HashMapPalette<>(bitsOnDisc, paletteEntries);
                    SimpleBitStorage oldStorage = new SimpleBitStorage(bitsOnDisc, entryCount, data);
                    Palette<T> newPalette = storedConfiguration.createPalette(strategy, paletteEntries);
                    int[] newContents = reencodeContents(oldStorage, oldPalette, newPalette);
                    palette = newPalette;
                    storage = new SimpleBitStorage(storedConfiguration.bitsInMemory(), entryCount, newContents);
                }
            } catch (SimpleBitStorage.InitializationException exception) {
                return DataResult.error(() -> "Failed to read PalettedContainer: " + exception.getMessage());
            }
        }

        return DataResult.success(new PalettedContainer<>(strategy, storedConfiguration, storage, palette));
    }

    /**
     * MODIFIED for porting: lithium chunk.serialization PalettedContainerMixin#pack. NBT serialization happens on the main
     * server thread, so this is worth optimizing:
     * <ul>
     *   <li>a palette with a single entry (or a zero-bit storage) is not repacked at all;</li>
     *   <li>the packed integer array is walked by a specialized routine instead of unpack()-ing into an int[];</li>
     *   <li>a thread-local scratch array caches the palette lookups/remaps while compacting;</li>
     *   <li>if the palette did not change during compaction, the raw long[] is simply cloned.</li>
     * </ul>
     * Note that upstream builds the result with the two-argument PackedData constructor, i.e. it reports
     * {@link PalettedContainerRO.PackedData#UNKNOWN_BITS_PER_ENTRY}. That only skips the optional bit-count cross-check
     * that {@link #unpack} performs when reading; the serialized data itself is unchanged.
     */
    @Override
    public PalettedContainerRO.PackedData<T> pack(final Strategy<T> strategy) {
        this.acquire();

        // The palette that will be serialized
        net.caffeinemc.mods.lithium.common.world.chunk.LithiumHashPalette<T> hashPalette = null;
        Optional<LongStream> packedStorage = Optional.empty();
        List<T> elements = null;
        Palette<T> palette = this.data.palette();
        BitStorage storage = this.data.storage();
        if (storage instanceof ZeroBitStorage || palette.getSize() == 1) {
            // If the palette only contains one entry, don't attempt to repack it.
            elements = List.of(palette.valueFor(0));
        } else if (palette instanceof net.caffeinemc.mods.lithium.common.world.chunk.LithiumHashPalette<T> lithiumHashPalette) {
            hashPalette = lithiumHashPalette;
        }

        if (elements == null) {
            net.caffeinemc.mods.lithium.common.world.chunk.LithiumHashPalette<T> compactedPalette = new net.caffeinemc.mods.lithium.common.world.chunk.LithiumHashPalette<>(storage.getBits());
            short[] array = lithium$getOrCreateScratchArray(strategy.entryCount());
            ((net.caffeinemc.mods.lithium.common.world.chunk.CompactingPackedIntegerArray)storage).lithium$compact(this.data.palette(), compactedPalette, array);
            Configuration origConfig;
            // paletteSize can de-sync from the palette - see CaffeineMC/lithium-fabric#279
            if (hashPalette != null
                && hashPalette.getSize() == compactedPalette.getSize()
                && !(origConfig = strategy.getConfigurationForPaletteSize(hashPalette.getSize())).alwaysRepack()
                && storage.getBits() == origConfig.bitsInStorage()) {
                packedStorage = Optional.of(Arrays.stream(storage.getRaw().clone()));
                elements = hashPalette.getElements();
            } else {
                int bits = strategy.getConfigurationForPaletteSize(compactedPalette.getSize()).bitsInStorage();
                if (bits != 0) {
                    // Re-pack the integer array as the palette has changed size
                    SimpleBitStorage copy = new SimpleBitStorage(bits, array.length);

                    for (int i = 0; i < array.length; i++) {
                        copy.set(i, array[i]);
                    }

                    // We don't need to clone the data array as we are the sole owner of it
                    packedStorage = Optional.of(Arrays.stream(copy.getRaw()));
                }

                elements = compactedPalette.getElements();
            }
        }

        this.release();
        return new PalettedContainerRO.PackedData<>(elements, packedStorage);
    }

    // MODIFIED for porting: lithium chunk.serialization PalettedContainerMixin scratch arrays
    private static final ThreadLocal<short[]> LITHIUM_CACHED_ARRAY_4096 = ThreadLocal.withInitial(() -> new short[4096]);
    private static final ThreadLocal<short[]> LITHIUM_CACHED_ARRAY_64 = ThreadLocal.withInitial(() -> new short[64]);

    private static short[] lithium$getOrCreateScratchArray(final int size) {
        return switch (size) {
            case 64 -> LITHIUM_CACHED_ARRAY_64.get();
            case 4096 -> LITHIUM_CACHED_ARRAY_4096.get();
            default -> new short[size];
        };
    }

    private static <T> int[] reencodeContents(final BitStorage storage, final Palette<T> oldPalette, final Palette<T> newPalette) {
        int[] buffer = new int[storage.getSize()];
        storage.unpack(buffer);
        PaletteResize<T> dummyResizer = PaletteResize.noResizeExpected();
        int lastReadId = -1;
        int lastWrittenId = -1;

        for (int index = 0; index < buffer.length; index++) {
            int id = buffer[index];
            if (id != lastReadId) {
                lastReadId = id;
                lastWrittenId = newPalette.idFor(oldPalette.valueFor(id), dummyResizer);
            }

            buffer[index] = lastWrittenId;
        }

        return buffer;
    }

    @Override
    public int getSerializedSize() {
        return this.data.getSerializedSize(this.strategy.globalMap());
    }

    @Override
    public int bitsPerEntry() {
        return this.data.storage().getBits();
    }

    @Override
    public boolean maybeHas(final Predicate<T> predicate) {
        return this.data.palette.maybeHas(predicate);
    }

    @Override
    public void forEachInPalette(final Consumer<T> consumer) {
        for (int i = 0; i < this.data.palette.getSize(); i++) {
            consumer.accept(this.data.palette.valueFor(i));
        }
    }

    @Override
    public PalettedContainer<T> copy() {
        return new PalettedContainer<>(this);
    }

    @Override
    public PalettedContainer<T> recreate() {
        return new PalettedContainer<>(this.data.palette.valueFor(0), this.strategy);
    }

    @Override
    public void count(final PalettedContainer.CountConsumer<T> output) {
        if (this.data.palette.getSize() == 1) {
            // MODIFIED for porting: lithium world.chunk_ticking.random_block_ticking
            // PalettedContainerMixin#handleWholeSectionSingleBlock (WrapOperation around CountConsumer#accept)
            if (output instanceof net.caffeinemc.mods.lithium.common.world.section.RandomTickingSectionDataHelper.LithiumRandomTickingBlockCounter lithium$counter) {
                lithium$counter.lithium$wholeSectionSingleBlock(this.data.palette.valueFor(0), this.data.storage.getSize());
            }

            output.accept(this.data.palette.valueFor(0), this.data.storage.getSize());
        } else {
            // MODIFIED for porting: lithium chunk.serialization PalettedContainerMixin (#returnDummyInstance,
            // #getFastCountingLambda, #fastForEachAndCancelMethod). When the palette is bounded and not huge, count into a
            // plain short[] indexed by palette id instead of an Int2IntOpenHashMap.
            int lithium$paletteSize = this.data.palette().getSize();
            final Int2IntOpenHashMap counts = lithium$paletteSize > 4096 ? new Int2IntOpenHashMap() : null;
            final short[] lithium$countsArray = counts == null ? new short[lithium$paletteSize] : null;
            final java.util.function.IntConsumer lithium$original = counts != null
                ? state -> counts.addTo(state, 1)
                : id -> lithium$countsArray[id]++;
            java.util.function.IntConsumer lithium$consumer = lithium$original;
            // MODIFIED for porting: lithium world.chunk_ticking.random_block_ticking
            // PalettedContainerMixin#initializeRandomTickExtraData (ModifyArg on BitStorage#getAll). The wrapper reports the
            // end of every 248-block "minisection" so the per-minisection random-ticking counts can be built while the
            // container is being counted anyway. Upstream applies the serialization mixin first (priority 50), so this
            // wrapper sits outside the counting consumer above and receives the shared counts array through @Share.
            if (output instanceof net.caffeinemc.mods.lithium.common.world.section.RandomTickingSectionDataHelper.LithiumRandomTickingBlockCounter lithium$counter) {
                final Palette<T> lithium$palette = this.data.palette();
                lithium$consumer = new java.util.function.IntConsumer() {
                    private int index = 0;

                    @Override
                    public void accept(final int value) {
                        lithium$original.accept(value);
                        this.index++;
                        if (this.index % net.caffeinemc.mods.lithium.common.world.section.RandomTickingSectionDataHelper.MINISECTION_SIZE == 0 || this.index == 4096) {
                            lithium$counter.lithium$finishedCountingMinisection(
                                counts, lithium$countsArray, (Palette<net.minecraft.world.level.block.state.BlockState>)lithium$palette
                            );
                        }
                    }
                };
            }

            this.data.storage.getAll(lithium$consumer);
            if (counts != null) {
                counts.int2IntEntrySet().forEach(entry -> output.accept(this.data.palette.valueFor(entry.getIntKey()), entry.getIntValue()));
            } else {
                for (int i = 0; i < lithium$countsArray.length; i++) {
                    output.accept(this.data.palette.valueFor(i), lithium$countsArray[i]);
                }
            }
        }
    }

    @FunctionalInterface
    public interface CountConsumer<T> {
        void accept(final T entry, final int count);
    }

    public record Data<T>(Configuration configuration, BitStorage storage, Palette<T> palette) { // MODIFIED for porting: lithium.accesswidener made this class accessible
        public void copyFrom(final Palette<T> oldPalette, final BitStorage oldStorage) {
            PaletteResize<T> dummyResizer = PaletteResize.noResizeExpected();

            for (int i = 0; i < oldStorage.getSize(); i++) {
                T value = oldPalette.valueFor(oldStorage.get(i));
                this.storage.set(i, this.palette.idFor(value, dummyResizer));
            }
        }

        public int getSerializedSize(final IdMap<T> globalMap) {
            return 1 + this.palette.getSerializedSize(globalMap) + this.storage.getRaw().length * 8;
        }

        public void write(final FriendlyByteBuf buffer, final IdMap<T> globalMap) {
            buffer.writeByte(this.storage.getBits());
            this.palette.write(buffer, globalMap);
            buffer.writeFixedSizeLongArray(this.storage.getRaw());
        }

        public PalettedContainer.Data<T> copy() {
            return new PalettedContainer.Data<>(this.configuration, this.storage.copy(), this.palette.copy());
        }
    }
}