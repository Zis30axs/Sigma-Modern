package net.caffeinemc.mods.lithium.common.world.interests;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public interface PoiOrdering {

    default boolean isOrdered(BlockPos center, PoiManager poiManager, List<BlockPos> positions) {
        if (!positions.isEmpty()) {
            BlockPos curr = positions.getFirst();
            for (int i = 1; i < positions.size(); i++) {
                BlockPos next = positions.get(i);
                int order = compare(center, poiManager, curr, next);
                if (order != 1) {
                    return false;
                }
                curr = next;
            }
        }
        return true;
    }

    default void checkOrderOrThrow(BlockPos center, PoiManager poiManager, List<BlockPos> positions) {
        if (!positions.isEmpty()) {
            BlockPos curr = positions.getFirst();
            for (int i = 1; i < positions.size(); i++) {
                BlockPos next = positions.get(i);
                int order = compare(center, poiManager, curr, next);
                if (order != -1) {
                    if (order == 0) {
                        order = compare(center, poiManager, curr, next); //Line for debugging
                        throw new IllegalStateException("Positions are equal at index " + i + ": " + curr + " == " + next);
                    } else {
                        order = compare(center, poiManager, curr, next); //Line for debugging
                        throw new IllegalStateException("Positions are ordered incorrectly at index " + i + ": " + curr + " < " + next + "! Offsets: " +
                                "curr=(" + (curr.getX() - center.getX()) + ", " + (curr.getY() - center.getY()) + ", " + (curr.getZ() - center.getZ()) + "), " +
                                "next=(" + (next.getX() - center.getX()) + ", " + (next.getY() - center.getY()) + ", " + (next.getZ() - center.getZ()) + ")");
                    }
                }
                curr = next;
            }
        }
    }

    default Comparator<BlockPos> getAsComparator(BlockPos center, PoiManager poiManager) {
        return (posA, posB) -> compare(center, poiManager, posA, posB);
    }

    int compare(BlockPos center, PoiManager poiManager, BlockPos posA, BlockPos posB);


    /**
     * Order:
     * - lower chunk Y first
     * - POI type hashmap iteration order
     * - Typed POI record hashset iteration order
     */
    record InChunk() implements PoiOrdering {

        public static final InChunk INSTANCE = new InChunk();

        @Override
        public int compare(BlockPos center, PoiManager poiManager, BlockPos posA, BlockPos posB) {
            if (posA.equals(posB)) {
                return 0;
            }

            int aChunkZ = posA.getZ() >> 4;
            int bChunkZ = posB.getZ() >> 4;

            int orderZ = Integer.compare(aChunkZ, bChunkZ);
            if (orderZ != 0) {
                throw new IllegalStateException("Positions are not in the same chunk: " + posA + " in " + ChunkPos.containing(posA) + " vs " + posB + " in " + ChunkPos.containing(posB));
            }

            int aChunkX = posA.getX() >> 4;
            int bChunkX = posB.getX() >> 4;

            int orderX = Integer.compare(aChunkX, bChunkX);
            if (orderX != 0) {
                throw new IllegalStateException("Positions are not in the same chunk: " + posA + " in " + ChunkPos.containing(posA) + " vs " + posB + " in " + ChunkPos.containing(posB));
            }

            int aChunkY = posA.getY() >> 4;
            int bChunkY = posB.getY() >> 4;

            int orderY = Integer.compare(aChunkY, bChunkY);
            if (orderY != 0) {
                return orderY;
            }

            Optional<PoiSection> poiSection = poiManager.getOrLoad(SectionPos.asLong(posA));
            if (poiSection.isEmpty()) {
                throw new IllegalStateException("PoiManager " + poiManager + " has no section at position " + posA);
            }
            PoiSection poiSection1 = poiSection.get();
            List<PoiRecord> retrievedRecords = poiSection1.getRecords(a -> true, PoiManager.Occupancy.ANY).filter(poiRecord -> poiRecord.getPos().equals(posA) || poiRecord.getPos().equals(posB)).toList();
            if (retrievedRecords.size() != 2) {
                throw new IllegalStateException("Expected two POI records at " + posA + ", " + posB + ", found " + retrievedRecords.size());
            }

            if (retrievedRecords.getFirst().getPos().equals(posA)) {
                return -1;
            }
            if (retrievedRecords.getFirst().getPos().equals(posB)) {
                return 1;
            }

            throw new IllegalStateException("Expected two differing poi positions matching " + posA + ", " + posB + ", found " + retrievedRecords);
        }
    }


    /**
     * Order:
     * - lower chunk Z first
     * - lower chunk X first
     * - InChunk order
     */
    record InSquare() implements PoiOrdering {

        public static final InSquare INSTANCE = new InSquare();

        @Override
        public int compare(BlockPos center, PoiManager poiManager, BlockPos posA, BlockPos posB) {
            int aChunkZ = posA.getZ() >> 4;
            int bChunkZ = posB.getZ() >> 4;

            int orderZ = Integer.compare(aChunkZ, bChunkZ);
            if (orderZ != 0) {
                return orderZ;
            }

            int aChunkX = posA.getX() >> 4;
            int bChunkX = posB.getX() >> 4;

            int orderX = Integer.compare(aChunkX, bChunkX);
            if (orderX != 0) {
                return orderX;
            }

            return InChunk.INSTANCE.compare(center, poiManager, posA, posB);
        }
    }


    /**
     * Order:
     * - lower distance squared first
     * - InSquare order
     */
    record L2ThenInSquare() implements PoiOrdering {

        public static final L2ThenInSquare INSTANCE = new L2ThenInSquare();

        @Override
        public int compare(BlockPos center, PoiManager poiManager, BlockPos posA, BlockPos posB) {
            double distASq = center.distSqr(posA);
            double distBSq = center.distSqr(posB);

            int orderDist = Double.compare(distASq, distBSq);
            if (orderDist != 0) {
                return orderDist;
            }

            return InSquare.INSTANCE.compare(center, poiManager, posA, posB);
        }
    }

    /**
     * Order:
     * - lower distance squared first
     * - lower Y first
     * - InSquare order
     */
    record L2ThenMinYThenInSquare() implements PoiOrdering {

        public static final L2ThenMinYThenInSquare INSTANCE = new L2ThenMinYThenInSquare();

        @Override
        public int compare(BlockPos center, PoiManager poiManager, BlockPos posA, BlockPos posB) {
            double distASq = center.distSqr(posA);
            double distBSq = center.distSqr(posB);

            int orderDist = Double.compare(distASq, distBSq);
            if (orderDist != 0) {
                return orderDist;
            }

            int orderY = Integer.compare(posA.getY(), posB.getY());
            if (orderY != 0) {
                return orderY;
            }

            return InSquare.INSTANCE.compare(center, poiManager, posA, posB);
        }
    }
}
