package com.mentalfrostbyte.jello.module.impl.world;

import static org.junit.jupiter.api.Assertions.*;

import com.mentalfrostbyte.jello.event.impl.game.network.EventSendPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GrimDisablerStateTest {
    private static final int PLAYER_ID = 41;
    private static final GrimDisablerState.Options ALL = new GrimDisablerState.Options(true, true, true, true);
    private static final GrimDisablerState.Options DUPLICATE = new GrimDisablerState.Options(false, false, false, true);
    private static final GrimDisablerState.Options MODULO = new GrimDisablerState.Options(false, false, true, false);

    @Test
    void firstSlotZeroAndFirstStopSprintAreAllowed() {
        GrimDisablerState state = new GrimDisablerState();
        assertFalse(apply(state, new ServerboundSetCarriedItemPacket(0), ALL).isCancelled());
        assertTrue(apply(state, new ServerboundSetCarriedItemPacket(0), ALL).isCancelled());
        assertFalse(apply(state, command(PLAYER_ID, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING), ALL).isCancelled());
        assertTrue(apply(state, command(PLAYER_ID, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING), ALL).isCancelled());
        assertFalse(apply(state, command(PLAYER_ID, ServerboundPlayerCommandPacket.Action.START_SPRINTING), ALL).isCancelled());
        assertTrue(apply(state, command(PLAYER_ID, ServerboundPlayerCommandPacket.Action.START_SPRINTING), ALL).isCancelled());
        assertFalse(apply(state, new ServerboundSetCarriedItemPacket(8), ALL).isCancelled());
    }

    @Test
    void invalidSlotsPassAndInvalidatePreviousSelection() {
        GrimDisablerState state = new GrimDisablerState();
        for (int invalid : new int[] {-1, 9, Integer.MAX_VALUE}) {
            apply(state, new ServerboundSetCarriedItemPacket(2), ALL);
            assertFalse(apply(state, new ServerboundSetCarriedItemPacket(invalid), ALL).isCancelled());
            assertFalse(apply(state, new ServerboundSetCarriedItemPacket(2), ALL).isCancelled());
        }
    }

    @Test
    void unrelatedAndForeignEntityCommandsDoNotChangeSprintHistory() {
        GrimDisablerState state = new GrimDisablerState();
        apply(state, command(PLAYER_ID, ServerboundPlayerCommandPacket.Action.START_SPRINTING), ALL);
        for (ServerboundPlayerCommandPacket.Action action : ServerboundPlayerCommandPacket.Action.values()) {
            assertFalse(apply(state, command(PLAYER_ID + 1, action), ALL).isCancelled());
            if (action != ServerboundPlayerCommandPacket.Action.START_SPRINTING && action != ServerboundPlayerCommandPacket.Action.STOP_SPRINTING) {
                assertFalse(apply(state, command(PLAYER_ID, action), ALL).isCancelled());
            }
        }
        assertTrue(apply(state, command(PLAYER_ID, ServerboundPlayerCommandPacket.Action.START_SPRINTING), ALL).isCancelled());
    }

    @Test
    void cancelledEventsDoNotAdvanceSlotsSprintOrModulo() {
        GrimDisablerState state = new GrimDisablerState();
        cancelled(state, new ServerboundSetCarriedItemPacket(3), ALL);
        assertFalse(apply(state, new ServerboundSetCarriedItemPacket(3), ALL).isCancelled());
        cancelled(state, command(PLAYER_ID, ServerboundPlayerCommandPacket.Action.START_SPRINTING), ALL);
        assertFalse(apply(state, command(PLAYER_ID, ServerboundPlayerCommandPacket.Action.START_SPRINTING), ALL).isCancelled());
        assertEquals(0F, yaw(apply(state, rotation(0), ALL)));
        cancelled(state, rotation(90), ALL);
        assertEquals(1806F, yaw(apply(state, rotation(6), ALL)));
    }

    @Test
    void cancelledPlacementDoesNotRememberADelta() {
        GrimDisablerState state = new GrimDisablerState();
        apply(state, rotation(0), DUPLICATE);
        apply(state, rotation(6), DUPLICATE);
        cancelled(state, placement(), DUPLICATE);
        assertEquals(12F, yaw(apply(state, rotation(12), DUPLICATE)));
        apply(state, placement(), DUPLICATE);
        assertEquals(18F + 0.0002F, yaw(apply(state, rotation(18), DUPLICATE)));
    }

    @Test
    void resetAndOptionsChangeDiscardAllSessionHistory() {
        GrimDisablerState state = new GrimDisablerState();
        prime(state);
        state.reset();
        assertFresh(state, ALL);
        prime(state);
        GrimDisablerState.Options changed = new GrimDisablerState.Options(true, true, true, false);
        assertFresh(state, changed);
        assertFalse(state.matchesTransformedLook(1, 0, 0, 0));
    }

    @Test
    void cancelledSettingsChangeDoesNotResetExistingHistory() {
        GrimDisablerState state = new GrimDisablerState();
        apply(state, new ServerboundSetCarriedItemPacket(3), ALL);
        apply(state, rotation(0), ALL);
        cancelled(state, rotation(10), MODULO);
        assertTrue(apply(state, new ServerboundSetCarriedItemPacket(3), ALL).isCancelled());
        assertEquals(1805F, yaw(apply(state, rotation(5), ALL)));
    }

    @Test
    void disabledFiltersAllowDuplicateSlotAndSprintPackets() {
        GrimDisablerState state = new GrimDisablerState();
        for (int i = 0; i < 3; i++) {
            assertFalse(apply(state, new ServerboundSetCarriedItemPacket(0), MODULO).isCancelled());
            assertFalse(apply(state, command(PLAYER_ID, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING), MODULO).isCancelled());
        }
    }

    @Test
    void allDisabledOptionsLeaveEveryPacketUntouched() {
        GrimDisablerState state = new GrimDisablerState();
        GrimDisablerState.Options disabled = new GrimDisablerState.Options(false, false, false, false);
        for (int i = 0; i < 3; i++) {
            for (Packet<?> packet : new Packet<?>[] {
                    new ServerboundSetCarriedItemPacket(0),
                    command(PLAYER_ID, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING),
                    rotation(30), new ServerboundMovePlayerPacket.PosRot(1, 2, 3, 30, 0, true, true),
                    new ServerboundMovePlayerPacket.Pos(1, 2, 3, true, true),
                    new ServerboundMovePlayerPacket.StatusOnly(true, true), placement(),
                    new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 7, 390, 0)}) {
                EventSendPacket event = apply(state, packet, disabled);
                assertSame(packet, event.getPacket());
                assertFalse(event.isCancelled());
            }
        }
    }

    @Test
    void moduloCyclesPerInstanceAndOnlyForRotationPackets() {
        GrimDisablerState state = new GrimDisablerState();
        for (int i = 0; i < 42; i++) {
            assertEquals(0.25F + (i % 20) * 1800, yaw(apply(state, rotation(0.25F), MODULO)));
            ServerboundMovePlayerPacket.Pos position = new ServerboundMovePlayerPacket.Pos(1, 2, 3, false, true);
            ServerboundMovePlayerPacket.StatusOnly status = new ServerboundMovePlayerPacket.StatusOnly(true, false);
            assertSame(position, apply(state, position, MODULO).getPacket());
            assertSame(status, apply(state, status, MODULO).getPacket());
        }
        assertEquals(0.25F, yaw(apply(new GrimDisablerState(), rotation(0.25F), MODULO)));
    }

    @Test
    void replacementPreservesPositionPitchAndBothFlags() {
        GrimDisablerState state = new GrimDisablerState();
        apply(state, rotation(0), MODULO);
        for (boolean ground : new boolean[] {true, false}) {
            for (boolean collision : new boolean[] {true, false}) {
                ServerboundMovePlayerPacket.PosRot full = new ServerboundMovePlayerPacket.PosRot(1.5, -64.25, 999, 15, -32, ground, collision);
                ServerboundMovePlayerPacket.PosRot sent = assertInstanceOf(ServerboundMovePlayerPacket.PosRot.class, apply(state, full, MODULO).getPacket());
                assertEquals(1.5, sent.getX(0));
                assertEquals(-64.25, sent.getY(0));
                assertEquals(999, sent.getZ(0));
                assertEquals(-32F, sent.getXRot(0));
                assertEquals(ground, sent.isOnGround());
                assertEquals(collision, sent.horizontalCollision());
                ServerboundMovePlayerPacket.Rot look = new ServerboundMovePlayerPacket.Rot(15, 32, ground, collision);
                ServerboundMovePlayerPacket.Rot sentLook = assertInstanceOf(ServerboundMovePlayerPacket.Rot.class, apply(state, look, MODULO).getPacket());
                assertEquals(32F, sentLook.getXRot(0));
                assertEquals(ground, sentLook.isOnGround());
                assertEquals(collision, sentLook.horizontalCollision());
                assertFalse(sentLook.hasPosition());
            }
        }
    }

    @Test
    void duplicatePlacementNeedsAnObservedDeltaAndUsesLogicalYaw() {
        GrimDisablerState state = new GrimDisablerState();
        apply(state, rotation(6), DUPLICATE);
        apply(state, placement(), DUPLICATE);
        assertEquals(12F, yaw(apply(state, rotation(12), DUPLICATE)));
        apply(state, placement(), DUPLICATE);
        assertEquals(18F + 0.0002F, yaw(apply(state, rotation(18), DUPLICATE)));
        apply(state, placement(), DUPLICATE);
        assertEquals(24F + 0.0002F, yaw(apply(state, rotation(24), DUPLICATE)));
    }

    @Test
    void duplicateThresholdIsStrictAndPlacementConsumesOnlyLatestDelta() {
        GrimDisablerState state = new GrimDisablerState();
        apply(state, rotation(0), DUPLICATE);
        apply(state, rotation(2), DUPLICATE);
        apply(state, placement(), DUPLICATE);
        assertEquals(4F, yaw(apply(state, rotation(4), DUPLICATE)));
        apply(state, rotation(10), DUPLICATE);
        apply(state, rotation(17), DUPLICATE);
        apply(state, placement(), DUPLICATE);
        apply(state, placement(), DUPLICATE);
        assertEquals(24F + 0.0002F, yaw(apply(state, rotation(24), DUPLICATE)));
        assertEquals(30F, yaw(apply(state, rotation(30), DUPLICATE)));
    }

    @Test
    void duplicateComparisonAllowsOnlyTheOriginalSmallTolerance() {
        GrimDisablerState state = new GrimDisablerState();
        apply(state, rotation(0), DUPLICATE);
        apply(state, rotation(6), DUPLICATE);
        apply(state, placement(), DUPLICATE);
        float near = 12.00005F;
        assertEquals(near + 0.0002F, yaw(apply(state, rotation(near), DUPLICATE)));
        float far = near + 6.0002F;
        assertEquals(far, yaw(apply(state, rotation(far), DUPLICATE)));
    }

    @Test
    void bothYawOptionsFollowOriginalJitterThenModuloOrdering() {
        GrimDisablerState state = new GrimDisablerState();
        apply(state, rotation(0), ALL);
        apply(state, rotation(6), ALL);
        apply(state, placement(), ALL);
        assertEquals((12F + 0.0002F) + 3600F, yaw(apply(state, rotation(12), ALL)));
        // The original tiny nudge can round away under the large offset; do not invent stronger jitter.
        for (int i = 3; i < 19; i++) apply(state, rotation(i * 6), ALL);
        apply(state, placement(), ALL);
        assertEquals((114F + 0.0002F) + 34200F, yaw(apply(state, rotation(114), ALL)));
    }

    @Test
    void nonFiniteAnglesPassUntouchedWithoutAdvancingModuloOrLeavingAUsableLook() {
        GrimDisablerState state = new GrimDisablerState();
        apply(state, rotation(0), ALL);
        for (float bad : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            ServerboundMovePlayerPacket.Rot badYaw = rotation(bad);
            ServerboundMovePlayerPacket.Rot badPitch = new ServerboundMovePlayerPacket.Rot(0, bad, true, true);
            assertSame(badYaw, apply(state, badYaw, ALL).getPacket());
            assertSame(badPitch, apply(state, badPitch, ALL).getPacket());
        }
        assertFalse(state.matchesTransformedLook(0, 0, 0, 0));
        assertEquals(1805F, yaw(apply(state, rotation(5), ALL)));
    }

    @Test
    void hugeYawCannotAcquireAnUnrelatedDirectionThroughFloatRounding() {
        GrimDisablerState state = new GrimDisablerState();
        apply(state, rotation(0), MODULO);
        ServerboundMovePlayerPacket.Rot enormous = rotation(268435456F);
        assertSame(enormous, apply(state, enormous, MODULO).getPacket());
        assertTrue(state.matchesTransformedLook(enormous.getYRot(0), 0, enormous.getYRot(0), 0));
    }

    @Test
    void matchingModernUseCopiesWireAnglesWithoutAdvancingModulo() {
        GrimDisablerState state = new GrimDisablerState();
        apply(state, rotation(0), MODULO);
        apply(state, new ServerboundMovePlayerPacket.Rot(30, -15, false, true), MODULO);
        ServerboundUseItemPacket use = new ServerboundUseItemPacket(InteractionHand.OFF_HAND, 37, 390, -15);
        ServerboundUseItemPacket sent = assertInstanceOf(ServerboundUseItemPacket.class, apply(state, use, MODULO).getPacket());
        assertEquals(1830F, sent.getYRot());
        assertEquals(-15F, sent.getXRot());
        assertEquals(InteractionHand.OFF_HAND, sent.getHand());
        assertEquals(37, sent.getSequence());
        assertEquals(3630F, yaw(apply(state, rotation(30), MODULO)));
    }

    @Test
    void unrelatedModernUseAimAndPacketsRemainUntouched() {
        GrimDisablerState state = new GrimDisablerState();
        apply(state, rotation(30), MODULO);
        apply(state, rotation(30), MODULO);
        for (ServerboundUseItemPacket use : new ServerboundUseItemPacket[] {
                new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 1, 31, 0),
                new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 1, 30, 0.01F),
                new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 1, Float.NaN, 0)}) {
            assertSame(use, apply(state, use, MODULO).getPacket());
        }
        ServerboundUseItemOnPacket placement = placement();
        assertSame(placement, apply(state, placement, MODULO).getPacket());
    }

    @Test
    void transformedMatchRequiresStoredExactWireLookAndMatchingLogicalDirection() {
        GrimDisablerState state = new GrimDisablerState();
        float logical = 0.123456F;
        for (int i = 0; i < 19; i++) apply(state, rotation(logical), MODULO);
        float wire = yaw(apply(state, rotation(logical), MODULO));
        assertTrue(Math.abs((double) wire - 34200 - logical) > 0.001);
        assertTrue(state.matchesTransformedLook(wire, 0, logical, 0));
        assertFalse(state.matchesTransformedLook(Math.nextUp(wire), 0, logical, 0));
        assertFalse(state.matchesTransformedLook(wire, 0.001F, logical, 0));
        assertFalse(state.matchesTransformedLook(wire, 0, logical + 0.01F, 0));
        assertFalse(state.matchesTransformedLook(wire, 0, Float.NaN, 0));
        state.reset();
        assertFalse(state.matchesTransformedLook(wire, 0, logical, 0));
    }

    private static void prime(GrimDisablerState state) {
        apply(state, new ServerboundSetCarriedItemPacket(0), ALL);
        apply(state, command(PLAYER_ID, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING), ALL);
        apply(state, rotation(0), ALL);
        apply(state, rotation(6), ALL);
        apply(state, placement(), ALL);
    }

    private static void assertFresh(GrimDisablerState state, GrimDisablerState.Options options) {
        assertFalse(apply(state, new ServerboundSetCarriedItemPacket(0), options).isCancelled());
        assertFalse(apply(state, command(PLAYER_ID, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING), options).isCancelled());
        assertEquals(0F, yaw(apply(state, rotation(0), options)));
    }

    private static EventSendPacket apply(GrimDisablerState state, Packet<?> packet, GrimDisablerState.Options options) {
        EventSendPacket event = new EventSendPacket(packet);
        state.apply(event, PLAYER_ID, options);
        return event;
    }

    private static void cancelled(GrimDisablerState state, Packet<?> packet, GrimDisablerState.Options options) {
        EventSendPacket event = new EventSendPacket(packet);
        event.cancel();
        state.apply(event, PLAYER_ID, options);
        assertTrue(event.isCancelled());
        assertSame(packet, event.getPacket());
    }

    private static ServerboundMovePlayerPacket.Rot rotation(float yaw) {
        return new ServerboundMovePlayerPacket.Rot(yaw, 0, false, false);
    }

    private static float yaw(EventSendPacket event) {
        return assertInstanceOf(ServerboundMovePlayerPacket.class, event.getPacket()).getYRot(0);
    }

    private static ServerboundUseItemOnPacket placement() {
        return new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false), 1);
    }

    private static ServerboundPlayerCommandPacket command(int entityId, ServerboundPlayerCommandPacket.Action action) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(entityId);
            buffer.writeEnum(action);
            buffer.writeVarInt(0);
            return ServerboundPlayerCommandPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }
}
