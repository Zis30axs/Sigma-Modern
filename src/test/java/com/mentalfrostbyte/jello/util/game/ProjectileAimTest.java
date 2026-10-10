package com.mentalfrostbyte.jello.util.game;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProjectileAimTest {
    private static final Vec3 EYE = new Vec3(0, 10, 0);

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(components -> components.apply());
    }

    @Test void candidateTemplatesRespectRealChargeInsteadOfIdlePreview() {
        var bow = new ItemStack(Items.BOW);
        assertNull(ProjectileLaunch.template(bow, 0));
        assertNull(ProjectileLaunch.template(bow, 2));
        assertEquals(1.25F, ProjectileLaunch.template(bow, 10).speed(), 1.0E-6);
        assertEquals(3, ProjectileLaunch.template(bow, 20).speed(), 1.0E-6);
        var trident = new ItemStack(Items.TRIDENT);
        assertNull(ProjectileLaunch.template(trident, 9));
        assertEquals(2.5, ProjectileLaunch.template(trident, 10).speed(), 1.0E-6);
        trident.setDamageValue(trident.getMaxDamage() - 1);
        assertNull(ProjectileLaunch.template(trident, 20));
    }

    @Test void candidateCrossbowNeedsAmmoAndDoesNotMutateItsCharge() {
        var crossbow = new ItemStack(Items.CROSSBOW);
        assertNull(ProjectileLaunch.template(crossbow, 40));
        var charged = ChargedProjectiles.of(ItemStackTemplate.fromStack(new ItemStack(Items.ARROW)));
        crossbow.set(DataComponents.CHARGED_PROJECTILES, charged);
        var spec = ProjectileLaunch.template(crossbow, 0);
        assertNotNull(spec);
        assertEquals(3.15F, spec.speed());
        assertFalse(spec.inheritsMovement());
        assertSame(charged, crossbow.get(DataComponents.CHARGED_PROJECTILES));
    }

    @Test void candidateThrowableTemplatesPreserveTheirDifferentLaunchPhysics() {
        var snowball = ProjectileLaunch.template(new ItemStack(Items.SNOWBALL), 0);
        var potion = ProjectileLaunch.template(new ItemStack(Items.SPLASH_POTION), 0);
        var bottle = ProjectileLaunch.template(new ItemStack(Items.EXPERIENCE_BOTTLE), 0);
        var wind = ProjectileLaunch.template(new ItemStack(Items.WIND_CHARGE), 0);
        assertEquals(1.5F, snowball.speed());
        assertEquals(0.03, snowball.gravity());
        assertEquals(0.5F, potion.speed());
        assertEquals(-20F, potion.pitchOffset());
        assertEquals(0.7F, bottle.speed());
        assertEquals(0.07, bottle.gravity());
        assertEquals(0, wind.originY());
        assertEquals(ProjectileLaunch.Physics.WIND_CHARGE, wind.physics());
        assertNull(ProjectileLaunch.template(new ItemStack(Items.STONE), 0));
    }

    @Test void stationaryArrowUsesTheLowArcAndMatchesTheDiscretePreview() {
        var aim = aim(template(ProjectileLaunch.Physics.ARROW, 3, 0, 0.05, true), new Vec3(0, 10, 24), Vec3.ZERO, Vec3.ZERO);
        assertNotNull(aim);
        assertTrue(aim.timeTicks() > 8 && aim.timeTicks() < 10);
        assertTrue(aim.pitch() < 0 && aim.pitch() > -15);
        assertEquals(0, aim.yaw(), 1.0E-4);
        assertMatchesFlight(aim);
    }

    @Test void pearlSearchOffersBothArcsWithoutChangingTheOriginalLowArc() {
        var spec = template(ProjectileLaunch.Physics.THROWABLE, 1.5F, 0, 0.03, true);
        var input = new ProjectileAim.Input(spec, EYE, Vec3.ZERO, new Vec3(0, 14, 20), Vec3.ZERO);
        var arcs = ProjectileAim.solveAll(input);
        assertEquals(2, arcs.size());
        assertEquals(ProjectileAim.solve(input), arcs.getFirst());
        assertTrue(arcs.getLast().timeTicks() > arcs.getFirst().timeTicks() + 20);
        assertTrue(arcs.getLast().pitch() < arcs.getFirst().pitch());
        for (var arc : arcs) assertMatchesFlight(arc, 0.015);
        assertThrows(UnsupportedOperationException.class, () -> arcs.clear());
    }

    @Test void arcSearchRejectsInvalidAndUnreachableInputs() {
        assertTrue(ProjectileAim.solveAll(null).isEmpty());
        var spec = template(ProjectileLaunch.Physics.THROWABLE, 1.5F, 0, 0.03, true);
        assertTrue(ProjectileAim.solveAll(new ProjectileAim.Input(spec, EYE, Vec3.ZERO,
                EYE.add(0, 80, 0), Vec3.ZERO)).isEmpty());
    }

    @Test void thrownItemsIncludeGravityAndDragBeforeTheFirstMove() {
        var spec = template(ProjectileLaunch.Physics.THROWABLE, 1.5F, 0, 0.03, true);
        var aim = aim(spec, new Vec3(0, 10, 14), Vec3.ZERO, Vec3.ZERO);
        assertNotNull(aim);
        Vec3 velocity = aim.shot().velocity().add(0, -0.03, 0).scale(0.99F);
        assertPoint(aim.shot().origin().add(velocity), aim.path().get(1), 1.0E-8);
        assertMatchesFlight(aim);
    }

    @Test void potionOffsetChangesOnlyTheVerticalLaunchComponent() {
        var spec = template(ProjectileLaunch.Physics.THROWABLE, 0.5F, -20, 0.05, true);
        var aim = aim(spec, new Vec3(0, 8, 4), Vec3.ZERO, Vec3.ZERO);
        assertNotNull(aim);
        double pitch = Math.toRadians(aim.pitch());
        Vec3 expected = new Vec3(0, -Math.sin(pitch - Math.toRadians(20)), Math.cos(pitch)).normalize().scale(0.5);
        assertPoint(expected, aim.shot().velocity(), 2.0E-4);
        assertMatchesFlight(aim);
    }

    @Test void sidewaysMovementLeadsTheTargetInsteadOfAimingAtItsOldPosition() {
        var aim = aim(template(ProjectileLaunch.Physics.ARROW, 3, 0, 0.05, true),
                new Vec3(0, 10, 24), new Vec3(0.25, 0, 0), Vec3.ZERO);
        assertNotNull(aim);
        assertTrue(aim.yaw() < -3);
        assertEquals(aim.timeTicks() * 0.25, aim.hitPoint().x, 1.0E-8);
        assertMatchesFlight(aim);
    }

    @Test void shooterMovementIsCompensatedBeforeConvertingVelocityToRotation() {
        var aim = aim(template(ProjectileLaunch.Physics.THROWABLE, 1.5F, 0, 0.03, true),
                new Vec3(0, 10, 14), Vec3.ZERO, new Vec3(0.3, 0.2, 0));
        assertNotNull(aim);
        assertTrue(aim.yaw() > 5);
        assertTrue(Math.abs(aim.shot().velocity().x) < 2.0E-4);
        assertMatchesFlight(aim);
    }

    @Test void crossbowsIgnoreShooterVelocity() {
        var spec = template(ProjectileLaunch.Physics.ARROW, 3.15F, 0, 0.05, false);
        var stationary = aim(spec, new Vec3(0, 10, 24), Vec3.ZERO, Vec3.ZERO);
        var moving = aim(spec, new Vec3(0, 10, 24), Vec3.ZERO, new Vec3(0.8, 0.5, 0.8));
        assertNotNull(stationary);
        assertEquals(stationary, moving);
        assertMatchesFlight(moving);
    }

    @Test void windChargesAndFireworksUseStraightFlightAndRespectLifetime() {
        for (var physics : new ProjectileLaunch.Physics[] {ProjectileLaunch.Physics.WIND_CHARGE, ProjectileLaunch.Physics.FIREWORK}) {
            var spec = new ProjectileLaunch.Template(1.5F, 0, physics, 0, 1, 1, 0, 0.25F, 0.25F, 10, false);
            var aim = aim(spec, new Vec3(0, 10, 9), Vec3.ZERO, Vec3.ZERO);
            assertNotNull(aim);
            assertEquals(6, aim.timeTicks(), 1.0E-7);
            assertEquals(0, aim.pitch(), 1.0E-6);
            assertMatchesFlight(aim);
            assertNull(aim(spec, new Vec3(0, 10, 18), Vec3.ZERO, Vec3.ZERO));
        }
    }

    @Test void bothVerticalDirectionsAreFiniteAndReachTheirTargets() {
        var spec = template(ProjectileLaunch.Physics.ARROW, 3, 0, 0.05, true);
        for (double height : new double[] {3, 20}) {
            var aim = aim(spec, new Vec3(0, height, 0), Vec3.ZERO, Vec3.ZERO);
            assertNotNull(aim);
            assertEquals(height > 10 ? -90F : 90F, aim.pitch());
            assertMatchesFlight(aim);
        }
    }

    @Test void impossibleBallisticRangeDoesNotProduceAnAim() {
        assertNull(aim(template(ProjectileLaunch.Physics.THROWABLE, 0.5F, -20, 0.05, true),
                new Vec3(0, 10, 100), Vec3.ZERO, Vec3.ZERO));
        assertNull(aim(template(ProjectileLaunch.Physics.ARROW, 3, 0, 0.05, true), EYE.add(0, 0, 300), Vec3.ZERO, Vec3.ZERO));
    }

    @Test void boundedLeadDoesNotPretendARecedingTargetWillStopAtTheHorizon() {
        var spec = new ProjectileLaunch.Template(1.5F, 0, ProjectileLaunch.Physics.WIND_CHARGE,
                0, 1, 1, 0, 0.25F, 0.25F, Integer.MAX_VALUE, true);
        assertNull(aim(spec, EYE.add(0, 0, 12), new Vec3(0, 0, 1.5), Vec3.ZERO));
        assertEquals(new Vec3(24, 0, 0), ProjectileAim.predictedTarget(Vec3.ZERO, new Vec3(100, 0, 0), 1000));
        assertEquals(new Vec3(3, 0, 0), ProjectileAim.predictedTarget(Vec3.ZERO, new Vec3(0.1, 0, 0), 1000));
    }

    @Test void invalidOrUnboundedInputsHaveNoSolution() {
        var spec = template(ProjectileLaunch.Physics.ARROW, 3, 0, 0.05, true);
        assertNull(ProjectileAim.solve(null));
        assertNull(aim(spec, new Vec3(Double.NaN, 0, 10), Vec3.ZERO, Vec3.ZERO));
        assertNull(aim(spec, EYE.add(0, 0, 10), new Vec3(Double.POSITIVE_INFINITY, 0, 0), Vec3.ZERO));
        assertNull(aim(spec, EYE.add(0, 0, 10), Vec3.ZERO, new Vec3(1.0E100, 0, 0)));
        var zero = template(ProjectileLaunch.Physics.ARROW, 0, 0, 0.05, true);
        assertNull(aim(zero, EYE.add(0, 0, 10), Vec3.ZERO, Vec3.ZERO));
    }

    @Test void resultingPathIsImmutableAndKeepsTheFractionalLastSegment() {
        var aim = aim(template(ProjectileLaunch.Physics.ARROW, 3, 0, 0.05, true), EYE.add(0, 0, 17), Vec3.ZERO, Vec3.ZERO);
        assertNotNull(aim);
        assertEquals((int) Math.ceil(aim.timeTicks()) + 1, aim.path().size());
        assertNotEquals(Math.floor(aim.timeTicks()), aim.timeTicks());
        assertThrows(UnsupportedOperationException.class, () -> aim.path().clear());
        assertMatchesFlight(aim);
    }

    @Test void fixedRotationPreservesTheMovementFacingAfterTheLaunchOriginMoves() {
        var spec = template(ProjectileLaunch.Physics.ARROW, 3, 0, 0.05, true);
        Vec3 target = EYE.add(0, 0, 20);
        var first = aim(spec, target, Vec3.ZERO, Vec3.ZERO);
        assertNotNull(first);
        Vec3 movedEye = EYE.add(0.2, 0.1, 0.1);
        var input = new ProjectileAim.Input(spec, movedEye, Vec3.ZERO, target, Vec3.ZERO);
        var fresh = ProjectileAim.solve(input);
        var fixed = ProjectileAim.withRotation(input, first.yaw(), first.pitch());
        assertNotNull(fresh);
        assertNotNull(fixed);
        assertEquals(first.yaw(), fixed.yaw());
        assertEquals(first.pitch(), fixed.pitch());
        assertNotEquals(fresh.yaw(), fixed.yaw());
        assertEquals(fresh.timeTicks(), fixed.timeTicks());
        assertEquals(movedEye.add(0, spec.originY(), 0), fixed.path().getFirst());
        assertEquals(target, fixed.targetPoint());
        assertEquals(fixed.path().getLast(), fixed.hitPoint());
        assertTrue(fixed.hitPoint().distanceTo(target) > 0.1);
        assertMatchesFlight(fixed);
    }

    @Test void fixedRotationStillUsesTheNewInheritedVelocityAndBoundedTargetLead() {
        var spec = template(ProjectileLaunch.Physics.THROWABLE, 1.5F, 0, 0.03, true);
        Vec3 target = EYE.add(0, 0, 12);
        Vec3 moving = new Vec3(0.3, 0.1, 0);
        Vec3 lead = new Vec3(0.1, 0, 0);
        var input = new ProjectileAim.Input(spec, EYE, moving, target, lead);
        var fixed = ProjectileAim.withRotation(input, 0, -10);
        assertNotNull(fixed);
        assertEquals(0, fixed.yaw());
        assertEquals(-10, fixed.pitch());
        assertEquals(0.3, fixed.shot().velocity().x, 1.0E-8);
        assertEquals(lead, fixed.targetVelocity());
        assertEquals(target, fixed.targetPoint());
        assertMatchesFlight(fixed);
    }

    @Test void fixedRotationRejectsInvalidAnglesAndUnreachableInput() {
        var spec = template(ProjectileLaunch.Physics.ARROW, 3, 0, 0.05, true);
        var input = new ProjectileAim.Input(spec, EYE, Vec3.ZERO, EYE.add(0, 0, 20), Vec3.ZERO);
        assertNull(ProjectileAim.withRotation(input, Float.NaN, 0));
        assertNull(ProjectileAim.withRotation(input, 0, Float.POSITIVE_INFINITY));
        assertNull(ProjectileAim.withRotation(input, 0, 90.1F));
        assertNull(ProjectileAim.withRotation(null, 0, 0));
        assertNull(ProjectileAim.withRotation(new ProjectileAim.Input(spec, EYE, Vec3.ZERO,
                EYE.add(0, 0, 300), Vec3.ZERO), 0, 0));
    }

    private static ProjectileAim.Aim aim(ProjectileLaunch.Template template, Vec3 target, Vec3 targetVelocity, Vec3 inherited) {
        return ProjectileAim.solve(new ProjectileAim.Input(template, EYE, inherited, target, targetVelocity));
    }

    private static ProjectileLaunch.Template template(ProjectileLaunch.Physics physics, float speed,
                                                       float offset, double gravity, boolean inherit) {
        return new ProjectileLaunch.Template(speed, offset, physics, gravity, 0.99F, 0.8F, -0.1F, 0.25F, 0.25F, 160, inherit);
    }

    private static void assertMatchesFlight(ProjectileAim.Aim aim) {
        assertMatchesFlight(aim, 0.005);
    }

    private static void assertMatchesFlight(ProjectileAim.Aim aim, double interceptTolerance) {
        var path = ProjectileTrajectories.simulate(aim.shot(), new ProjectileTrajectories.Environment() {
            @Override public boolean loaded(Vec3 from, Vec3 to) { return true; }
            @Override public boolean water(Vec3 position, ProjectileLaunch.Shot shot) { return false; }
            @Override public ProjectileTrajectories.Hit collision(Vec3 from, Vec3 to, ProjectileLaunch.Shot shot, int tick) { return null; }
        }, 256);
        int full = (int) aim.timeTicks();
        Vec3 end = path.points().get(full).lerp(path.points().get(full + 1), aim.timeTicks() - full);
        assertPoint(aim.path().getLast(), end, 1.0E-7);
        assertPoint(aim.hitPoint(), end, interceptTolerance);
    }

    private static void assertPoint(Vec3 expected, Vec3 actual, double tolerance) {
        assertEquals(expected.x, actual.x, tolerance);
        assertEquals(expected.y, actual.y, tolerance);
        assertEquals(expected.z, actual.z, tolerance);
    }
}
