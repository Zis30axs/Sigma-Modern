package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.*;

import com.mentalfrostbyte.jello.module.impl.gui.Radar;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class RadarContactsTest {
    @Test void sweepUsesGameTicksAndCoversBothDirections() {
        assertEquals(-60.0, RadarContacts.sweep(0, 60, 120), 0.001);
        assertEquals(0.0, RadarContacts.sweep(15, 60, 120), 0.001);
        assertEquals(60.0, RadarContacts.sweep(30, 60, 120), 0.001);
        assertEquals(0.0, RadarContacts.sweep(45, 60, 120), 0.001);
        assertEquals(-60.0, RadarContacts.sweep(60, 60, 120), 0.001);
        assertEquals(RadarContacts.sweep(15, 60, 120), RadarContacts.sweep(75, 60, 120));
    }

    @Test void skippedFramesTraceTheTurnaroundWithoutAdmittingRearContacts() {
        assertTrue(RadarContacts.swept(59, 25, 35, 60, 120));
        assertFalse(RadarContacts.swept(0, 25, 35, 60, 120));
        assertTrue(RadarContacts.swept(-59, 55, 65, 60, 120));
        assertFalse(RadarContacts.swept(0, 55, 65, 60, 120));
        assertTrue(RadarContacts.swept(17, 4, 75, 60, 120));
        assertFalse(RadarContacts.swept(150, 4, 75, 60, 120));
        assertFalse(RadarContacts.swept(0, 20, 10, 60, 120));
    }

    @Test void srcDoesNotRevealContactsBeforeTheBeamAndKeepsTheMeasuredSnapshot() {
        RadarContacts.Tracker tracker = new RadarContacts.Tracker();
        assertTrue(tracker.advance(List.of(detection(1, 0, 0)), Radar.Mode.SRC, 0, 60, 120, -1).contacts().isEmpty());
        RadarContacts.Frame measured = tracker.advance(List.of(detection(1, 0, 15)), Radar.Mode.SRC, 15, 60, 120, -1);
        assertEquals(1, measured.contacts().size());
        assertEquals(15, measured.contacts().getFirst().seenTick());
        RadarContacts.Frame later = tracker.advance(List.of(detection(1, 20, 16)), Radar.Mode.SRC, 16, 60, 120, -1);
        assertEquals(measured.contacts(), later.contacts());
        assertEquals(-1, later.selectedId());
    }

    @Test void srcRemovesExpiredAndNoLongerLoadedEchoes() {
        RadarContacts.Tracker tracker = new RadarContacts.Tracker();
        assertEquals(1, tracker.advance(List.of(detection(1, -60, 0)), Radar.Mode.SRC, 0, 60, 120, -1).contacts().size());
        for (int tick = 1; tick <= 60; tick++) {
            double awayFromBeam = RadarContacts.sweep(tick, 60, 120) > 0 ? -60 : 60;
            RadarContacts.Frame frame = tracker.advance(List.of(detection(1, awayFromBeam, tick)), Radar.Mode.SRC, tick, 60, 120, -1);
            if (tick == 59) assertEquals(1, frame.contacts().size());
            if (tick == 60) assertTrue(frame.contacts().isEmpty());
        }
        tracker.reset();
        assertEquals(1, tracker.advance(List.of(detection(1, -60, 120)), Radar.Mode.SRC, 120, 60, 120, -1).contacts().size());
        assertTrue(tracker.advance(List.of(), Radar.Mode.SRC, 121, 60, 120, -1).contacts().isEmpty());
    }

    @Test void acqKeepsOneContactUntilItLeavesTheCaptureCone() {
        RadarContacts.Tracker tracker = new RadarContacts.Tracker();
        assertEquals(1, tracker.advance(List.of(detection(1, 2, 0), detection(2, 8, 0)), Radar.Mode.ACQ, 0, 60, 30, -1).selectedId());
        RadarContacts.Frame retained = tracker.advance(List.of(detection(1, 10, 1), detection(2, 0, 1)), Radar.Mode.ACQ, 1, 60, 30, 2);
        assertEquals(1, retained.selectedId());
        assertEquals(List.of(1), retained.contacts().stream().map(RadarContacts.Contact::entityId).toList());
        assertEquals(2, tracker.advance(List.of(detection(2, 0, 2)), Radar.Mode.ACQ, 2, 60, 30, -1).selectedId());
        assertEquals(-1, tracker.advance(List.of(), Radar.Mode.ACQ, 3, 60, 30, -1).selectedId());
    }

    @Test void twsPreferenceOverridesSelectionButOtherwisePreservesIt() {
        RadarContacts.Tracker tracker = new RadarContacts.Tracker();
        List<RadarContacts.Detection> contacts = List.of(detection(1, 2, 0), detection(2, 20, 0));
        assertEquals(1, tracker.advance(contacts, Radar.Mode.TWS, 0, 60, 120, -1).selectedId());
        RadarContacts.Frame preferred = tracker.advance(contacts, Radar.Mode.TWS, 1, 60, 120, 2);
        assertEquals(2, preferred.selectedId());
        assertEquals(2, preferred.contacts().size());
        assertEquals(2, tracker.advance(contacts, Radar.Mode.TWS, 2, 60, 120, -1).selectedId());
        tracker.reset();
        assertEquals(1, tracker.advance(contacts, Radar.Mode.TWS, 3, 60, 120, -1).selectedId());
        assertEquals(1, tracker.advance(contacts, Radar.Mode.TWS, -1, 60, 120, -1).selectedId());
    }

    @Test void echoHistoryHasAHardCapacityAndReturnedFramesAreImmutable() {
        RadarContacts.Tracker tracker = new RadarContacts.Tracker();
        List<RadarContacts.Detection> detections = new ArrayList<>();
        for (int id = 0; id < 300; id++) detections.add(detection(id, -60, 0));
        RadarContacts.Frame frame = tracker.advance(detections, Radar.Mode.SRC, 0, 60, 120, -1);
        assertEquals(RadarContacts.MAX_CONTACTS, frame.contacts().size());
        assertThrows(UnsupportedOperationException.class, () -> frame.contacts().clear());
        tracker.reset();
        assertEquals(RadarContacts.MAX_CONTACTS, frame.contacts().size());
        assertTrue(tracker.advance(List.of(), Radar.Mode.SRC, 1, 60, 120, -1).contacts().isEmpty());
    }

    @Test void manualTrackChoosesClosestToCrosshairAndTogglesBackWithinTheSameTick() {
        RadarContacts.Tracker tracker = new RadarContacts.Tracker();
        List<RadarContacts.Detection> contacts = List.of(detection(1, 8, 15), detection(2, 2, 15));
        assertFalse(tracker.advance(contacts, Radar.Mode.TWS, 15, 60, 120, 1).tracking());
        assertTrue(tracker.toggleTrack(contacts));
        RadarContacts.Frame tracked = tracker.advance(contacts, Radar.Mode.TWS, 15, 60, 120, 1);
        assertTrue(tracked.tracking());
        assertEquals(2, tracked.selectedId());
        assertEquals(List.of(2), tracked.contacts().stream().map(RadarContacts.Contact::entityId).toList());
        assertEquals(0.0, tracked.sweepDegrees());
        assertTrue(tracker.toggleTrack(contacts));
        RadarContacts.Frame restored = tracker.advance(contacts, Radar.Mode.TWS, 15, 60, 120, 1);
        assertFalse(restored.tracking());
        assertEquals(2, restored.contacts().size());
        assertEquals(1, restored.selectedId());
    }

    @Test void manualTrackIgnoresAuraSelectionAndKeepsFollowingOutsideTheCaptureCone() {
        RadarContacts.Tracker tracker = new RadarContacts.Tracker();
        assertTrue(tracker.toggleTrack(List.of(detection(1, 1, 0))));
        List<RadarContacts.Detection> moved = List.of(detection(1, 80, 1), detection(2, 0, 1));
        RadarContacts.Frame frame = tracker.advance(moved, Radar.Mode.ACQ, 1, 60, 30, 2);
        assertTrue(frame.tracking());
        assertEquals(1, frame.selectedId());
        assertEquals(moved.getFirst().contact(), frame.contacts().getFirst());
        assertEquals(1, frame.contacts().size());
    }

    @Test void directCrosshairHitWinsEvenWhenItsCenterIsOutsideTheCaptureCone() {
        RadarContacts.Tracker tracker = new RadarContacts.Tracker();
        RadarContacts.Detection headHit = new RadarContacts.Detection(detection(1, 2, 0).contact(), 2, 45);
        List<RadarContacts.Detection> candidates = List.of(headHit, detection(2, 0, 0));
        assertTrue(tracker.toggleTrack(candidates, 1));
        RadarContacts.Frame frame = tracker.advance(candidates, Radar.Mode.TWS, 0, 60, 120, 2);
        assertTrue(frame.tracking());
        assertEquals(1, frame.selectedId());
    }

    @Test void manualTrackCannotAcquireOutsideItsConeAndDoesNotInventATarget() {
        RadarContacts.Tracker tracker = new RadarContacts.Tracker();
        assertFalse(tracker.toggleTrack(List.of()));
        assertFalse(tracker.toggleTrack(List.of(detection(1, 15.1, 0), detection(2, 80, 0))));
        assertFalse(tracker.isTracking());
        assertTrue(tracker.toggleTrack(List.of(detection(1, 15, 0))));
    }

    @Test void lostOrReusedEntityIdsEndManualTrackInsteadOfLockingAReplacement() {
        RadarContacts.Tracker tracker = new RadarContacts.Tracker();
        RadarContacts.Detection original = detection(1, 1, 0);
        assertTrue(tracker.toggleTrack(List.of(original)));
        RadarContacts.Detection replacement = new RadarContacts.Detection(original.contact(), 1, 1, new UUID(9, 1));
        RadarContacts.Frame reused = tracker.advance(List.of(replacement), Radar.Mode.TWS, 1, 60, 120, 1);
        assertFalse(reused.tracking());
        assertFalse(tracker.isTracking());
        assertTrue(tracker.toggleTrack(List.of(original)));
        RadarContacts.Frame lost = tracker.advance(List.of(), Radar.Mode.TWS, 2, 60, 120, -1);
        assertFalse(lost.tracking());
        assertEquals(-1, lost.selectedId());
        assertTrue(lost.contacts().isEmpty());
    }

    @Test void contextResetDropsManualTrackAndReturnsToTheConfiguredSearchMode() {
        RadarContacts.Tracker tracker = new RadarContacts.Tracker();
        List<RadarContacts.Detection> contacts = List.of(detection(1, 0, 15));
        assertTrue(tracker.toggleTrack(contacts));
        assertTrue(tracker.advance(contacts, Radar.Mode.SRC, 15, 60, 120, -1).tracking());
        // The facade applies this reset to a world/player/session/settings change or disabled module.
        tracker.reset();
        RadarContacts.Frame frame = tracker.advance(contacts, Radar.Mode.SRC, 15, 60, 120, -1);
        assertFalse(frame.tracking());
        assertEquals(-1, frame.selectedId());
        assertEquals(1, frame.contacts().size());
    }

    private static RadarContacts.Detection detection(final int id, final double bearing, final long tick) {
        RadarContacts.Contact contact = new RadarContacts.Contact(id, "Contact " + id,
                new Vec3(id, 64, 12), new Vec3(0, 0, -0.1), false, tick);
        return new RadarContacts.Detection(contact, bearing, Math.abs(bearing));
    }
}
