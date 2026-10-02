package com.mentalfrostbyte.jello.module.impl.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.setting.EnumSetting;
import com.mentalfrostbyte.jello.setting.Setting;
import com.mentalfrostbyte.jello.util.math.Rotations;
import com.mentalfrostbyte.jello.util.math.Rotations.Rotation;
import com.mentalfrostbyte.jello.util.movement.MovementCorrection;
import com.mentalfrostbyte.jello.util.movement.MovementCorrector;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class KillAuraTest {

    private static final double STEP = Rotations.mouseStep(0.5);

    /** GrimAC's AimModulo360, as a rule over the yaws a client reports in turn. */
    private static int modulo360Flags(final List<Float> yaws) {
        int flags = 0;
        float lastDelta = 0.0F;
        for (int i = 1; i < yaws.size(); i++) {
            float yaw = yaws.get(i);
            float delta = yaw - yaws.get(i - 1);
            if (yaw < 360 && yaw > -360 && Math.abs(delta) > 320 && Math.abs(lastDelta) < 30) {
                flags++;
            }
            lastDelta = delta;
        }
        return flags;
    }

    /**
     * A target north of the player, stepping from one side of the -180/180 seam to the other and back, and staying a
     * few ticks each time (the rule wants a quiet tick before a snap, as the lab's target gives it).
     */
    private static List<Rotation> acrossTheSeam() {
        Vec3 eye = new Vec3(0.5, 1.62, 0.5);
        List<Rotation> wanted = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            double x = i % 2 == 0 ? 0.15 : 0.85;
            for (int tick = 0; tick < 3; tick++) {
                wanted.add(Rotations.towards(eye, new Vec3(x, 1.0, -2.0)));
            }
        }
        return wanted;
    }

    @Test
    void aWrappedYawSnapsRoundTheSeamAndAContinuousOneDoesNot() {
        List<Float> wrapped = new ArrayList<>(List.of(180.0F));
        List<Float> continuous = new ArrayList<>(List.of(180.0F));
        for (Rotation wanted : acrossTheSeam()) {
            wrapped.add(wanted.yaw());
            continuous.add(Rotations.continuous(continuous.getLast(), wanted.yaw()));
        }
        assertEquals(5, modulo360Flags(wrapped), "atan2's yaw jumps ~344 degrees at each of the five crossings: " + wrapped);
        assertEquals(0, modulo360Flags(continuous), "the same looks kept continuous turn ~16 degrees: " + continuous);
    }

    @Test
    void claude1SettlesInWholeMouseSteps() {
        Rotation at = new Rotation(0.0F, 0.0F);
        Rotation goal = new Rotation(100.0F, 20.0F);
        List<Float> deltas = new ArrayList<>();
        for (int tick = 0; tick < 20; tick++) {
            Rotation next = KillAura.claude1(at, goal, STEP);
            deltas.add(next.yaw() - at.yaw());
            double steps = (next.yaw() - at.yaw()) / STEP;
            assertEquals(Math.round(steps), steps, 1e-3, "tick " + tick + " turned a whole number of mouse steps");
            at = next;
        }
        assertEquals(100.0F, at.yaw(), STEP, "within a mouse step of the goal");
        assertEquals(20.0F, at.pitch(), STEP);
        assertTrue(deltas.get(0) <= 55.0F + STEP && deltas.get(0) > 50.0F, "fast at first, capped at 55: " + deltas.get(0));
        assertTrue(deltas.get(0) > deltas.get(1) && deltas.get(1) > deltas.get(2), "then easing out: " + deltas);
    }

    @Test
    void easeMovesAtLeastThreeDegreesAndAtMostTheCap() {
        assertEquals(3.0F, KillAura.ease(4.0F, 55.0F), 1e-6);
        assertEquals(2.0F, KillAura.ease(2.0F, 55.0F), 1e-6, "never past the goal");
        assertEquals(-30.0F, KillAura.ease(-90.0F, 30.0F), 1e-6);
        assertEquals(12.0F, KillAura.ease(20.0F, 55.0F), 1e-6, "60% of what is left");
    }

    @Test
    void autoTimingKeepsTheCpsLimiterOnEveryProtocol() {
        assertFalse(KillAura.timingDue(KillAura.Timing.AUTO, false, true, false),
                "modern Auto must not attack just because the cooldown is full");
        assertFalse(KillAura.timingDue(KillAura.Timing.AUTO, false, false, true),
                "modern Auto also waits for the vanilla cooldown");
        assertTrue(KillAura.timingDue(KillAura.Timing.AUTO, false, true, true),
                "modern Auto attacks only when both gates are ready");

        assertFalse(KillAura.timingDue(KillAura.Timing.AUTO, true, false, false));
        assertTrue(KillAura.timingDue(KillAura.Timing.AUTO, true, false, true),
                "1.8 Auto has no cooldown gate, but still obeys CPS");
        assertTrue(KillAura.timingDue(KillAura.Timing.CPS, false, false, true));
        assertTrue(KillAura.timingDue(KillAura.Timing.COOLDOWN, false, true, false));
    }

    @Test
    void movCorCanFollowTheModuleWithoutChangingTheOldChoices() {
        MovementCorrector corrector = new MovementCorrector();
        assertEquals(MovementCorrection.STRICT, KillAura.MovementCorrectorMode.MOVCOR.resolve(corrector));
        assertEquals(MovementCorrection.CLAUDE3, KillAura.MovementCorrectorMode.CLAUDE3.resolve(corrector));

        @SuppressWarnings("unchecked")
        EnumSetting<MovementCorrection> mode = (EnumSetting<MovementCorrection>) corrector.setting("Mode").orElseThrow();
        mode.set(MovementCorrection.SILENT);
        assertEquals(MovementCorrection.SILENT, KillAura.MovementCorrectorMode.MOVCOR.resolve(corrector));
    }

    @Test
    void onlyTheSettingsInUseAreShown() {
        KillAura aura = new KillAura();
        assertFalse(this.visible(aura, "Turn Speed"));
        this.choose(aura, "Rotation", KillAura.RotationMode.SMOOTH);
        assertTrue(this.visible(aura, "Turn Speed"));
        assertTrue(this.visible(aura, "Movement Corrector"), "a silent look is what needs correcting");
        this.choose(aura, "Rotation", KillAura.RotationMode.NONE);
        assertFalse(this.visible(aura, "Silent"), "nothing to hide when it doesn't turn");
        assertFalse(this.visible(aura, "Movement Corrector"));
        this.choose(aura, "Timing", KillAura.Timing.COOLDOWN);
        assertFalse(this.visible(aura, "Min CPS"));
    }

    private boolean visible(final KillAura aura, final String name) {
        return aura.setting(name).map(Setting::isVisible).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private <E extends Enum<E>> void choose(final KillAura aura, final String name, final E value) {
        ((EnumSetting<E>) aura.setting(name).orElseThrow()).set(value);
    }
}
