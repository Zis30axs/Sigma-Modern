package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TellyModeTest {
    @Test
    void activationKeepsItsDiagonalToleranceAcrossTheYawSeam() {
        for (float yaw : new float[]{-1035, -315, -225, -135, -45, 45, 135, 225, 315, 1125}) {
            assertTrue(BlockFlyTellyMode.isActivationYawAligned(yaw));
            assertTrue(BlockFlyTellyMode.isActivationYawAligned(yaw - 2));
            assertTrue(BlockFlyTellyMode.isActivationYawAligned(yaw + 2));
            assertFalse(BlockFlyTellyMode.isActivationYawAligned(yaw - 2.1f));
            assertFalse(BlockFlyTellyMode.isActivationYawAligned(yaw + 2.1f));
        }
        assertFalse(BlockFlyTellyMode.isActivationYawAligned(Float.NaN));
        assertFalse(BlockFlyTellyMode.isActivationYawAligned(Float.POSITIVE_INFINITY));
        assertFalse(BlockFlyTellyMode.isActivationYawAligned(Float.NEGATIVE_INFINITY));
    }

    @Test
    void backwardRightSetupTravelsAlongEachCardinalLane() {
        assertArrayEquals(new int[]{0, -1}, BlockFlyTellyMode.travelDirectionFromYaw(45));
        assertArrayEquals(new int[]{1, 0}, BlockFlyTellyMode.travelDirectionFromYaw(135));
        assertArrayEquals(new int[]{0, 1}, BlockFlyTellyMode.travelDirectionFromYaw(225));
        assertArrayEquals(new int[]{-1, 0}, BlockFlyTellyMode.travelDirectionFromYaw(-45));
    }

    @Test
    void activationFaceMirrorsItsAcrossCoordinateForWestAndSouth() {
        assertTrue(inFace(5, 0.5, 0.4));
        assertTrue(inFace(4, 0.5, 0.6));
        assertTrue(inFace(2, 0.4, 0.5));
        assertTrue(inFace(3, 0.6, 0.5));
        assertFalse(inFace(5, 0.5, 0.7));
        assertFalse(inFace(4, 0.5, 0.3));
        assertFalse(inFace(2, 0.7, 0.5));
        assertFalse(inFace(3, 0.3, 0.5));
        assertFalse(BlockFlyTellyMode.isInActivationFaceCenter(1, new BlockFlyTellyMode.Vec3(0.5, 0.5, 0.5)));
        assertFalse(BlockFlyTellyMode.isInActivationFaceCenter(5, new BlockFlyTellyMode.Vec3(1, 0.751, 0.5)));
        assertFalse(BlockFlyTellyMode.isInActivationFaceCenter(5, new BlockFlyTellyMode.Vec3(1, 0.249, 0.5)));
    }

    private static boolean inFace(int face, double x, double z) {
        return BlockFlyTellyMode.isInActivationFaceCenter(face, new BlockFlyTellyMode.Vec3(x, 0.5, z));
    }

    @Test
    void straightLaneRejectsSidewaysAndBackwardPlacementsInEveryDirection() {
        int[] start = {-4, 63, -7};
        for (int[] travel : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int lane = travel[0] != 0 ? start[2] : start[0];
            int progress = start[0] * travel[0] + start[2] * travel[1];
            assertTrue(BlockFlyTellyMode.isInStraightLane(start, travel[0], travel[1], lane, progress));
            assertTrue(BlockFlyTellyMode.isInStraightLane(new int[]{start[0] + travel[0], 64, start[2] + travel[1]},
                    travel[0], travel[1], lane, progress));
            assertFalse(BlockFlyTellyMode.isInStraightLane(new int[]{start[0] - travel[0], 63, start[2] - travel[1]},
                    travel[0], travel[1], lane, progress));
            assertFalse(BlockFlyTellyMode.isInStraightLane(new int[]{start[0] + travel[1], 63, start[2] + travel[0]},
                    travel[0], travel[1], lane, progress));
        }
    }

    @Test
    void fallbackPathUsesAdjacentSupportCellsAndHonorsItsSearchBudget() {
        List<int[]> path = BlockFlyTellyMode.rasterizeHorizontalLineAtY(new int[]{-3, 0, -2}, new int[]{2, 0, 1}, 64, 20);
        assertArrayEquals(new int[]{-3, 64, -2}, path.getFirst());
        assertArrayEquals(new int[]{2, 64, 1}, path.getLast());
        assertEquals(9, path.size());
        for (int i = 1; i < path.size(); i++) {
            int[] previous = path.get(i - 1);
            int[] current = path.get(i);
            assertEquals(64, current[1]);
            assertEquals(1, Math.abs(current[0] - previous[0]) + Math.abs(current[2] - previous[2]));
        }
        assertEquals(3, BlockFlyTellyMode.rasterizeHorizontalLineAtY(new int[]{0, 0, 0}, new int[]{10, 0, 10}, 64, 3).size());
        assertTrue(BlockFlyTellyMode.rasterizeHorizontalLineAtY(new int[]{0, 0, 0}, new int[]{10, 0, 10}, 64, 0).isEmpty());
    }

    @Test
    void supportEdgePredictionRequiresMovementTowardTheSameFace() {
        assertTrue(BlockFlyTellyMode.isPastStraightSupportEdgeThreshold(5, 0.52, 0.5));
        assertFalse(BlockFlyTellyMode.isPastStraightSupportEdgeThreshold(5, 0.51, 0.5));
        assertTrue(BlockFlyTellyMode.isPastStraightSupportEdgeThreshold(4, 0.48, 0.5));
        assertTrue(BlockFlyTellyMode.isPastStraightSupportEdgeThreshold(3, 0.5, 0.52));
        assertTrue(BlockFlyTellyMode.isPastStraightSupportEdgeThreshold(2, 0.5, 0.48));
        assertTrue(BlockFlyTellyMode.isMovingTowardStraightSupportEdge(5, 0.1, -0.1));
        assertFalse(BlockFlyTellyMode.isMovingTowardStraightSupportEdge(5, -0.1, 0.1));
        assertFalse(BlockFlyTellyMode.isMovingTowardStraightSupportEdge(1, 0.1, 0.1));
    }
}
