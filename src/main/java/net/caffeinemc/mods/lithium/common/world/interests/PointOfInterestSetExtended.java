package net.caffeinemc.mods.lithium.common.world.interests;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;

import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Predicate;

public interface PointOfInterestSetExtended {

    void lithium$collectMatchingPoints(Predicate<Holder<PoiType>> type, PoiManager.Occupancy status,
                                       Consumer<PoiRecord> consumer);

    default PoiRecord lithium$getL2ClosestMatchingPoint(BlockPos center, Predicate<Holder<PoiType>> typeFilter, PoiManager.Occupancy status) {
        return this.lithium$getL2ClosestMatchingPoint(center, typeFilter, status.getTest());
    }

    PoiRecord lithium$getL2ClosestMatchingPoint(BlockPos center, Predicate<Holder<PoiType>> typeFilter, Predicate<? super PoiRecord> poiPredicate);

    default void lithium$collectMatchingPointsL2Limited(BlockPos center, long maxDistanceSq, Predicate<Holder<PoiType>> typeFilter, PoiManager.Occupancy status, Consumer<PoiRecord> consumer, int limit) {
        this.lithium$collectMatchingPointsL2Limited(center, maxDistanceSq, typeFilter, status.getTest(), consumer, limit);
    }

    void lithium$collectMatchingPointsL2Limited(BlockPos center, long maxDistanceSq, Predicate<Holder<PoiType>> typeFilter, Predicate<? super PoiRecord> poiPredicate, Consumer<PoiRecord> consumer, int limit);

    PoiRecord lithium$getFirstMatchingPoint(BlockPos pos, long maxDistSq, Predicate<Holder<PoiType>> typeFilter, Predicate<BlockPos> posPredicate, PoiManager.Occupancy status);

    PoiRecord lithium$getAt(BlockPos pos);

    Iterator<PoiRecord> lithium$iterate(Predicate<Holder<PoiType>> typeFilter);
}