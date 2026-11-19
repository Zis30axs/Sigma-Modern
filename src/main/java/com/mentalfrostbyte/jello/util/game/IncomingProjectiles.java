package com.mentalfrostbyte.jello.util.game;

import com.mentalfrostbyte.jello.util.game.ProjectileDefensePlanner.Flight;
import com.mentalfrostbyte.jello.util.game.ProjectileDefensePlanner.Segment;
import com.mentalfrostbyte.jello.util.game.ProjectileDefensePlanner.Walk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Read-only, tick-local world adapter for the defense planner. Flight paths are shared by every movement candidate. */
public final class IncomingProjectiles {
    private IncomingProjectiles() { }

    public static ProjectileDefensePlanner.Plan plan(LocalPlayer player, Input keys, Vec2 input, ItemStack shield, int ticks) {
        TrajectoryWorld world = new TrajectoryWorld(player, 1F);
        List<Flight> flights = new ArrayList<>();
        BlocksAttacks block = shield.get(DataComponents.BLOCKS_ATTACKS);
        for (AbstractArrow arrow : player.level().getEntitiesOfClass(AbstractArrow.class, player.getBoundingBox().inflate(64),
                a -> threatens(player, a))) {
            Flight path = trace(player, world, arrow, ticks, block);
            if (!path.segments().isEmpty()) flights.add(path);
        }
        int used = player.isUsingItem() && player.getUseItem() == shield ? player.getTicksUsingItem() : 0;
        int delay = block == null ? -1 : Math.max(0, block.blockDelayTicks() - used);
        float useSpeed = shield.getOrDefault(DataComponents.USE_EFFECTS, UseEffects.DEFAULT).speedMultiplier();
        return ProjectileDefensePlanner.choose(flights, input, player.getYRot(), delay, useSpeed,
                (direction, scale) -> walk(player, keys, direction, scale, ticks, world), world::unobstructedEnd);
    }

    private static boolean threatens(LocalPlayer player, AbstractArrow arrow) {
        return (arrow instanceof Arrow || arrow instanceof SpectralArrow || arrow instanceof ThrownTrident)
                && arrow.getOwner() != player && arrow.canHitOnCurrentFlight(player);
    }

    private static Flight trace(LocalPlayer player, TrajectoryWorld world, AbstractArrow arrow, int ticks,
                                @Nullable BlocksAttacks block) {
        Vec3 pos = arrow.position();
        Vec3 velocity = arrow.getDeltaMovement();
        List<Segment> segments = new ArrayList<>(ticks);
        Set<Integer> pierced = new HashSet<>();
        boolean blockable = canBlock(player, arrow, block, velocity);
        for (int i = 0; i < ticks; i++) {
            Vec3 end = pos.add(velocity);
            Vec3 clear = world.unobstructedEnd(pos, end);
            if (clear == null) break;
            float margin = TrajectoryWorld.entityMargin(arrow.tickCount + i);
            Vec3 stop = firstIntercept(player, arrow, pos, clear, margin, pierced);
            boolean stopped = stop != null || !clear.equals(end);
            if (stop != null) clear = stop;
            double length = velocity.length();
            double duration = stopped && length > 0 ? Math.min(1, pos.distanceTo(clear) / length) : 1;
            segments.add(new Segment(pos, clear, duration, margin, stopped));
            if (stopped) break;
            boolean wet = i == 0 ? arrow.isInWater() : world.water(pos, arrow.getBbWidth(), arrow.getBbHeight(), 0);
            double drag = wet && !(arrow instanceof ThrownTrident) ? 0.6F : 0.99F;
            velocity = velocity.scale(drag).add(0, -arrow.getGravity(), 0);
            pos = end;
        }
        return new Flight(segments, blockable);
    }

    private static @Nullable Vec3 firstIntercept(LocalPlayer player, AbstractArrow arrow, Vec3 start, Vec3 end,
                                                float margin, Set<Integer> pierced) {
        var hits = ProjectileUtil.getManyEntityHitResult(player.level(), arrow, start, end,
                new AABB(start, end).inflate(1), entity -> entity != player
                        && !pierced.contains(entity.getId()) && arrow.canHitOnCurrentFlight(entity),
                margin, ClipContext.Block.COLLIDER, true);
        if (hits.isEmpty()) return null;
        var ordered = new ArrayList<>(hits);
        ordered.sort(Comparator.comparingDouble(hit -> start.distanceToSqr(hit.getLocation())));
        for (EntityHitResult hit : ordered) {
            if (pierced.size() >= arrow.getPierceLevel()) {
                // Expanded-box grazes report a point on the entity, sometimes off the original flight line.
                Vec3 line = end.subtract(start);
                double t = line.lengthSqr() == 0 ? 0 : hit.getLocation().subtract(start).dot(line) / line.lengthSqr();
                return start.lerp(end, Mth.clamp(t, 0, 1));
            }
            pierced.add(hit.getEntity().getId());
        }
        return null;
    }

    private static boolean canBlock(LocalPlayer player, AbstractArrow arrow, @Nullable BlocksAttacks block, Vec3 velocity) {
        if (block == null || arrow.getPierceLevel() > 0) return false;
        DamageSource source = arrow instanceof ThrownTrident ? player.damageSources().trident(arrow, arrow.getOwner())
                : player.damageSources().arrow(arrow, arrow.getOwner());
        if (block.bypassedBy().map(tag -> tag.contains(source.typeHolder())).orElse(false)) return false;
        Vec3 incoming = new Vec3(-velocity.x, 0, -velocity.z).normalize();
        Vec3 facing = Vec3.directionFromRotation(0, player.getYRot());
        double angle = Math.acos(Mth.clamp(incoming.dot(facing), -1, 1));
        return block.resolveBlockedDamage(source, 1F, angle) > 0;
    }

    /** Short ground/air extrapolation using the real swept body box. Reject a side step that loses footing. */
    private static Walk walk(LocalPlayer player, Input keys, Vec2 input, float useSpeed, int ticks, TrajectoryWorld world) {
        Level level = player.level();
        AABB box = player.getBoundingBox();
        Vec3 velocity = player.getDeltaMovement();
        List<AABB> boxes = new ArrayList<>(ticks + 1);
        boxes.add(box);
        boolean grounded = player.onGround();
        boolean leftEdge = false;
        boolean sprinting = player.isSprinting() && input.y > 0 && useSpeed == 1F && !keys.shift();
        double groundSpeed = player.getSpeed();
        if (player.isSprinting() && !sprinting) groundSpeed /= 1.3;
        double airDrag = 1 - 0.09 * player.getAttributeValue(Attributes.AIR_DRAG_MODIFIER);
        double verticalDrag = 1 - 0.02 * player.getAttributeValue(Attributes.AIR_DRAG_MODIFIER);
        double surfaceModifier = player.getAttributeValue(Attributes.FRICTION_MODIFIER);
        double scale = useSpeed * 0.98 * (keys.shift() ? player.getAttributeValue(Attributes.SNEAKING_SPEED) : 1);
        Vec3 heading = ProjectileDefensePlanner.heading(input, player.getYRot()).scale(scale);
        for (int i = 0; i < ticks; i++) {
            BlockPos below = BlockPos.containing(box.getBottomCenter().add(0, -0.01, 0));
            var surface = level.getBlockState(below).getBlock();
            double friction = grounded ? 1 - (1 - surface.getFriction()) * surfaceModifier : 1;
            double speed = grounded ? groundSpeed : (sprinting ? 0.026 : 0.02);
            if (grounded && friction > 0.01) speed *= 0.216 / (friction * friction * friction);
            boolean jump = i == 0 && grounded && keys.jump();
            if (jump) {
                double lift = player.getAttributeValue(Attributes.JUMP_STRENGTH) * surface.getJumpFactor()
                        + player.getJumpBoostPower();
                velocity = new Vec3(velocity.x, Math.max(velocity.y, lift), velocity.z);
                if (sprinting) velocity = velocity.add(Vec3.directionFromRotation(0, player.getYRot()).scale(0.2));
            }
            Vec3 requested = velocity.add(heading.scale(speed));
            if (!world.loaded(box.getBottomCenter(), box.getBottomCenter().add(requested))) {
                leftEdge = true;
                break;
            }
            Vec3 movement = Entity.collideBoundingBox(player, requested, box, level,
                    level.getEntityCollisions(player, box.expandTowards(requested)));
            box = box.move(movement);
            boxes.add(box);
            boolean supported = movement.y <= 0 && !level.noCollision(player, box.move(0, -0.06, 0));
            if ((grounded && !jump && !supported) || (player.onGround() && box.minY < player.getY() - 1)) leftEdge = true;
            grounded = supported;
            velocity = new Vec3(movement.x == requested.x ? movement.x * friction * airDrag : 0,
                    ((movement.y == requested.y ? movement.y : 0) - player.getGravity()) * verticalDrag,
                    movement.z == requested.z ? movement.z * friction * airDrag : 0);
        }
        return new Walk(boxes, leftEdge);
    }
}
