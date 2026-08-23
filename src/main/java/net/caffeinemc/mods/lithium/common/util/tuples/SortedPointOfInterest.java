package net.caffeinemc.mods.lithium.common.util.tuples;

import net.caffeinemc.mods.lithium.common.util.Distances;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;

public final class SortedPointOfInterest {
    private final PoiRecord poi;
    private final int distanceSq;
    private boolean consumed;

    public SortedPointOfInterest(PoiRecord poi, int distanceSq) {
        this.poi = poi;
        this.distanceSq = distanceSq;
    }

    public SortedPointOfInterest(PoiRecord poi, BlockPos origin) {
        this(poi, Distances.distanceSqInt(poi.getPos(), origin));
    }

    public BlockPos getPos() {
        return this.poi.getPos();
    }

    public int getX() {
        return this.getPos().getX();
    }

    public int getY() {
        return this.getPos().getY();
    }

    public int getZ() {
        return this.getPos().getZ();
    }

    public PoiRecord poi() {
        return poi;
    }

    public int distanceSq() {
        return distanceSq;
    }

    public boolean isConsumed() {
        return consumed;
    }

    public void setConsumed() {
        this.consumed = true;
    }
}
