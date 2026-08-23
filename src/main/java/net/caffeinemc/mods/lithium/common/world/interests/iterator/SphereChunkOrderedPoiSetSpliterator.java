package net.caffeinemc.mods.lithium.common.world.interests.iterator;

import net.caffeinemc.mods.lithium.common.util.Distances;
import net.caffeinemc.mods.lithium.common.world.interests.PointOfInterestSetExtended;
import net.caffeinemc.mods.lithium.common.world.interests.RegionBasedStorageSectionExtended;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.chunk.storage.SectionStorage;

import java.util.BitSet;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class SphereChunkOrderedPoiSetSpliterator extends Spliterators.AbstractSpliterator<PoiRecord> {
    private final RegionBasedStorageSectionExtended<PoiSection> storage;
    private final int chunkYMin;

    private final int chunkLimit;
    private final int minChunkX;
    private final int maxChunkX;

    private final BlockPos origin;
    private final int radiusSq;
    private final Predicate<Holder<PoiType>> typeFilter;
    private final PoiManager.Occupancy status;

    int chunkX;
    int chunkZ;
    int iteratedChunks;

    BitSet chunkPoiSections;
    int nextPoiSectionIndex;
    Iterator<PoiRecord> sectionIterator;

    public SphereChunkOrderedPoiSetSpliterator(int radius, BlockPos origin, RegionBasedStorageSectionExtended<PoiSection> storage, Predicate<Holder<PoiType>> typeFilter, PoiManager.Occupancy status) {
        super((long) ((origin.getX() + radius + 1 >> 4) - (origin.getX() - radius - 1 >> 4) + 1) * ((origin.getZ() + radius + 1 >> 4) - (origin.getZ() - radius - 1 >> 4) + 1), Spliterator.ORDERED);
        this.storage = storage;
        this.chunkYMin = this.storage.lithium$getChunkYMin();

        this.origin = origin;
        this.radiusSq = Math.multiplyExact(radius, radius);
        this.typeFilter = typeFilter;
        this.status = status;


        this.minChunkX = origin.getX() - radius - 1 >> 4;
        this.maxChunkX = origin.getX() + radius + 1 >> 4;
        int minChunkZ = origin.getZ() - radius - 1 >> 4;
        int maxChunkZ = origin.getZ() + radius + 1 >> 4;
        this.chunkLimit = (this.maxChunkX - this.minChunkX + 1) * ((maxChunkZ) - (minChunkZ) + 1);

        this.chunkX = this.minChunkX - 1;
        this.chunkZ = minChunkZ;
        this.iteratedChunks = -1;
    }

    @Override
    public boolean tryAdvance(Consumer<? super PoiRecord> action) {
        do {
            while (this.sectionIterator != null && this.sectionIterator.hasNext()) {
                PoiRecord next = this.sectionIterator.next();
                if (this.status.getTest().test(next) && Distances.isWithinSphereRadius(this.origin, this.radiusSq, next.getPos())) {
                    action.accept(next);
                    return true;
                }
            }

        } while (this.nextSection());
        return false;
    }

    private boolean nextSection() {
        do {
            if (this.chunkPoiSections != null) {
                int nextSectionIndex;
                while ((nextSectionIndex = this.chunkPoiSections.nextSetBit(this.nextPoiSectionIndex)) != -1) {
                    this.nextPoiSectionIndex = nextSectionIndex + 1;
                    int chunkY = nextSectionIndex + this.chunkYMin;

                    if (Distances.getMinSectionDistanceSq(this.origin, this.chunkX, chunkY, this.chunkZ) <= this.radiusSq) {
                        this.sectionIterator = this.getSectionIterator(this.chunkX, chunkY, this.chunkZ);
                        if (this.sectionIterator != null && this.sectionIterator.hasNext()) {
                            return true;
                        }
                    }

                }
            }
        } while (this.nextChunk());

        return false;
    }

    private Iterator<PoiRecord> getSectionIterator(int chunkX, int chunkY, int chunkZ) {
        //noinspection unchecked
        PoiSection poiSection = ((SectionStorage<PoiSection, ?>) this.storage).getOrLoad(SectionPos.asLong(chunkX, chunkY, chunkZ)).orElse(null);
        if (poiSection == null) {
            return null;
        }
        return ((PointOfInterestSetExtended) poiSection).lithium$iterate(this.typeFilter);
    }

    private boolean nextChunk() {
        do {
            if (this.iteratedChunks >= this.chunkLimit) {
                return false;
            }
            this.iteratedChunks++;

            this.chunkX++;
            if (this.chunkX > this.maxChunkX) {
                this.chunkZ++;
                this.chunkX = this.minChunkX;
            }
        } while (Distances.getMinChunkToBlockDistanceL2Sq(this.origin, this.chunkX, this.chunkZ) > this.radiusSq);

        this.chunkPoiSections = this.storage.lithium$getNonEmptyPOISections(this.chunkX, this.chunkZ);
        this.nextPoiSectionIndex = 0;
        this.sectionIterator = null;
        return true;
    }
}
