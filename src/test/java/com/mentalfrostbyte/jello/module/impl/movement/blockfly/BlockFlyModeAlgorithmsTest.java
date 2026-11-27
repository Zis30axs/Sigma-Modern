package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockFlyModeAlgorithmsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void lcustomSearchCoversAllDirectionsWithoutEverBuildingAboveTheLockedLayer() {
        BlockPos base = new BlockPos(-23, 71, 42);
        var positions = BlockFlyCustomBase.pyramid(base);
        assertEquals(base, positions.iterator().next());
        assertTrue(positions.contains(base.below(6)));
        assertFalse(positions.contains(base.below(7)));
        assertFalse(positions.contains(base.above()));
        for (BlockPos pos : positions) {
            int x = pos.getX() - base.getX();
            int y = pos.getY() - base.getY();
            int z = pos.getZ() - base.getZ();
            assertTrue(y <= 0);
            assertTrue(Math.abs(x) - y + Math.abs(z) <= 6);
            assertTrue(positions.contains(base.offset(-x, y, z)), "west/east coverage must be symmetric");
            assertTrue(positions.contains(base.offset(x, y, -z)), "north/south coverage must be symmetric");
        }
        assertEquals(new ArrayList<>(positions), new ArrayList<>(BlockFlyCustomBase.pyramid(base)),
                "search order must remain stable between ticks");
    }

    @Test
    void physicalDiagonalKeysKeepTheirWorldDirectionWhenTheServerLookIsSilent() {
        List<Input> directions = List.of(keys(true, false, false, false), keys(false, true, false, false),
                keys(false, false, true, false), keys(false, false, false, true),
                keys(true, false, true, false), keys(false, true, true, false));
        List<Vec3> expected = List.of(new Vec3(0, 0, 1), new Vec3(0, 0, -1), new Vec3(1, 0, 0),
                new Vec3(-1, 0, 0), new Vec3(1, 0, 1).normalize(), new Vec3(1, 0, -1).normalize());
        for (int i = 0; i < directions.size(); i++) {
            float yaw = BlockFlyLegacyMode.movementYaw(0, directions.get(i));
            Vec3 actual = Vec3.directionFromRotation(0, yaw);
            // Vanilla's direction vector uses its finite sine table, not Math.sin/cos.
            assertEquals(expected.get(i).x, actual.x, 1.0E-4);
            assertEquals(expected.get(i).z, actual.z, 1.0E-4);
        }
        assertFalse(BlockFlyLegacyMode.isMoving(keys(true, true, true, true)));
    }

    @Test
    void findBlockCacheReachesMultiRingAndRespectsExcludeDown() {
        StubWorld world = new StubWorld();
        world.set(new BlockPos(3, 63, 0), Blocks.STONE.defaultBlockState());
        Vec3 eyes = new Vec3(0.5, 65.62, 0.5);
        BlockFlyLegacyMode.BlockCache multiRing = BlockFlyLegacyMode.findBlockCache(
                world, new BlockPos(0, 63, 0), true, eyes, 4.5);
        assertNotNull(multiRing);
        assertEquals(new BlockPos(3, 63, 0), multiRing.anchor());
        assertEquals(Direction.WEST, multiRing.face());

        StubWorld ceilingWorld = new StubWorld();
        ceilingWorld.set(new BlockPos(0, 64, 0), Blocks.STONE.defaultBlockState());
        assertNull(BlockFlyLegacyMode.findBlockCache(ceilingWorld, new BlockPos(0, 63, 0), true, eyes, 4.5));
        BlockFlyLegacyMode.BlockCache underside = BlockFlyLegacyMode.findBlockCache(
                ceilingWorld, new BlockPos(0, 63, 0), false, eyes, 4.5);
        assertNotNull(underside);
        assertEquals(Direction.DOWN, underside.face());
    }

    @Test
    void extendPositionStepsUntilFirstReplaceableCell() {
        StubWorld world = new StubWorld();
        world.set(new BlockPos(0, 63, 0), Blocks.STONE.defaultBlockState());
        world.set(new BlockPos(0, 63, 1), Blocks.STONE.defaultBlockState());
        Vec3 extended = BlockFlyLegacyMode.extendPosition(world, 0.5, 64.0, 0.5, 0.0F, 3.0F);
        assertEquals(0.5, extended.x, 1.0E-6);
        assertTrue(extended.z >= 2.0 && extended.z < 2.5);
        assertTrue(BlockFlyLegacyMode.isReplaceable(world, BlockPos.containing(extended.x, extended.y - 1.0, extended.z)));
    }

    @Test
    void navenFaceVisibilityAndFallingPredictionMatchUpstreamMath() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        assertTrue(BlockFlyLCustomMode.checkNavenFaceVisible(new Vec3(1.5, 66.0, 0.5), anchor, Direction.EAST, 20.25));
        assertFalse(BlockFlyLCustomMode.checkNavenFaceVisible(new Vec3(0.5, 66.0, 0.5), anchor, Direction.EAST, 20.25));
        assertFalse(BlockFlyLCustomMode.checkNavenFaceVisible(new Vec3(8.0, 66.0, 0.5), anchor, Direction.EAST, 20.25));
        assertTrue(BlockFlyLCustomMode.checkNavenFaceVisible(new Vec3(0.5, 66.0, 0.5), anchor, Direction.UP, 20.25));
        assertFalse(BlockFlyLCustomMode.checkNavenFaceVisible(new Vec3(0.5, 64.0, 0.5), anchor, Direction.UP, 20.25));

        double predictedY = BlockFlyLCustomMode.predictFallingY(65.0, 0.0, 2);
        assertEquals(64.9216, predictedY, 1.0E-6);
    }

    @Test
    void grimSprintStoredBackwardAimIntersectsSupportCubeAcrossConsecutiveStraightAndDiagonalBlocks() {
        com.mentalfrostbyte.jello.util.math.Rotations.Rotation straightAim =
                new com.mentalfrostbyte.jello.util.math.Rotations.Rotation(180.0F, 78.0F);
        for (int blockZ = 0; blockZ < 5; blockZ++) {
            BlockPos support = new BlockPos(0, 63, blockZ);
            assertTrue(BlockFlyCustomBase.grimRayHitsSupport(
                    new Vec3(0.5, 65.62, blockZ + 1.05), straightAim, support, 4.5));
            assertTrue(BlockFlyCustomBase.grimRayHitsSupport(
                    new Vec3(0.5, 65.62, blockZ + 1.25), straightAim, support, 4.5));
        }

        com.mentalfrostbyte.jello.util.math.Rotations.Rotation diagAim =
                new com.mentalfrostbyte.jello.util.math.Rotations.Rotation(135.0F, 78.0F);
        assertTrue(BlockFlyCustomBase.grimRayHitsSupport(
                new Vec3(1.08, 65.62, 0.88), diagAim, new BlockPos(0, 63, 0), 4.5));
        assertTrue(BlockFlyCustomBase.grimRayHitsSupport(
                new Vec3(1.28, 65.62, 1.08), diagAim, new BlockPos(1, 63, 0), 4.5));

        com.mentalfrostbyte.jello.util.math.Rotations.Rotation forwardLook =
                new com.mentalfrostbyte.jello.util.math.Rotations.Rotation(0.0F, 78.0F);
        assertFalse(BlockFlyCustomBase.grimRayHitsSupport(
                new Vec3(0.5, 65.62, 1.15), forwardLook, new BlockPos(0, 63, 0), 4.5));
    }

    @Test
    void grimSprintYawStepPatternNeverRepeatsConsecutiveDeltaForDuplicateRotPlace() {
        float mouseStep = 0.15F;
        for (int i = 0; i < 20; i++) {
            float deltaA = Math.abs(180.0F - BlockFlyCustomBase.grimYawStepOffset(i) * mouseStep);
            float deltaB = Math.abs(180.0F - BlockFlyCustomBase.grimYawStepOffset(i + 1) * mouseStep);
            assertTrue(Math.abs(deltaA - deltaB) >= 0.1F,
                    "Consecutive aim snaps must differ by >> 0.0001 deg to avoid Grim DuplicateRotPlace");
        }
    }

    private static Input keys(final boolean forward, final boolean backward, final boolean left, final boolean right) {
        return new Input(forward, backward, left, right, false, false, false);
    }

    private static final class StubWorld implements BlockGetter {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void set(final BlockPos pos, final BlockState state) {
            this.blocks.put(pos.immutable(), state);
        }

        @Override
        public BlockEntity getBlockEntity(final BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(final BlockPos pos) {
            return this.blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(final BlockPos pos) {
            return this.getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }
}
