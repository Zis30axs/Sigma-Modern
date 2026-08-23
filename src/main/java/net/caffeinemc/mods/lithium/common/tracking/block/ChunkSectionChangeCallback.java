package net.caffeinemc.mods.lithium.common.tracking.block;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.caffeinemc.mods.lithium.common.block.BlockListeningSection;
import net.caffeinemc.mods.lithium.common.util.Pos;
import net.caffeinemc.mods.lithium.common.world.LithiumData;
import net.caffeinemc.mods.lithium.common.world.chunk.ChunkStatusTracker;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;

public final class ChunkSectionChangeCallback {
    private final long sectionPos;
    private ArrayList<BlockChangeTracker> trackers;

    public static void init() {
        if (BlockListeningSection.class.isAssignableFrom(LevelChunkSection.class)) {
            ChunkStatusTracker.registerUnloadCallback((serverWorld, chunkPos) -> {
                Long2ReferenceOpenHashMap<ChunkSectionChangeCallback> changeCallbacks = ((LithiumData) serverWorld).lithium$getData().chunkSectionChangeCallbacks();
                int x = chunkPos.x();
                int z = chunkPos.z();
                for (int y = Pos.SectionYCoord.getMinYSection(serverWorld); y <= Pos.SectionYCoord.getMaxYSectionInclusive(serverWorld); y++) {
                    SectionPos chunkSectionPos = SectionPos.of(x, y, z);
                    ChunkSectionChangeCallback chunkSectionChangeCallback = changeCallbacks.remove(chunkSectionPos.asLong());
                    if (chunkSectionChangeCallback != null) {
                        chunkSectionChangeCallback.onChunkSectionInvalidated(chunkSectionPos);
                    }
                }
            });
        }
    }

    public ChunkSectionChangeCallback(long sectionPos) {
        this.sectionPos = sectionPos;
    }

    public static ChunkSectionChangeCallback create(long sectionPos, Level world) {
        ChunkSectionChangeCallback chunkSectionChangeCallback = new ChunkSectionChangeCallback(sectionPos);
        Long2ReferenceOpenHashMap<ChunkSectionChangeCallback> changeCallbacks = ((LithiumData) world).lithium$getData().chunkSectionChangeCallbacks();
        ChunkSectionChangeCallback previous = changeCallbacks.put(sectionPos, chunkSectionChangeCallback);
        if (previous != null) {
            previous.onChunkSectionInvalidated(SectionPos.of(sectionPos));
        }
        return chunkSectionChangeCallback;
    }

    public void onBlockChange(BlockListeningSection section, int localX, int localY, int localZ, BlockState oldState, BlockState newState) {
        ArrayList<BlockChangeTracker> blockChangeTrackers = this.trackers;
        this.trackers = null; //Avoid concurrent modification issues
        if (blockChangeTrackers != null) {
            for (int i = blockChangeTrackers.size() - 1; i >= 0; i--) {
                BlockChangeTracker tracker = blockChangeTrackers.get(i);
                if (!tracker.setChanged(section, localX, localY, localZ, oldState, newState)) {
                    //Remove by swapping the (already iterated) last element, as array list removal is faster for elements at the end
                    BlockChangeTracker swap = blockChangeTrackers.removeLast();
                    if (i != blockChangeTrackers.size()) {
                        blockChangeTrackers.set(i, swap);
                    }
                }
            }
            if (this.trackers != null) {
                blockChangeTrackers.addAll(this.trackers);
            }
            if (!blockChangeTrackers.isEmpty()) {
                this.trackers = blockChangeTrackers;
            }
        }
    }

    public void addTracker(BlockChangeTracker tracker) {
        ArrayList<BlockChangeTracker> blockChangeTrackers = this.trackers;
        if (blockChangeTrackers == null) {
            this.trackers = (blockChangeTrackers = new ArrayList<>());
        }
        blockChangeTrackers.add(tracker);
    }

    public void removeTracker(BlockChangeTracker tracker) {
        ArrayList<BlockChangeTracker> blockChangeTrackers = this.trackers;
        if (blockChangeTrackers != null) {
            blockChangeTrackers.remove(tracker);
        }
    }

    public void onChunkSectionInvalidated(SectionPos sectionPos) {
        ArrayList<BlockChangeTracker> blockChangeTrackers = this.trackers;
        this.trackers = null;
        if (blockChangeTrackers != null) {
            for (int i = 0; i < blockChangeTrackers.size(); i++) {
                blockChangeTrackers.get(i).onChunkSectionInvalidated(sectionPos);
            }
        }
    }

    public long getSectionPos() {
        return this.sectionPos;
    }

    public int getX(int localX) {
        return SectionPos.sectionToBlockCoord(SectionPos.x(this.sectionPos)) + localX;
    }

    public int getY(int localY) {
        return SectionPos.sectionToBlockCoord(SectionPos.y(this.sectionPos)) + localY;
    }

    public int getZ(int localZ) {
        return SectionPos.sectionToBlockCoord(SectionPos.z(this.sectionPos)) + localZ;
    }
}
