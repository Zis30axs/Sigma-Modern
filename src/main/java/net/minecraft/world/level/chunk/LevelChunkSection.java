package net.minecraft.world.level.chunk;

import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

// MODIFIED for porting: lithium util.section_data_storage + util.block_tracking (LevelChunkSectionMixin). Lithium keeps
// per-section counters of how many blocks match a set of predicates (over-sized collision shape, water, lava, random
// ticking, ...) so that collision / ticking code can skip whole sections, and lets trackers listen for block changes.
public class LevelChunkSection
    implements net.caffeinemc.mods.lithium.common.world.section.LithiumSectionData,
    net.caffeinemc.mods.lithium.common.block.BlockCountingSection,
    net.caffeinemc.mods.lithium.common.block.BlockListeningSection {
    public static final int BIOME_CONTAINER_BITS = 2;
    private short nonEmptyBlockCount;
    private short fluidCount;
    private short tickingBlockCount;
    private short tickingFluidCount;
    private final PalettedContainer<BlockState> states;
    private PalettedContainerRO<Holder<Biome>> biomes;
    // MODIFIED for porting: lithium util.section_data_storage LevelChunkSectionMixin @Unique field
    private net.caffeinemc.mods.lithium.common.world.section.LithiumSectionData.SectionData lithium$sectionData;

    // MODIFIED for porting: lithium util.section_data_storage LevelChunkSectionMixin
    @Override
    public net.caffeinemc.mods.lithium.common.world.section.LithiumSectionData.SectionData lithium$getSectionData() {
        if (this.lithium$sectionData == null) {
            this.lithium$sectionData = new net.caffeinemc.mods.lithium.common.world.section.LithiumSectionData.SectionData(this);
        }
        return this.lithium$sectionData;
    }

    // MODIFIED for porting: lithium util.section_data_storage LevelChunkSectionMixin
    @Override
    public net.caffeinemc.mods.lithium.common.world.section.LithiumSectionData.SectionData lithium$getSectionDataDirect() {
        if (this.lithium$sectionData == null) {
            throw new NullPointerException("SectionData has not been created yet!");
        }
        return this.lithium$sectionData;
    }

    private LevelChunkSection(final LevelChunkSection source) {
        this.nonEmptyBlockCount = source.nonEmptyBlockCount;
        this.fluidCount = source.fluidCount;
        this.tickingBlockCount = source.tickingBlockCount;
        this.tickingFluidCount = source.tickingFluidCount;
        this.states = source.states.copy();
        this.biomes = source.biomes.copy();
    }

    public LevelChunkSection(final PalettedContainer<BlockState> states, final PalettedContainerRO<Holder<Biome>> biomes) {
        this.states = states;
        this.biomes = biomes;
        this.recalcBlockCounts();
    }

    public LevelChunkSection(final PalettedContainerFactory containerFactory) {
        this.states = containerFactory.createForBlockStates();
        this.biomes = containerFactory.createForBiomes();
        // MODIFIED for porting: lithium util.block_tracking LevelChunkSectionMixin#initAirSection. Instead of leaving
        // all flag counters at 0, initialize them correctly for the (all-air) section this constructor produces.
        net.caffeinemc.mods.lithium.common.world.section.LithiumSectionData.SectionData sectionData = this.lithium$getSectionData();
        if (sectionData.getCountsByFlag() != null) {
            throw new IllegalStateException("CountsByFlag already initialized!");
        }

        sectionData.setCountsByFlag(new short[net.caffeinemc.mods.lithium.common.block.BlockStateFlags.NUM_TRACKED_FLAGS]);

        for (net.caffeinemc.mods.lithium.common.block.TrackedBlockStatePredicate predicate : net.caffeinemc.mods.lithium.common.block.BlockStateFlags.TRACKED_FLAGS) {
            if (this.states.maybeHas(predicate)) {
                sectionData.getCountsByFlag()[predicate.getIndex()] = 16 * 16 * 16;
            }
        }

        // MODIFIED for porting: lithium world.chunk_ticking.random_block_ticking LevelChunkSectionMixin#initAirSection
        if (sectionData.getRandomTickableBlocksByY() != null) {
            throw new IllegalStateException("RandomTickableBlocksByY already initialized!");
        }

        sectionData.setRandomTickableBlocksByY(new byte[net.caffeinemc.mods.lithium.common.world.section.RandomTickingSectionDataHelper.BYTE_COUNT]);
        if (this.states.maybeHas(net.caffeinemc.mods.lithium.common.block.BlockStateFlags.RANDOM_TICKING)) {
            net.caffeinemc.mods.lithium.common.world.section.RandomTickingSectionDataHelper.initDataForAllRandomTickingSection(sectionData);
        } else {
            net.caffeinemc.mods.lithium.common.world.section.RandomTickingSectionDataHelper.initNonRandomTickingSection(sectionData);
        }
    }

    public BlockState getBlockState(final int sectionX, final int sectionY, final int sectionZ) {
        return this.states.get(sectionX, sectionY, sectionZ);
    }

    public FluidState getFluidState(final int sectionX, final int sectionY, final int sectionZ) {
        return this.states.get(sectionX, sectionY, sectionZ).getFluidState();
    }

    public void acquire() {
        this.states.acquire();
    }

    public void release() {
        this.states.release();
    }

    public BlockState setBlockState(final int sectionX, final int sectionY, final int sectionZ, final BlockState state) {
        // MODIFIED for porting: lithium chunk.no_locking LevelChunkSectionMixin#setBlockStateNoLocking - the threading
        // check inside PalettedContainer is a no-op after chunk.no_locking, so do not ask for it either.
        return this.setBlockState(sectionX, sectionY, sectionZ, state, false);
    }

    public BlockState setBlockState(final int sectionX, final int sectionY, final int sectionZ, final BlockState state, final boolean checkThreading) {
        BlockState previous;
        if (checkThreading) {
            previous = this.states.getAndSet(sectionX, sectionY, sectionZ, state);
        } else {
            previous = this.states.getAndSetUnchecked(sectionX, sectionY, sectionZ, state);
        }

        if (!previous.isAir()) {
            this.nonEmptyBlockCount--;
            if (previous.isRandomlyTicking()) {
                this.tickingBlockCount--;
            }

            FluidState previousFluid = previous.getFluidState();
            if (!previousFluid.isEmpty()) {
                this.fluidCount--;
                if (previousFluid.isRandomlyTicking()) {
                    this.tickingFluidCount--;
                }
            }
        }

        if (!state.isAir()) {
            this.nonEmptyBlockCount++;
            if (state.isRandomlyTicking()) {
                this.tickingBlockCount++;
            }

            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                this.fluidCount++;
                if (fluid.isRandomlyTicking()) {
                    this.tickingFluidCount++;
                }
            }
        }

        // MODIFIED for porting: lithium world.chunk_ticking.random_block_ticking
        // LevelChunkSectionMixin#updateRandomTickableBlockCounts (RETURN)
        {
            int prevFlags = ((net.caffeinemc.mods.lithium.common.block.BlockStateFlagHolder)previous).lithium$getAllFlags();
            int flags = ((net.caffeinemc.mods.lithium.common.block.BlockStateFlagHolder)state).lithium$getAllFlags();
            int mask = 1 << net.caffeinemc.mods.lithium.common.block.BlockStateFlags.RANDOM_TICKING.getIndex();
            if ((prevFlags & mask) != (flags & mask)) {
                if ((prevFlags & mask) != 0) {
                    net.caffeinemc.mods.lithium.common.world.section.RandomTickingSectionDataHelper.removeAt(sectionX, sectionY, sectionZ, this.lithium$getSectionDataDirect().getRandomTickableBlocksByY());
                } else {
                    net.caffeinemc.mods.lithium.common.world.section.RandomTickingSectionDataHelper.addAt(sectionX, sectionY, sectionZ, this.lithium$getSectionDataDirect().getRandomTickableBlocksByY());
                }
            }
        }

        // MODIFIED for porting: lithium util.block_tracking LevelChunkSectionMixin#updateFlagCounters (RETURN)
        this.lithium$trackBlockStateChange(state, previous);
        net.caffeinemc.mods.lithium.common.tracking.block.ChunkSectionChangeCallback changeListener = this.lithium$getSectionData().getChangeListener();
        if (changeListener != null) {
            changeListener.onBlockChange(this, sectionX, sectionY, sectionZ, previous, state);
        }

        return previous;
    }

    public boolean hasOnlyAir() {
        return this.nonEmptyBlockCount == 0;
    }

    public boolean hasFluid() {
        return this.fluidCount > 0;
    }

    public boolean isRandomlyTicking() {
        return this.isRandomlyTickingBlocks() || this.isRandomlyTickingFluids();
    }

    public boolean isRandomlyTickingBlocks() {
        return this.tickingBlockCount > 0;
    }

    public boolean isRandomlyTickingFluids() {
        return this.tickingFluidCount > 0;
    }

    public void recalcBlockCounts() {
        // MODIFIED for porting: lithium util.block_tracking LevelChunkSectionMixin#createFlagCounters (HEAD)
        this.lithium$getSectionData().setCountsByFlag(new short[net.caffeinemc.mods.lithium.common.block.BlockStateFlags.NUM_TRACKED_FLAGS]);
        // MODIFIED for porting: lithium world.chunk_ticking.random_block_ticking LevelChunkSectionMixin#createFlagCounters
        this.lithium$getSectionData().setRandomTickableBlocksByY(new byte[net.caffeinemc.mods.lithium.common.world.section.RandomTickingSectionDataHelper.BYTE_COUNT]);

        // MODIFIED for porting: lithium util.block_tracking LevelChunkSection$BlockCounterMixin adds lithium's flag
        // counters to this vanilla counter.
        // MODIFIED for porting: lithium world.chunk_ticking.random_block_ticking LevelChunkSection$BlockCounterMixin adds
        // the per-minisection random-ticking counters to the same vanilla counter.
        class BlockCounter
            implements PalettedContainer.CountConsumer<BlockState>,
            net.caffeinemc.mods.lithium.common.tracking.block.LithiumBlockCounter,
            net.caffeinemc.mods.lithium.common.world.section.RandomTickingSectionDataHelper.LithiumRandomTickingBlockCounter {
            public int nonEmptyBlockCount;
            public int fluidCount;
            public int tickingBlockCount;
            public int tickingFluidCount;
            short[] countsByFlag;
            byte[] randomTickData;
            byte lastRandomTickableBlockCountTotal;
            int minisectionIndex;

            @Override
            public void lithium$initBlockCounter(final short[] countsByFlag) {
                this.countsByFlag = countsByFlag;
            }

            @Override
            public void lithium$initRandomTickingBlockCounter(final byte[] randomTickData) {
                this.randomTickData = randomTickData;
                this.lastRandomTickableBlockCountTotal = 0;
                this.minisectionIndex = 0;
            }

            @Override
            public void lithium$finishedCountingMinisection(
                final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap indexCounts, final short[] indexCountsArray, final Palette<BlockState> palette
            ) {
                // A bunch of bytes can over- and underflow here, but actually it is no issue.
                // Subtract the previous total first, since the new total is added below; the result is the count of
                // random tickable blocks in that minisection.
                this.randomTickData[this.minisectionIndex] -= this.lastRandomTickableBlockCountTotal;
                if (indexCountsArray != null) {
                    for (int i = 0; i < indexCountsArray.length; i++) {
                        BlockState blockState = palette.valueFor(i);
                        if ((((net.caffeinemc.mods.lithium.common.block.BlockStateFlagHolder)blockState).lithium$getAllFlags() & net.caffeinemc.mods.lithium.common.world.section.RandomTickingSectionDataHelper.RANDOM_TICKING_FLAG_MASK) != 0) {
                            this.randomTickData[this.minisectionIndex] += (byte)indexCountsArray[i];
                        }
                    }
                } else {
                    indexCounts.int2IntEntrySet().forEach(entry -> {
                        BlockState blockState = palette.valueFor(entry.getIntKey());
                        if ((((net.caffeinemc.mods.lithium.common.block.BlockStateFlagHolder)blockState).lithium$getAllFlags() & net.caffeinemc.mods.lithium.common.world.section.RandomTickingSectionDataHelper.RANDOM_TICKING_FLAG_MASK) != 0) {
                            this.randomTickData[this.minisectionIndex] += (byte)entry.getIntValue();
                        }
                    });
                }

                this.lastRandomTickableBlockCountTotal += this.randomTickData[this.minisectionIndex];
                this.minisectionIndex++;
            }

            @Override
            public <T> void lithium$wholeSectionSingleBlock(final T singleBlockState, final int count) {
                if (count != 4096) {
                    return; // lithium$handleAfterCounting will fall back to scanning the section's blocks
                }

                if (singleBlockState instanceof BlockState state) {
                    net.caffeinemc.mods.lithium.common.world.section.RandomTickingSectionDataHelper.handleSectionSingleBlockState(state, this.randomTickData);
                    this.minisectionIndex = net.caffeinemc.mods.lithium.common.world.section.RandomTickingSectionDataHelper.MINISECTION_COUNT;
                }
            }

            @Override
            public void lithium$handleAfterCounting(final LevelChunkSection section) {
                if (net.caffeinemc.mods.lithium.common.world.section.RandomTickingSectionDataHelper.MINISECTION_COUNT != this.minisectionIndex) {
                    // Fallback for the case that the counter could not be instrumented (e.g. another way of counting was
                    // used); recompute the data with a naive scan.
                    if (this.randomTickData != ((net.caffeinemc.mods.lithium.common.world.section.LithiumSectionData)section).lithium$getSectionData().getRandomTickableBlocksByY()) {
                        throw new IllegalArgumentException("Lithium random tick data was replaced unexpectedly!");
                    }

                    net.caffeinemc.mods.lithium.common.world.section.RandomTickingSectionDataHelper.naiveInitializeData(section.getStates(), this.randomTickData);
                }
            }

            private static void addToFlagCount(final short[] countsByFlag, final BlockState state, final short change) {
                int flags = ((net.caffeinemc.mods.lithium.common.block.BlockStateFlagHolder)state).lithium$getAllFlags();
                int i;
                while ((i = Integer.numberOfTrailingZeros(flags)) < 32 && i < countsByFlag.length) {
                    countsByFlag[i] += change;
                    flags &= ~(1 << i);
                }
            }

            public void accept(final BlockState state, final int count) {
                // MODIFIED for porting: lithium LevelChunkSection$BlockCounterMixin#acceptLithium (HEAD)
                addToFlagCount(this.countsByFlag, state, (short)count);
                if (!state.isAir()) {
                    this.nonEmptyBlockCount += count;
                    if (state.isRandomlyTicking()) {
                        this.tickingBlockCount += count;
                    }

                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        this.fluidCount += count;
                        if (fluid.isRandomlyTicking()) {
                            this.tickingFluidCount += count;
                        }
                    }
                }
            }
        }

        BlockCounter blockCounter = new BlockCounter();
        // MODIFIED for porting: lithium util.block_tracking LevelChunkSectionMixin#initLithiumBlockCounter (ModifyArg)
        blockCounter.lithium$initBlockCounter(java.util.Objects.requireNonNull(this.lithium$getSectionData().getCountsByFlag()));
        // MODIFIED for porting: lithium world.chunk_ticking.random_block_ticking
        // LevelChunkSectionMixin#initFlagCountersAndRecalcBlockCounts (WrapOperation around PalettedContainer#count)
        byte[] lithium$randomTickableBlocksByY = java.util.Objects.requireNonNull(this.lithium$getSectionData().getRandomTickableBlocksByY());
        blockCounter.lithium$initRandomTickingBlockCounter(lithium$randomTickableBlocksByY);
        this.states.count(blockCounter);
        blockCounter.lithium$handleAfterCounting(this);
        this.nonEmptyBlockCount = (short)blockCounter.nonEmptyBlockCount;
        this.fluidCount = (short)blockCounter.fluidCount;
        this.tickingBlockCount = (short)blockCounter.tickingBlockCount;
        this.tickingFluidCount = (short)blockCounter.tickingFluidCount;
    }

    public PalettedContainer<BlockState> getStates() {
        return this.states;
    }

    public PalettedContainerRO<Holder<Biome>> getBiomes() {
        return this.biomes;
    }

    public void read(final FriendlyByteBuf buffer) {
        // MODIFIED for porting: lithium util.block_tracking LevelChunkSectionMixin#resetData (HEAD)
        this.lithium$getSectionData().setCountsByFlag(null);
        this.nonEmptyBlockCount = buffer.readShort();
        this.fluidCount = buffer.readShort();
        this.states.read(buffer);
        PalettedContainer<Holder<Biome>> biomes = this.biomes.recreate();
        biomes.read(buffer);
        this.biomes = biomes;
    }

    public void readBiomes(final FriendlyByteBuf buffer) {
        PalettedContainer<Holder<Biome>> biomes = this.biomes.recreate();
        biomes.read(buffer);
        this.biomes = biomes;
    }

    public void write(final FriendlyByteBuf buffer) {
        buffer.writeShort(this.nonEmptyBlockCount);
        buffer.writeShort(this.fluidCount);
        this.states.write(buffer);
        this.biomes.write(buffer);
    }

    public int getSerializedSize() {
        return 4 + this.states.getSerializedSize() + this.biomes.getSerializedSize();
    }

    public boolean maybeHas(final Predicate<BlockState> predicate) {
        return this.states.maybeHas(predicate);
    }

    public Holder<Biome> getNoiseBiome(final int quartX, final int quartY, final int quartZ) {
        return this.biomes.get(quartX, quartY, quartZ);
    }

    public void fillBiomesFromNoise(
        final BiomeResolver biomeResolver, final Climate.Sampler sampler, final int quartMinX, final int quartMinY, final int quartMinZ
    ) {
        PalettedContainer<Holder<Biome>> newBiomes = this.biomes.recreate();
        int size = 4;

        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    newBiomes.getAndSetUnchecked(x, y, z, biomeResolver.getNoiseBiome(quartMinX + x, quartMinY + y, quartMinZ + z, sampler));
                }
            }
        }

        this.biomes = newBiomes;
    }

    public LevelChunkSection copy() {
        return new LevelChunkSection(this);
    }

    // MODIFIED for porting: the remaining methods were lithium's util.block_tracking LevelChunkSectionMixin
    @Override
    public short lithium$getCount(final int predicateIndex) {
        net.caffeinemc.mods.lithium.common.world.section.LithiumSectionData.SectionData sectionData = this.lithium$getSectionData();
        if (sectionData.getCountsByFlag() == null) {
            this.lithium$fastInitClientCounts();
        }

        return sectionData.getCountsByFlag()[predicateIndex];
    }

    @Override
    public boolean lithium$mayContainAny(final net.caffeinemc.mods.lithium.common.block.TrackedBlockStatePredicate trackedBlockStatePredicate) {
        net.caffeinemc.mods.lithium.common.world.section.LithiumSectionData.SectionData sectionData = this.lithium$getSectionData();
        if (sectionData.getCountsByFlag() == null) {
            this.lithium$fastInitClientCounts();
        }

        return sectionData.getCountsByFlag()[trackedBlockStatePredicate.getIndex()] != (short)0;
    }

    private void lithium$fastInitClientCounts() {
        net.caffeinemc.mods.lithium.common.world.section.LithiumSectionData.SectionData sectionData = this.lithium$getSectionData();
        sectionData.setCountsByFlag(new short[net.caffeinemc.mods.lithium.common.block.BlockStateFlags.NUM_TRACKED_FLAGS]);

        for (net.caffeinemc.mods.lithium.common.block.TrackedBlockStatePredicate predicate : net.caffeinemc.mods.lithium.common.block.BlockStateFlags.TRACKED_FLAGS) {
            if (this.states.maybeHas(predicate)) {
                // We haven't counted, so we just set the count so high that it never incorrectly reaches 0.
                sectionData.getCountsByFlag()[predicate.getIndex()] = 16 * 16 * 16;
            }
        }
    }

    @Override
    public void lithium$trackBlockStateChange(final BlockState newState, final BlockState oldState) {
        short[] countsByFlag = this.lithium$getSectionData().getCountsByFlag();
        if (countsByFlag == null) {
            return;
        }

        int prevFlags = ((net.caffeinemc.mods.lithium.common.block.BlockStateFlagHolder)oldState).lithium$getAllFlags();
        int flags = ((net.caffeinemc.mods.lithium.common.block.BlockStateFlagHolder)newState).lithium$getAllFlags();
        int flagsXOR = prevFlags ^ flags;
        int flagIndex;
        while ((flagIndex = Integer.numberOfTrailingZeros(flagsXOR)) < 32 && flagIndex < countsByFlag.length) {
            int flagBit = 1 << flagIndex;
            if ((flagsXOR & flagBit) != 0) {
                countsByFlag[flagIndex] += (short)(1 - (((prevFlags >>> flagIndex) & 1) << 1));
            }

            flagsXOR &= ~flagBit;
        }
    }

    @Override
    public void lithium$addToCallback(
        final net.caffeinemc.mods.lithium.common.tracking.block.SectionedBlockChangeTracker tracker,
        final long sectionPos,
        final net.minecraft.world.level.Level world
    ) {
        net.caffeinemc.mods.lithium.common.world.section.LithiumSectionData.SectionData sectionData = this.lithium$getSectionData();
        if (sectionData.getChangeListener() == null) {
            if (sectionPos == Long.MIN_VALUE || world == null) {
                throw new IllegalArgumentException("Expected world and section pos during intialization!");
            }

            sectionData.setChangeListener(net.caffeinemc.mods.lithium.common.tracking.block.ChunkSectionChangeCallback.create(sectionPos, world));
        }

        sectionData.getChangeListener().addTracker(tracker);
    }

    @Override
    public void lithium$removeFromCallback(final net.caffeinemc.mods.lithium.common.tracking.block.SectionedBlockChangeTracker tracker) {
        net.caffeinemc.mods.lithium.common.tracking.block.ChunkSectionChangeCallback changeListener = this.lithium$getSectionData().getChangeListener();
        if (changeListener != null) {
            changeListener.removeTracker(tracker);
        }
    }
}