package com.mentalfrostbyte.jello.module.impl.movement.blockfly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Input;
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

    private static Input keys(final boolean forward, final boolean backward, final boolean left, final boolean right) {
        return new Input(forward, backward, left, right, false, false, false);
    }
}
