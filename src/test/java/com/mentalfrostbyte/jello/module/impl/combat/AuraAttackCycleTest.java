package com.mentalfrostbyte.jello.module.impl.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.event.EventState;
import com.mentalfrostbyte.jello.event.impl.player.movement.EventMotion;
import com.mentalfrostbyte.jello.util.math.Rotations;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class AuraAttackCycleTest {

    private static final Vec3 EYE = new Vec3(0.0, 1.62, 0.0);
    private static final Rotation AIM = new Rotation(0.0F, 0.0F);
    private static final Rotation CAMERA = new Rotation(180.0F, 0.0F);
    private static final AABB FRONT = new AABB(-0.3, 0.0, 1.0, 0.3, 1.95, 1.6);
    private static final AABB BACK = new AABB(-0.3, 0.0, -1.6, 0.3, 1.95, -1.0);

    @Test
    void idleSuppressionLeavesThreeHitsToBeCheckedAgainstTheBackwardCamera() {
        FollowingReportCheck server = new FollowingReportCheck();
        server.report(AIM);
        for (int i = 0; i < 3; i++) {
            server.attack(FRONT);
            // The old motion path sends nothing: standing still, unchanged aura aim and ground state.
        }
        assertEquals(3, server.queued());
        assertEquals(0, server.misses);

        server.report(CAMERA);
        assertEquals(3, server.misses, "all three old hits are checked when disabling restores the backward camera");
    }

    @Test
    void everyUnchangedAttackGetsAFollowingAimReportBeforeDisabling() {
        AuraAttackCycle cycle = new AuraAttackCycle();
        cycle.reset(AIM);
        FollowingReportCheck server = new FollowingReportCheck();
        server.report(AIM);

        for (int i = 0; i < 3; i++) {
            server.attack(FRONT);
            cycle.attacked(AIM, true);
            EventMotion motion = motion(CAMERA);
            cycle.motion(motion, AIM, true);
            assertEquals(AIM.yaw(), motion.getYaw());
            assertTrue(motion.isRotationForced(), "the following report is required even with unchanged aim");
            assertEquals(1, server.queued(), "PRE does not pretend that the movement was already sent");

            ServerboundMovePlayerPacket packet = packet(new Rotation(motion.getYaw(), motion.getPitch()));
            server.report(packet);
            cycle.reported(packet);
            motion.setState(EventState.POST);
            cycle.motion(motion, AIM, true);
            assertEquals(0, server.queued());
            assertEquals(0, server.misses);
        }

        List<Rotation> restored = new ArrayList<>();
        cycle.stop(CAMERA, look -> {
            restored.add(look);
            server.report(look);
        });
        assertEquals(List.of(CAMERA), restored, "the old hits were drained, so only the camera needs restoring");
        assertEquals(0, server.misses);
        assertNull(cycle.lastSent());
    }

    @Test
    void midTickDisableDrainsTheHitBeforeRestoringTheCameraAndManualAttack() {
        AuraAttackCycle cycle = new AuraAttackCycle();
        cycle.reset(AIM);
        FollowingReportCheck server = new FollowingReportCheck();
        server.report(AIM);
        server.attack(FRONT);
        cycle.attacked(AIM, true);

        List<Rotation> restored = new ArrayList<>();
        cycle.stop(CAMERA, look -> {
            restored.add(look);
            server.report(look);
        });
        assertEquals(List.of(AIM, CAMERA), restored, "an earlier identical report cannot finish the new hit");
        assertEquals(0, server.queued());
        assertEquals(0, server.misses);

        server.attack(BACK);
        server.report(CAMERA);
        assertEquals(0, server.misses, "the next manual attack starts from the restored camera look");
        cycle.stop(CAMERA, restored::add);
        assertEquals(List.of(AIM, CAMERA), restored, "stopping again cannot replay a hit or a restore");
    }

    @Test
    void cancelledMotionKeepsTheUnreportedHitForDisable() {
        AuraAttackCycle cycle = new AuraAttackCycle();
        cycle.reset(AIM);
        cycle.attacked(AIM, true);
        EventMotion motion = motion(CAMERA);
        cycle.motion(motion, AIM, true);
        motion.cancel();
        motion.setState(EventState.POST);
        cycle.motion(motion, AIM, true);

        List<Rotation> restored = new ArrayList<>();
        cycle.stop(CAMERA, restored::add);
        assertEquals(List.of(AIM, CAMERA), restored);
    }

    @Test
    void nonSilentUnchangedAttackAlsoRequiresAFollowingReport() {
        AuraAttackCycle cycle = new AuraAttackCycle();
        cycle.reset(AIM);
        cycle.attacked(AIM, false);
        EventMotion motion = motion(AIM);
        cycle.motion(motion, AIM, false);
        assertTrue(motion.isRotationForced());

        List<Rotation> restored = new ArrayList<>();
        cycle.stop(CAMERA, restored::add);
        assertEquals(List.of(AIM), restored, "without silent ownership only the pending hit needs draining");
    }

    @Test
    void anUnrelatedMovementRotationCannotFinishTheHit() {
        AuraAttackCycle cycle = new AuraAttackCycle();
        cycle.reset(AIM);
        cycle.attacked(AIM, true);
        cycle.reported(packet(CAMERA));
        assertEquals(CAMERA, cycle.lastSent());

        List<Rotation> restored = new ArrayList<>();
        cycle.stop(CAMERA, restored::add);
        assertEquals(List.of(AIM, CAMERA), restored, "the pending aim flush changes the look before camera handback");
    }

    @Test
    void positionOnlyPacketsDoNotPretendToReportTheAim() {
        AuraAttackCycle cycle = new AuraAttackCycle();
        cycle.reset(CAMERA);
        cycle.attacked(AIM, true);
        cycle.reported(new ServerboundMovePlayerPacket.Pos(0.0, 0.0, 0.0, true, false));
        assertEquals(CAMERA, cycle.lastSent());
        List<Rotation> restored = new ArrayList<>();
        cycle.stop(CAMERA, restored::add);
        assertEquals(List.of(AIM, CAMERA), restored);
    }

    @Test
    void aPositionReportAfterTheHitCanFinishItUsingTheUnchangedServerLook() {
        AuraAttackCycle cycle = new AuraAttackCycle();
        cycle.reset(AIM);
        cycle.attacked(AIM, true);
        cycle.reported(new ServerboundMovePlayerPacket.Pos(0.0, 0.0, 0.0, true, false));

        List<Rotation> restored = new ArrayList<>();
        cycle.stop(CAMERA, restored::add);
        assertEquals(List.of(CAMERA), restored, "an actual following movement can drain the hit without new angles");
    }

    @Test
    void cancelledOutgoingMovementCannotBeInferredFromTheOldIdenticalLook() {
        AuraAttackCycle cycle = new AuraAttackCycle();
        cycle.reset(AIM);
        cycle.attacked(AIM, true);
        EventMotion motion = motion(CAMERA);
        cycle.motion(motion, AIM, true);
        // EventSendPacket cancels the packet. LocalPlayer still fires EventMotion POST, but reported() is never called.
        motion.setState(EventState.POST);
        cycle.motion(motion, AIM, true);

        List<Rotation> restored = new ArrayList<>();
        cycle.stop(CAMERA, restored::add);
        assertEquals(List.of(AIM, CAMERA), restored, "the old matching angle is not a report following this hit");
    }

    @Test
    void aCancelledCameraHandbackCannotLoseOwnershipOfAPendingHit() {
        AuraAttackCycle cycle = new AuraAttackCycle();
        cycle.reset(CAMERA);
        cycle.attacked(AIM, true);
        EventMotion handback = motion(CAMERA);
        cycle.motion(handback, null, true);
        // The forced packet is cancelled downstream, so the old server look is still the camera.
        handback.setState(EventState.POST);
        cycle.motion(handback, null, true);

        List<Rotation> restored = new ArrayList<>();
        cycle.stop(CAMERA, restored::add);
        assertEquals(List.of(AIM, CAMERA), restored, "draining the pending hit must still be followed by camera handback");
    }

    @Test
    void aCancelledReportIsDrainedBeforeTheNextTickLosesItsTarget() {
        AuraAttackCycle cycle = new AuraAttackCycle();
        cycle.reset(AIM);
        FollowingReportCheck server = new FollowingReportCheck();
        server.report(AIM);
        server.attack(FRONT);
        cycle.attacked(AIM, true);
        EventMotion cancelled = motion(CAMERA);
        cycle.motion(cancelled, AIM, true);
        cancelled.cancel();

        List<Rotation> reports = new ArrayList<>();
        cycle.flushAttack(look -> {
            reports.add(look);
            server.report(look);
        });
        EventMotion handback = motion(CAMERA);
        cycle.motion(handback, null, true);
        cycle.reported(packet(CAMERA));
        server.report(CAMERA);
        handback.setState(EventState.POST);
        cycle.motion(handback, null, true);
        assertEquals(List.of(AIM), reports);
        assertEquals(0, server.misses);
        cycle.stop(CAMERA, reports::add);
        assertEquals(List.of(AIM), reports, "the new tick already completed the handback");
    }

    @Test
    void aChangedSilentAimIsForcedWithoutAnAttackAfterATeleport() {
        AuraAttackCycle cycle = new AuraAttackCycle();
        cycle.reset(AIM);
        cycle.reported(new ServerboundMovePlayerPacket.PosRot(0.0, 0.0, 0.0,
                CAMERA.yaw(), CAMERA.pitch(), false, false));
        EventMotion motion = motion(AIM);
        cycle.motion(motion, AIM, true);
        assertTrue(motion.isRotationForced(), "the server's teleport confirmation look can differ from the local cache");

        cycle.reported(packet(AIM));
        List<Rotation> restored = new ArrayList<>();
        cycle.stop(AIM, restored::add);
        assertTrue(restored.isEmpty(), "no pending hit and camera already matches the actual report");
    }

    @Test
    void handingBackTheCameraKeepsOwnershipUntilItsReportIsKnown() {
        AuraAttackCycle cycle = new AuraAttackCycle();
        cycle.reset(AIM);
        cycle.motion(motion(CAMERA), AIM, true);

        EventMotion handback = motion(CAMERA);
        cycle.motion(handback, null, true);
        assertTrue(handback.isRotationForced());
        handback.cancel();
        List<Rotation> restored = new ArrayList<>();
        cycle.stop(CAMERA, restored::add);
        assertEquals(List.of(CAMERA), restored, "a cancelled handback cannot leave the server facing the aura target");
    }

    @Test
    void successfulCameraHandbackNeedsNoFurtherRestore() {
        AuraAttackCycle cycle = new AuraAttackCycle();
        cycle.reset(AIM);
        cycle.motion(motion(CAMERA), AIM, true);
        EventMotion handback = motion(CAMERA);
        cycle.motion(handback, null, true);
        cycle.reported(packet(CAMERA));
        handback.setState(EventState.POST);
        cycle.motion(handback, null, true);

        List<Rotation> restored = new ArrayList<>();
        cycle.stop(AIM, restored::add);
        assertTrue(restored.isEmpty(), "the aura already handed ownership back to vanilla");
    }

    @Test
    void itemActionsWaitForAnAttackAndItsCameraHandbackEvenAfterTheTargetDisappears() {
        AuraAttackCycle cycle = new AuraAttackCycle();
        cycle.reset(CAMERA);
        assertFalse(cycle.isBusy());
        cycle.attacked(AIM, true);
        assertTrue(cycle.isBusy());

        cycle.reported(packet(AIM));
        assertTrue(cycle.isBusy(), "draining the attack does not hand a silent look back to the camera");
        EventMotion handback = motion(CAMERA);
        cycle.motion(handback, null, true);
        handback.setState(EventState.POST);
        cycle.motion(handback, null, true);
        assertTrue(cycle.isBusy(), "a cancelled outgoing handback cannot let another item use steal the look");

        cycle.reported(packet(CAMERA));
        cycle.motion(handback, null, true);
        assertFalse(cycle.isBusy(), "the target need not exist once the original look is restored");
    }

    @Test
    void rotationNoneBlocksOtherItemActionsOnlyUntilTheActualAttackHasBeenReported() {
        AuraAttackCycle cycle = new AuraAttackCycle();
        cycle.reset(CAMERA);
        assertFalse(cycle.isBusy(), "an idle enabled aura does not reserve item use");
        cycle.attacked(AIM, false);
        assertTrue(cycle.isBusy());
        cycle.reported(packet(CAMERA));
        assertTrue(cycle.isBusy(), "an unrelated facing does not finish the attack");
        cycle.reported(packet(AIM));
        assertFalse(cycle.isBusy(), "non-silent attacks have no camera handback to wait for");
    }

    @Test
    void disablingOrResettingAnActiveCycleReleasesTheItemActionReservation() {
        AuraAttackCycle cycle = new AuraAttackCycle();
        cycle.reset(CAMERA);
        cycle.attacked(AIM, true);
        cycle.reset(null);
        assertFalse(cycle.isBusy());

        cycle.attacked(AIM, true);
        List<Rotation> reported = new ArrayList<>();
        cycle.stop(CAMERA, reported::add);
        assertEquals(List.of(AIM, CAMERA), reported);
        assertFalse(cycle.isBusy());
    }

    @Test
    void resetAndFailedRestoreBothDiscardOldState() {
        AuraAttackCycle cycle = new AuraAttackCycle();
        cycle.reset(AIM);
        cycle.attacked(AIM, true);
        cycle.reset(CAMERA);
        EventMotion motion = motion(CAMERA);
        cycle.motion(motion, null, false);
        assertFalse(motion.isRotationForced());

        cycle.attacked(AIM, true);
        assertThrows(IllegalStateException.class, () -> cycle.stop(CAMERA, look -> {
            throw new IllegalStateException("send failed");
        }));
        assertNull(cycle.lastSent());
        List<Rotation> restored = new ArrayList<>();
        cycle.stop(CAMERA, restored::add);
        assertTrue(restored.isEmpty(), "a failed cleanup cannot leave an old hit armed");
    }

    private static EventMotion motion(final Rotation camera) {
        return new EventMotion(0.0, 0.0, 0.0, camera.yaw(), camera.pitch(), true);
    }

    private static ServerboundMovePlayerPacket packet(final Rotation look) {
        return new ServerboundMovePlayerPacket.Rot(look.yaw(), look.pitch(), true, false);
    }

    /** The observed server behaviour: pending hits are ray-tested when the next look report arrives. */
    private static final class FollowingReportCheck {
        private final List<AABB> pending = new ArrayList<>();
        private int misses;

        void attack(final AABB target) {
            this.pending.add(target);
        }

        int queued() {
            return this.pending.size();
        }

        void report(final ServerboundMovePlayerPacket packet) {
            assertTrue(packet.hasRotation());
            this.report(new Rotation(packet.getYRot(0.0F), packet.getXRot(0.0F)));
        }

        void report(final Rotation look) {
            for (AABB target : this.pending) {
                if (Rotations.hit(EYE, look, target, 3.0).isEmpty()) {
                    this.misses++;
                }
            }
            this.pending.clear();
        }
    }
}
